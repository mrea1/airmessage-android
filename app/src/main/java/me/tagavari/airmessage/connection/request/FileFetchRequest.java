package me.tagavari.airmessage.connection.request;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import me.tagavari.airmessage.data.DatabaseManager;
import me.tagavari.airmessage.helper.AttachmentStorageHelper;

public class FileFetchRequest {
	private final Scheduler requestScheduler = Schedulers.from(Executors.newSingleThreadExecutor(), true);
	
	private final long messageID;
	private final long attachmentID;
	private final String fileName;
	
	private File targetFile;
	private OutputStream outputStream;
	private long totalLength;
	private long bytesWritten = 0;
	private int expectedResponseIndex = 0;
	
	public FileFetchRequest(long messageID, long attachmentID, String fileName) {
		this.messageID = messageID;
		this.attachmentID = attachmentID;
		this.fileName = fileName;
	}
	
	public long getMessageID() {
		return messageID;
	}
	
	public long getAttachmentID() {
		return attachmentID;
	}
	
	/**
	 * Initializes this request's streams
	 */
	public void initialize(Context context, long totalLength, @Nullable Function<OutputStream, OutputStream> streamWrapper) throws IOException {
		targetFile = AttachmentStorageHelper.prepareContentFile(context, AttachmentStorageHelper.dirNameAttachment, fileName);
		outputStream = new BufferedOutputStream(new FileOutputStream(targetFile));
		if(streamWrapper != null) outputStream = streamWrapper.apply(outputStream);
		this.totalLength = totalLength;
	}
	
	/**
	 * Writes a chunk of data to disk for this request
	 * @return A single that completes with the total amount of bytes written
	 */
	public Single<Long> writeChunk(int responseIndex, byte[] data) {
		//Validating the request index
		if(responseIndex != expectedResponseIndex) {
			return Single.error(new IllegalStateException("Request out of order: expected #" + expectedResponseIndex + ", received #" + responseIndex));
		}
		expectedResponseIndex++;
		
		//Writing the data
		return Completable.fromAction(() -> outputStream.write(data))
				.subscribeOn(requestScheduler)
				.observeOn(AndroidSchedulers.mainThread())
				//Incrementing the bytes written
				.doOnComplete(() -> bytesWritten += data.length)
				//Return the total bytes written
				.andThen(Single.fromCallable(() -> bytesWritten));
	}
	
	/**
	 * Completes this request and updates the attachment's state on disk
	 */
	public Single<File> complete(Context context) {
		return Completable.fromAction(() -> {
			close();
			DatabaseManager.getInstance().updateAttachmentFile(attachmentID, context, targetFile);
		}).subscribeOn(requestScheduler).observeOn(AndroidSchedulers.mainThread()).andThen(Single.just(targetFile));
	}
	
	/**
	 * Closes this request's streams and scheduler for use when we are done with this request
	 */
	public void close() throws IOException {
		if(outputStream != null) outputStream.close();
		requestScheduler.shutdown();
	}
	
	/**
	 * Cancels this request, closing its streams and cleaning up any saved data
	 */
	public void cancel() throws IOException {
		close();
		if(targetFile != null) AttachmentStorageHelper.deleteContentFile(AttachmentStorageHelper.dirNameAttachment, targetFile);
	}
	
	/**
	 * Gets the total length of the attachment file that's being downloaded
	 */
	public long getTotalLength() {
		return totalLength;
	}
}
package com.flume.source;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import org.apache.flume.Event;
import org.apache.flume.SystemClock;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.instrumentation.SourceCounter;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Cursor {
	private static final Logger logger = LoggerFactory.getLogger(Cursor.class);

	
	private String insertLine="";
	private SystemClock systemClock = new SystemClock();
	private Long lastPushToChannel = systemClock.currentTimeMillis();

	// For following a file name
	final File file;
	// PositionLog
	final File positionlog;
	
	private String fileNameSeparator="\t";

	// For buffering reads
	final ByteBuffer buf = ByteBuffer.allocateDirect(Short.MAX_VALUE*30);
	// For closing file handles and getting FileChannels
	RandomAccessFile raf = null;
	// For reading data
	FileChannel in = null;

	long lastFileMod;
	long lastChannelPos;
	long lastChannelSize;
	int readFailures;
	String charEncode;

	AbstractSource source;
	SourceCounter sourceCounter;
	int batchSize;
	String startChar;
	boolean isAddFileName;

	public Cursor(AbstractSource source, SourceCounter sourceCounter, File f,
			String charEncode, int batchSize, File positionlog, String startChar,boolean isAddFileName,String fileNameSeparator) {
		this(source, sourceCounter, f, 0, 0, 0, charEncode, batchSize,
				positionlog, startChar,isAddFileName,fileNameSeparator);
	}

	Cursor(AbstractSource source, SourceCounter sourceCounter, File f,
			long lastReadOffset, long lastFileLen, long lastMod,
			String charEncode, int batchSize, File positionlog, String startChar,boolean isAddFileName,String fileNameSeparator) {
		this.source = source;
		this.sourceCounter = sourceCounter;
		this.batchSize = batchSize;
		this.file = f;
		this.lastChannelPos = lastReadOffset;
		this.lastChannelSize = lastFileLen;
		this.lastFileMod = lastMod;
		this.readFailures = 0;
		this.charEncode = charEncode;
		this.positionlog = positionlog;
		this.startChar = startChar;
		this.isAddFileName=isAddFileName;
		this.fileNameSeparator=fileNameSeparator;
	}

	/**
	 * Setup the initial cursor position.
	 */
	public void initCursorPos() throws InterruptedException {
		try {
			logger.debug("initCursorPos " + file);
			raf = new RandomAccessFile(file, "r");
			raf.seek(lastChannelPos);
			in = raf.getChannel();
		} catch (FileNotFoundException e) {
			resetRAF();
		} catch (IOException e) {
			resetRAF();
		}
	}

	/**
	 * Flush any buffering the cursor has done. If the buffer does not end with
	 * '\n', the remainder will get turned into a new event.
	 * <p/>
	 * This assumes that any remainders in buf are a single event -- this
	 * corresponds to the last lines of files that do not end with '\n'
	 */
	void flush() throws InterruptedException {
		if (raf != null) {
			try {
				raf.close(); // release handles
			} catch (IOException e) {
				logger.error("problem closing file " + e.getMessage(), e);
			}
		}

		buf.flip(); // buffer consume mode
		int remaining = buf.remaining();
		if (remaining > 0) {
			byte[] body = new byte[remaining];
			buf.get(body, 0, remaining); // read all data

			try {
				logger.debug("flush method line info >>>>> "
						+ new String(body, charEncode));

				sourceCounter.incrementAppendBatchReceivedCount();
				Event event = EventBuilder
						.withBody(new String(body, charEncode).getBytes());
				source.getChannelProcessor().processEvent(event);
				sourceCounter.incrementAppendBatchAcceptedCount();
			} catch (UnsupportedEncodingException e) {
				logger.error("UnsupportedEncodingException! " + e.getMessage(),
						e);
			}
		}
		in = null;
		buf.clear();
	}

	/**
	 * Closes cursor and releases all resources used by it. NOTE: to flush any
	 * buffering the cursor has done and close cursor use {@link #flush()}
	 * instead.
	 */
	void close() {
		if (raf != null) {
			try {
				raf.close(); // release handles
			} catch (IOException e) {
				logger.error("problem closing file " + e.getMessage(), e);
			}
		}

		in = null;
		buf.clear();
	}

	/**
	 * Restart random accessfile cursor
	 * <p/>
	 * This assumes that the buf is in write mode, and that any remainders in
	 * buf are last bytes of files that do not end with \n
	 *
	 * @throws InterruptedException
	 */
	void resetRAF() throws InterruptedException {
		logger.debug("reseting cursor");
		flush();
		lastChannelPos = 0;
		lastFileMod = 0;
		readFailures = 0;
	}

	/**
	 * There is a race here on file system states -- a truly evil dir structure
	 * can change things between these calls. Nothing we can really do though.
	 * <p/>
	 * Ideally we would get and compare inode numbers, but in java land, we
	 * can't do that.
	 * <p/>
	 * returns true if changed, false if not.
	 */
	boolean checkForUpdates() throws IOException {
		logger.debug("tail " + file + " : recheck");
		if (file.isDirectory()) { // exists but not a file
			IOException ioe = new IOException("Tail expects a file '" + file
					+ "', but it is a dir!");
			logger.error(ioe.getMessage());
			throw ioe;
		}

		if (!file.exists()) {
			logger.debug("Tail '" + file
					+ "': nothing to do, waiting for a file");
			return false; // do nothing
		}

		if (!file.canRead()) {
			throw new IOException("Permission denied on " + file);
		}

		// oh! f exists and is a file
		try {
			// let's open the file
			raf = new RandomAccessFile(file, "r");
			lastFileMod = file.lastModified();
			in = raf.getChannel();
			lastChannelPos = in.position();
			lastChannelSize = in.size();

			logger.debug("Tail '" + file + "': opened last mod=" + lastFileMod
					+ " lastChannelPos=" + lastChannelPos + " lastChannelSize="
					+ lastChannelSize);
			return true;
		} catch (FileNotFoundException fnfe) {
			// possible because of file system race, we can recover from this.
			logger.debug("Tail '" + file
					+ "': a file existed then disappeared, odd but continue");
			return false;
		}
	}

	boolean extractLines(ByteBuffer buf) throws IOException,
			InterruptedException {
		boolean madeProgress = false;
		int start = buf.position();
		buf.mark();
		final List<Event> eventList = new ArrayList<Event>();
//		String insertLine = "";

		while (buf.hasRemaining()) {
			byte b = buf.get();

			if(startChar.equals("-")){
				if (b == 10) {
					int end = buf.position();
					int sz = end - start;
					byte[] body = new byte[sz - 1];
					buf.reset(); // go back to mark
					buf.get(body, 0, sz - 1); // read data
					buf.get(); // read '\n'
					buf.mark(); // new mark.
					start = buf.position();
					logger.debug("line info >>>>>>>>>"
							+ new String(body, charEncode)
									.replaceAll("\n", " "));
					synchronized (eventList) {
						String tmpLine = new String(body, charEncode)
								.replaceAll("\n", " ").trim();
						if (tmpLine.startsWith("-")
								) {
							insertLine=insertLine+tmpLine;
								sourceCounter.incrementEventReceivedCount();
								if(isAddFileName){
									insertLine=file.getPath()+fileNameSeparator+insertLine;
								}
								eventList.add(EventBuilder.withBody(insertLine
										.getBytes()));
								insertLine = startChar.charAt(0) + tmpLine;
								if (eventList.size() >= batchSize || timeout()) {
									flushEventBatch(eventList);
								}
						} else {
							insertLine = tmpLine;
						}
					}
					madeProgress = true;
				}
			}
			else if (!startChar.equals("-1")) {
				if (b == 10) {
					int end = buf.position();
					int sz = end - start;
					byte[] body = new byte[sz - 1];
					buf.reset(); // go back to mark
					buf.get(body, 0, sz - 1); // read data
					buf.get(); // read '\n'
					buf.mark(); // new mark.
					start = buf.position();
					logger.debug("line info >>>>>>>>>"
							+ new String(body, charEncode)
									.replaceAll("\n", " "));
					synchronized (eventList) {
						String tmpLine = new String(body, charEncode)
								.replaceAll("\n", " ").trim();
						if (tmpLine.startsWith(startChar) || tmpLine.trim().equals("")
								) {
								sourceCounter.incrementEventReceivedCount();
								if(isAddFileName){
									insertLine=file.getPath()+" "+insertLine;
								}
								if(!insertLine.trim().equals(file.getPath())){
									eventList.add(EventBuilder.withBody(insertLine
											.getBytes()));
								}
								insertLine = tmpLine;
								if (eventList.size() >= batchSize || timeout()) {
									flushEventBatch(eventList);
								}
						} else
						{
							insertLine = insertLine + " "
									+ tmpLine;
						}
					}
					madeProgress = true;
				}
			}
			else {
				if (b == 10) {
					int end = buf.position();
					int sz = end - start;
					byte[] body = new byte[sz - 1];
					buf.reset(); // go back to mark
					buf.get(body, 0, sz - 1); // read data
					buf.get(); // read '\n'
					buf.mark(); // new mark.
					start = buf.position();
					logger.debug("line info >>>>>>>>>"
							+ new String(body, charEncode));
					synchronized (eventList) {
						sourceCounter.incrementEventReceivedCount();
						String tmpLine = new String(body, charEncode)
						.replaceAll("\n", " ").trim();
						if(isAddFileName){
							tmpLine=file.getPath()+" "+tmpLine;
						}
						eventList.add(EventBuilder.withBody(tmpLine.getBytes()));
						if (eventList.size() >= batchSize || timeout()) {
							flushEventBatch(eventList);
						}
					}
					madeProgress = true;
				}
			}

		}

		synchronized (eventList) {
			if (!eventList.isEmpty()) {
				flushEventBatch(eventList);
			}
		}

		// rewind for any left overs
		buf.reset();
		buf.compact(); // shift leftovers to front.
		return madeProgress;
	}

	void flushEventBatch(List<Event> eventList) {
		sourceCounter.addToEventReceivedCount(eventList.size());
		sourceCounter.incrementAppendBatchReceivedCount();

		source.getChannelProcessor().processEventBatch(eventList);

		eventList.clear();
		lastPushToChannel = systemClock.currentTimeMillis();
	}

	boolean timeout() {
		return (systemClock.currentTimeMillis() - lastPushToChannel) >= 3000;
	}

	/**
	 * Attempt to get new data.
	 * <p/>
	 * Returns true if cursor's state has changed (progress was made)
	 */
	public boolean tailBody() throws InterruptedException {
		try {
			// no file named f currently, needs to be opened.
			if (in == null) {
				logger.debug("tail " + file + " : cur file is null");
				return checkForUpdates();
			}

			long chlen = in.size();
			boolean madeProgress = readAllFromChannel();

			if (madeProgress) {
				lastChannelSize = lastChannelPos; // this is more safe than
													// in.size()
				// due to possible race conditions
				// NOTE: this is racy (but very very small chance): if file was
				// rotated right before execution of next line with the file of
				// the
				// same length and this new file is never modified the logic in
				// this
				// method will never discover the rotation.
				lastFileMod = file.lastModified();
				logger.debug("$$$" + file.getName() + lastFileMod);
				logger.debug("$$$" + file.getName() + lastChannelPos);

				List<String> tmp = new ArrayList<String>();
				synchronized (positionlog) {
					BufferedReader reader = null;
					FileWriter fw = null;
					logger.debug("i am is running");

					try {

						reader = new BufferedReader(new FileReader(positionlog));
						String tempString = null;
						while ((tempString = reader.readLine()) != null) {
							if (!tempString.contains(file.getAbsolutePath())) {
								tmp.add(tempString);
							}
						}
						reader.close();
						fw = new FileWriter(positionlog);
						for (int i = 0; i < tmp.size(); i++) {
							fw.write(tmp.get(i) + "\r\n");
						}
						fw.write(file.getAbsolutePath() + "," + lastChannelPos
								+ "," + lastChannelSize + "," + lastFileMod);
						fw.close();

					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						if (reader != null) {
							try {
								reader.close();
								fw.close();
							} catch (IOException e1) {
							}
						}
					}

				}

				logger.debug("tail " + file + " : new data found");
				return true;
			}

			long fmod = file.lastModified();
			long flen = file.length(); // length of filename

			if (flen == lastChannelSize && fmod == lastFileMod) {
				logger.debug("tail " + file + " : no change");
				return false;
			}

			// 2) file rotated
			logger.debug("tail " + file + " : file rotated?");
			// a) rotated with file of same length
			if (flen == lastChannelSize && fmod != lastFileMod) {
				// this is not trivial situation: it can
				// be "false positive", so we want to be sure rotation with same
				// file length really happened by additional checks.
				// Situation is ultimately rare so doing time consuming/heavy
				// things is OK here
				logger.debug("tail " + file
						+ " : file rotated with new one with " + "same length?");
				raf.getFD().sync(); // Alex: not sure this helps at all...
				Thread.sleep(1000); // sanity interval: more data may be written
			}

			// b) "false positive" for file rotation due to race condition
			// during fetching file stats: actually more data was added into the
			// file (and hence it is visible in channel)
			if (in.size() != chlen) {
				logger.debug("tail " + file
						+ " : there's extra data to be read from "
						+ "file, aborting file rotation handling");
				return true;
			}

			// c) again "false positive" for file rotation: file was truncated
			if (chlen < lastChannelSize) {
				logger.debug("tail " + file + " : file was truncated, "
						+ "aborting file rotation handling");
				lastChannelSize = chlen;
				lastChannelPos = chlen;
				lastFileMod = file.lastModified();
				in.position(chlen); // setting cursor to the last position of
				// truncated file
				return false;
			}

			logger.debug("tail " + file + " : file rotated!");
			resetRAF(); // resetting raf to catch up new file

			// if file is not empty report true to start reading from it without
			// a
			// delay
			return flen > 0;

		} catch (IOException e) {
			logger.debug(e.getMessage(), e);
			in = null;
			readFailures++;

			/*
			 * Back off on retries after 3 failures so we don't burn cycles.
			 * Note that this can exacerbate the race condition illustrated
			 * above where a file is truncated, created, written to, and
			 * truncated / removed while we're sleeping.
			 */
			if (readFailures > 3) {
				logger.warn("Encountered " + readFailures + " failures on "
						+ file.getAbsolutePath() + " - sleeping");
				return false;
			}
		}
		return true;
	}

	private boolean readAllFromChannel() throws IOException,
			InterruptedException {
		// I make this a rendezvous because this source is being pulled
		// copy data from current file pointer to EOF to dest.
		boolean madeProgress = false;

		int rd;
		while ((rd = in.read(buf)) > 0) {
			madeProgress = true;

			// need char encoder to find line breaks in buf.
			lastChannelPos += (rd < 0 ? 0 : rd); // rd == -1 if at end of
			// stream.

			buf.flip();

			// extract lines
			extractLines(buf);
			// int lastRd = 0;
			// boolean progress = false;
			// do {
			//
			// if (lastRd == -1 && rd == -1) {
			// return true;
			// }
			//
			// buf.flip();
			//
			// // extract lines
			// extractLines(buf);
			//
			// lastRd = rd;
			// } while (progress); // / potential race

			// if the amount read catches up to the size of the file, we can
			// fall
			// out and let another fileChannel be read. If the last buffer isn't
			// read, then it remain in the byte buffer.

		}

		// logger.debug("tail " + file + ": last read position " +
		// lastChannelPos
		// + ", madeProgress: " + madeProgress);

		return madeProgress;
	}

}

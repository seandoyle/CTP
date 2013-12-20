/*---------------------------------------------------------------
*  Copyright 2005 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense)
*----------------------------------------------------------------*/

package org.rsna.ctp.stdstages;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.util.Enumeration;
import java.util.zip.*;
import org.apache.log4j.Logger;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.objects.XmlObject;
import org.rsna.ctp.objects.ZipObject;
import org.rsna.ctp.pipeline.AbstractImportService;
import org.rsna.ctp.pipeline.Quarantine;
import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;

/**
 * An ImportService that polls a PolledHttpExportService to obtain files on request.
 */
public class PollingHttpImportService extends AbstractImportService {

	static final Logger logger = Logger.getLogger(PollingHttpImportService.class);

	URL url;
	boolean zip = false;
	Poller poller = null;
	long interval = 10000;

	/**
	 * Construct a PollingHttpImportService. This import service does
	 * not queue objects. It connects to the source when a request is
	 * received.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the stage.
	 */
	public PollingHttpImportService(Element element) throws Exception {
		super(element);

		//Get the destination url
		url = new URL(element.getAttribute("url").trim());

		//Get the attribute that specifies whether files
		//are to be unzipped when received.
		zip = element.getAttribute("zip").trim().equals("yes");
	}

	/**
	 * Start the service. This method can be overridden by stages
	 * which can use it to start subordinate threads created in their constructors.
	 * This method is called by the Pipeline after all the stages have been
	 * constructed.
	 */
	public synchronized void start() {
		poller = new Poller();
		poller.start();
	}

	/**
	 * Stop the service.
	 */
	public synchronized void shutdown() {
		if (poller != null) poller.interrupt();
		super.shutdown();
	}

	class Poller extends Thread {
		String prefix = "IS-";

		public Poller() {
			super("Poller");
		}

		public void run() {
			File file;
			while (!isInterrupted()) {
				while ( !isInterrupted() && (file=getFile()) != null ) {
					if (!zip) fileReceived(file);
					else unpackAndReceive(file);
				}
				if (!isInterrupted()) {
					try { sleep(interval); }
					catch (Exception ignore) { }
				}
			}
		}

		//Get a file from the external system.
		private File getFile() {
			File file = null;
			Socket socket = null;
			InputStream in = null;
			OutputStream out = null;
			try {
				//Establish the connection
				socket = new Socket(url.getHost(), url.getPort());
				socket.setTcpNoDelay(true);
				socket.setSoTimeout(0);
				in = socket.getInputStream();
				out = socket.getOutputStream();

				//Get the length of the input
				long length = getLong(in);

				if (length > 0) {
					file = File.createTempFile(prefix,".md", getTempDirectory());
					BufferedInputStream is = new BufferedInputStream(in);
					FileOutputStream fos = null;
					try {
						fos = new FileOutputStream(file);
						int n;
						byte[] bbuf = new byte[1024];
						while ((length > 0) && ((n=is.read(bbuf,0,bbuf.length)) >= 0)) {
							fos.write(bbuf,0,n);
							length -= n;
						}
						fos.flush();
						fos.close();
						out.write(1); //send OK
					}
					catch (Exception ex) {
						logger.warn("Exception while receiving a file", ex);
						try {
							out.write(0); //send not ok
							fos.close();
						}
						catch (Exception ignore) { logger.warn("Unable to send a negative response."); }
						file.delete();
						file = null;
					}
				}
			}
			catch (Exception ex) { logger.debug("Exception while polling", ex); }
			close(socket);
			return file;
		}

		//Close a socket and its streams if possible
		private void close(Socket socket) {
			if (socket != null) {
				try { socket.getInputStream().close(); }
				catch (Exception ignore) { }

				try { socket.getOutputStream().close(); }
				catch (Exception ignore) { }

				try { socket.close(); }
				catch (Exception ignore) { }
			}
		}

		//Get a long value from an InputStream.
		//The long value is transmitted in little endian.
		private long getLong(InputStream in) {
			long el = 0;
			long x;
			try {
				for (int i=0; i<4; i++) {
					x = in.read();
					x = (x & 0x000000ff) << (8*i);
					el = x | el;
				}
				return el;
			}
			catch (Exception ex) { return 0; }
		}

		//Try to unpack a file and receive all its contents.
		//If it doesn't work, then receive the file itself.
		private void unpackAndReceive(File file) {
			if (!file.exists()) return;
			File parent = file.getParentFile();
			try {
				ZipFile zipFile = new ZipFile(file);
				Enumeration zipEntries = zipFile.entries();
				while (zipEntries.hasMoreElements()) {
					ZipEntry entry = (ZipEntry)zipEntries.nextElement();
					if (!entry.isDirectory()) {
						String name = entry.getName();
						name = name.substring(name.lastIndexOf("/")+1).trim();
						if (!name.equals("")) {
							File outFile = File.createTempFile("FS-",".tmp",parent);
							logger.debug("unpacking "+name+" to "+outFile);
							BufferedOutputStream out =
								new BufferedOutputStream(
									new FileOutputStream(outFile));
							BufferedInputStream in =
								new BufferedInputStream(
									zipFile.getInputStream(entry));
							int size = 1024;
							int n = 0;
							byte[] b = new byte[size];
							while ((n = in.read(b,0,size)) != -1) out.write(b,0,n);
							in.close();
							out.close();
							fileReceived(outFile);
						}
					}
				}
				zipFile.close();
				file.delete();
			}
			catch (Exception e) {
				fileReceived(file);
			}
		}
	}
}
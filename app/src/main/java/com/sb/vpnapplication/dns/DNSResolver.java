package com.sb.vpnapplication.dns;
import static com.sb.vpnapplication.dns.LoggerFormatter.format;

public class DNSResolver implements Runnable {
	private static final java.util.logging.Logger log = LoggerHelper.getLogger(DNSResolver.class);
	private static final java.nio.charset.Charset UTF8 = java.nio.charset.Charset.forName("UTF8");
	private static final long DEFAULT_QUERY_TIMEOUT = 1000;
	private static long localCacheTimeout = 60000; // 1 minute default
	private static final int DEFAULT_QUERY_RETRY = 3;
	private static final int PACKET_SIZE = 1024;
	private static final java.util.regex.Pattern IP_ADDRSS = java.util.regex.Pattern
			.compile("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");

	private static DNSResolver instance;

	private long dnsQueryTimeout = DEFAULT_QUERY_TIMEOUT;
	private int dnsRetryCount = DEFAULT_QUERY_RETRY;
	private java.net.InetSocketAddress dnsServerAddress = new java.net.InetSocketAddress("208.67.222.222", 443); // OpenDNS has 443 open for queries!
	private Thread resolverThread;
	private final java.net.DatagramPacket sendingPacket = new java.net.DatagramPacket(new byte[PACKET_SIZE],
			PACKET_SIZE);
	private final java.net.DatagramPacket receivingPacket = new java.net.DatagramPacket(new byte[PACKET_SIZE],
			PACKET_SIZE);
	private java.net.DatagramSocket socket;
	private ReplyWaiter[] holders = new ReplyWaiter[65536];
	private int nextHolder;

	private DNSResolver() {
		this.resolverThread = new Thread(this, "dns-resolver");
		this.resolverThread.setDaemon(true);
		this.resolverThread.start();
		synchronized (this) {
			if (this.socket == null) {
				try { // wait for thread start
					wait(1000);
				} catch (InterruptedException ex) {
				}
			}
		}
	}

	public void run() {
		try {
			socket = new java.net.DatagramSocket();
			synchronized (this) {
				notifyAll();
			}
			socket.setSoTimeout(1000);
			for (;;) {
				try {
					receivingPacket.setLength(PACKET_SIZE);
					socket.receive(receivingPacket);
					processReceivedPacket();
				} catch (java.net.SocketTimeoutException ex) {
				}

				long resendTime = System.currentTimeMillis() - this.dnsQueryTimeout;
				for (int i = 0; i < holders.length; i++) {
					if ((holders[i] != null) && (holders[i].retryCounter < this.dnsRetryCount)
							&& (holders[i].requestSentTime < resendTime)) {
						if (holders[i].retryCounter > this.dnsRetryCount) {
							holders[i].done(null);
							holders[i] = null;
							continue;
						}
						holders[i].retryCounter++;
						holders[i].requestSentTime = System.currentTimeMillis();
						log.finest(format("resending query: ", holders[i].domainName).toString());
						sendRequest(holders[i].domainName, i);
					}
				}
			}
		} catch (Exception ex) {
			log.warning(format("error in resolver. thread will be restarted on next query. ", ex).toString());
		} finally {
			socket.close();
		}
	}

	private void processReceivedPacket() {
		log.finest(format("received dns response from ", receivingPacket.getSocketAddress()).toString());
		byte[] buf = receivingPacket.getData();
		int len = receivingPacket.getLength();
		int id = ((0xFF & ((int) buf[0])) << 8) | (0xFF & ((int) buf[1]));
		if ((holders[id] != null) && (!holders[id].done)) {
			parseResponse(buf, len, holders[id]);
			holders[id] = null; // cleanup!
		}
	}

	private void parseResponse(byte[] buf, int len, ReplyWaiter holder) {
		if ((((int) buf[2] & 0xF1) != 0x81) || (((int) buf[3] & 0xFF) != 0x80) || (buf[7] <= 0)) {
			log.warning("failed DNS request for " + holder.domainName);
			holder.retryCounter = Integer.MAX_VALUE;
			holder.done(null);
			return;
		}

		log.finest(format("received ", len, " bytes").toString());
		int pos = skipName(buf, 12, len);
		pos += 4; // tail of A query

		int resp_count = buf[7];
		while ((pos < len) && (resp_count > 0)) {
			pos = skipName(buf, pos, len) + 1;
			if (buf[pos] == 5) { // cname response - skip it.
				pos += 9;
				pos = skipName(buf, pos, len);
				resp_count--;
				continue;
			}

			if ((buf[pos] == 1) && (buf[pos + 8] == 4)) { // good A response - process it.
				byte[] ab = new byte[4];
				pos += 9;
				ab[0] = buf[pos++];
				ab[1] = buf[pos++];
				ab[2] = buf[pos++];
				ab[3] = buf[pos++];
				try {
					holder.done(java.net.InetAddress.getByAddress(holder.domainName, ab));
					holder.retryCounter = Integer.MAX_VALUE;
				} catch (Exception shouldnHappen) {
					log.warning(format("unexpected exception", shouldnHappen).toString());
					holder.done(null);
				}
				return;
			}

		}
		log.warning(format("Couldn't parse DNS response: ", format(buf, len)).toString());
	}

	private int skipName(byte[] buf, int currentPos, int max) {
		while (currentPos < max) {
			if (buf[currentPos] == 0) {
				return currentPos + 1;
			}
			if ((buf[currentPos] & 0xC0) == 0xC0) {
				return currentPos + 2;
			}
			int np = currentPos + buf[currentPos] + 1;
			if (np < max) {
				currentPos = np;
			} else {
				break;
			}
		}
		return currentPos;
	}

	private static java.util.WeakHashMap<String, CacheHolder> localCache = new java.util.WeakHashMap<String, CacheHolder>();

	public void clearLocalCache() {
		localCache.clear();
	}

	private static class CacheHolder {
		final java.net.InetAddress addr;
		final long expiry;

		CacheHolder(java.net.InetAddress addr, long expiry) {
			this.addr = addr;
			this.expiry = expiry;
		}
	}

	private void sendRequest(String domainName, int id) throws java.io.IOException {
		synchronized (this) {
			byte[] buf = sendingPacket.getData();
			java.util.Arrays.fill(buf, (byte) 0);
			buf[0] = (byte) (0xFF & (id >> 8));
			buf[1] = (byte) (0xFF & id);
			buf[2] = buf[5] = 1;

			byte[] db = (domainName.toLowerCase() + ".").getBytes(UTF8);
			int pos = 12;
			byte n = 0;
			for (int i = 0; i < db.length; i++, n++) {
				if (db[i] == '.') {
					buf[pos] = n;
					pos += n + 1;
					n = -1;
				} else {
					buf[13 + i] = db[i];
				}
			}
			pos += 2;
			buf[pos] = 1; // type=A
			pos += 2;
			buf[pos++] = 1;
			// log.debug(buf, pos);
			sendingPacket.setLength(pos);
			sendingPacket.setSocketAddress(dnsServerAddress);
			if (socket == null || socket.isClosed()) { // not started or failed?..
				throw new java.io.IOException("resolver is not working");
			}
			socket.send(sendingPacket);
		}
	}

	public static DNSResolver getInstance() {
		if (instance == null || !instance.resolverThread.isAlive()) {
			instance = new DNSResolver();
		}
		return instance;
	}

	public static void setLocalCacheTimeout(long timeout) {
		localCacheTimeout = timeout;
	}

	public void setQueryTimeout(long timeout) {
		this.dnsQueryTimeout = timeout;
	}

	public void setRetry(int count) {
		this.dnsRetryCount = count;
	}

	public void setDNSAddress(java.net.InetSocketAddress address) {
		this.dnsServerAddress = address;
	}

	public java.net.InetAddress getHostByName(String hostName) throws java.io.IOException {
		if (IP_ADDRSS.matcher(hostName).matches()) {
			return java.net.InetAddress.getByName(hostName);
		}
		CacheHolder ch = localCache.get(hostName);
		if (ch != null) {
			if (ch.expiry > System.currentTimeMillis()) {
				return ch.addr;
			}
		}
		int id;
		synchronized (this) {
			id = nextHolder;
			nextHolder = (nextHolder + 1) % holders.length;
		}
		ReplyWaiter rw = holders[id] = new ReplyWaiter(hostName);
		sendRequest(hostName, id);
		synchronized (rw) {
			try {
				rw.wait(dnsQueryTimeout);
			} catch (InterruptedException ex) {
				throw new java.io.IOException(ex);
			}
		}
		if (rw.result == null) {
			throw new java.net.UnknownHostException(hostName);
		}
		return rw.result;
	}

	public java.net.Socket connect(String hostName, int port) throws java.io.IOException {
		if (IP_ADDRSS.matcher(hostName).matches()) {
			return new java.net.Socket(hostName, port);
		}
		CacheHolder ch = localCache.get(hostName);
		if (ch != null) {
			if (ch.expiry > System.currentTimeMillis()) {
				return new java.net.Socket(ch.addr, port);
			}
		}
		int id;
		synchronized (this) {
			id = nextHolder;
			nextHolder = (nextHolder + 1) % holders.length;
		}
		ReplyWaiter rw = holders[id] = new ReplyWaiter(hostName);
		sendRequest(hostName, id);
		synchronized (rw) {
			try {
				rw.wait(dnsQueryTimeout);
			} catch (InterruptedException ex) {
				throw new java.io.IOException(ex);
			}
		}
		if (rw.result == null) {
			throw new java.net.UnknownHostException(hostName);
		}
		return new java.net.Socket(rw.result, port);
	}

	private static class ReplyWaiter {
		private final String domainName;
		long requestSentTime;
		int retryCounter;
		java.net.InetAddress result;
		boolean done;

		ReplyWaiter(String domainName) {
			this.domainName = domainName;
		}

		void done(java.net.InetAddress result) {
			synchronized (this) {
				this.result = result;
				this.done = true;
				notifyAll();
			}
			if (result != null) {
				log.finest(result.toString());
				localCache.put(domainName, new CacheHolder(result, System.currentTimeMillis() + localCacheTimeout));
			}
		}
	}
}

package com.sb.vpnapplication.dns;

import com.sb.vpnapplication.logger.LoggerHelper;

import static com.sb.vpnapplication.logger.LoggerFormatter.format;

public class TCPPacketHandler extends PacketHandler {
	private static final java.util.logging.Logger log = LoggerHelper.getLogger(TCPPacketHandler.class);
	private static final java.util.Random rnd = new java.util.Random();
	private static final int TCP = 6;
	private static final int RST = 4;
	private static final int SYN = 2;
	private static final int FIN = 1;
	private static final int CHECKSUM_OFFSET = 16;
	private static final int MAX_SYNS_OR_FINS = 10;
	private static final int RESET_INT_FLAGS = 0x50140000;
	private static final int PUSH_DATA_FLAGS = 0x50180550;
	private static final int SYN_ACK_FLAGS = 0x50120550;
	private static final int FIN_ACK_FLAGS = 0x50110550;
	private static final int ACK_FLAGS = 0x50100550;
	private PacketHandler sender;
	private final java.nio.ByteBuffer replyBuffer = java.nio.ByteBuffer.allocate(2048);
	private final byte[] replyData = replyBuffer.array();

	private final java.util.HashMap<TcpConnectionKey, TcpConnection> connections = new java.util.HashMap<>();

	@Override
	public boolean handlePacket(int protocol, java.nio.ByteBuffer data, java.net.InetAddress from,
			java.net.InetAddress to, PacketHandler sender) {
		long now = System.currentTimeMillis();
		closeExpiredConnections(now);

		this.sender = sender;

		int start = data.position();
		int size = data.limit() - start;
		int srcPort = 0xFFFF & data.getShort();
		int dstPort = 0xFFFF & data.getShort();

		int seqNo = data.getInt();
		int ackNo = data.getInt();
		int dataOff = (0xF0 & data.get()) >> 2;
		int flags = 0xFF & data.get();
		int window = 0xFFFF & data.getShort(); // we should have not more than "window" in TCPConnectionHandler...

		int checksum = 0xFFFF & data.getShort();

		data.putShort(start + CHECKSUM_OFFSET, (short) 0);
		long pseudoHeaderChecksum = TCP + size; // TCP protocol + length 
		pseudoHeaderChecksum += UDPPackeHandler.checkSumFromIP(from);
		pseudoHeaderChecksum += UDPPackeHandler.checkSumFromIP(to);
		int cs = checksum(data.array(), start, size + start, pseudoHeaderChecksum);
		if (cs != checksum) {
			log.log(java.util.logging.Level.WARNING, "{0}",
					format("Bad TCP checksum ", Integer.toHexString(checksum), " (->", Integer.toHexString(cs), ")!"));
			log.log(java.util.logging.Level.INFO, "{0}", format(data.array(), start, size));
			return false;
		}

		TcpConnectionKey tck = new TcpConnectionKey(from, to, srcPort, dstPort);
		TcpConnection tc = connections.get(tck);
		if (tc == null) {
			if ((flags & SYN) != 0) {
				log.fine("New connection from " + from + ":" + srcPort + " to " + to + ":" + dstPort);
				TCPConnectionHandler handler = TCPConnectionHandler
						.getHandler(new java.net.InetSocketAddress(from, srcPort), to, dstPort);
				if (handler == null) {
					log.fine("Connection " + tck + " rejected");
					reset(tck, seqNo + 1, ackNo);
					return true;
				}
				if (window < TCPConnectionHandler.IN_BUFFER_SIZE) {
					log.warning("TCP Window is too small: " + window);
				}
				tc = new TcpConnection(tck, seqNo + 1, now, handler);
				connections.put(tck, tc);
			} else {
				log.fine("Packet out of sequence for " + tck);
				return true;
			}
		} else {
			tc.lastAccess = now;
		}

		if (!tc.connected) {
			if (!tc.handler.isConnected()) {
				return true;
			}
			if (ackNo == tc.ackNo + 1) {
				tc.ackNo++;
				tc.connected = true;
			} else {
				if (++tc.synsReceved > MAX_SYNS_OR_FINS) {
					log.info("SYN FLOOD");
					return true;
				}
				synAck(tc);
				return true;
			}
		}

		log.log(java.util.logging.Level.FINEST, "{0}", tc);

		if ((flags & FIN) != 0) {
			if (!tc.finReceived) { // first FIN
				tc.seqNo++;
				tc.finReceived = true;
				tc.synsReceved = 0;
				tc.handler.shutdownOutput();
			}
			if (tc.inputClosed) {
				tc.handler.close();
			}
		}

		if ((flags & RST) != 0) { // hard reset
			tc.handler.close();
			if (!tc.finReceived) { // got RST before FIN
				tc.seqNo++;
				tc.finReceived = true;
				tc.handler.shutdownOutput();
			}
			tc.handler.close();
			if (!tc.finSent) {
				dataAck(tc, true);
			}
			connections.remove(tck);
			return true;
		}

		int dataSize = size - dataOff;
		if (dataSize > 0) {
			data.position(start + dataOff);
			int ads = tc.handler.send(data); // bytes SENT to the remote
			if (ads < 0) {
				tc.handler.close();
				tc.inputClosed = true;
			} else {
				tc.seqNo += ads;
			}
		} else if (!tc.finReceived && !tc.inputClosed) { // nothing to tell
			return true;
		}

		if (tc.finReceived && tc.inputClosed) {
			if (++tc.synsReceved > MAX_SYNS_OR_FINS) { // Already confirmed close, stop talking
				log.info("BREAK");
				return true;
			}
		}
		if (tc.inputClosed && !tc.finSent) {
			tc.finSent = true;
			dataAck(tc, true);
			tc.ackNo++;
		} else {
			if (ackNo == tc.ackNo + 1) { // last ack
				tc.ackNo++;
			}
			dataAck(tc, false);
		}
		return true;
	}

	private long idleTimeout = 120000;
	private long expirationCheckInterval = 10000;
	private long nextCheckForExpired;

	private void closeExpiredConnections(long now) {
		if (nextCheckForExpired > now) {
			return;
		}

		long expiry = now - idleTimeout;
		long drop = now - idleTimeout - 10000; // forget it completely in 10 seconds more
		nextCheckForExpired = now + expirationCheckInterval;
		java.util.Iterator<TcpConnection> it = connections.values().iterator();
		while (it.hasNext()) {
			TcpConnection tc = it.next();
			if (tc.lastAccess < expiry) {
				if (!tc.finReceived) {
					log.fine("Connection " + tc.key + " expired");
					tc.handler.close();
				} else if (tc.lastAccess < drop) {
					it.remove();
				}
				tc.ackNo++;
				tc.inputClosed = true;
				if (!tc.finSent) {
					tc.finSent = true;
					dataAck(tc, true);
				} else {
					dataAck(tc, false);
				}
			}
		}
	}

	private class TcpConnection implements TCPDataConsumer {
		boolean connectionAcknowledged;
		boolean finReceived;
		boolean connected;
		boolean finSent;
		boolean inputClosed;
		int synsReceved;
		final TcpConnectionKey key;
		final TCPConnectionHandler handler;
		long lastAccess;
		int seqNo;
		int ackNo = rnd.nextInt();

		TcpConnection(TcpConnectionKey key, int seqNo, long now, TCPConnectionHandler handler) {
			this.handler = handler;
			this.lastAccess = now;
			this.key = key;
			this.seqNo = seqNo;
			handler.setActionCallback(this);
		}

		public void consume(java.nio.Buffer data) {
			if (!connectionAcknowledged) {
				if (data != null) {
					connectionAcknowledged = true;
					synAck(this);
				} else {
					reset(key, seqNo, 0);
				}
				return;
			}
			if (data == null) {
				inputClosed = true;
				if (finReceived && !finSent) {
					finSent = true;
					dataAck(this, true);
				}
			} else {
				sendData(this, (java.nio.ByteBuffer) data);
			}
		}

		public String toString() {
			return "TcpConnection" + key + " (ackd=" + connectionAcknowledged + ", eof=" + inputClosed + ", finR="
					+ finReceived + ", finS=" + finSent + ", seqNo=" + toSeqNo(seqNo) + ", ackNo=" + toSeqNo(ackNo)
					+ ")";
		}
	}

	private static String toSeqNo(int no) {
		return "(" + Integer.toHexString(0xFFFF & (no >> 16)) + " " + Integer.toHexString(0xFFFF & no) + ")";
	}

	private void sendData(TcpConnection tc, java.nio.ByteBuffer data) {
		int pos = data.position();
		int lim = data.limit();
		if (pos == lim) { // no data
			return;
		}
		int dataSize = lim - pos;
		if (pos < 20) { // not enough space for TCP header (shouldn't really happen if read has right logic)
			int cap = data.capacity();
			if (cap < lim + 20) {
				log.warning("---------- REALLOCATE ---------");
				lim += 20;
				java.nio.ByteBuffer tmp = java.nio.ByteBuffer.allocate(lim);
				tmp.position(20);
				tmp.put(data);
				tmp.position(0);
				lim += 20;
				tmp.limit(lim);
				data = tmp;
			} else {
				log.warning("---------- MOVE DATA ---------");
				byte[] ba = data.array();
				System.arraycopy(ba, pos, ba, 20, lim - pos);
				data.position(pos = 0);
				lim += 20;
				data.limit(lim);
			}
		} else {
			pos -= 20;
			data.position(pos);
		}
		data.putShort(tc.key.dstPort);
		data.putShort(tc.key.srcPort);
		data.putInt(tc.ackNo);
		data.putInt(tc.seqNo);
		data.putInt(PUSH_DATA_FLAGS);
		data.putInt(0); // CS and Urgent
		int cs = checksum(data.array(), pos + 4, lim, tc.key.checksum + dataSize);
		data.putShort(pos + 16, (short) cs);
		data.position(pos);
		data.limit(lim);
		if (!sender.handlePacket(TCP, data, tc.key.to, tc.key.from, null)) {
			// VPN channel destroyed => close everything and prepare to die 
			tc.handler.close();
			connections.remove(tc.key);
		}
		tc.ackNo += dataSize;
	}

	private void synAck(TcpConnection tc) {
		replyBuffer.clear();
		replyBuffer.putShort(tc.key.dstPort);
		replyBuffer.putShort(tc.key.srcPort);
		replyBuffer.putInt(tc.ackNo);
		replyBuffer.putInt(tc.seqNo);
		replyBuffer.putInt(SYN_ACK_FLAGS);
		int csOffset = replyBuffer.position();
		replyBuffer.putInt(0); // CS and Urgent
		int cs = checksum(replyData, 4, replyBuffer.position(), tc.key.checksum);
		replyBuffer.putShort(csOffset, (short) cs);
		replyBuffer.flip();
		if (!sender.handlePacket(TCP, replyBuffer, tc.key.to, tc.key.from, null)) {
			// VPN channel destroyed => close everything and prepare to die 
			tc.handler.close();
			connections.remove(tc.key);
		}
		log.log(java.util.logging.Level.FINEST, "SYN ACK {0}", tc);
	}

	private void dataAck(TcpConnection tc, boolean sendFin) {
		replyBuffer.clear();
		replyBuffer.putShort(tc.key.dstPort);
		replyBuffer.putShort(tc.key.srcPort);
		replyBuffer.putInt(tc.ackNo);
		replyBuffer.putInt(tc.seqNo);
		replyBuffer.putInt(sendFin ? FIN_ACK_FLAGS : ACK_FLAGS);
		int csOffset = replyBuffer.position();
		replyBuffer.putInt(0); // CS and Urgent

		int cs = checksum(replyData, 4, replyBuffer.position(), tc.key.checksum);
		replyBuffer.putShort(csOffset, (short) cs);
		replyBuffer.flip();
		if (!sender.handlePacket(TCP, replyBuffer, tc.key.to, tc.key.from, null)) {
			// VPN channel destroyed => close everything and prepare to die
			tc.handler.close();
			connections.remove(tc.key);
		}
		log.log(java.util.logging.Level.FINEST, (sendFin ? "FIN ACK" : "ACK") + " {0}", tc);
	}

	private void reset(TcpConnectionKey key, int seqNo, int ackNo) {
		replyBuffer.clear();
		replyBuffer.putShort(key.dstPort);
		replyBuffer.putShort(key.srcPort);
		replyBuffer.putInt(ackNo);
		replyBuffer.putInt(seqNo);
		replyBuffer.putInt(RESET_INT_FLAGS);
		int csOffset = replyBuffer.position();
		replyBuffer.putInt(0);
		int cs = checksum(replyData, 4, 20, key.checksum);
		replyBuffer.putShort(csOffset, (short) cs);
		replyBuffer.flip();
		if (!sender.handlePacket(TCP, replyBuffer, key.to, key.from, null)) {
			// VPN channel destroyed => close everything and prepare to die
			connections.remove(key);
		}
	}

	@Override
	public void terminate() {
		while (!connections.isEmpty()) {
			try {
				java.util.Iterator<TcpConnection> it = connections.values().iterator();
				TcpConnection tc = it.next();
				it.remove();
				if (!tc.handler.isClosed()) {
					tc.handler.close();
				}
				tc.ackNo++;
				tc.inputClosed = true;
				tc.finSent = true;
				dataAck(tc, true);
			} catch (java.util.ConcurrentModificationException ignore) {
			}
		}
		super.terminate();
	}

	private static class TcpConnectionKey {
		final java.net.InetAddress from;
		final java.net.InetAddress to;
		final short srcPort;
		final short dstPort;
		final int checksum;

		TcpConnectionKey(java.net.InetAddress from, java.net.InetAddress to, int srcPort, int dstPort) {
			this.from = from;
			this.to = to;
			this.srcPort = (short) srcPort;
			this.dstPort = (short) dstPort;
			int cs = 26;
			cs += UDPPackeHandler.checkSumFromIP(from);
			cs += UDPPackeHandler.checkSumFromIP(to);
			cs += srcPort;
			cs += dstPort;
			this.checksum = cs;
		}

		@Override
		public int hashCode() {
			return checksum;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof TcpConnectionKey)) {
				return false;
			}
			if (obj == this) {
				return true;
			}
			TcpConnectionKey o = (TcpConnectionKey) obj;
			return o.checksum == checksum && o.srcPort == srcPort && o.dstPort == dstPort && o.to.equals(to)
					&& o.from.equals(from);
		}

		@Override
		public String toString() {
			return "(" + from.getHostAddress() + ":" + (0xFFFF & srcPort) + " -> " + to.getHostAddress() + ":"
					+ (0xFFFF & dstPort) + ")";
		}
	}
}

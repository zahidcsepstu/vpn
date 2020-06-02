package com.sb.vpnapplication.dns;

import java.nio.ByteBuffer;

import static com.sb.vpnapplication.logger.LoggerFormatter.format;

public class UDPPackeHandler extends PacketHandler {
	private static final int PROTO_UDP = 17;
	private java.net.DatagramSocket socket;
	private final SocketProtector dsf;
	private final byte[] sendingPacketData = new byte[TunDNSResolver.MAX_PACKET_SIZE];
	private final java.net.DatagramPacket sendingPacket = new java.net.DatagramPacket(sendingPacketData,
			sendingPacketData.length);
	private boolean stopping = false;

	UDPPackeHandler(SocketProtector dsf) {
		this.dsf = dsf;
	}

	private static class Waiter {
		private final java.net.InetAddress src;
		private final java.net.InetAddress dst;
		private final int srcPort;
		private final int dstPort;
		private final PacketHandler sender;

		Waiter(java.net.InetAddress src, java.net.InetAddress dst, int srcPort, int dstPort, PacketHandler sender) {
			this.src = src;
			this.dst = dst;
			this.srcPort = srcPort;
			this.dstPort = dstPort;
			this.sender = sender;
		}
	}

	private Waiter[] waiters = new Waiter[65536];

	@Override
	public boolean handlePacket(int protocol, ByteBuffer data, java.net.InetAddress from,
			java.net.InetAddress to, PacketHandler sender) {
		int start = data.position();
		int size = data.limit() - start;
		// log.fine(format(data.array(), start, size));
		int srcPort = 0xFFFF & data.getShort();
		int dstPort = 0xFFFF & data.getShort();
		if (dstPort != 53) { // just ignore all non-DNS messages
			return false;
		}

		int len = 0xFFFF & data.getShort();
		int checksum = 0xFFFF & data.getShort();
		// log.fine(format("srcPort=", srcPort, ", dstPort=", dstPort, ", length=", len, ", checksum=",
		//		String.format("%04X", checksum)));
		if (len != size) {
			log.warning("Packet size " + size + " does not match specified length " + len + " (dropped)");
			return true;
		}

		if (checksum != 0) { // checksum is optional in UDP, so in theory client may send zero and we should ignore it
			data.putShort(start + 6, (short) 0);
			long pseudoHeaderChecksum = 17 + len; // UDP protocol + length 
			pseudoHeaderChecksum += checkSumFromIP(from);
			pseudoHeaderChecksum += checkSumFromIP(to);
			int cs = checksum(data.array(), start, size + start, pseudoHeaderChecksum);
			if (cs != checksum) {
				log.log(java.util.logging.Level.WARNING, "{0}", format("Bad UDP checksum ",
						Integer.toHexString(checksum), " (->", Integer.toHexString(cs), ")!"));
				log.log(java.util.logging.Level.INFO, "{0}", format(data.array(), start, size));
				return false;
			}
		}

		processDNSrequest(data, start + 8, size - 8, from, to, srcPort, dstPort, sender);
		return true;
	}

	private void processDNSrequest(ByteBuffer buf, int start, int length, java.net.InetAddress src,
			java.net.InetAddress dst, int srcPort, int dstPort, PacketHandler sender) {
		byte[] ba = buf.array();
		log.log(java.util.logging.Level.FINEST, "{0}", format(ba, start, length));
		try {
			if (socket == null || socket.isClosed()) {
				socket = new java.net.DatagramSocket();
				if (dsf != null) {
					dsf.protect(socket);
				}
				new UdpReceiverThread(socket);
			}
			System.arraycopy(ba, start, sendingPacketData, 0, length);
			int id = ((0xFF & sendingPacketData[0]) << 8) | (0xFF & sendingPacketData[1]);
			String domain = getDomain(ba, start + 12);
			log.finer("Domain=" + domain);

//			Configurator cfg = Configurator.getInstance();
//
//			java.net.InetAddress[] fixedResponse = cfg.getFixedResponse(domain);
//			if (fixedResponse != null) {
//				if (sendFixedResponse(ba, start, length, fixedResponse, src, dst, srcPort, dstPort, sender)) {
//					return;
//				}
//			}

			waiters[id % waiters.length] = new Waiter(src, dst, srcPort, dstPort, sender);
			log.log(java.util.logging.Level.FINEST, "{0}", format(sendingPacketData, start, length));
			sendingPacket.setLength(length);
			java.net.SocketAddress isa = null;
			if (isa == null) {
				// we should have "protected" socket and never get our own packet... so, send it to target.
				sendingPacket.setSocketAddress(isa = new java.net.InetSocketAddress(dst, dstPort));
				log.log(java.util.logging.Level.FINER, "{0}",
						format("sending request for ", domain, " to ", isa, " (standard)"));
			} else {
				log.log(java.util.logging.Level.FINER, "{0}",
						format("sending request for ", domain, " to ", isa, " (override)"));
				sendingPacket.setSocketAddress(isa);
			}
			socket.send(sendingPacket);
		} catch (Exception ex) {
			log.log(java.util.logging.Level.WARNING, "{0}", format(ex));
		}
	}

	private final byte[] fixedReplyData = new byte[TunDNSResolver.MAX_PACKET_SIZE];
	private final ByteBuffer fixedReplyBuffer = ByteBuffer.wrap(fixedReplyData);

	private boolean sendFixedResponse(byte[] requestData, final int offset, final int length,
			java.net.InetAddress[] reply, java.net.InetAddress src, java.net.InetAddress dst, int srcPort, int dstPort,
			PacketHandler sender) {
		int pos = offset + 12;
		int end = offset + length;
		if (end > requestData.length) { // shouldn't happen
			end = requestData.length;
		}
		while (pos < end) {
			int size = requestData[pos++];
			if (size == 0) {
				break;
			}
			if (size + pos > end) {
				return false; // shouldn't happen if we're here
			}
			pos += size;
		}

		int type = 0xFF & (requestData[++pos]);
		if (type != 1 && type != 28 && type != 255) { // answer only A, AAAA and ANY requests
			return false;
		}
		pos += 3;
		if (pos > end) { // something's wrong with packet length (truncated?)
			return false;
		}

		int len = pos - offset;
		fixedReplyBuffer.limit(fixedReplyData.length);

		System.arraycopy(requestData, offset, fixedReplyData, 8, len);
		fixedReplyBuffer.position(len + 8);
		for (int i = 0; i < reply.length; i++) {
			fixedReplyBuffer.putShort((short) 0xC00C);
			byte[] addr = reply[i].getAddress();
			fixedReplyBuffer.putShort((short) (addr.length == 4 ? 1 : 28));
			fixedReplyBuffer.putInt(0x00010000);
			fixedReplyBuffer.putShort((short) 60); // TTL
			fixedReplyBuffer.putShort((short) addr.length);
			fixedReplyBuffer.put(addr);
			len += 12 + addr.length;
		}

		fixedReplyBuffer.putShort(10, (short) 0x8180);
		fixedReplyBuffer.put(15, (byte) reply.length);
		fixedReplyBuffer.put(19, (byte) 0); // reset EDNS flag (if any)

		int lenWithHeader = len + 8;
		fixedReplyBuffer.putShort(0, (short) dstPort);
		fixedReplyBuffer.putShort(2, (short) srcPort);
		fixedReplyBuffer.putShort(4, (short) lenWithHeader);
		fixedReplyData[6] = fixedReplyData[7] = 0; // reset it first
		long pseudoHeaderChecksum = 17 + lenWithHeader; // UDP protocol + length with UDP header
		pseudoHeaderChecksum += checkSumFromIP(src);
		pseudoHeaderChecksum += checkSumFromIP(dst);
		fixedReplyBuffer.putShort(6, (short) checksum(fixedReplyData, 0, lenWithHeader, pseudoHeaderChecksum));
		fixedReplyBuffer.flip();
		log.finest(format(fixedReplyData, fixedReplyBuffer.position(), fixedReplyBuffer.limit()).toString());
		sender.handlePacket(PROTO_UDP, fixedReplyBuffer, dst, src, null);
		return true;
	}

	private String getDomain(byte[] data, int offset) {
		StringBuilder sb = new StringBuilder();
		while (offset < data.length) {
			int size = data[offset++];
			if (size == 0) {
				break;
			}
			if (size + offset > data.length) {
				log.warning("Couldn't parse domain name");
				break;
			}
			sb.append(new String(data, offset, size, UTF8)).append('.');
			offset += size;
		}
		return sb.delete(sb.length() - 1, 10000).toString();
	}

	private static final java.nio.charset.Charset UTF8 = java.nio.charset.Charset.forName("UTF8");

	static int checkSumFromIP(java.net.InetAddress addr) {
		byte[] ba = addr.getAddress();
		int res = 0;
		for (int i = 0; i < ba.length;) {
			res += ((0xFF & ba[i++]) << 8) | (0xFF & ba[i++]);
		}
		return res;
	}

	private class UdpReceiverThread extends Thread {
		private final java.net.DatagramSocket socket;

		UdpReceiverThread(java.net.DatagramSocket socket) {
			this.socket = socket;
			setDaemon(true);
			start();
		}

		public void run() {
			try {
				byte[] data = new byte[TunDNSResolver.MAX_PACKET_SIZE];
				ByteBuffer dataBuffer = ByteBuffer.wrap(data);
				int dataSize = TunDNSResolver.MAX_PACKET_SIZE - 8;
				java.net.DatagramPacket dp = new java.net.DatagramPacket(data, 8, dataSize);
				while (!stopping) {
					socket.receive(dp);
					log.log(java.util.logging.Level.FINER, "{0}", format("Message from", dp.getSocketAddress()));
					int len = dp.getLength();
					log.log(java.util.logging.Level.FINEST, "{0}", format(data, 8, len));
					int id = ((0xFF & data[8]) << 8) | (0xFF & data[9]);
					Waiter w = waiters[id % waiters.length];
					if (w == null) {
						log.info("Nobody's waiting for DNS reply " + id);
					} else {
						int lenWithHeader = len + 8;
						dataBuffer.position(0);
						dataBuffer.putShort(0, (short) w.dstPort);
						dataBuffer.putShort(2, (short) w.srcPort);
						dataBuffer.putShort(4, (short) lenWithHeader);
						data[6] = data[7] = 0; // reset it first
						long pseudoHeaderChecksum = 17 + lenWithHeader; // UDP protocol + length with UDP header 
						pseudoHeaderChecksum += checkSumFromIP(w.src);
						pseudoHeaderChecksum += checkSumFromIP(w.dst);
						dataBuffer.putShort(6, (short) checksum(data, 0, lenWithHeader, pseudoHeaderChecksum));
						dataBuffer.limit(lenWithHeader);
						w.sender.handlePacket(PROTO_UDP, dataBuffer, w.dst, w.src, null);
					}
					dp.setLength(dataSize);
				}
			} catch (Exception ex) {
				if (!stopping) {
					log.log(java.util.logging.Level.WARNING, "{0}", format(ex));
				}
			} finally {
				socket.close();
				log.fine("stopped");
			}
		}
	}

	@Override
	public void terminate() {
		log.fine("terminate");
		stopping = true;
		if (socket != null) {
			socket.close();
		}
	}
}

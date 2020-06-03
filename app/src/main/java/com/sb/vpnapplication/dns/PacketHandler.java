package com.sb.vpnapplication.dns;

import com.sb.vpnapplication.logger.LoggerHelper;

public abstract class PacketHandler {
	final java.util.logging.Logger log = LoggerHelper.getLogger(getClass());

	public abstract boolean handlePacket(int protocol, java.nio.ByteBuffer data, java.net.InetAddress from,
			java.net.InetAddress to, PacketHandler sender);

	public void terminate() {
		log.fine("terminate/" + log.getName());
	}

	static int checksum(byte[] data, int start, int end, long initialValue) {
		int pos = start;
		int lim = end - 1;
		long sum = initialValue;
		while (pos < lim) {
			sum += ((0xFF & data[pos++]) << 8) | (0xFF & data[pos++]);
		}
		if (pos < end) { // odd number in the buffer
			sum += ((0xFF & data[pos]) << 8);
		}
		sum = (sum >>> 16) + (sum & 0xffff);
		sum += sum >>> 16;
		pos = 0xFFFF & (int) ~sum;
		return pos;
	}
}

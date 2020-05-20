package com.sb.vpnapplication.dns;

import static com.sb.vpnapplication.dns.LoggerFormatter.format;

import java.nio.ByteBuffer;

public class RawPacketSender extends PacketHandler {
	private final java.nio.channels.FileChannel outChannel;

	RawPacketSender(java.nio.channels.FileChannel outChannel) {
		this.outChannel = outChannel;
	}

	@Override
	public boolean handlePacket(int protocol, java.nio.ByteBuffer data, java.net.InetAddress from, java.net.InetAddress to, PacketHandler sender) {
		try {
			int packetLength = data.limit() - data.position();
			log.log(java.util.logging.Level.FINEST, "{0}", format(data.array(), data.position(), packetLength));
			int offset = data.position();
			java.net.InetAddress src = getSourceIP(data, offset);
			java.net.InetAddress dst = getDestinationIP(data, offset);
			int checksum = getIPv4CheckSum(data, offset);
			log.log(java.util.logging.Level.FINER, "{0}", format("SRC=", src, ", DST=", dst, ", IPv4 Checksum=",
					Integer.toHexString(checksum), ", Length=", packetLength));

			int res = outChannel.write(data);
			if (data.remaining() != 0) {
				log.warning("Not all data written (" + res + " out of " + (data.remaining() + res) + ")");
			}
		} catch (java.io.IOException ex) {
			log.warning(format("Write to TUN failed", ex).toString());
		}
		return true;
	}

	private static java.net.InetAddress getSourceIP(ByteBuffer bb, int offset) {
		return TunDNSResolver.convertIP(bb.getInt(12 + offset));
	}

	private static java.net.InetAddress getDestinationIP(ByteBuffer bb, int offset) {
		return TunDNSResolver.convertIP(bb.getInt(16 + offset));
	}

	private static int getIPv4CheckSum(ByteBuffer bb, int offset) {
		return 0xFFFF & bb.getShort(10 + offset);
	}
}

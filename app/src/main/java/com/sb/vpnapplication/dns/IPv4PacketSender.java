package com.sb.vpnapplication.dns;

import static com.sb.vpnapplication.logger.LoggerFormatter.format;

public class IPv4PacketSender extends PacketHandler {
	private final byte[] outBufferData = new byte[TunDNSResolver.MAX_PACKET_SIZE];
	private final java.nio.ByteBuffer outBuffer = java.nio.ByteBuffer.wrap(outBufferData);
	private final java.nio.channels.FileChannel outChannel;

	IPv4PacketSender(java.nio.channels.FileChannel outChannel) {
		this.outChannel = outChannel;
	}

	@Override
	public boolean handlePacket(int protocol, java.nio.ByteBuffer data, java.net.InetAddress from,
			java.net.InetAddress to, PacketHandler sender) {
		synchronized (this) {
			outBuffer.position(0);
			outBuffer.limit(TunDNSResolver.MAX_PACKET_SIZE);
			outBuffer.putShort((short) 0x4500);
			outBuffer.putShort((short) (data.remaining() + 20));
			outBuffer.putShort(getNextShortID());
			outBuffer.putShort((short) 0);
			outBuffer.put((byte) 60); // TTL (whatever) 
			outBuffer.put((byte) protocol);
			outBuffer.putShort((short) 0); // future checksum
			outBuffer.put(from.getAddress());
			outBuffer.put(to.getAddress());
			outBuffer.putShort(10, (short) checksum(outBufferData, 0, 20, 0));
			outBuffer.put(data);
			outBuffer.flip();
			try {
				int res = outChannel.write(outBuffer);
				// Not on Android	outChannel.force(true);
				if (outBuffer.remaining() != 0) {
					log.warning("Not all data written (" + res + " out of " + (outBuffer.remaining() + res) + ")");
				}
			} catch (java.io.IOException ex) {
				log.warning(format("Write to TUN failed", ex).toString());
				return false;
			}
		}
		return true;
	}

	private static short nextId = (short) System.currentTimeMillis();

	private static short getNextShortID() {
		synchronized (IPv4PacketSender.class) {
			return (short) (nextId++);
		}
	}
}

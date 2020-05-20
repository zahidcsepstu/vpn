package com.sb.vpnapplication.dns;


import static com.sb.vpnapplication.dns.LoggerFormatter.format;

public class VPNPacketHandler extends PacketHandler {
	private final java.net.InetSocketAddress target;
	private final java.net.DatagramSocket socket;
	private boolean destroyed = false;
	private PacketHandler sender;
	private final byte[] receiverData = new byte[TunDNSResolver.MAX_PACKET_SIZE];
	private final byte[] senderData = new byte[TunDNSResolver.MAX_PACKET_SIZE];
	private final java.net.DatagramPacket receiverPacket = new java.net.DatagramPacket(receiverData,
			receiverData.length);
	private final java.net.DatagramPacket senderPacket = new java.net.DatagramPacket(senderData, receiverData.length);
	private final java.nio.ByteBuffer receiverBuffer = java.nio.ByteBuffer.wrap(receiverData);

	public VPNPacketHandler(java.net.InetSocketAddress target, SocketProtector dsf) throws java.net.SocketException {
		log.fine("created " + target);
		this.target = target;
		this.socket = new java.net.DatagramSocket();
		if (dsf != null) {
			dsf.protect(this.socket);
		}
		Thread receiverThread = new Thread() {
			public void run() {
				while (!socket.isClosed()) {
					try {
						receivePacketFromSocket(sender);
					} catch (java.io.IOException ex) {
						if (!destroyed) {
							log.log(java.util.logging.Level.WARNING, "{0}", format("Receive failed ", ex));
						}
					}
				}
			}
		};
		receiverThread.setDaemon(true);
		receiverThread.start();
	}

	private void receivePacketFromSocket(PacketHandler sender) throws java.io.IOException {
		receiverPacket.setLength(receiverData.length);
		socket.receive(receiverPacket);
		int size = receiverPacket.getLength();
		java.net.SocketAddress remote = receiverPacket.getSocketAddress();
		log.log(java.util.logging.Level.FINEST, "{0}",
				format("Received packet from ", remote, " ", format(receiverData, size)));
		if (sender != null) {
			receiverBuffer.position(0);
			receiverBuffer.limit(size);
			sender.handlePacket(0, receiverBuffer, null, null, null);
		}
	}

	public boolean handlePacket(int protocol, java.nio.ByteBuffer data, java.net.InetAddress from,
			java.net.InetAddress to, PacketHandler sender) {
		if (destroyed) {
			return false;
		}

		this.sender = sender;
		int size = data.limit(); // we have full IPv4 packet starting at 0 offset
		data.position(0);
		try {
			data.get(senderData, 0, size);
			senderPacket.setLength(size);
			senderPacket.setSocketAddress(target);
			socket.send(senderPacket);
			return true;
		} catch (java.io.IOException ex) {
			log.log(java.util.logging.Level.WARNING, "{0}", format("Couldn't send packet to ", target, " ", ex));
			return false;
		}
	}

	@Override
	public void terminate() {
		log.fine("destroyed " + target);
		destroyed = true;
		socket.close();
	}

	protected void finalize() throws Throwable {
		if (!destroyed) {
			terminate();
		}
	}
}

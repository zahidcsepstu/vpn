package com.sb.vpnapplication.dns;

public interface SocketProtector {
	public boolean protect(java.net.Socket socket);

	public boolean protect(java.net.DatagramSocket socket);
}

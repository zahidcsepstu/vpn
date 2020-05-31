package com.sb.vpnapplication.dns;

public class InetAddressWithMask {
	private final java.net.InetAddress addr;
	private final short bits;
	private final long start;
	private final long end;

	public InetAddressWithMask(java.net.InetAddress addr, short bits) {
		this.addr = addr;
		this.bits = bits;
		long mask = (0xFFFFFFFF >>> (32 - bits)) << (32 - bits);
		this.start = toLong(addr.getAddress()) & mask;
		this.end = start | ~mask;
	}


	static long toLong(byte[] ba) {
		return (((long) (0xFF & ba[0]) << 24)) | ((0xFF & ba[1]) << 16) | ((0xFF & ba[2]) << 8) | (0xFF & ba[3]);
	}



	public java.net.InetAddress getAddress() {
		return addr;
	}

	@Override
	public int hashCode() {
		return addr.hashCode() ^ bits;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof InetAddressWithMask)) {
			return false;
		}
		InetAddressWithMask o = (InetAddressWithMask) obj;
		return o.addr.equals(this.addr) && o.bits == this.bits;
	}



	public String getHostAddress() {
		return addr.getHostAddress();
	}

	public short getBits() {
		return bits;
	}

	public String toString() {
		return addr.getHostAddress() + "/" + bits;
	}

	public static InetAddressWithMask parse(String addrWithBits) {
		int pos = addrWithBits.lastIndexOf('/');
		java.net.InetAddress addr;
		short bits = 0;
		if (pos > 0) {
			bits = Short.parseShort(addrWithBits.substring(pos + 1));
			addrWithBits = addrWithBits.substring(0, pos);
		}
		try {
			addr = java.net.InetAddress.getByName(addrWithBits);
			if (bits == 0) {
				bits = (short) (addr.getAddress().length * 8);
			}
		} catch (java.net.UnknownHostException ex) {
			return null;
		}
		return new InetAddressWithMask(addr, bits);
	}

}

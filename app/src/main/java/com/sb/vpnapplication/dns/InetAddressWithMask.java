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
	public InetAddressWithMask(java.net.InetAddress addr) {
		this(addr, (short) addr.getAddress().length * 8);
	}

	public InetAddressWithMask(java.net.InetAddress addr, int bits) {
		this(addr, (short) bits);
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

	public boolean isMatches(java.net.InetAddress other) {
		byte[] ob = other.getAddress();
		if (addr.getAddress().length != ob.length) {
			return false;
		}
		long al = toLong(ob);
		return al >= start && al <= end;
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

	public static InetAddressWithMask[] parseList(String listOfAddrsWithBits) {
		java.util.ArrayList<InetAddressWithMask> res = new java.util.ArrayList<>();
		for (String addrWithBits : listOfAddrsWithBits.split("[,\\s]+")) {
			int pos = addrWithBits.lastIndexOf('/');
			java.net.InetAddress addr;
			short bits = 0;
			if (pos > 0) {
				bits = Short.parseShort(addrWithBits.substring(pos + 1));
				addrWithBits = addrWithBits.substring(0, pos);
			}
			try {
				addr = java.net.InetAddress.getByName(addrWithBits);
				if (pos <= 0) {
					bits = (short) (addr.getAddress().length * 8);
				}
				res.add(new InetAddressWithMask(addr, bits));
			} catch (java.net.UnknownHostException shouldNotHappen) {
				System.err.println("Couldn't create IP address from '" + addrWithBits + "'");
				shouldNotHappen.printStackTrace();
			}
		}
		return res.toArray(new InetAddressWithMask[res.size()]);
	}
}

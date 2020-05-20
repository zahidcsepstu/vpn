package com.sb.vpnapplication.dns;

public class DefaultPacketHandler extends PacketHandler {
    public boolean handlePacket(int protocolIndex, java.nio.ByteBuffer data, java.net.InetAddress from,
                                java.net.InetAddress to, PacketHandler sender) {

        IPProtocol proto;
        if (protocolIndex >= TunDNSResolver.PROTOCOLS.length) {
            proto = IPProtocol.NON_SUPPORTED;
        } else {
            proto = TunDNSResolver.PROTOCOLS[protocolIndex];
        }

        log.info("Protocol " + proto + " (" + protocolIndex + ") not supported (yet?). Packet dropped");
        return false;
    }
}

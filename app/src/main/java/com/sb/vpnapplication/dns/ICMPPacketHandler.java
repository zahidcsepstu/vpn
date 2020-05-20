package com.sb.vpnapplication.dns;
import static com.sb.vpnapplication.dns.LoggerFormatter.format;

public class ICMPPacketHandler extends PacketHandler {
    @Override
    public boolean handlePacket(int protocol, java.nio.ByteBuffer data, java.net.InetAddress from, java.net.InetAddress to,PacketHandler sender) {
        int start = data.position();
        int type = data.get();

        if (type != 8) { // echo request
            log.log(java.util.logging.Level.FINE, "{0}",
                    format("icmp type ", type, " ignored (only 8/ECHO_REQ expected)"));
            return false;
        }

        byte code = data.get();
        int checksum = 0xFFFF & data.getShort();
        log.log(java.util.logging.Level.FINER, "{0}",
                format("ICMP code=", code, ", ICMP Checksum=", Integer.toHexString(checksum)));
        data.putShort(start + 2, (short) 0); // reset old value for calculation now and later
        int calculatedChecksum = checksum(data.array(), start, data.limit(), 0);
        if (calculatedChecksum != checksum) {
            log.log(java.util.logging.Level.WARNING, "{0}", format("Bad ICMP checksum ", Integer.toHexString(checksum),
                    " (->", Integer.toHexString(calculatedChecksum), ")!"));
            return false;
        }

        data.put(start, (byte) 0); // ICMP_REPLY
        checksum = checksum(data.array(), start, data.limit(), 0);
        data.putShort(start + 2, (short) checksum);
        // log.finer("new cs=" + Integer.toHexString(checksum));
        data.position(start);
        sender.handlePacket(1, data, to, from, this); // sending reply back to client
        return true;
    }
}

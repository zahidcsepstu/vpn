package com.sb.vpnapplication.dns;

import android.util.Log;

import java.nio.ByteBuffer;
import static com.sb.vpnapplication.dns.LoggerFormatter.format;

public class TunDNSResolver implements Runnable {
    private static final java.util.logging.Logger log = LoggerHelper.getLogger(TunDNSResolver.class);
    static final int MAX_PACKET_SIZE = 2048; // normal MTU is 1500, so 2k is good enough

    private final PacketHandler icmpHandler = new ICMPPacketHandler();
    private final PacketHandler udpHandler;
    private final java.nio.channels.FileChannel inChannel;
    private final SocketProtector dsf;
    private final IPv4PacketSender replySender;
    private final RawPacketSender rawSender;
    private PacketHandler vpnHandler;
    private boolean closing = false;
    private boolean closed = false;
    private static final java.util.HashSet<java.net.InetAddress> alwaysIgnore = buildAddrList(
            "239.255.255.250, 224.0.0.22");

    static final IPProtocol[] PROTOCOLS = { IPProtocol.IP, IPProtocol.ICMP, IPProtocol.IGMP, IPProtocol.GGP,
            IPProtocol.IP_ENCAP, IPProtocol.ST2, IPProtocol.TCP, IPProtocol.CBT, IPProtocol.EGP, IPProtocol.IGP,
            IPProtocol.BBN_RCC_MON, IPProtocol.NVP_II, IPProtocol.PUP, IPProtocol.ARGUS, IPProtocol.EMCON,
            IPProtocol.XNET, IPProtocol.CHAOS, IPProtocol.UDP, IPProtocol.MUX, IPProtocol.DCN_MEAS };
    private static final int IP_HEADER_LENGTH = 20;
    private Thread readerThread;

    private static  TunnelHandler tunnelHandler;
    private static TunnelHandler.ConnectionState tunnelState = TunnelHandler.ConnectionState.NO_CONNECTED;

    public TunDNSResolver(SocketProtector dsf, java.io.FileInputStream in, java.io.FileOutputStream out, TunnelHandler th) {
        this.udpHandler = new UDPPacketHandler(dsf);
        this.dsf = dsf;
        this.inChannel = in.getChannel();
        java.nio.channels.FileChannel outChannel = out.getChannel();
        this.replySender = new IPv4PacketSender(outChannel);
        this.rawSender = new RawPacketSender(outChannel);
        tunnelHandler = th;
        tunnelHandler.setOnConnectionStateUpdate(new TunnelConnectionStateChangeListener());
        tunnelState = TunnelHandler.getState();
    }

    public synchronized TunDNSResolver start() {
        if (readerThread != null && readerThread.isAlive()) {
            log.warning("Thread already running, stop it first if you want to restart");
            return this;
        }
        closed = closing = false;
        Thread t = new Thread(this, "TunDNSResolver");
        t.setDaemon(true);
        t.start();
        readerThread = t;
        return this;
    }

    private void terminateHandlers() {
        if (udpHandler != null) {
            udpHandler.terminate();
        }
        if (icmpHandler != null) {
            icmpHandler.terminate();
        }
        if (vpnHandler != null) {
            vpnHandler.terminate();
        }
    }

    public void stop() {
        closing = true;
        final Thread shutdownThread = new Thread() {
            public void run() {
                try {
                    terminateHandlers(); // this likely requires IO, which is not permitted in UI thread
                } catch (Throwable ignore) {
                    log.warning(format("Exception in terminateHandlers ", ignore).toString());
                }
                synchronized (this) {
                    notifyAll();
                }
            }
        };
        shutdownThread.start();
        try {
            synchronized (shutdownThread) {
                shutdownThread.wait(1000);
            }
        } catch (Throwable ex) {

        }
        if (readerThread != null && readerThread.isAlive()) {
            if (inChannel != null && inChannel.isOpen()) {
                try {
                    inChannel.close();
                } catch (java.io.IOException ex) {
                    log.warning(format(ex).toString());
                }
            }
            readerThread.interrupt();
            synchronized (this) {
                if (!closed) {
                    try {
                        wait(2000);
                    } catch (InterruptedException ex) {
                        log.warning(format(ex).toString());
                    }
                }
                if (!closed) {
                    log.warning("Main receiver thread is not finished");
                }
            }
        }
    }

    private class TunnelConnectionStateChangeListener implements TunnelHandler.OnConnectionStateUpdate{

        @Override
        public void onUpdate(TunnelHandler.ConnectionState state, String serverAddress) {
            tunnelState = state;
            log.info("on update tunnel["+ serverAddress+ "] connection state: " + tunnelState);
        }
    }
    private PacketHandler getVpnHandler(SocketProtector factory) {

        try {
            java.net.InetAddress ia = java.net.InetAddress.getByName("138.197.67.177");
            return new VPNPacketHandler(new java.net.InetSocketAddress(ia, 54085), factory);
        } catch (java.net.SocketException ex) {
            log.log(java.util.logging.Level.WARNING, "{0}", format("Couldn't create socket for " , " ", ex));
            return null;
        } catch (java.net.UnknownHostException ex) {
            log.log(java.util.logging.Level.WARNING, "{0}", format("Couldn't resolve " , " ", ex));
            return null;
        }
    }

    public void run() {
        try {
            byte[] ba = new byte[MAX_PACKET_SIZE];
            ByteBuffer bb = ByteBuffer.wrap(ba);
            int size = 0;
            int packetLength = 0;
            bb.limit(ba.length);
            PacketHandler defaultHandler = vpnHandler = getVpnHandler(dsf);
            if (defaultHandler == null) {
                defaultHandler = new DefaultPacketHandler();
            }

            log.info(RemoteLogger.log("TUN handler is running"));
            log.info("SLVA: Tunnel handler is running");
            while (true) {
                try {
                    int i = inChannel.read(bb);
                    if (i < 0) {
                        break;
                    }

                    if (i == 0) {
                        Thread.sleep(1);
                        continue;
                    }

                    size += i;
                    if (packetLength == 0) {
                        if (size < IP_HEADER_LENGTH) {
                            continue;
                        }
                        packetLength = getLength(bb);
                        if (packetLength > ba.length) {
                            log.warning("Packet length is too large for the buffer: " + packetLength + " (dropped)");
                            bb.clear();
                            size = packetLength = 0;
                            continue;
                        }
                        if (packetLength > size) {
                            continue;
                        }
                    }
                    if (size < packetLength) {
                        log.fine("Keep reading: " + size);
                        continue; // keep reading
                    }

                    size -= packetLength;
                    bb.limit(packetLength);
                    java.net.InetAddress src = getSourceIP(bb);
                    java.net.InetAddress dst = getDestinationIP(bb);

                    Log.d("cvghnvbhjgjhgj","dst="+dst.getHostAddress());
                    Log.d("cvghnvbhjgjhgj","src="+src.getHostAddress());

                    // check if netflix streaming ip and redirect to our proxy server

                    if (tunnelHandler.sendToTunnel(dst)) {
                        if(tunnelState == TunnelHandler.ConnectionState.CONNECTED) {
                            tunnelHandler.handleTunnelPacket(bb, packetLength);
                            bb.clear();
                            size = packetLength = 0;
                            continue;
                        }else if(tunnelState == TunnelHandler.ConnectionState.NO_CONNECTED){
                            tunnelHandler.startConnection();
                        }
                    }
                    int checksum = getIPv4CheckSum(bb);

                    int protocolIndex = getProtocol(bb);
                    IPProtocol proto;
                    if (protocolIndex >= PROTOCOLS.length) {
                        proto = IPProtocol.NON_SUPPORTED;
                    } else {
                        proto = PROTOCOLS[protocolIndex];
                    }

                    if (alwaysIgnore.contains(dst)) {
                        bb.clear();
                        size = packetLength = 0;
                        continue;
                    }

                    if (proto != IPProtocol.TCP) { // There could be a lot of data going through TCP
                        log.log(java.util.logging.Level.FINEST, "{0}", format(ba, i));
                    }
                    log.log(java.util.logging.Level.FINER, "{0}",
                            format("SRC=", src, ", DST=", dst, ", IPv4 Checksum=", Integer.toHexString(checksum),
                                    ", Length=", packetLength, ", protocol=", proto, " (", i, ")"));

                    ba[10] = ba[11] = 0;
                    int calculatedChecksum = PacketHandler.checksum(ba, 0, IP_HEADER_LENGTH, 0);
                    if (calculatedChecksum != checksum) {
                        log.warning(format("Bad IPv4 checksum ", Integer.toHexString(checksum), " (->",
                                Integer.toHexString(calculatedChecksum), ")! packetLength=", packetLength).toString());
                        log.log(java.util.logging.Level.FINER, "{0}", format(ba, packetLength));
                        size = packetLength = 0;
                        bb.position(0);
                        bb.limit(ba.length);
                        continue;
                    }
                    bb.putShort(10, (short) checksum); // restore checksum for VPN handler

                    bb.position(IP_HEADER_LENGTH);
                    // log.log(java.util.logging.Level.FINE, "{0}",  format("Protocol=", proto));
                    Log.d("cvghnvbhjgjhgj","here");
                    switch (proto) {
                        case ICMP:
                            Log.d("cvghnvbhjgjhgj","icmp");
                            if (!icmpHandler.handlePacket(protocolIndex, bb, src, dst, replySender)) {
                                defaultHandler.handlePacket(protocolIndex, bb, src, dst, rawSender);
                            }
                            break;
                        case UDP:
                            Log.d("cvghnvbhjgjhgj","udp");
                            if (!udpHandler.handlePacket(protocolIndex, bb, src, dst, replySender)) {
                                defaultHandler.handlePacket(protocolIndex, bb, src, dst, rawSender);
                            }
                            break;
                        case TCP:
                            Log.d("cvghnvbhjgjhgj","TCP");
                            break;
                        default:
                            if (!defaultHandler.handlePacket(protocolIndex, bb, src, dst, rawSender)) {
                                log.warning("Default handler refused " + proto + " packet");
                            }
                            break;
                    }

                    if (size > 0) { // just in case there was more than packet in pipe
                        if (size > ba.length) { // corrupted or incomplete packet? clear buffer and continue
                            size = 0;
                        } else {
                            System.arraycopy(ba, packetLength, ba, 0, size);
                        }
                    }
                    bb.limit(ba.length);
                    bb.position(size);
                    packetLength = 0;
                } catch (InterruptedException | java.nio.channels.AsynchronousCloseException ex) {
                    if (!closing) { // external forced close or interrupt?..
                        log.warning(format(ex).toString());
                    }
                    break;
                } catch (Exception ex) { // clear buffer and continue reading
                    log.warning(format(ex).toString());
                    bb.clear();
                    size = packetLength = 0;
                }
            }
        } catch (Exception ex) {
            log.warning(format(ex).toString());
        }
        log.info(RemoteLogger.log("TUN handler is stopped"));
        log.info("SLVA: Tunnel handler is stopped");
        synchronized (this) {
            this.closed = true;
            terminateHandlers();
        }
    }

    static java.net.InetAddress convertIP(int ip) {
        byte[] b = new byte[] { (byte) ((ip >> 24) & 0xFF), (byte) ((ip >> 16) & 0xFF), (byte) ((ip >> 8) & 0xFF),
                (byte) (ip & 0xFF) };
        try {
            return java.net.InetAddress.getByAddress(b);
        } catch (java.net.UnknownHostException ex) {
            log.warning(format(ex).toString());
            return null;
        }
    }

    private static java.util.HashSet<java.net.InetAddress> buildAddrList(String addrs) {
        java.util.HashSet<java.net.InetAddress> res = new java.util.HashSet<>();
        for (String s : addrs.split("[,\\s]+")) {
            try {
                res.add(java.net.InetAddress.getByName(s));
            } catch (java.net.UnknownHostException ignore) {
                log.warning(s + " /" + ignore.toString());
            }
        }
        return res;
    }

    private static java.net.InetAddress getSourceIP(ByteBuffer bb) {
        return convertIP(bb.getInt(12));
    }

    private static java.net.InetAddress getDestinationIP(ByteBuffer bb) {
        return convertIP(bb.getInt(16));
    }

    private static int getIPv4CheckSum(ByteBuffer bb) {
        return 0xFFFF & bb.getShort(10);
    }

    private static int getProtocol(ByteBuffer bb) {
        return 0xFF & bb.get(9);
    }

    private static int getLength(ByteBuffer bb) {
        return 0xFFFF & bb.getShort(2);
    }

}
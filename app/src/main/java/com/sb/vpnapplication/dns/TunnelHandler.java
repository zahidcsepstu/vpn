package com.sb.vpnapplication.dns;

import android.os.AsyncTask;
import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.sb.vpnapplication.dns.LoggerFormatter.format;

public class TunnelHandler {
    private static Logger log = LoggerHelper.getLogger(TunnelHandler.class);
    private static final long TUNNEL_INACTIVE_TIMEOUT_SECOND = 300;
    private static final long TUNNEL_TRAFFIC_MONITOR_INTERVAL_SECOND = 30;
    private static final int TUNNEL_SERVER_PORT = 54076;
    private static boolean closed = false;

    // holds the value for connection state with tunnel server
    public enum ConnectionState{
        NO_CONNECTED,
        CONNECTING,
        CONNECTED,
        DISABLED,
    }
    private static ConnectionState state = ConnectionState.NO_CONNECTED;

    // the start packet
    private static final byte _packet_marker = (byte) 0x81;
    private static final byte _end_connection = (byte) 0x82;

    private DatagramChannel mTunnel;
    private static FileChannel mVpnOut = null;


    private Thread tunnelReadThread;
    private Thread tunnelTrafficMonitorThread;
    private OnTunnelConnection tunnelConnection;
    private static OnConnectionStateUpdate onConnectionStateUpdate;
    private SocketProtector socketProtector;

    private static String tunnelServerAddress = "";
    private static int tunnelServerPort;

    private static long lastTunnelEvent = 0;

    public TunnelHandler(SocketProtector socketProtector, OnTunnelConnection onTunnelConnection) {
        this.tunnelConnection = onTunnelConnection;
        this.socketProtector = socketProtector;
    }

    public void finish() {
        endConnection();
        if (tunnelReadThread != null && tunnelReadThread.isAlive()) {
            tunnelReadThread.interrupt();
        }
        if (tunnelTrafficMonitorThread != null && tunnelTrafficMonitorThread.isAlive()) {
            tunnelTrafficMonitorThread.interrupt();
        }
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
        if (this.tunnelConnection != null) {
            this.tunnelConnection.onClose();
        }
    }

    public void setOnConnectionStateUpdate(OnConnectionStateUpdate connectionStateUpdate) {
        onConnectionStateUpdate = connectionStateUpdate;
    }

    public void setVpnOut(FileChannel vpnOut) {
        mVpnOut = vpnOut;
    }

    public static ConnectionState getState() {
        return state;
    }

    public DatagramChannel getTunnel() {
        return mTunnel;
    }

    /**
     * Initialize the tunnel connection
     */
    public void initConnection(){
        try {



                Random rand = new Random();
                tunnelServerPort = 54085;
                tunnelServerAddress ="138.197.67.177";


                state = ConnectionState.NO_CONNECTED;
                if (onConnectionStateUpdate != null) {
                    onConnectionStateUpdate.onUpdate(state, (tunnelServerAddress + ":" + tunnelServerPort));
                }

                SocketAddress server = new InetSocketAddress(tunnelServerAddress, tunnelServerPort);

                mTunnel = DatagramChannel.open();

                // Protect this socket, so package send by it will not be feedback to the vpn service.
                if (!this.socketProtector.protect(mTunnel.socket())) {
                    this.tunnelConnection.onFail("Cannot protect the tunnel");
                    throw new IllegalStateException("Cannot protect the tunnel");
                }

                log.info("SLVA: connecting to tunnel server [" + tunnelServerAddress + ":" + tunnelServerPort + "]");
                // Connect to the server.clio
                mTunnel.connect(server);

                if (tunnelConnection != null) {
                    tunnelConnection.onInit();
                }

                tunnelTrafficMonitorThread = new Thread(
                        new TunnelTrafficMonitorRunnable(this.tunnelConnection),
                        "tunnel-read");
                tunnelTrafficMonitorThread.setPriority(Thread.NORM_PRIORITY);
                tunnelTrafficMonitorThread.start();
                log.info("SLVA: Tunnel Traffic Monitor Thread Started");

                tunnelReadThread = new Thread(new TunnelReadRunnable(this.mTunnel, 2000, this.tunnelConnection),
                        "tunnel-read");
                tunnelReadThread.setPriority(Thread.MAX_PRIORITY);
                tunnelReadThread.start();
                log.info("SLVA: Tunnel Read Thread Started");

        } catch (IOException e) {
            e.printStackTrace();
            // and suggest that they stop the service, since we can't do it ourselves
            log.log(Level.SEVERE, "SLVA: Error initializing Tunnel", e);
            if (this.tunnelConnection != null) {
                this.tunnelConnection.onFail("SLVA: Error initializing  Tunnel");
            }
        }
    }

    /**
     * Start the tunnel connection
     */
    public void startConnection() {
        state = ConnectionState.CONNECTING;
        if(onConnectionStateUpdate != null){
            onConnectionStateUpdate.onUpdate(state, (tunnelServerAddress+":"+tunnelServerPort));
        }

        Thread initiateThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ByteBuffer bb = ByteBuffer.allocateDirect(1);
                bb.put(_packet_marker);
                bb.flip();
                bb.limit(1);
                try {
                    mTunnel.write(bb);
                    new ConnectionTimeoutTask(500, tunnelConnection).executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
                } catch (IOException e) {
                    log.log(Level.SEVERE, e.toString(), e);
                    if (tunnelConnection != null) {
                        tunnelConnection.onFail(e.toString());
                    }
                }
            }
        }, "initiate-connection-thread");
        initiateThread.setPriority(Thread.MAX_PRIORITY);
        initiateThread.run();
    }

    public void endConnection(){
        if(state == ConnectionState.CONNECTED) {
            state = ConnectionState.NO_CONNECTED;
            if(onConnectionStateUpdate != null){
                onConnectionStateUpdate.onUpdate(state, (tunnelServerAddress+":"+tunnelServerPort));
            }
            ByteBuffer bb = ByteBuffer.allocateDirect(1);
            bb.put(_end_connection);
            bb.flip();
            bb.limit(1);
            try{
                log.info("SLVA: Sending request to terminate the tunnel");
                mTunnel.write(bb);
            }catch (Exception e){
                log.log(Level.SEVERE, e.toString(), e);
            }
        }
    }

    //TODO: handle independent tunnel address
    boolean sendToTunnel(java.net.InetAddress dst){
        return true;
    }

    void handleTunnelPacket(ByteBuffer bb, int length) {
        if(state == ConnectionState.CONNECTED){
            try {
                if (length > 0 && this.mTunnel != null) {
                    bb.flip();
                    bb.limit(length);
                    this.mTunnel.write(bb);
                    lastTunnelEvent = System.currentTimeMillis()/1000;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static class TunnelTrafficMonitorRunnable implements Runnable {
        private Logger log = LoggerHelper.getLogger(TunnelTrafficMonitorRunnable.class);

        private OnTunnelConnection cs;

        TunnelTrafficMonitorRunnable(OnTunnelConnection cs) {
            this.cs = cs;
        }

        @Override
        public void run() {
            try {
                while (!Thread.interrupted()) {
                    Thread.sleep(TUNNEL_TRAFFIC_MONITOR_INTERVAL_SECOND * 1000);
                    if(state == ConnectionState.CONNECTED) {
                        long currentTime = System.currentTimeMillis() / 1000;
                        if ((currentTime - lastTunnelEvent) >= TUNNEL_INACTIVE_TIMEOUT_SECOND) {
                            log.warning("SLVA: Terminating tunnel connection due to inactivity");
                            this.cs.onRestart();
                        }
                    }
                }
            } catch (Exception e) {
                log.log(Level.SEVERE, e.toString(), e);
            }
        }
    }

    private static class TunnelReadRunnable implements Runnable {
        private Logger log = LoggerHelper.getLogger(TunnelReadRunnable.class);

        @NonNull
        private DatagramChannel tunnel;
        private int mtu;
        private OnTunnelConnection cs;

        TunnelReadRunnable(@NonNull DatagramChannel tunnel,
                           int mtu, OnTunnelConnection cs) {
            this.tunnel = tunnel;
            this.mtu = mtu;
            this.cs = cs;
        }

        @Override
        public void run() {
            log.info("SLVA: Tunnel handler starting");
            try {
                ByteBuffer bufferFromNetwork = ByteBuffer.allocate(this.mtu);
                while (!Thread.interrupted()) {
                    int len = tunnel.read(bufferFromNetwork);
                    lastTunnelEvent = System.currentTimeMillis()/1000;
                    if (len > 0) {
                        bufferFromNetwork.flip();
                        bufferFromNetwork.limit(len);
                        if (state == ConnectionState.CONNECTED && mVpnOut != null) {
                            // when tunnel is connected and interface is created,
                            // foreword all packet to vpn interface
                            mVpnOut.write(bufferFromNetwork);
                        } else if (bufferFromNetwork.array()[0] == _packet_marker) {
                            // confirmation of successful connection,
                            // start vpn connection
                            byte[] address = new byte[len - 1];
                            bufferFromNetwork.position(1);
                            bufferFromNetwork.get(address, 0, (len - 1));
                            String vpnAddress = new String(address, StandardCharsets.US_ASCII);
                            state = ConnectionState.CONNECTED;
                            if(onConnectionStateUpdate != null){
                                onConnectionStateUpdate.onUpdate(state, (tunnelServerAddress+":"+tunnelServerPort));
                            }
                            log.info("SLVA: Successfully connected to tunnel server");
                            cs.onConnect(vpnAddress + "/31");
                        } else {
                            // auth token packet received,
                            // send it back to server
                            this.tunnel.write(bufferFromNetwork);
                            String s = new String(bufferFromNetwork.array(), StandardCharsets.US_ASCII);
                            log.info("SLVA: sending: " + s.trim() + ", " + bufferFromNetwork.limit() + " auth token");
                        }
                    }
                    bufferFromNetwork.clear();
                }

            } catch (IOException e) {
                log.log(Level.SEVERE, e.toString(), e);
            } catch (Exception e) {
                log.log(Level.SEVERE, e.toString(), e);
            }
            synchronized (this) {
                closed = true;
                state = ConnectionState.NO_CONNECTED;
                if(onConnectionStateUpdate != null){
                    onConnectionStateUpdate.onUpdate(state, (tunnelServerAddress+":"+tunnelServerPort));
                }
            }
        }
    }

    private static class ConnectionTimeoutTask extends AsyncTask<Void, Void, ConnectionState> {
        private OnTunnelConnection cs;
        private long timeout;

        ConnectionTimeoutTask(long timeout, OnTunnelConnection cs) {
            this.timeout = timeout;
            this.cs = cs;
        }

        @Override
        protected ConnectionState doInBackground(Void... voids) {
            try {
                Thread.sleep(this.timeout);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return state;
        }

        @Override
        protected void onPostExecute(ConnectionState state) {
            super.onPostExecute(state);
            if(onConnectionStateUpdate != null){
                onConnectionStateUpdate.onUpdate(state, (tunnelServerAddress+":"+tunnelServerPort));
            }
            if (state != ConnectionState.CONNECTED) {
                this.cs.onFail("SLVA: Tunnel connection timeout");
            }
        }
    }

    public interface OnConnectionStateUpdate {
        void onUpdate(ConnectionState state, String serverAddress);
    }

    public interface OnTunnelConnection {
        void onInit();

        void onConnect(String localAddress);

        void onRestart();

        void onFail(String message);

        void onClose();
    }
}

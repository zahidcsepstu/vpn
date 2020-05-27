package com.sb.vpnapplication.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;


import com.sb.vpnapplication.MainActivity;
import com.sb.vpnapplication.dns.InetAddressWithMask;
import com.sb.vpnapplication.dns.LoggerHelper;
import com.sb.vpnapplication.dns.SocketProtector;
import com.sb.vpnapplication.dns.TunDNSResolver;
import com.sb.vpnapplication.dns.TunnelHandler;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.logging.Logger;


public class PHVpnService extends VpnService implements SocketProtector {
    private final String CHANNEL_ID = "vpn-service-status";
    private static Logger log = LoggerHelper.getLogger(PHVpnService.class);


    public static final String PHSERVICE_MESSAGE = PHVpnService.class.getName() + ".message";
    public static final int SERVICE_STOPPED = 1;
    public static final int SERVICE_STARTED = 2;


    private Thread mThread;
    private ParcelFileDescriptor mInterface;

    private Builder _builder;
    private TunDNSResolver tunResolver;

    private TunnelHandler tunnelHandler;

    private static boolean reloadService = false;

    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("STATUS");
            if(status != null){
                stop();
            }

        }
    };

    @Override
    public void onCreate() {
        setServiceStatus(ServiceStatus.STOPPED);
    }


    // Services interface
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log.info("SLVA: Entered onStartCommand.");

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("pushNotification");
        registerReceiver(broadcastReceiver, intentFilter);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this,
                    0, notificationIntent, 0);
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("StreamLocator is running.")
                    .setContentIntent(pendingIntent)
                    .build();
            startForeground(1, notification);
        }

        if (tunResolver != null) {
            log.warning("Service already running");
            return START_STICKY;
        }


        startTunnel();

        return START_STICKY;
    }


    @Override
    public void onDestroy() {
        log.info("SLVA: onDestroy StreamLocator VPN Service");
        stopTunnel();
        stopVPN();
        try {
            unregisterReceiver(broadcastReceiver);
        }catch (IllegalArgumentException ignored){

        }
    }

    public void stop() {
        stopTunnel();
        stopVPN();
        stopSelf();
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            stopForeground(true);
        }
    }

    public void reload(){
        reloadService = true;
        stopTunnel();
    }

    private void stopTunnel(){
        if(tunnelHandler != null){
            tunnelHandler.finish();
        }
    }

    private void stopVPN() {
        if (tunResolver != null) {
            tunResolver.stop();
            tunResolver = null;
        }
    }

    private void startTunnel(){
        Thread tunnelThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (tunnelHandler != null) {
                    tunnelHandler = null;
                }
                tunnelHandler = new TunnelHandler(PHVpnService.this, new TunnelConnectionEvents());
                tunnelHandler.initConnection();
            }
        }, "start-tunnel");
        tunnelThread.start();
    }

    private void startVPN(){
        // Start a new session by creating a new thread.
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {


                    _builder = new Builder();

                    log.info("SLVA: Starting StreamLocator VPN Service...");
                    log.info("vpnAddress: 138.197.67.177");
                    _builder.setSession("PHVPNService");
                    _builder.addAddress("145.168.1.102",32);

                    int mtu = 20048;
                    _builder.setMtu(mtu);

                    android.app.ActivityManager am = (android.app.ActivityManager)
                            PHVpnService.this.getSystemService(Context.ACTIVITY_SERVICE);

//
//                for (InetAddress ia : _config.getDNSServers()) {
//                    _builder.addDnsServer(ia);
//                    log.info("DNS added: " + ia);
//                }

                    _builder.addAllowedApplication("com.google.android.youtube");
                    _builder.addDnsServer("8.8.8.8");
                    _builder.addDnsServer("8.8.4.4");
                    _builder.addRoute("0.0.0.0",0);
                    mInterface = _builder.establish();

                    if (null == mInterface) {
                        log.severe("SLVA: Could not establish VPN Connection");
                        PHVpnService.this.setServiceStatus(ServiceStatus.FAILED);
                        return;
                    }

                    FileInputStream in = new FileInputStream(
                            mInterface.getFileDescriptor());
                    //b. Packets received need to be written to this output stream.
                    FileOutputStream out = new FileOutputStream(
                            mInterface.getFileDescriptor());


                    tunnelHandler.setVpnOut(out.getChannel());

                    log.info("SLVA: Tunnel state: " + TunnelHandler.getState());


                    tunResolver = new TunDNSResolver(PHVpnService.this, in, out, tunnelHandler);

                    log.info("SLVA: Stream Locator VPN Service started");
                    PHVpnService.this.setServiceStatus(ServiceStatus.STARTED);

                    tunResolver.run();
                } catch (Exception e) {
                    // Catch any exception
                    e.printStackTrace();
                } finally {
                    if (tunResolver != null) {
                        tunResolver.stop();
                        tunResolver = null;
                    }
                    try {
                        if (mInterface != null) {
                            mInterface.close();
                            mInterface = null;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    if (reloadService) {
                        log.info("SLVA: Reloading StreamLocator VPN Service...");
                        reloadService = false;
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        PHVpnService.this.startTunnel();
                    } else {
                        PHVpnService.this.setServiceStatus(ServiceStatus.STOPPED);
                    }
                }
            }
        }, "PHVpnService");

        //start the service
        mThread.start();
    }

    public enum ServiceStatus {STARTED, STOPPED, FAILED}

    private ServiceStatus _serviceStatus = ServiceStatus.STOPPED;

    private void setServiceStatus(ServiceStatus status) {
        _serviceStatus = status;

        log.info("StreamLocator VPN Service is:" + _serviceStatus);

        Intent intent = new Intent(PHSERVICE_MESSAGE);
        intent.putExtra(PHSERVICE_MESSAGE, SERVICE_STARTED);
    }

    public ServiceStatus getStatus() {
        return _serviceStatus;
    }

    private class TunnelConnectionEvents implements TunnelHandler.OnTunnelConnection {

        @Override
        public void onInit() throws UnknownHostException {
            log.info("SLVA: Start default VPN connection");
            startVPN();
        }

        @Override
        public void onConnect(final String vpnAddress) {
            log.info("SLVA: Start streaming VPN connection");
            if (mThread != null) {
                log.info("Stopping StreamLocator VPN Service...");
                mThread.interrupt();
                mThread = null;
            }
            startVPN();

        }

        @Override
        public void onRestart() {
            tunnelHandler.endConnection();
        }

        @Override
        public void onFail(String message) {
            log.warning(message);
            // TUN  connection state should not influence the overall service status. The TUN connection might fail while the DNS resolver works for other services
            //setServiceStatus(ServiceStatus.FAILED);
        }

        @Override
        public void onClose() {
            log.info("SLVA: Tunnel connection closed");
            if (mThread != null) {
                log.info("Stopping PrivacyHero VPN Service...");
                mThread.interrupt();
                mThread = null;
            }
        }
    }


    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    private final IBinder mBinder = new LocalBinder();


    public class LocalBinder extends Binder {
        public PHVpnService getService() {
            return PHVpnService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }


    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "VPN Service Status",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

}
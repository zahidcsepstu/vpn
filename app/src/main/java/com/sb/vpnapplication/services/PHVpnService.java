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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.sb.vpnapplication.MainActivity;
import com.sb.vpnapplication.R;
import com.sb.vpnapplication.dns.Configurator;
import com.sb.vpnapplication.dns.InetAddressWithMask;
import com.sb.vpnapplication.dns.SocketProtector;
import com.sb.vpnapplication.dns.TunDNSResolver;
import com.sb.vpnapplication.dns.TunnelHandler;
import com.sb.vpnapplication.logger.LoggerHelper;


import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.util.logging.Logger;


public class PHVpnService extends VpnService implements SocketProtector {
    private final String CHANNEL_ID = "vpn-service-status";
    private static Logger log = LoggerHelper.getLogger(PHVpnService.class);


    public static final String PHSERVICE_MESSAGE = PHVpnService.class.getName() + ".message";
    public static final int SERVICE_STOPPED = 1;
    public static final int SERVICE_STARTED = 2;

    private LocalBroadcastManager _broadcaster;

    private Configurator _config;

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
        log.info("SLVA: onCreate()");
        _broadcaster = LocalBroadcastManager.getInstance(this);
        setServiceStatus(ServiceStatus.STOPPED);
    }

    private void checkInstalledApps(Configurator cfg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            java.util.List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(
                    android.content.pm.PackageManager.GET_META_DATA);
            java.util.ArrayList<String> res = new java.util.ArrayList<>();
            for (android.content.pm.ApplicationInfo app : apps) {
                res.add(app.packageName);
            }
            cfg.setInstalledApps(res.toArray(new String[res.size()]));
        } catch (Throwable ignore) {
            log.warning("Check failed: " + ignore);
        }
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
                    .setContentTitle("Vpn Application is running.")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .build();
            startForeground(1, notification);
        }

        if (tunResolver != null) {
            log.warning("Service already running");
            return START_STICKY;
        }

        _config = Configurator.getInstance().setPlatformSpecificObjects(
                getContentResolver(), getApplicationContext(), null);

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

    private void startVPN(final InetAddressWithMask addr){
        // Start a new session by creating a new thread.
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PHVpnService.this.checkInstalledApps(_config); // results may be useful for getApps if we don't know exact name

                    if (addr == null) {
                        log.warning("Can't start without Internet connection");
                        PHVpnService.this.setServiceStatus(ServiceStatus.FAILED);
                        return;
                    }

                    _builder = new Builder();

                    log.info("SLVA: Starting StreamLocator VPN Service...");
                    log.info("vpnAddress: " + addr.getHostAddress());
                    Log.d("addresscheck", "phvpn addAddress" + addr.getHostAddress());
                    _builder.setSession("PHVPNService");
                    _builder.addAddress(addr.getAddress(), addr.getBits());

                    int mtu = _config.getMTU();
                    if (mtu > 0) {
                        Log.d("zahid", "builder mtu" + mtu);
                        _builder.setMtu(mtu);
                    }

                    android.app.ActivityManager am = (android.app.ActivityManager)
                            PHVpnService.this.getSystemService(Context.ACTIVITY_SERVICE);
                    for (String s : _config.getApps()) {
                        _builder.addAllowedApplication(s);
                        am.killBackgroundProcesses(s);
                        log.info("App added: " + s);
                    }

                    for (InetAddress ia : _config.getDNSServers()) {
                        _builder.addDnsServer(ia);
                        Log.d("addresscheck", "phvpn dnsadd " + ia.getHostAddress());
                    }

                    //for testing purpose
                    _builder.addRoute("0.0.0.0", 0);
                    _builder.addAllowedApplication("com.android.chrome");

                    for (InetAddressWithMask iam : _config.getRedirectRanges()) {
                        try {
                            _builder.addRoute(iam.getAddress(), iam.getBits());
                            Log.d("addresscheck", "phvpn addroute " + iam);
                        } catch (Exception ex) {
                            log.severe("Couldn't add route: " + iam + " >> " + ex);
                        }
                    }

                    // added by stitel
                    for (InetAddressWithMask subnetIp : _config.getSubnetIps()) {
                        try {
                            _builder.addRoute(subnetIp.getAddress(), subnetIp.getBits());
                            log.info("Route added: " + subnetIp);
                        } catch (Exception ex) {
                            log.severe("Couldn't add route: " + subnetIp + " >> " + ex);
                        }
                    }


                    mInterface = _builder.establish();

                    if (null == mInterface) {
                        log.severe("SLVA: Could not establish VPN Connection");
                        PHVpnService.this.setServiceStatus(ServiceStatus.FAILED);
                        return;
                    }

                    // in means server in
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
        _broadcaster.sendBroadcast(intent);
    }

    public ServiceStatus getStatus() {
        return _serviceStatus;
    }

    private class TunnelConnectionEvents implements TunnelHandler.OnTunnelConnection {

        @Override
        public void onInit() {
            log.info("SLVA: Start default VPN connection");
            startVPN(InetAddressWithMask.parse("1.1.1.0/31"));
        }

        @Override
        public void onConnect(final String vpnAddress) {
            log.info("SLVA: Start streaming VPN connection");
            if (mThread != null) {
                log.info("Stopping StreamLocator VPN Service...");
                mThread.interrupt();
                mThread = null;
            }
            Log.d("addresscheck","phvpn Connect "+vpnAddress);
            startVPN(InetAddressWithMask.parse(vpnAddress));

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
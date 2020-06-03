package com.sb.vpnapplication.controller;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;


import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.amplitude.api.Amplitude;
import com.sb.vpnapplication.services.PHVpnService;

import java.util.Observable;
import java.util.Observer;
import java.util.logging.Logger;

//mport com.streamlocator.vpn.Preferences;

/**
 * A class that separates and hides all the complexity of controlling VpnService from UI
 *
 * The complexities are:
 *  - A service maybe already started by boot when UI comes alive.
 *  - There is specific sequence of creating a starting a service mandated by Android
 *  - When VPNService is started the user may be prompted for permission to start such service.
 *    The dialog appears as a child of main application activity and therefore must be properly
 *    integrated.
 *  - The calls to start() and stop() are asynchronous, therefore proper "status change" listener
 *    must be provided.
 *
 */
public class VpnServiceUiController {

    private static Logger log = Logger.getLogger(VpnServiceUiController.class.getName());

    private PHVpnService _serviceInstance;
    private LocalBroadcastManager _broadcastManager;
   // private Preferences _preferences;
    private Activity _activity;
    private Context _context;

    private Observable _observable = new Observable() {
        public void notifyObservers() {
            setChanged();
            super.notifyObservers();
        }
    };

    private BroadcastReceiver _broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            _observable.notifyObservers();
        }
    };


    private ServiceConnection _serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {

            PHVpnService.LocalBinder binder = (PHVpnService.LocalBinder)service;
            _serviceInstance = binder.getService();

            _broadcastManager.registerReceiver((_broadcastReceiver),
                    new IntentFilter(PHVpnService.PHSERVICE_MESSAGE)
            );
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            _broadcastManager.unregisterReceiver(_broadcastReceiver);
            _serviceInstance = null;
        }
    };



    public VpnServiceUiController( Activity activity) {
        this._activity = activity;
        this._context = _activity;

        //_preferences = Preferences.getPreferences( _context );
        _broadcastManager = LocalBroadcastManager.getInstance(_context);

        // self observe to send mixpanel events
        //
        _observable.addObserver(
                new Observer() {
                    @Override
                    public void update(Observable observable, Object data) {
                        switch (_serviceInstance.getStatus()) {
                            case STARTED:
                                Amplitude.getInstance().logEvent("fire-vpn-start-success");
                                break;
                            case STOPPED:
                                Amplitude.getInstance().logEvent("fire-vpn-stop-success");
                                break;
                            case FAILED:
                                Amplitude.getInstance().logEvent("fire-vpn-start-failure");
                                break;
                        }
                    }
                });
    }

    /**
     * call this method from Activity.onStart()
     */
    public void onStart() {
        Intent intent = new Intent(_context, PHVpnService.class);
        _context.bindService(intent, _serviceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * call this method from Activity.onDestroy()
     */
    public void onDestroy() {
        _context.unbindService(_serviceConnection);
        _broadcastManager.unregisterReceiver(_broadcastReceiver);
        _serviceInstance = null;
    }


    /**
     * call this method from Activity.onActivityResult()
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            Amplitude.getInstance().logEvent("fire-allow-vpn-granted");
            startTheService();
        }
        else {
            Amplitude.getInstance().logEvent("fire-allow-vpn-denied");
        }
    }

    /**
     * Starts the service including UI for permission.
     */
    public void start() {
        // protect against double start
        if ( getStatus() == PHVpnService.ServiceStatus.STARTED )
            return;

        //_preferences.setLastRunningState (true);
        Amplitude.getInstance().logEvent("fire-vpn-start-clicked");
        initiateServiceStart();
    }

    /**
     * Stops the service
     */
    public void stop() {
        // protect against double stop
        if ( getStatus() == PHVpnService.ServiceStatus.STOPPED )
            return;
       // _preferences.setLastRunningState (false);
        Amplitude.getInstance().logEvent("fire-vpn-stop-clicked");
        _serviceInstance.stop();
    }

    /**
     * Reloads the service
     */
    public void reload(){
        _serviceInstance.reload();
    }

    /**
     * Returns service status
     *
     * @return service status
     */
    public PHVpnService.ServiceStatus getStatus() {
        return (null != _serviceInstance) ?
                _serviceInstance.getStatus() : PHVpnService.ServiceStatus.STOPPED;
    }

    /**
     * Register an observer for service status changes. Call getStatus() to obtain current service
     * status from the observer.update() method.
     * @param observer
     */
    public void registerStatusObserver(Observer observer) {
        _observable.addObserver(observer);
    }


    private void initiateServiceStart() {
        log.info("SLVA: Invoking start service");
        Intent intent = PHVpnService.prepare(_context);
        if (intent != null) {
            Amplitude.getInstance().logEvent("fire-allow-vpn-requested");
            _activity.startActivityForResult(intent, 0);
        } else {
            startTheService();
        }
    }


    private void startTheService() {
        Intent i = new Intent(_context, PHVpnService.class);
        _context.startService(i);
    }
}

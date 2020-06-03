package com.sb.vpnapplication;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;

import androidx.annotation.NonNull;


public class ConnectionStateMonitor extends ConnectivityManager.NetworkCallback {

    private ConnectivityListener connectivityListener;
    private NetworkRequest networkRequest;
    private ConnectivityManager connectivityManager;

    public ConnectionStateMonitor(Context context, ConnectivityListener connectivityListener) {
        this.connectivityListener = connectivityListener;
        networkRequest = new NetworkRequest.Builder()
                .build();
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void enableMonitor(){
        connectivityManager.registerNetworkCallback(networkRequest, this);
    }

    public void disableMonitor(){
        connectivityManager.unregisterNetworkCallback(this);
    }

    @Override
    public void onAvailable(@NonNull Network network) {
        super.onAvailable(network);
        connectivityListener.onConnectivityChanged(true);
    }

    @Override
    public void onLost(@NonNull Network network) {
        super.onLost(network);
        connectivityListener.onConnectivityChanged(false);
    }

    public interface ConnectivityListener{
        void onConnectivityChanged(boolean isNetworkAvailable);
    }

}

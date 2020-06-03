package com.sb.vpnapplication;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.fragment.app.FragmentActivity;

import com.amplitude.api.Amplitude;
import com.sb.vpnapplication.controller.VpnController;
import com.sb.vpnapplication.controller.VpnServiceUiController;
import com.sb.vpnapplication.services.PHVpnService;


//import com.streamlocator.vpn.controllers.AppsController;


public class MainActivity extends FragmentActivity implements  ConnectionStateMonitor.ConnectivityListener {


    private VpnServiceUiController _vpnServiceUiController;


    VpnController vpnController;
   // AppsController appsController;
    boolean isVpnStarted = false;


    ConnectionStateMonitor connectionStateMonitor;
    boolean isNetworkAvailable = false;
    Button Start, Stop;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Start = findViewById(R.id.button);

        Log.d("debug", ">> onCreate " + this);
        connectionStateMonitor = new ConnectionStateMonitor(this, this);
        connectionStateMonitor.enableMonitor();

        _vpnServiceUiController = new VpnServiceUiController(this);

        //appsController = new AppsController(this, _vpnServiceUiController);


        vpnController = new VpnController(this, _vpnServiceUiController);


        Start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!isVpnStarted) {
                    vpnController.start();
                } else {
                    vpnController.stop();
                }
            }
        });



    }


    private void checkVpnStatus() {
        isVpnStarted = vpnController.getStatus().equals(PHVpnService.ServiceStatus.STARTED);
    }

    public void refresh() {

        checkVpnStatus();

    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        _vpnServiceUiController.onActivityResult(requestCode, resultCode, data);
    }


    @Override
    protected void onStart() {
        super.onStart();
        Log.d("debug", ">> onStart " + this);
        _vpnServiceUiController.onStart();

        Amplitude.getInstance().logEvent("fire-launch");
    }


    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {

        Log.d("debug", ">> onDestroy " + this);
        _vpnServiceUiController.onDestroy();

        Amplitude.getInstance().logEvent("fire-kill");

        connectionStateMonitor.disableMonitor();
        super.onDestroy();
    }













    @Override
    public void onConnectivityChanged(boolean isNetworkAvailable) {

        this.isNetworkAvailable = isNetworkAvailable;

        if(this.isNetworkAvailable){

            if(vpnController != null) {
                boolean isVpnStarted = vpnController.getStatus().equals(PHVpnService.ServiceStatus.STARTED);
                if (isVpnStarted) {
                    vpnController.reload();
                }
            }
        }

    }
}
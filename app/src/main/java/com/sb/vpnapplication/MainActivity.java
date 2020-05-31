package com.sb.vpnapplication;

import androidx.fragment.app.FragmentActivity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.sb.vpnapplication.controller.VpnController;
import com.sb.vpnapplication.controller.VpnServiceUiController;

public class   MainActivity extends FragmentActivity{

    Button start;
    Button stop;
    VpnController vpnController;
    VpnServiceUiController _vpnServiceUiController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        start=findViewById(R.id.button);
        stop=findViewById(R.id.button1);
        Log.d("debug",">> onCreate " + this);

       _vpnServiceUiController = new VpnServiceUiController( this);

        vpnController = new VpnController(this, _vpnServiceUiController);

        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
             vpnController.start();
            }
        });

        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                vpnController.stop();
            }
        });


    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        _vpnServiceUiController.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d("debug",">> onStart " + this);
        _vpnServiceUiController.onStart();
    }



}

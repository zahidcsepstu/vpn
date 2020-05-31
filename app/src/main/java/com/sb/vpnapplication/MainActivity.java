package com.sb.vpnapplication;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.SyncStateContract;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.sb.vpnapplication.controller.VpnController;
import com.sb.vpnapplication.controller.VpnServiceUiController;
import com.sb.vpnapplication.dns.LoggerHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

public class   MainActivity extends FragmentActivity{

    Button button;
    VpnController vpnController;
    boolean isVpnStarted = false;
    VpnServiceUiController _vpnServiceUiController;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        button=findViewById(R.id.button);
        Log.d("debug",">> onCreate " + this);

       _vpnServiceUiController = new VpnServiceUiController( this);

        vpnController = new VpnController(this, _vpnServiceUiController);

        button.setOnClickListener(new View.OnClickListener() {
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

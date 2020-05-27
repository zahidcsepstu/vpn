package com.sb.vpnapplication.controller;


import com.sb.vpnapplication.MainActivity;

import java.util.Observable;
import java.util.Observer;

public class VpnController extends AbstractJsiController{

    private VpnServiceUiController _vpnServiceUiController;

    public VpnController(final MainActivity mainActivity, final VpnServiceUiController vpnServiceUiController) {
        super(mainActivity);
        _vpnServiceUiController = vpnServiceUiController;
        _vpnServiceUiController.registerStatusObserver(new Observer() {
            @Override
            public void update(Observable observable, Object data) {

            }
        });
    }
    public void start() {
        _vpnServiceUiController.start();
    }

    public void stop() {
        _vpnServiceUiController.stop();
    }
    public void reload(){
        _vpnServiceUiController.reload();
    }


    public Enum getStatus() {
        return _vpnServiceUiController.getStatus();
    }

}

package com.sb.vpnapplication.controller;


import com.sb.vpnapplication.MainActivity;

public abstract class AbstractJsiController {

    MainActivity _mainActivity;

    public AbstractJsiController(final MainActivity mainActivity) {
        _mainActivity = mainActivity;
    }

}

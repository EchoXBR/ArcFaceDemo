package com.arcsoft.arcfacedemo.activity;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

import com.arcsoft.arcfacedemo.R;

public class MenuActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);
    }

    public void gotoRegister(View view) {
        startActivity(new Intent(MenuActivity.this, IdCardRegisterActivity.class));

    }

    public void gotoRecongnize(View view) {
        startActivity(new Intent(MenuActivity.this, UsbCameraRegisterAndRecognizeActivity.class));

    }

    public void gotoSystemRecongnize(View view) {

        startActivity(new Intent(MenuActivity.this, RegisterAndRecognizeActivity.class));

    }
}

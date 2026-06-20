package com.jlxc.mikuvvvf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class SpeedCommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Intent service = new Intent(context, VvvfSoundService.class);

        if ("com.jlxc.mikuvvvf.SET_SPEED".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_SPEED);
            service.putExtra(VvvfSoundService.EXTRA_SPEED, intent.getFloatExtra("speed", 0f));
        } else if ("com.jlxc.mikuvvvf.SET_STYLE".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_STYLE);
            service.putExtra(VvvfSoundService.EXTRA_STYLE, intent.getStringExtra("style"));
        } else if ("com.jlxc.mikuvvvf.SET_HOOK".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_HOOK);
            service.putExtra(VvvfSoundService.EXTRA_HOOK_ENABLED, intent.getBooleanExtra("enabled", true));
        } else if ("com.jlxc.mikuvvvf.SET_MODE".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_INPUT_MODE);
            service.putExtra(VvvfSoundService.EXTRA_INPUT_MODE, intent.getStringExtra("mode"));
        } else if ("com.jlxc.mikuvvvf.RESET_SENSOR".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_RESET_SENSOR);
        } else if ("com.jlxc.mikuvvvf.TOGGLE_ACCEL".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_TOGGLE_ACCEL_DIRECTION);
        } else if ("com.jlxc.mikuvvvf.STOP".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_STOP);
        } else {
            service.setAction(VvvfSoundService.ACTION_START);
        }

        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}

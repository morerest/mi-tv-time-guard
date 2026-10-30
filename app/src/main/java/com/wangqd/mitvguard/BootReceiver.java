package com.wangqd.mitvguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机与系统广播接收器：确保小米电视冷启动或更新后自动拉起守护服务
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        startGuardServiceSafely(context);
    }

    public static void startGuardServiceSafely(Context context) {
        try {
            Intent serviceIntent = new Intent(context, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            try {
                context.startService(new Intent(context, GuardService.class));
            } catch (Exception ignored) {
            }
        }
    }
}

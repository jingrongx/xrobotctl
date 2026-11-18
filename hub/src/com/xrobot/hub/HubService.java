package com.xrobot.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/** 前台服务：提升中枢进程优先级，保证 HTTP 服务长期存活。 */
public final class HubService extends Service {

    public static final String CHANNEL_ID = "xrobot_hub";

    @Override
    public void onCreate() {
        super.onCreate();
        try { startForeground(1001, buildNotification()); }
        catch (Throwable t) { Log.w("XRobotHub", "前台通知失败(不影响控制)", t); }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "XRobot 中枢", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("XRobot 中枢运行中")
                .setContentText("HTTP 服务端口 " + LocalHttpServer.PORT)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

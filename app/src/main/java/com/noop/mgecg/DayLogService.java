package com.noop.mgecg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

/** Keeps the app process alive and shows what the logger is doing, with one-tap marker and pull buttons. */
public class DayLogService extends Service {
    static final String CHANNEL = "daylog";
    static final int ID = 4101;
    static final String ACTION_MARK = "com.noop.mgecg.DAYLOG_MARK";
    static final String ACTION_PULL = "com.noop.mgecg.DAYLOG_PULL";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        ensureChannel(this);
        Notification n = build(this);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(ID, n);
        return START_STICKY;
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "MG day logger", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the all-day logger running");
        nm.createNotificationChannel(ch);
    }

    static Notification build(Context c) {
        PendingIntent open = PendingIntent.getActivity(c, 1, new Intent(c, DayLogActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent mark = new Intent(c, DayLogReceiver.class).setAction(ACTION_MARK);
        Intent pull = new Intent(c, DayLogReceiver.class).setAction(ACTION_PULL);
        PendingIntent pm = PendingIntent.getBroadcast(c, 2, mark, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pp = PendingIntent.getBroadcast(c, 3, pull, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("MG day logger")
                .setContentText(DayLog.statusLine(c))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_mylocation), "Mark now", pm).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_rotate), "Pull now", pp).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_edit), "Cuff / notes", open).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    static void refresh(Context c) {
        try {
            if (!DayLog.enabled(c)) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, build(c));
        } catch (Throwable ignored) { }
    }

    static void start(Context c) {
        Intent i = new Intent(c, DayLogService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, DayLogService.class)); }
}

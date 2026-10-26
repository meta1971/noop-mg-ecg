package com.noop.mgecg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Runs the daily backup in the foreground so Android does not end it half way; shows what it is copying and the result. */
public class BackupService extends Service {
    static final String CHANNEL = "backup";
    static final int ID = 4102, ID_DONE = 4103;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        final Context c = getApplicationContext();
        final String why = in == null || in.getStringExtra("why") == null ? "start" : in.getStringExtra("why");
        channel(c);
        Notification n = build(c, "Backing up your MG data", "Starting\u2026", true, false);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(ID, n);
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                    Backup.Result r = Backup.run(c, why, new Backup.Listener() {
                        @Override public void on(String s) { try { nm.notify(ID, build(c, "Backing up your MG data", s, true, false)); } catch (Throwable ignored) { } }
                    });
                    if (r.message != null && r.message.length() > 0 && !r.message.startsWith("A backup is already")) {
                        nm.notify(ID_DONE, build(c, r.ok ? "MG backup done" : "MG backup FAILED", r.message, false, !r.ok));
                    }
                } catch (Throwable ignored) {
                } finally {
                    stopForeground(true);
                    stopSelf();
                }
            }
        }, "backup").start();
        return START_NOT_STICKY;
    }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Daily backup", NotificationManager.IMPORTANCE_LOW));
    }

    static Notification build(Context c, String title, String text, boolean ongoing, boolean important) {
        Intent open = new Intent(c, HealthActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(ongoing).setAutoCancel(!ongoing).setContentIntent(pi);
        return b.build();
    }
}

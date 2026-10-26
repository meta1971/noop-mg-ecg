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
import android.provider.Settings;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/** Keeps the app process alive and shows what the logger is doing, with one-tap marker and pull buttons. */
public class DayLogService extends Service {
    static final String CHANNEL = "daylog";
    static final int ID = 4101;
    static final String ACTION_MARK = "com.noop.mgecg.DAYLOG_MARK";
    static final String ACTION_PULL = "com.noop.mgecg.DAYLOG_PULL";

    @Override public IBinder onBind(Intent i) { return null; }

    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private boolean watchdogOn;
    private long serviceStart;

    /** Every 5 minutes: if the logger is meant to be on and no strap second has arrived for a while, say so (KeepAlive). */
    private final Runnable watchdogTick = new Runnable() {
        @Override public void run() {
            final Context c = getApplicationContext();
            if (SystemClock.elapsedRealtime() - serviceStart > 10L * 60L * 1000L) {          // not while it is still connecting
                new Thread(new Runnable() { @Override public void run() { KeepAlive.checkStale(c); } }, "daylog-watchdog").start();
            }
            watchdog.postDelayed(this, 5L * 60L * 1000L);
        }
    };

    /** 0.15.0: counters kept in preferences, shown on Strap data (they survive the process being ended). */
    static void bump(Context c, String key) {
        try { android.content.SharedPreferences p = c.getSharedPreferences("linkdiag", Context.MODE_PRIVATE); p.edit().putInt("n_" + key, p.getInt("n_" + key, 0) + 1).apply(); } catch (Throwable ignored) { }
    }

    /** 0.15.0: if the logger is on and nothing holds the strap link, start the invisible link owner (works from the background only with "Display over other apps"). */
    static void tryHost(Context c) {
        try {
            if (DayLog.enabled(c) && !MainActivity.linkRunning() && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c))) MainActivity.startLinkHost(c);
        } catch (Throwable ignored) { }
    }

    private final Runnable hostTick = new Runnable() {
        @Override public void run() {
            tryHost(getApplicationContext());
            refresh(getApplicationContext());
            watchdog.postDelayed(this, 60L * 1000L);
        }
    };

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        bump(this, i == null ? "svc_restart" : "svc_start");
        ensureChannel(this);
        Notification n = build(this);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(ID, n);
        if (!watchdogOn) {
            watchdogOn = true;
            serviceStart = SystemClock.elapsedRealtime();
            watchdog.postDelayed(watchdogTick, 5L * 60L * 1000L);
            watchdog.postDelayed(hostTick, 20L * 1000L);
        }
        if (i == null) tryHost(this);          // the system restarted the service: bring the strap link back if allowed
        return START_STICKY;
    }

    @Override public void onDestroy() {
        watchdog.removeCallbacks(watchdogTick);
        watchdog.removeCallbacks(hostTick);
        watchdogOn = false;
        super.onDestroy();
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "MG day logger", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the all-day logger running");
        nm.createNotificationChannel(ch);
    }

    static Notification build(Context c) {
        // 0.13.4: a tap opens the heart app. If nothing holds the strap link yet, go through the launcher (it starts the link); otherwise go straight
        // to the heart app so the running link is not touched.
        Intent openApp = new Intent(c, MainActivity.linkRunning() ? HealthActivity.class : MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(c, 1, openApp, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent mark = new Intent(c, DayLogReceiver.class).setAction(ACTION_MARK);
        Intent pull = new Intent(c, DayLogReceiver.class).setAction(ACTION_PULL);
        PendingIntent pm = PendingIntent.getBroadcast(c, 2, mark, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pp = PendingIntent.getBroadcast(c, 3, pull, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(DayLog.enabled(c) ? "MG day logger" : "Polar H10")
                .setContentText(textFor(c))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_mylocation), "Mark now", pm).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_rotate), "Pull now", pp).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_edit), "Cuff / notes", open).build());
        if (DayLog.enabled(c) && !MainActivity.linkRunning()) {        // 0.15.0: one tap brings the strap link back, with no screen
            Intent host = new Intent(c, LinkHostActivity.class).putExtra("host_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            b.setActions(new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_mylocation), "Mark now", pm).build(),
                    new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_rotate), "Pull now", pp).build(),
                    new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_popup_sync), "Reconnect", PendingIntent.getActivity(c, 4, host, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    static String textFor(Context c) {
        boolean log = DayLog.enabled(c), h10 = PolarH10.wanted;
        String link = "";
        try { if (log) link = MainActivity.linkState(c).chip + " | "; } catch (Throwable ignored) { }   // 0.13.4: LIVE / OFFLINE / ... right in the notification
        if (log && h10) return link + DayLog.statusLine(c) + " | H10: " + PolarH10.shortStatus();
        if (h10) return "H10: " + PolarH10.shortStatus();
        return link + DayLog.statusLine(c);
    }

    static void refresh(Context c) {
        try {
            if (!DayLog.enabled(c) && !PolarH10.wanted) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, build(c));
        } catch (Throwable ignored) { }
    }

    static volatile long startedAt = 0L;

    static void start(Context c) {
        startedAt = SystemClock.elapsedRealtime();
        Intent i = new Intent(c, DayLogService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    /** Android 11 kills the app if a foreground service is stopped before it has called startForeground(), which is what happened when Bluetooth was off (start, then an instant failure and stop). So a stop within 3 s of the start is delayed until 3 s have passed. */
    static void stop(final Context c) {
        long wait = 3000L - (SystemClock.elapsedRealtime() - startedAt);
        if (wait <= 0) { c.stopService(new Intent(c, DayLogService.class)); return; }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try { if (!DayLog.enabled(c) && !PolarH10.wanted) c.stopService(new Intent(c, DayLogService.class)); } catch (Throwable ignored) { }
            }
        }, wait);
    }
}

package com.noop.mgecg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/** 0.5.15: receives the ALADIN report text (Termux stdout) from the RUN_COMMAND pending intent, saves it and notifies. */
public class AladinResultReceiver extends BroadcastReceiver {
    static final String CHANNEL = "aladin";

    @Override public void onReceive(Context c, Intent in) {
        String folder = in.getStringExtra("folder");
        String sent = in.getStringExtra("text");
        if (sent != null) {
            // 0.5.16: the report text posted by our own Termux script (am broadcast); ignore anything without the right token
            String tok = in.getStringExtra("token");
            if (tok == null || !tok.equals(TermuxBridge.token(c))) return;
            String body = "ALADIN run on " + folder + " finished (sent by Termux)\n\n" + cleanProgress(sent);
            TermuxBridge.saveResult(c, body);
            notify(c, true, body);
            return;
        }
        Bundle r = in.getBundleExtra("result");
        StringBuilder sb = new StringBuilder();
        boolean ok = false;
        if (r == null) {
            sb.append("Termux sent no result. Check that Termux is the F-Droid version and that setup_termux.sh was run.\n");
        } else {
            String out = r.getString("stdout", "");
            String err = r.getString("stderr", "");
            String errmsg = r.getString("errmsg", "");
            Object ec = r.get("exitCode");
            Object ee = r.get("err");
            int code = ec instanceof Number ? ((Number) ec).intValue() : -1;
            int ecode = ee instanceof Number ? ((Number) ee).intValue() : -1;
            if (errmsg != null && errmsg.length() > 0) sb.append("Termux error: ").append(errmsg).append('\n');
            sb.append(cleanProgress(out));
            if (err != null && err.trim().length() > 0) sb.append("\n--- messages ---\n").append(cleanProgress(err));
            ok = code == 0 && (errmsg == null || errmsg.length() == 0) && ecode <= 0;
            sb.insert(0, "ALADIN run on " + folder + (ok ? " finished" : " ended with exit code " + code) + "\n\n");
        }
        String text = sb.toString();
        TermuxBridge.saveResult(c, text);
        notify(c, ok, text);
    }

    /** Progress lines use carriage returns; keep only what is left on the line after the last one. */
    static String cleanProgress(String s) {
        List<String> keep = new ArrayList<String>();
        for (String line : s.split("\n")) {
            int k = line.lastIndexOf('\r');
            keep.add(k >= 0 ? line.substring(k + 1) : line);
        }
        return TermuxBridge.join(keep);
    }

    private void notify(Context c, boolean ok, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ALADIN second opinion", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(c, AladinActivity.class);
            PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);   // FLAG_IMMUTABLE
            String tail = text.length() > 3500 ? text.substring(text.length() - 3500) : text;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
             .setContentTitle(ok ? "ALADIN second opinion ready" : "ALADIN run did not finish cleanly")
             .setContentText("Tap to read the report")
             .setStyle(new Notification.BigTextStyle().bigText(tail))
             .setContentIntent(pi)
             .setAutoCancel(true);
            nm.notify(4401, b.build());
        } catch (Throwable ignore) {}
    }
}

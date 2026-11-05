package com.noop.mgecg;

// FILE VERSION 0.42.0 (5 Nov 2026): home-screen widget: latest reading (HR, HRV, grade, rhythm), this week's days and streak, and a "Take an ECG" button.
// Reads only the saved report files (their meta tags and names); the layout is res/layout/ecg_widget.xml, found by name so no generated R class is needed.

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class EcgWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) { try { m.updateAppWidget(id, build(c)); } catch (Throwable ignored) { } }
    }

    /** Called after a new report is saved (EcgReport) and when the app opens. */
    static void refreshAll(Context c0) {
        try {
            Context c = c0.getApplicationContext(); AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, EcgWidget.class));
            if (ids != null && ids.length > 0) { RemoteViews v = build(c); for (int id : ids) m.updateAppWidget(id, v); }
        } catch (Throwable ignored) { }
    }

    private static int id(Context c, String name, String type) { return c.getResources().getIdentifier(name, type, c.getPackageName()); }

    static long[] startsOf(File dir) {
        List<File> fs = NewLookActivity.listReportFiles(dir); List<Long> out = new ArrayList<Long>();
        for (File f : fs) { String n = f.getName(); try { out.add(Long.parseLong(n.substring("ecg_report_".length(), n.length() - ".html".length()))); } catch (Exception ignored) { } }
        long[] r = new long[out.size()]; for (int i = 0; i < r.length; i++) r[i] = out.get(i); return r;
    }

    static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), id(c, "ecg_widget", "layout"));
        String l1 = "No readings yet", l2 = "Tap Take an ECG", l3 = "";
        try {
            File dir = c.getExternalFilesDir(null); long[] st = startsOf(dir);
            EcgStreak.Out s = EcgStreak.compute(st, System.currentTimeMillis(), TimeZone.getDefault());
            l3 = EcgStreak.summary(s);
            long best = 0; for (long t : st) if (t > best) best = t;
            if (best > 0) {
                String head = NewLookActivity.readHead(new File(dir, "ecg_report_" + best + ".html"), 3000);
                String hr = NewLookActivity.meta(head, "ecg-hr"), hrv = NewLookActivity.meta(head, "ecg-hrv"), gr = NewLookActivity.meta(head, "ecg-grade"), rh = NewLookActivity.meta(head, "ecg-rhythm");
                l1 = (hr == null || hr.equals("-") ? "HR -" : hr + " bpm") + (hrv == null || hrv.equals("-") || hrv.isEmpty() ? "" : "  \u00b7  HRV " + hrv + " ms") + (gr == null ? "" : "  \u00b7  grade " + gr);
                long ago = (System.currentTimeMillis() / 1000L - best) / 60L;
                String when = ago < 60 ? ago + " min ago" : ago < 48 * 60 ? (ago / 60) + " h ago" : (ago / 1440) + " days ago";
                l2 = (rh == null || rh.isEmpty() ? "" : rh + "  \u00b7  ") + new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK).format(new Date(best * 1000L)) + "  (" + when + ")";
            }
        } catch (Throwable ignored) { }
        v.setTextViewText(id(c, "ecg_w_l1", "id"), l1); v.setTextViewText(id(c, "ecg_w_l2", "id"), l2); v.setTextViewText(id(c, "ecg_w_l3", "id"), l3);
        v.setInt(id(c, "ecg_w_root", "id"), "setBackgroundColor", 0xFF0D1116);
        v.setInt(id(c, "ecg_w_btn", "id"), "setBackgroundColor", 0xFF3A7BFF);
        Intent take = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("stay", true).putExtra("start_ecg", true);
        v.setOnClickPendingIntent(id(c, "ecg_w_btn", "id"), PendingIntent.getActivity(c, 7110, take, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        v.setOnClickPendingIntent(id(c, "ecg_w_root", "id"), PendingIntent.getActivity(c, 7111, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        v.setContentDescription(id(c, "ecg_w_root", "id"), "ECG. " + l1 + ". " + l2 + ". " + l3);
        return v;
    }
}

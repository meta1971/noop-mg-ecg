package com.noop.mgecg;

// FILE VERSION 0.9.0 (23 Oct): the heart-first personal health app. Research only.

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

/**
 * Today, Heart, ECG, Rest and You, laid out like a glass-and-glow fitness app but about the heart: live heart rate, the ECG
 * readings the strap makes, sleep with its stages, SpO2, and how resting pulse moves over the nights. Everything is computed on
 * this phone from the strap's own records. Sleep stages, recovery-style comparisons and zones are estimates and say so.
 * RESEARCH ONLY, not a medical device and not a diagnosis.
 */
public class HealthActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private NavBar nav;
    private int tab = 0;
    private HealthModel.Data data;
    private volatile boolean loading;
    private final Runnable tick = new Runnable() {
        @Override public void run() { load(); ui.postDelayed(this, 60000L); }
    };

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(new GlowBg(this), new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        nav = new NavBar(this, 0, new NavBar.Listener() {
            @Override public void onTab(int t) { tab = t; render(); }
        });
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, Math.round(dp(this, 68)), Gravity.BOTTOM);
        np.setMargins(Math.round(dp(this, 14)), 0, Math.round(dp(this, 14)), Math.round(dp(this, 18)));
        View scrim = new View(this) {
            @Override protected void onDraw(Canvas cv) {
                Paint p = new Paint();
                p.setShader(new LinearGradient(0, 0, 0, getHeight(), 0x0007090C, 0xFF07090C, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, getWidth(), getHeight(), p);
            }
        };
        root.addView(scrim, new FrameLayout.LayoutParams(-1, Math.round(dp(this, 130)), Gravity.BOTTOM));
        root.addView(nav, np);
        setContentView(root);
        render();
    }

    @Override protected void onResume() { super.onResume(); ui.removeCallbacks(tick); ui.post(tick); }

    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }

    private SharedPreferences sp() { return getSharedPreferences("today", MODE_PRIVATE); }
    private int maxHr() { return sp().getInt("maxhr", 167); }
    private float goal() { return sp().getFloat("goal", 8.0f); }
    private String name() { return sp().getString("name", "Ame"); }

    private void load() {
        if (loading) return;
        loading = true;
        final Context app = getApplicationContext();
        final int mh = maxHr();
        final double g = goal();
        new Thread(new Runnable() {
            @Override public void run() {
                HealthModel.Data d = null;
                try { d = HealthModel.load(app, mh, g); } catch (Throwable ignored) { }
                final HealthModel.Data dd = d;
                ui.post(new Runnable() { @Override public void run() { loading = false; if (!isFinishing()) { data = dd; render(); } } });
            }
        }, "health-load").start();
    }

    // ------------------------------------------------------------------ small builders
    private int dpi(float v) { return Math.round(dp(this, v)); }

    private TextView tv(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font(this, weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView caps(String s) {
        TextView t = tv(s, 11, MUT, 400);
        t.setLetterSpacing(0.16f);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dpi(topDp);
        return p;
    }

    private LinearLayout card(float padDp) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(glass(this, 22));
        c.setPadding(dpi(padDp), dpi(padDp), dpi(padDp), dpi(padDp));
        return c;
    }

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private View spacer() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f)); return v; }

    private TextView chip(String s, int dot) {
        TextView t = tv(s, 12, INK, 400);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dpi(13), dpi(8), dpi(13), dpi(8));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x0DFFFFFF); g.setCornerRadius(dp(this, 17)); g.setStroke(dpi(1), 0x0FFFFFFF);
        t.setBackground(g);
        if (dot != 0) {
            t.setText("\u25CF  " + s);
        }
        return t;
    }

    private LinearLayout screen() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpi(18), dpi(40), dpi(18), dpi(120));
        return col;
    }

    private View wrap(LinearLayout col) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return sv;
    }

    private View head(String greet, String title, View right) {
        LinearLayout r = hrow();
        r.setGravity(Gravity.TOP);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(tv(greet, 13, MUT, 400));
        TextView t = tv(title, 28, INK, 400);
        t.setPadding(0, dpi(6), 0, 0);
        l.addView(t);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (right != null) r.addView(right);
        return r;
    }

    private View rowCard(String title, String sub, View.OnClickListener click) {
        LinearLayout c = card(15);
        c.addView(tv(title, 13.5f, INK, 400));
        TextView s = tv(sub, 12.5f, MUT, 400);
        s.setPadding(0, dpi(3), 0, 0);
        c.addView(s);
        if (click != null) c.setOnClickListener(click);
        return c;
    }

    private View pillButton(String text, int color, int fg, View.OnClickListener l) {
        TextView t = tv(text, 14, fg, 600);
        t.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(this, 21));
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    // ------------------------------------------------------------------ import a pull zip
    private String importNote = "A reinstall empties the app. Import the zip you exported (history, ECG reports, strips) to bring those nights back.";
    private static final int REQ_ZIP = 77;

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(i, REQ_ZIP);
    }

    @Override protected void onActivityResult(int req, int res, final Intent data2) {
        super.onActivityResult(req, res, data2);
        if (req != REQ_ZIP || res != RESULT_OK || data2 == null || data2.getData() == null) return;
        final android.net.Uri uri = data2.getData();
        final Context app = getApplicationContext();
        importNote = "Importing\u2026";
        render();
        new Thread(new Runnable() {
            @Override public void run() {
                final String msg = importZip(app, uri);
                ui.post(new Runnable() { @Override public void run() { importNote = msg; render(); } });
                long[] waits = {3000L, 7000L, 12000L, 20000L, 30000L};          // the history is read in the background
                for (long w : waits) {
                    try { Thread.sleep(w); } catch (InterruptedException ignored) { }
                    ui.post(new Runnable() { @Override public void run() { load(); } });
                }
            }
        }, "health-import").start();
    }

    // ------------------------------------------------------------------ export summary (small CSV files, the raw files stay on the phone)
    private volatile boolean exporting;

    private void exportSummary(final int days) {
        if (exporting) return;
        exporting = true;
        importNote = "Writing the summary files\u2026 (about a minute)";
        render();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                SummaryExport.Result r;
                try {
                    r = SummaryExport.run(app, days);
                } catch (Throwable t) {
                    r = new SummaryExport.Result();
                    r.note = "Export stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                }
                final SummaryExport.Result res = r;
                ui.post(new Runnable() {
                    @Override public void run() {
                        exporting = false;
                        importNote = res.note;
                        render();
                        if (!res.uris.isEmpty()) {
                            try {
                                Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                                i.setType("text/csv");
                                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, res.uris);
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(i, "Send the summary files"));
                            } catch (Throwable ignored) { }
                        }
                    }
                });
            }
        }, "summary-export").start();
    }

    /** Copy the history files, ECG reports and strips out of a pull zip into the app's folder, then let the day logger read them. */
    private static String importZip(Context app, android.net.Uri uri) {
        java.io.File dir = app.getExternalFilesDir(null);
        if (dir == null) return "No storage folder available.";
        int bins = 0, reports = 0, other = 0, skipped = 0;
        try {
            java.io.InputStream raw = app.getContentResolver().openInputStream(uri);
            if (raw == null) return "Could not open that file.";
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(raw));
            try {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[64 * 1024];
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    n = n.substring(n.lastIndexOf('/') + 1);
                    boolean bin = n.startsWith("historical_bin_") && n.endsWith(".bin");
                    boolean rep = n.startsWith("ecg_report_") && n.endsWith(".html");
                    boolean oth = (n.startsWith("ecg_strip_") && n.endsWith(".png")) || (n.startsWith("labrador_analysis_") && n.endsWith(".txt")) || (n.startsWith("realtime_ecg_bin_") && n.endsWith(".bin"))
                            || (n.startsWith("labrador_bin_") && n.endsWith(".bin")) || (n.startsWith("reconstructed_waveform_") && n.endsWith(".csv"));
                    if (!bin && !rep && !oth) continue;
                    java.io.File out = new java.io.File(dir, n);
                    if (out.exists() && out.length() > 0) { skipped++; continue; }
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                    try {
                        int k;
                        while ((k = zin.read(buf)) > 0) fo.write(buf, 0, k);
                    } finally {
                        fo.close();
                    }
                    // the day logger skips files touched in the last 90 s ("still being written"), so date these as old
                    //noinspection ResultOfMethodCallIgnored
                    out.setLastModified(System.currentTimeMillis() - 5L * 60L * 1000L);
                    if (bin) bins++; else if (rep) reports++; else other++;
                }
            } finally {
                zin.close();
            }
        } catch (Throwable t) {
            return "Import stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
        DayLog.scanNewFiles(app);
        return "Imported " + bins + " history files, " + reports + " ECG reports and " + other + " other files" + (skipped > 0 ? " (" + skipped + " were already here)" : "") + ". Reading the history now; the screens fill in over the next minute.";
    }

    /** Bring the already-running main screen forward (never a second copy: it owns the strap link). */
    private Intent oldScreens() { Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); i.putExtra("stay", true); return i; }

    private void startEcg() { NewLookActivity.pendingEcg = true; Intent i = oldScreens(); i.putExtra("start_ecg", true); startActivity(i); }

    private void openReport(java.io.File f) { NewLookActivity.pendingReport = f.getAbsolutePath(); Intent i = oldScreens(); i.putExtra("open_report", f.getAbsolutePath()); startActivity(i); }

    @Override public void onBackPressed() { moveTaskToBack(true); }

    private void go(int t) { tab = t; nav.setActive(t); render(); }

    // ------------------------------------------------------------------ render
    private void render() {
        content.removeAllViews();
        View v;
        switch (tab) {
            case 1: v = heartTab(); break;
            case 2: v = ecgTab(); break;
            case 3: v = restTab(); break;
            case 4: v = youTab(); break;
            default: v = todayTab();
        }
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private static String ago(long unix) {
        long s = System.currentTimeMillis() / 1000L - unix;
        if (s < 90) return s + " s ago";
        if (s < 5400) return (s / 60) + " min ago";
        if (s < 172800) return String.format(Locale.UK, "%.0f h ago", s / 3600.0);
        return (s / 86400) + " days ago";
    }

    private static String hhmm(long unix) { return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(unix * 1000L)); }

    private int screenW() { return getResources().getDisplayMetrics().widthPixels - dpi(36); }

    // ------------------------------------------------------------------ TODAY
    private View todayTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        if (d == null || d.day == null || d.day.newestTs == 0) {
            col.addView(head("Hi, " + name(), "Today", null));
            LinearLayout c = card(18);
            c.addView(caps("NO RECORDS YET"));
            TextView t = tv("Turn on the day logger and pull the strap's history. This screen fills in from the strap's own per-second records.", 14, MUT, 400);
            t.setPadding(0, dpi(8), 0, 0);
            c.addView(t);
            col.addView(c, lp(-1, -2, 18));
            col.addView(pillButton("Open the day logger", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, DayLogActivity.class)); } }), lp(-1, dpi(46), 14));
            return wrap(col);
        }
        TodayModel.Day day = d.day;
        SleepNight.Night n = day.night;
        long age = System.currentTimeMillis() / 1000L - day.newestTs;
        TextView strap = chip("STRAP", 1);
        strap.setTextColor(age < 900 ? GREEN : GOLD);
        col.addView(head("Hi, " + name(), n != null ? "You slept " + HealthModel.hmShort((long) (n.tstH * 3600)) : "Good morning", strap));
        float frac = 0f;
        if (d.nowHr > 0 && !Double.isNaN(day.restingHr)) frac = (float) ((d.nowHr - day.restingHr) / Math.max(30.0, maxHr() - day.restingHr));
        int ow = Math.min(screenW(), dpi(300));
        View orb = new OrbView(this, d.nowHr > 0 ? String.valueOf(d.nowHr) : "\u2014", "BPM NOW", frac, BLUE, 0xFF8FD8FF, 0xFF1FA0EE, 0xFF0B6FD0);
        LinearLayout.LayoutParams op = lp(ow, ow, 4);
        op.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(orb, op);
        if (n != null) {
            double pct = day.sleepPct;
            col.addView(tv(pct >= 92 ? "A full night" : "A short night", 19, INK, 400), lp(-2, -2, 6));
            StringBuilder s = new StringBuilder();
            s.append("You woke at ").append(hhmm(n.endTs));
            if (!Double.isNaN(day.restingHr)) s.append(" with a lowest five-minute heart rate of ").append(Math.round(day.restingHr));
            s.append(".");
            if (day.spo2 != null && day.spo2.validWindows > 0) s.append(" SpO2 held around ").append(day.spo2.median).append("%.");
            if (!Double.isNaN(n.brMedian)) s.append(" Breathing was ").append(String.format(Locale.UK, "%.1f", n.brMedian)).append(" a minute.");
            s.append(" Efficiency ").append(Math.round(n.effPct)).append("%.");
            TextView p = tv(s.toString(), 14, MUT, 400);
            p.setLineSpacing(0, 1.25f);
            p.setPadding(0, dpi(6), 0, 0);
            col.addView(p);
        }
        String ecgTitle = "No ECG reading yet", ecgSub = "Start one from the ECG tab";
        if (!d.readings.isEmpty()) {
            HealthModel.Reading r = d.readings.get(0);
            for (HealthModel.Reading q : d.readings) if (!"-".equals(q.hr)) { r = q; break; }
            ecgTitle = "Last ECG \u00b7 " + r.when;
            ecgSub = ("-".equals(r.hr) ? "" : "HR " + r.hr + " bpm") + ("-".equals(r.rhythm) ? "" : (("-".equals(r.hr) ? "" : " \u00b7 ") + r.rhythm));
            if (ecgSub.isEmpty()) ecgSub = "Open to see why";
        }
        col.addView(rowCard(ecgTitle, ecgSub, new View.OnClickListener() { @Override public void onClick(View v) { go(2); } }), lp(-1, -2, 16));
        if (n != null) {
            String sub = (Double.isNaN(n.brMedian) ? "" : "Breathing " + String.format(Locale.UK, "%.1f", n.brMedian) + " a minute") + (Double.isNaN(n.skinMedian) ? "" : ", skin " + String.format(Locale.UK, "%.1f", n.skinMedian) + " \u00b0C");
            col.addView(rowCard("Last night \u00b7 " + HealthModel.hm(n.tstH) + " asleep" + (day.spo2 != null && day.spo2.validWindows > 0 ? " \u00b7 SpO2 " + day.spo2.median + "%" : ""), sub.isEmpty() ? "Open Rest for the whole night" : sub,
                    new View.OnClickListener() { @Override public void onClick(View v) { go(3); } }), lp(-1, -2, 10));
        }
        LinearLayout c = card(16);
        LinearLayout h = hrow();
        h.addView(caps("THE LAST 24 HOURS"));
        h.addView(spacer());
        h.addView(tv("data to " + ago(day.newestTs), 12, MUT, 400));
        c.addView(h);
        c.addView(new HrChartView(this, day.hrT, day.hrV, n == null ? 0 : n.bedTs, n == null ? 0 : n.endTs, false), lp(-1, dpi(80), 8));
        col.addView(c, lp(-1, -2, 10));
        return wrap(col);
    }

    // ------------------------------------------------------------------ HEART
    private View heartTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        if (d == null || d.day == null || d.day.newestTs == 0) {
            col.addView(head("Heart", "Heart", null));
            col.addView(tv("No records yet.", 14, MUT, 400), lp(-2, -2, 16));
            return wrap(col);
        }
        TodayModel.Day day = d.day;
        col.addView(tv("Heart", 13, MUT, 400));
        LinearLayout big = hrow();
        big.setGravity(Gravity.BOTTOM);
        TextView num = tv(d.nowHr > 0 ? String.valueOf(d.nowHr) : "\u2014", 64, INK, 200);
        num.setTypeface(font(this, 200));
        big.addView(num);
        TextView unit = tv("  bpm, live", 14, MUT, 400);
        unit.setPadding(0, 0, 0, dpi(8));
        big.addView(unit);
        big.addView(spacer());
        LinearLayout rt = new LinearLayout(this);
        rt.setOrientation(LinearLayout.VERTICAL);
        rt.setGravity(Gravity.END);
        rt.addView(caps("BEAT TO BEAT"));
        String hrv = "\u2014";
        String hrvSrc = "No ECG reading yet";
        for (HealthModel.Reading r : d.readings) if (!"-".equals(r.hrv)) { hrv = r.hrv + " ms"; hrvSrc = "From the " + r.when + " ECG"; break; }
        rt.addView(tv(hrv, 24, INK, 300));
        big.addView(rt);
        col.addView(big, lp(-1, -2, 8));
        col.addView(tv(d.nowHr > 0 && !Double.isNaN(day.restingHr) ? (d.nowHr > day.restingHr + 8 ? "Above your lowest 5-minute rate of " : "Close to your lowest 5-minute rate of ") + Math.round(day.restingHr) + ". " + hrvSrc + "." : hrvSrc + ".", 12, MUT, 400), lp(-2, -2, 6));

        LinearLayout c = card(16);
        LinearLayout h = hrow();
        h.addView(caps("LAST 24 HOURS"));
        h.addView(spacer());
        h.addView(tv(d.hr24Max > 0 ? d.hr24Min + " low \u00b7 " + Math.round(d.hr24Mean) + " avg \u00b7 " + d.hr24Max + " high" : "", 12, MUT, 400));
        c.addView(h);
        c.addView(new HrChartView(this, day.hrT, day.hrV, day.night == null ? 0 : day.night.bedTs, day.night == null ? 0 : day.night.endTs, true), lp(-1, dpi(130), 8));
        col.addView(c, lp(-1, -2, 14));

        LinearLayout z = card(16);
        z.addView(caps("WHERE THE TIME WAS SPENT"));
        z.addView(new ZoneBarView(this, d.zoneSec), lp(-1, dpi(10), 12));
        int mh = maxHr();
        String[] nm = {"Resting", "Easy", "Steady", "Hard", "All out"};
        int[] colz = {0xFF6A73F2, 0xFF4CB4FF, 0xFF2EE0B8, 0xFFF2B35B, 0xFFFF6B6B};
        int[] ze = HealthModel.zoneEdges(mh);
        int[] edge = {0, ze[0], ze[1], ze[2], ze[3]};
        for (int i = 0; i < 5; i++) {
            if (i > 2 && d.zoneSec[i] == 0 && i == 4) continue;
            LinearLayout r = hrow();
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
            dg.setColor(colz[i]); dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setBackground(dg);
            r.addView(dot, new LinearLayout.LayoutParams(dpi(8), dpi(8)));
            TextView a = tv("  " + nm[i], 13, INK, 400);
            r.addView(a, new LinearLayout.LayoutParams(dpi(96), -2));
            r.addView(tv(i == 0 ? "under " + edge[1] : edge[i] + (i < 4 ? " to " + edge[i + 1] : " and over"), 12, MUT, 400));
            r.addView(spacer());
            r.addView(tv(d.zoneSec[i] == 0 ? "\u2014" : HealthModel.hm(d.zoneSec[i] / 3600.0), 13, INK, 600));
            z.addView(r, lp(-1, dpi(26), i == 0 ? 10 : 0));
        }
        col.addView(z, lp(-1, -2, 12));

        LinearLayout rd = card(16);
        rd.addView(tv("A reading, now", 16, INK, 600));
        TextView t = tv("About three minutes with two fingers on the clasp. An ECG reading gives beat-to-beat figures you can set beside the overnight ones.", 12.5f, MUT, 400);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dpi(6), 0, 0);
        rd.addView(t);
        rd.addView(pillButton("Start an ECG reading", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startEcg(); } }), lp(-1, dpi(42), 12));
        col.addView(rd, lp(-1, -2, 12));

        // heart over time
        LinearLayout ov = card(16);
        LinearLayout oh = hrow();
        oh.addView(caps("HEART OVER TIME"));
        oh.addView(spacer());
        int nn = d.nights.size();
        oh.addView(tv(nn + (nn == 1 ? " night" : " nights") + " logged", 12, nn >= 14 ? MUT : GOLD, 400));
        ov.addView(oh);
        if (nn >= 2) ov.addView(new NightsLineView(this, d.nights), lp(-1, dpi(110), 10));
        TextView note = tv(nn >= 2 ? "Lowest five-minute heart rate each night. Optical HRV is in the Rest tab." : "The line draws itself from the second night on. Last night's lowest five-minute heart rate was " + (Double.isNaN(day.restingHr) ? "\u2014" : String.valueOf(Math.round(day.restingHr))) + ".", 12, MUT, 400);
        note.setPadding(0, dpi(8), 0, 0);
        ov.addView(note);
        col.addView(ov, lp(-1, -2, 12));
        return wrap(col);
    }

    /** Lowest five-minute heart rate per logged night, newest on the right. */
    static final class NightsLineView extends View {
        private final List<TodayModel.NightStat> nights;
        NightsLineView(Context c, List<TodayModel.NightStat> nights) { super(c); this.nights = nights; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            List<TodayModel.NightStat> use = new ArrayList<>();
            for (TodayModel.NightStat s : nights) if (!Double.isNaN(s.hrLow5)) use.add(s);
            if (use.size() > 14) use = use.subList(use.size() - 14, use.size());
            if (use.size() < 2) return;
            float w = getWidth(), h = getHeight() - dp(c, 14);
            double lo = 1e9, hi = -1e9;
            for (TodayModel.NightStat s : use) { lo = Math.min(lo, s.hrLow5); hi = Math.max(hi, s.hrLow5); }
            lo = Math.floor(lo - 2); hi = Math.ceil(hi + 2);
            if (hi - lo < 8) { hi = lo + 8; }
            List<float[]> pts = new ArrayList<>();
            for (int i = 0; i < use.size(); i++) pts.add(new float[] {dp(c, 8) + (w - dp(c, 16)) * i / (use.size() - 1f), (float) (h * (1 - (use.get(i).hrLow5 - lo) / (hi - lo)))});
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setColor(0x12FFFFFF); g.setStrokeWidth(dp(c, 1));
            for (int k = 0; k < 4; k++) cv.drawLine(0, h * k / 3f, w, h * k / 3f, g);
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStyle(Paint.Style.STROKE); ln.setStrokeWidth(dp(c, 2.2f)); ln.setStrokeCap(Paint.Cap.ROUND);
            ln.setShader(new LinearGradient(0, 0, w, 0, 0xFFFF7A93, 0xFFFFC36B, Shader.TileMode.CLAMP));
            cv.drawPath(smooth(pts), ln);
            for (float[] p : pts) { cv.drawCircle(p[0], p[1], dp(c, 5), paint(0x40FF7A93)); cv.drawCircle(p[0], p[1], dp(c, 2.6f), paint(0xFFFFFFFF)); }
            Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
            tx.setColor(MUT); tx.setTextSize(dp(c, 10)); tx.setTypeface(font(c, 400));
            SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.UK);
            tx.setTextAlign(Paint.Align.LEFT);
            cv.drawText(f.format(new Date(use.get(0).bed * 1000L)), 0, getHeight() - dp(c, 2), tx);
            tx.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(f.format(new Date(use.get(use.size() - 1).bed * 1000L)), w, getHeight() - dp(c, 2), tx);
        }
    }

    // ------------------------------------------------------------------ ECG
    private View ecgTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        int n = d == null ? 0 : d.readings.size();
        col.addView(tv("ECG", 13, MUT, 400));
        LinearLayout top = hrow();
        top.addView(tv(n + (n == 1 ? " reading" : " readings"), 40, INK, 200));
        top.addView(spacer());
        top.addView(pillButton("Start", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startEcg(); } }), new LinearLayout.LayoutParams(dpi(110), dpi(38)));
        col.addView(top, lp(-1, -2, 8));
        col.addView(tv(n == 0 ? "None saved yet. Two fingers on the clasp, about three minutes." : "Newest first. Tap one to open its full report.", 12.5f, MUT, 400), lp(-2, -2, 4));
        if (d != null) {
            for (final HealthModel.Reading r : d.readings) {
                LinearLayout c = card(16);
                LinearLayout h = hrow();
                h.addView(tv(r.when, 15, INK, 600));
                h.addView(spacer());
                if (!"-".equals(r.grade)) {
                    String gr = r.grade.toUpperCase(Locale.UK);
                    h.addView(chipSmall("GRADE " + gr, gr.startsWith("A") || gr.startsWith("B") ? GREEN : (gr.startsWith("C") ? GOLD : MUT)));
                }
                c.addView(h);
                StringBuilder sub = new StringBuilder();
                if (!"-".equals(r.hr)) sub.append("HR ").append(r.hr).append(" bpm");
                if (!"-".equals(r.hrv)) sub.append(sub.length() > 0 ? " \u00b7 " : "").append("HRV ").append(r.hrv).append(" ms");
                if (!"-".equals(r.rhythm)) sub.append(sub.length() > 0 ? " \u00b7 " : "").append(r.rhythm);
                TextView s = tv(sub.length() == 0 ? "Open to see why" : sub.toString(), 12.5f, MUT, 400);
                s.setPadding(0, dpi(4), 0, 0);
                c.addView(s);
                c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openReport(r.file); } });
                col.addView(c, lp(-1, -2, 12));
            }
        }
        col.addView(tv("Research only: not a diagnosis. Reports show how reliable each measurement was.", 11, MUT, 400), lp(-2, -2, 16));
        return wrap(col);
    }

    private View chipSmall(String s, int color) {
        TextView t = tv(s, 9.5f, color, 600);
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dpi(11), dpi(5), dpi(11), dpi(5));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x0DFFFFFF); g.setCornerRadius(dp(this, 13)); g.setStroke(dpi(1), 0x0FFFFFFF);
        t.setBackground(g);
        return t;
    }

    // ------------------------------------------------------------------ REST
    private View restTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        if (d == null || d.day == null || d.day.night == null) {
            col.addView(head("Last night", "Rest", null));
            col.addView(tv("No complete night in the records yet.", 14, MUT, 400), lp(-2, -2, 16));
            return wrap(col);
        }
        SleepNight.Night n = d.day.night;
        double goal = goal();
        col.addView(head("Last night", "Rest", chip(hhmm(n.bedTs) + " to " + hhmm(n.endTs), 0)));
        int rs = Math.min(screenW(), dpi(290));
        double need = Math.min(1.0, n.tstH / goal);
        View ring = new StageRingView(this, ringSegments(d, need), HealthModel.hm(n.tstH).replace(" ", ""), "ASLEEP");
        LinearLayout.LayoutParams rp = lp(rs, rs, 0);
        rp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(ring, rp);
        double diff = n.tstH - goal;
        TextView chipT = chip((diff < 0 ? HealthModel.hm(-diff) + " under" : HealthModel.hm(diff) + " over"), 0);
        chipT.setTextColor(0xFFBFE6FF);
        LinearLayout.LayoutParams cp = lp(-2, -2, -64);
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(chipT, cp);
        TextView capt = tv("the ring closes at your need \u00b7 " + HealthModel.hm(goal), 12, MUT, 400);
        capt.setGravity(Gravity.CENTER);
        col.addView(capt, lp(-1, -2, 28));
        col.addView(rowCard("The ring is " + Math.round(100 * need) + "% closed. See what the night was made of", "Stages are estimates from heart rate and movement", null), lp(-1, -2, 14));

        LinearLayout w = card(16);
        w.addView(caps("YOUR LAST SEVEN"));
        w.addView(new WeekBarsView(this, d.lastSeven, d.lastSevenBed, goal), lp(-1, dpi(140), 12));
        col.addView(w, lp(-1, -2, 12));

        long tot = 0;
        for (long s : d.stageSecs) tot += s;
        LinearLayout sh = card(16);
        LinearLayout hh = hrow();
        hh.addView(caps("THE SHAPE OF IT"));
        hh.addView(spacer());
        hh.addView(tv(hhmm(n.bedTs) + " to " + hhmm(n.endTs), 12, MUT, 400));
        sh.addView(hh);
        if (tot > 0) {
            sh.addView(stageBar(d.stageSecs), lp(-1, dpi(10), 12));
            TextView lg = tv("Deep " + HealthModel.hmShort(d.stageSecs[HealthModel.DEEP]) + " \u00b7 REM " + HealthModel.hmShort(d.stageSecs[HealthModel.REM]) + " \u00b7 Light " + HealthModel.hmShort(d.stageSecs[HealthModel.LIGHT]) + " \u00b7 estimate", 12, MUT, 400);
            lg.setPadding(0, dpi(8), 0, 0);
            sh.addView(lg);
        } else {
            sh.addView(tv("The night was too short to stage.", 13, MUT, 400), lp(-2, -2, 10));
        }
        col.addView(sh, lp(-1, -2, 12));

        if (tot > 0) {
            LinearLayout wn = card(16);
            LinearLayout wh = hrow();
            wh.addView(caps("THE WHOLE NIGHT"));
            wh.addView(spacer());
            wh.addView(chipSmall("ESTIMATE", GOLD));
            wn.addView(wh);
            wn.addView(new StageFlowView(this, d.binStage, d.binSure, d.binStart), lp(-1, dpi(140), 10));
            long[] s = d.stageSecs;
            String[] nm = {"AWAKE", "REM", "LIGHT", "DEEP"};
            int[] idx = {HealthModel.WAKE, HealthModel.REM, HealthModel.LIGHT, HealthModel.DEEP};
            for (int i = 0; i < 4; i++) {
                LinearLayout r = hrow();
                TextView a = tv(nm[i], 11, MUT, 600);
                a.setLetterSpacing(0.14f);
                r.addView(a, new LinearLayout.LayoutParams(dpi(58), -2));
                r.addView(pips((float) s[idx[i]] / tot, STAGE[idx[i]]), new LinearLayout.LayoutParams(0, dpi(6), 1f));
                TextView pc = tv(Math.round(100.0 * s[idx[i]] / tot) + "%", 12, STAGE[idx[i]], 400);
                pc.setGravity(Gravity.END);
                r.addView(pc, new LinearLayout.LayoutParams(dpi(44), -2));
                TextView tm = tv(HealthModel.hmShort(s[idx[i]]), 15, INK, 300);
                tm.setGravity(Gravity.END);
                r.addView(tm, new LinearLayout.LayoutParams(dpi(48), -2));
                wn.addView(r, lp(-1, dpi(30), i == 0 ? 8 : 0));
            }
            int sure = 0, all = 0;
            for (int i = 0; i < d.binStage.length; i++) if (d.binStage[i] >= 0) { all++; if (d.binSure[i]) sure++; }
            TextView note = tv("Solid blocks are where two staging methods agree (" + (all == 0 ? 0 : Math.round(100.0 * sure / all)) + "% of the night). Dashed blocks are where they differ. Nothing in a wrist band sees brain activity, so these are estimates, not a sleep study.", 11, MUT, 400);
            note.setLineSpacing(0, 1.2f);
            note.setPadding(0, dpi(10), 0, 0);
            wn.addView(note);
            col.addView(wn, lp(-1, -2, 12));
        }
        if (d.day.spo2 != null && d.day.spo2.validWindows > 0) {
            LinearLayout so = card(16);
            LinearLayout sh2 = hrow();
            sh2.addView(caps("SPO2 \u00b7 STRAP ESTIMATE"));
            sh2.addView(spacer());
            sh2.addView(tv(d.day.spo2.median + "%", 20, 0xFF46E0C8, 300));
            so.addView(sh2);
            so.addView(new SpoView(this, d.spo2Windows), lp(-1, dpi(96), 10));
            TextView t = tv(d.day.spo2.validWindows + " windows, " + d.day.spo2.min + " to " + d.day.spo2.max + "%. " + (d.day.spo2.below94 == 0 ? "None below 94. " : d.day.spo2.below94 + " below 94. ") + "Not calibrated against an oximeter.", 12, MUT, 400);
            t.setPadding(0, dpi(8), 0, 0);
            so.addView(t);
            col.addView(so, lp(-1, -2, 12));
        }
        return wrap(col);
    }

    /** Segments for the ring: {stage, from, to, sure}. Fractions of the closed part of the ring, merged and de-confettied. */
    private List<float[]> ringSegments(HealthModel.Data d, double need) {
        List<float[]> out = new ArrayList<>();
        int n = d.binStage.length;
        if (n == 0) return out;
        int[] sm = new int[n];
        for (int i = 0; i < n; i++) {
            int[] c = new int[4];
            for (int k = Math.max(0, i - 1); k <= Math.min(n - 1, i + 1); k++) if (d.binStage[k] >= 0) c[d.binStage[k]]++;
            int best = d.binStage[i] >= 0 ? d.binStage[i] : HealthModel.LIGHT, bc = c[best];
            for (int k = 0; k < 4; k++) if (c[k] > bc) { bc = c[k]; best = k; }
            sm[i] = best;
        }
        int i = 0;
        while (i < n) {
            int j = i;
            double sure = 0;
            while (j + 1 < n && sm[j + 1] == sm[i]) j++;
            for (int k = i; k <= j; k++) sure += d.binSure[k] ? 1 : 0;
            out.add(new float[] {sm[i], (float) (need * i / n), (float) (need * (j + 1) / n), sure / (j - i + 1) >= 0.5 ? 1f : 0f});
            i = j + 1;
        }
        return out;
    }

    private View stageBar(final long[] s) {
        return new View(this) {
            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight(), gap = dp(getContext(), 3), x = 0;
                long tot = s[HealthModel.DEEP] + s[HealthModel.REM] + s[HealthModel.LIGHT];
                if (tot == 0) return;
                int[] order = {HealthModel.DEEP, HealthModel.REM, HealthModel.LIGHT};
                float usable = w - 2 * gap;
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (int k : order) {
                    float wi = usable * s[k] / tot;
                    p.setColor(STAGE[k]);
                    cv.drawRoundRect(new RectF(x, 0, x + wi, h), dp(getContext(), 3), dp(getContext(), 3), p);
                    x += wi + gap;
                }
            }
        };
    }

    private View pips(final float frac, final int color) {
        return new View(this) {
            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight(), gap = dp(getContext(), 3);
                int n = 14, on = Math.round(frac * n);
                float pw = (w - gap * (n - 1)) / n;
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (int i = 0; i < n; i++) {
                    p.setColor(alpha(color, i < on ? 1f : 0.14f));
                    cv.drawRoundRect(new RectF(i * (pw + gap), 0, i * (pw + gap) + pw, h), h / 2f, h / 2f, p);
                }
            }
        };
    }

    // ------------------------------------------------------------------ YOU
    private View youTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        col.addView(head("Strap, logger and settings", "You", null));
        float logged = d == null || d.day == null ? 0f : (float) d.day.loggedH;
        View av = avatar(Math.min(1f, logged / 24f));
        LinearLayout.LayoutParams ap = lp(dpi(170), dpi(170), 10);
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(av, ap);
        TextView nm = tv(name(), 22, INK, 400);
        nm.setGravity(Gravity.CENTER);
        col.addView(nm, lp(-1, -2, 4));
        TextView cap = tv(String.format(Locale.UK, "%.1f h logged in the last 24", logged), 12.5f, MUT, 400);
        cap.setGravity(Gravity.CENTER);
        col.addView(cap, lp(-1, -2, 6));

        LinearLayout st = card(16);
        st.addView(caps("SETTINGS"));
        final SharedPreferences ctl = getSharedPreferences("labrador_ecg_control", MODE_PRIVATE);
        st.addView(toggleRow("Near-live 500 Hz during readings", ctl, "ecg_tail"), lp(-1, dpi(52), 6));
        st.addView(toggleRow("Auto-restart at the 10-minute cap", ctl, "ecg_autorestart"), lp(-1, dpi(52), 0));
        final TextView goalV = tv(String.format(Locale.UK, "%.1f h", goal()), 14, INK, 600);
        st.addView(valueRow("Sleep goal", goalV, new View.OnClickListener() {
            @Override public void onClick(View v) { float g = goal() + 0.5f; if (g > 9.5f) g = 6.5f; sp().edit().putFloat("goal", g).apply(); goalV.setText(String.format(Locale.UK, "%.1f h", g)); load(); }
        }), lp(-1, dpi(52), 0));
        final TextView mxV = tv(maxHr() + " bpm", 14, INK, 600);
        st.addView(valueRow("Maximum heart rate", mxV, new View.OnClickListener() { @Override public void onClick(View v) { askMaxHr(mxV); } }), lp(-1, dpi(52), 0));
        col.addView(st, lp(-1, -2, 16));

        LinearLayout dt = card(16);
        dt.addView(caps("YOUR DATA"));
        TextView t = tv("Everything stays on this phone. The day logger holds the raw history and exports it as one zip.", 12.5f, MUT, 400);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dpi(6), 0, 0);
        dt.addView(t);
        dt.addView(pillButton("Import a pull (zip)", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { pickZip(); } }), lp(-1, dpi(44), 12));
        dt.addView(pillButton("Export summary (CSV, last 4 days)", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { exportSummary(4); } }), lp(-1, dpi(44), 12));
        dt.addView(pillButton("Export summary, all days", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { exportSummary(0); } }), lp(-1, dpi(44), 8));
        dt.addView(pillButton("Day logger and export", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, DayLogActivity.class)); } }), lp(-1, dpi(44), 12));
        dt.addView(pillButton("Older screens (ECG tools)", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(oldScreens()); } }), lp(-1, dpi(44), 8));
        TextView imp = tv(importNote, 11.5f, MUT, 400);
        imp.setPadding(0, dpi(10), 0, 0);
        dt.addView(imp);
        col.addView(dt, lp(-1, -2, 12));
        return wrap(col);
    }

    private View avatar(final float frac) {
        final String letter = name().isEmpty() ? "A" : name().substring(0, 1).toUpperCase(Locale.UK);
        return new View(this) {
            @Override protected void onDraw(Canvas cv) {
                Context c = getContext();
                float size = getWidth(), cx = size / 2f, cy = size / 2f, r = size * 0.40f, sw = dp(c, 10);
                RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(sw);
                p.setColor(0x12FFFFFF);
                cv.drawArc(box, 0, 360, false, p);
                for (int pass = 0; pass < 3; pass++) {
                    p.setShader(new LinearGradient(0, 0, size, size, 0xFFF2B35B, 0xFFFF8CA2, Shader.TileMode.CLAMP));
                    p.setStrokeWidth(sw + dp(c, 3 * (2 - pass)));
                    p.setAlpha(pass < 2 ? 50 + 40 * pass : 255);
                    cv.drawArc(box, -90, 360f * frac, false, p);
                }
                p.setShader(null);
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(INK); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 200)); t.setTextSize(dp(c, 62));
                cv.drawText(letter, cx, cy + dp(c, 22), t);
            }
        };
    }

    private View toggleRow(String label, final SharedPreferences prefs, final String key) {
        LinearLayout r = hrow();
        r.addView(tv(label, 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        final Toggle t = new Toggle(this, prefs.getBoolean(key, true));
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { boolean on = !prefs.getBoolean(key, true); prefs.edit().putBoolean(key, on).apply(); t.set(on); } });
        r.addView(t, new LinearLayout.LayoutParams(dpi(46), dpi(28)));
        r.setBackground(null);
        return r;
    }

    private View valueRow(String label, TextView value, View.OnClickListener l) {
        LinearLayout r = hrow();
        r.addView(tv(label, 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(value);
        r.setOnClickListener(l);
        return r;
    }

    /** The small pill switch. */
    static final class Toggle extends View {
        private boolean on;
        Toggle(Context c, boolean on) { super(c); this.on = on; }
        void set(boolean v) { on = v; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (on) p.setShader(new LinearGradient(0, 0, w, h, 0xFF37B6F6, 0xFF1788E8, Shader.TileMode.CLAMP)); else p.setColor(0x1FFFFFFF);
            cv.drawRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, p);
            p.setShader(null);
            p.setColor(0xFFFFFFFF);
            float r = h / 2f - dp(c, 3);
            cv.drawCircle(on ? w - h / 2f : h / 2f, h / 2f, r, p);
        }
    }

    private void askMaxHr(final TextView target) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(maxHr()));
        new AlertDialog.Builder(this).setTitle("Maximum heart rate (bpm)")
                .setMessage("Used only for the heart-rate zones. 220 minus your age is a rough guess; use a measured value if you have one.")
                .setView(e)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) {
                        try {
                            int v = Integer.parseInt(e.getText().toString().trim());
                            if (v >= 120 && v <= 230) { sp().edit().putInt("maxhr", v).apply(); target.setText(v + " bpm"); load(); }
                        } catch (NumberFormatException ignored) { }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Soft colour behind everything: a faint teal wash at the top and a blue glow low down, as in the mock-ups. */
    static final class GlowBg extends View {
        GlowBg(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new android.graphics.RadialGradient(w / 2f, 0f, w * 0.9f, new int[] {0x3328788F, 0x00000000}, null, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(new android.graphics.RadialGradient(w / 2f, h * 0.78f, w * 0.7f, new int[] {0x2A2FA8F0, 0x00000000}, null, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
        }
    }
}

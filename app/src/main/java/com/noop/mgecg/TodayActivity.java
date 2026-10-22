package com.noop.mgecg;

// FILE VERSION 0.8.0 (22 Oct): the all-day health screen. Research only.

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Today: the all-day picture from the strap's own per-second records, laid out like a fitness-strap app. Three rings
 * (sleep, recovery, effort), then the night, the overnight SpO2 windows, today's effort and the last 24 hours of heart
 * rate. Every number is an ESTIMATE from this strap's records and is labelled as one; recovery appears only once there
 * are enough earlier nights to compare with. Pure canvas drawing, no libraries. RESEARCH ONLY, not a medical device.
 */
public class TodayActivity extends Activity {

    static final int BG = 0xFF000000, CARD = 0xFF121418, LINE = 0xFF23262D, TEXT = 0xFFF4F6F9, MUTED = 0xFF8B93A0,
            DIM = 0xFF565D69, SLEEP = 0xFF7F8CFF, EFFORT = 0xFF2FA8FF, RED = 0xFFFF4D4F, AMBER = 0xFFFFC53D,
            GREEN = 0xFF1FE07A, HRC = 0xFFFF6B7A, TEMP = 0xFFFFB86B, SPO = 0xFF4FD6D0;

    private static final String PREFS = "today";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView subtitle;
    private volatile boolean loading;
    private final Runnable tick = new Runnable() {
        @Override public void run() { load(); ui.postDelayed(this, 60000L); }
    };

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(38), dp(18), dp(28));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv("TODAY", 30, TEXT, true);
        title.setLetterSpacing(0.06f);
        head.addView(title);
        subtitle = tv("loading", 12, MUTED, false);
        head.addView(subtitle);
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        col.addView(content, new LinearLayout.LayoutParams(-1, -2));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    // ------------------------------------------------------------------ settings
    private SharedPreferences sp() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private int maxHr() { return sp().getInt("maxhr", 167); }                 // 220 minus age, an assumption, editable
    private float sleepGoal() { return sp().getFloat("goal", 8.0f); }

    // ------------------------------------------------------------------ load and render
    private void load() {
        if (loading) return;
        loading = true;
        final Context app = getApplicationContext();
        final int mh = maxHr();
        final double goal = sleepGoal();
        new Thread(new Runnable() {
            @Override public void run() {
                TodayModel.Day d = null;
                SleepNight.Series s = null;
                try {
                    d = DayLog.today(app, mh, goal);
                    if (d != null && d.night != null) s = DayLog.sleepSeries(app, 0);
                } catch (Throwable ignored) { }
                final TodayModel.Day dd = d;
                final SleepNight.Series ss = s;
                ui.post(new Runnable() {
                    @Override public void run() { loading = false; if (!isFinishing()) render(dd, ss); }
                });
            }
        }, "today-load").start();
    }

    private static String hm(long unix) { return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(unix * 1000L)); }
    private static String hours(double h) {
        if (Double.isNaN(h)) return "\u2014";
        int m = (int) Math.round(h * 60.0);
        return (m / 60) + ":" + String.format(Locale.UK, "%02d", m % 60);
    }
    private static String ago(long unix) {
        long s = System.currentTimeMillis() / 1000L - unix;
        if (s < 90) return s + " s ago";
        if (s < 5400) return (s / 60) + " min ago";
        return String.format(Locale.UK, "%.1f h ago", s / 3600.0);
    }

    private void render(TodayModel.Day d, SleepNight.Series series) {
        content.removeAllViews();
        if (d == null || d.newestTs == 0) {
            subtitle.setText("no per-second records yet");
            LinearLayout c = card("NO DATA YET");
            c.addView(tv("Turn the day logger on and pull the strap's history. Today fills in from the strap's own per-second records.", 14, MUTED, false));
            addCard(c);
            addFooter();
            return;
        }
        String bat = "";
        try { bat = "  \u00b7  strap " + DayLog.statusLine(this).replaceAll(".*strap ", ""); } catch (Throwable ignored) { }
        subtitle.setText(new SimpleDateFormat("EEEE d MMMM", Locale.UK).format(new Date()) + "  \u00b7  data to " + ago(d.newestTs) + bat);

        // ---- the three rings
        LinearLayout rings = new LinearLayout(this);
        rings.setOrientation(LinearLayout.HORIZONTAL);
        rings.setWeightSum(3f);
        double sleepFrac = Double.isNaN(d.sleepPct) ? 0 : Math.min(1.0, d.sleepPct / 100.0);
        rings.addView(ringCell(sleepFrac, SLEEP, d.night == null ? "\u2014" : hours(d.night.tstH), "SLEEP", d.night == null ? "no night yet" : (int) Math.round(d.sleepPct) + "% of goal"));
        int recCol = Double.isNaN(d.recovery) ? DIM : (d.recovery < 34 ? RED : (d.recovery < 67 ? AMBER : GREEN));
        rings.addView(ringCell(Double.isNaN(d.recovery) ? 0 : d.recovery / 100.0, recCol,
                Double.isNaN(d.recovery) ? "\u2014" : String.valueOf((int) Math.round(d.recovery)) + "%", "RECOVERY",
                Double.isNaN(d.recovery) ? "est. needs " + TodayModel.MIN_BASELINE + " nights" : "estimate"));
        rings.addView(ringCell(d.effort / 21.0, EFFORT, String.format(Locale.UK, "%.1f", d.effort), "EFFORT", "of 21, estimate"));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(22);
        content.addView(rings, rp);

        // ---- sleep
        if (d.night != null) {
            SleepNight.Night n = d.night;
            LinearLayout c = card("SLEEP");
            c.addView(rowBig(hours(n.tstH), " asleep  \u00b7  " + hm(n.bedTs) + " to " + hm(n.endTs), TEXT));
            if (series != null && series.n > 10) {
                StripView strip = new StripView(this, series);
                c.addView(strip, lp(-1, dp(54), 12));
            }
            c.addView(statRow(new String[] {"IN BED", "EFFICIENCY", "SETTLED IN", "RESTLESS"},
                    new String[] {hours(n.tibH), String.format(Locale.UK, "%.0f%%", n.effPct), String.format(Locale.UK, "%.0f min", n.settleMin), String.valueOf(n.restless.size()) + "\u00d7"}), lp(-1, -2, 12));
            if (n.partialStart || n.partialEnd) c.addView(tv("The log starts or ends while the band is in sleep, so the totals cover only the logged part.", 11, AMBER, false), lp(-2, -2, 8));
            addCard(c);

            // ---- recovery contributors
            LinearLayout rc = card("RECOVERY  (ESTIMATE)");
            String why;
            if (Double.isNaN(d.recovery)) why = "Compares tonight's resting heart rate and overnight HRV with your own earlier nights. It needs " + TodayModel.MIN_BASELINE + " of them; there are " + d.baselineNights + " so far.";
            else why = "Against your last " + d.baselineNights + " nights. 50% is your usual night.";
            rc.addView(tv(why, 12, MUTED, false));
            rc.addView(statRow(new String[] {"RESTING HR", "HRV (OPTICAL)", "BREATHING", "SKIN TEMP"},
                    new String[] {Double.isNaN(n.hrLow5) ? "\u2014" : String.format(Locale.UK, "%.0f", n.hrLow5) + " bpm",
                            Double.isNaN(n.rmssd) ? "\u2014" : String.format(Locale.UK, "%.0f ms", n.rmssd),
                            Double.isNaN(n.brMedian) ? "\u2014" : String.format(Locale.UK, "%.1f /min", n.brMedian),
                            Double.isNaN(n.skinMedian) ? "\u2014" : String.format(Locale.UK, "%.1f \u00b0C", n.skinMedian)}), lp(-1, -2, 12));
            if (!Double.isNaN(d.zRhr)) rc.addView(tv(String.format(Locale.UK, "Resting HR %+.1f and HRV %+.1f standard deviations from your baseline.", d.zRhr, d.zHrv), 11, DIM, false), lp(-2, -2, 8));
            rc.addView(tv("HRV here comes from the strap's optical beat intervals, which read about 1.5 times higher than an ECG chest strap did on the validation night. Use it for your own trend, not against other people's numbers.", 11, DIM, false), lp(-2, -2, 8));
            addCard(rc);

            // ---- SpO2
            LinearLayout sc = card("SPO2  (STRAP ESTIMATE, NOT CALIBRATED)");
            if (d.spo2 != null && d.spo2.validWindows > 0) {
                sc.addView(rowBig(d.spo2.median + "%", " median  \u00b7  " + d.spo2.validWindows + " windows " + hm(d.spo2.first) + " to " + hm(d.spo2.last), TEXT));
                sc.addView(statRow(new String[] {"LOWEST WINDOW", "HIGHEST", "BELOW 94%", "BELOW 90%"},
                        new String[] {d.spo2.min + "%", d.spo2.max + "%", String.valueOf(d.spo2.below94), String.valueOf(d.spo2.below90)}), lp(-1, -2, 10));
                try {
                    List<Spo2.Window> ws = nightSpo2(n);
                    if (ws != null && ws.size() > 1) sc.addView(new SpoView(this, ws), lp(-1, dp(86), 12));
                } catch (Throwable ignored) { }
            } else {
                sc.addView(tv("No sleep measurement windows in the latest night. The strap measures for about 30 s every 20 minutes while you sleep.", 12, MUTED, false));
            }
            addCard(sc);
        }

        // ---- effort
        LinearLayout ec = card("EFFORT TODAY  (ESTIMATE)");
        ec.addView(rowBig(String.format(Locale.UK, "%.1f", d.effort), " of 21  \u00b7  since " + hm(d.dayStart), EFFORT));
        ec.addView(new ZoneBar(this, d.zoneMin), lp(-1, dp(14), 12));
        ec.addView(tv("Minutes by heart-rate zone: " + d.zoneMin[1] + " easy, " + d.zoneMin[2] + " light, " + d.zoneMin[3] + " moderate, " + d.zoneMin[4] + " hard, " + d.zoneMin[5] + " peak (max HR " + d.maxHr + " bpm).", 11, MUTED, false), lp(-2, -2, 8));
        ec.addView(statRow(new String[] {"STEPS", "WALKING", "RUNNING", "LOGGED"},
                new String[] {String.valueOf(d.steps), d.walkMin + " min", d.runMin + " min", String.format(Locale.UK, "%.1f h", d.loggedH)}), lp(-1, -2, 10));
        ec.addView(tv("Effort is zone minutes weighted 1 to 5, squeezed onto 0 to 21. It is not WHOOP's strain.", 11, DIM, false), lp(-2, -2, 8));
        addCard(ec);

        // ---- heart rate, last 24 h
        LinearLayout hc = card("HEART RATE, LAST 24 HOURS");
        hc.addView(rowBig(Double.isNaN(d.restingHr) ? "\u2014" : String.format(Locale.UK, "%.0f", d.restingHr), " bpm resting (lowest 5-minute median)", TEXT));
        if (d.hrT.length > 3) hc.addView(new HrView(this, d), lp(-1, dp(150), 12));
        addCard(hc);

        // ---- settings
        LinearLayout set = card("SETTINGS");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView goal = chip("Sleep goal  " + String.format(Locale.UK, "%.1f", sleepGoal()) + " h");
        goal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                float g = sleepGoal() + 0.5f;
                if (g > 9.5f) g = 6.5f;
                sp().edit().putFloat("goal", g).apply();
                load();
            }
        });
        TextView mx = chip("Max HR  " + maxHr() + " bpm");
        mx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askMaxHr(); }
        });
        row.addView(goal, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
        mp.leftMargin = dp(10);
        row.addView(mx, mp);
        set.addView(row);
        final SharedPreferences ctl = getSharedPreferences("labrador_ecg_control", MODE_PRIVATE);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        final TextView tail = chip("Near-live 500 Hz  " + (ctl.getBoolean("ecg_tail", true) ? "ON" : "OFF"));
        tail.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !ctl.getBoolean("ecg_tail", true);
                ctl.edit().putBoolean("ecg_tail", on).apply();
                tail.setText("Near-live 500 Hz  " + (on ? "ON" : "OFF"));
            }
        });
        final TextView restart = chip("Auto-restart at 10 min  " + (ctl.getBoolean("ecg_autorestart", true) ? "ON" : "OFF"));
        restart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !ctl.getBoolean("ecg_autorestart", true);
                ctl.edit().putBoolean("ecg_autorestart", on).apply();
                restart.setText("Auto-restart at 10 min  " + (on ? "ON" : "OFF"));
            }
        });
        row2.addView(tail, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rsp = new LinearLayout.LayoutParams(0, -2, 1f);
        rsp.leftMargin = dp(10);
        row2.addView(restart, rsp);
        set.addView(row2, lp(-1, -2, 10));
        TextView logger = chip("Day logger and pulls");
        logger.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(TodayActivity.this, DayLogActivity.class)); }
        });
        set.addView(logger, lp(-1, -2, 10));
        addCard(set);
        addFooter();
    }

    private List<Spo2.Window> nightSpo2(SleepNight.Night n) {
        android.database.sqlite.SQLiteDatabase db = DayLog.db(this);
        android.database.Cursor q = db.rawQuery("SELECT rec FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts",
                new String[] {String.valueOf(n.bedTs - 600L), String.valueOf(n.endTs + 600L)});
        List<byte[]> recs = new ArrayList<>();
        while (q.moveToNext()) recs.add(q.getBlob(0));
        q.close();
        return Spo2.windows(recs);
    }

    private void askMaxHr() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(maxHr()));
        new AlertDialog.Builder(this).setTitle("Maximum heart rate (bpm)")
                .setMessage("Used only for the effort estimate. 220 minus your age is a rough guess; use a measured value if you have one.")
                .setView(e)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int v = Integer.parseInt(e.getText().toString().trim());
                            if (v >= 120 && v <= 230) { sp().edit().putInt("maxhr", v).apply(); load(); }
                        } catch (NumberFormatException ignored) { }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void addFooter() {
        TextView foot = tv("Research instrumentation, not a medical device and not a diagnosis. Sleep, recovery, effort and SpO2 are estimates computed on this phone from the strap's own records.", 11, DIM, false);
        foot.setGravity(Gravity.CENTER);
        content.addView(foot, lp(-1, -2, 18));
    }

    // ------------------------------------------------------------------ small builders
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topDp);
        return p;
    }

    private GradientDrawable round(int color, int rDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setBackground(round(CARD, 20, LINE));
        TextView t = tv(title, 11, MUTED, true);
        t.setLetterSpacing(0.12f);
        c.addView(t, new LinearLayout.LayoutParams(-2, -2));
        return c;
    }

    private void addCard(LinearLayout c) { content.addView(c, lp(-1, -2, 14)); }

    private TextView chip(String s) {
        TextView t = tv(s, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackground(round(0xFF1B1E24, 14, LINE));
        return t;
    }

    private LinearLayout rowBig(String big, String rest, int color) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.BOTTOM);
        r.addView(tv(big, 34, color, true));
        TextView t = tv(rest, 13, MUTED, false);
        t.setPadding(dp(8), 0, 0, dp(6));
        r.addView(t);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(4);
        r.setLayoutParams(p);
        return r;
    }

    private LinearLayout statRow(String[] labels, String[] values) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setWeightSum(labels.length);
        for (int i = 0; i < labels.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView v = tv(values[i], 16, TEXT, true);
            TextView l = tv(labels[i], 9, MUTED, true);
            l.setLetterSpacing(0.1f);
            cell.addView(v);
            cell.addView(l);
            r.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return r;
    }

    private View ringCell(double frac, int color, String big, String label, String caption) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        RingView ring = new RingView(this, (float) frac, color, big);
        cell.addView(ring, new LinearLayout.LayoutParams(dp(104), dp(104)));
        TextView l = tv(label, 11, TEXT, true);
        l.setLetterSpacing(0.14f);
        cell.addView(l, lp(-2, -2, 8));
        TextView c = tv(caption, 10, MUTED, false);
        c.setGravity(Gravity.CENTER);
        cell.addView(c);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return cell;
    }

    // ------------------------------------------------------------------ canvas views
    private static Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    /** A thin ring with a rounded progress arc and one big number. */
    static final class RingView extends View {
        private final float frac;
        private final int color;
        private final String big;
        RingView(Context c, float frac, int color, String big) {
            super(c);
            this.frac = Math.max(0f, Math.min(1f, frac));
            this.color = color;
            this.big = big;
        }
        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float w = 9f * d;
            float s = Math.min(getWidth(), getHeight());
            RectF r = new RectF(w, w, s - w, s - w);
            cv.drawArc(r, 0, 360, false, stroke(LINE, w));
            if (frac > 0.003f) cv.drawArc(r, -90, 360f * frac, false, stroke(color, w));
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize((big.length() > 4 ? 22f : 28f) * d);
            cv.drawText(big, s / 2f, s / 2f + 10f * d, t);
        }
    }

    /** One night's band state as a coloured strip with the heart rate drawn over it. */
    static final class StripView extends View {
        private final SleepNight.Series s;
        StripView(Context c, SleepNight.Series s) { super(c); this.s = s; }
        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            float stripH = 18f * d;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < s.n; i++) {
                int st = s.state[i];
                if (st < 0) continue;
                p.setColor(st == 2 ? SLEEP : (st == 1 ? 0xFF4A5380 : (st == 3 ? TEMP : 0xFF3A3F4B)));
                float x0 = w * i / s.n, x1 = w * (i + 1) / s.n + 1f;
                cv.drawRect(x0, 0, x1, stripH, p);
            }
            // heart rate under the strip
            float lo = 1e9f, hi = -1e9f;
            for (int i = 0; i < s.n; i++) { float v = s.hr[i]; if (!Float.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); } }
            if (hi > lo) {
                Path path = new Path();
                boolean open = false;
                for (int i = 0; i < s.n; i++) {
                    float v = s.hr[i];
                    if (Float.isNaN(v)) { open = false; continue; }
                    float x = w * (i + 0.5f) / s.n, y = stripH + 6f * d + (h - stripH - 18f * d) * (1f - (v - lo) / (hi - lo));
                    if (!open) { path.moveTo(x, y); open = true; } else path.lineTo(x, y);
                }
                cv.drawPath(path, stroke(HRC, 1.6f * d));
            }
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(MUTED);
            t.setTextSize(10f * d);
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.UK);
            t.setTextAlign(Paint.Align.LEFT);
            cv.drawText(f.format(new Date(s.t0 * 1000L)), 0, h - 2f * d, t);
            t.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(f.format(new Date((s.t0 + s.n * 60L) * 1000L)), w, h - 2f * d, t);
        }
    }

    /** SpO2 windows over the night: one dot per window, the 94% line dashed. Scale 88-100. */
    static final class SpoView extends View {
        private final List<Spo2.Window> ws;
        SpoView(Context c, List<Spo2.Window> ws) { super(c); this.ws = ws; }
        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight() - 14f * d;
            long t0 = Long.MAX_VALUE, t1 = 0;
            for (Spo2.Window x : ws) { if (x.median < 0) continue; t0 = Math.min(t0, x.start); t1 = Math.max(t1, x.end); }
            if (t1 <= t0) return;
            final float lo = 88f, hi = 100f;
            Paint g = stroke(LINE, 1f * d);
            for (int v = 90; v <= 100; v += 5) { float y = h * (1f - (v - lo) / (hi - lo)); cv.drawLine(0, y, w, y, g); }
            Paint dash = stroke(0xFF3A3F4B, 1.2f * d);
            dash.setPathEffect(new android.graphics.DashPathEffect(new float[] {6f * d, 5f * d}, 0));
            float y94 = h * (1f - (94f - lo) / (hi - lo));
            cv.drawLine(0, y94, w, y94, dash);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(MUTED);
            t.setTextSize(10f * d);
            for (Spo2.Window x : ws) {
                if (x.median < 0 || x.valid < Spo2.MIN_VALID_SECONDS) continue;
                float cx = 8f * d + (w - 16f * d) * (x.start - t0) / (float) (t1 - t0);
                float y = h * (1f - (Math.max(lo, Math.min(hi, x.median)) - lo) / (hi - lo));
                dot.setColor(x.median < 90 ? RED : (x.median < 94 ? AMBER : SPO));
                cv.drawCircle(cx, y, 4.5f * d, dot);
            }
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.UK);
            t.setTextAlign(Paint.Align.LEFT);
            cv.drawText(f.format(new Date(t0 * 1000L)), 0, getHeight() - 1f * d, t);
            t.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(f.format(new Date(t1 * 1000L)), w, getHeight() - 1f * d, t);
            t.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("94", w, y94 - 2f * d, t);
        }
    }

    /** Zone minutes as one stacked bar (zones 1-5), empty when there are none. */
    static final class ZoneBar extends View {
        private final int[] z;
        ZoneBar(Context c, int[] z) { super(c); this.z = z; }
        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(LINE);
            cv.drawRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, p);
            int tot = 0;
            for (int i = 1; i < 6; i++) tot += z[i];
            if (tot == 0) return;
            int[] col = {0, 0xFF3E7BFF, 0xFF2FA8FF, 0xFF1FE07A, 0xFFFFC53D, 0xFFFF4D4F};
            float x = 0;
            for (int i = 1; i < 6; i++) {
                if (z[i] == 0) continue;
                float wi = w * z[i] / tot;
                p.setColor(col[i]);
                cv.drawRect(x, 0, x + wi, h, p);
                x += wi;
            }
        }
    }

    /** Heart rate over the last 24 hours (5-minute medians) with the night shaded. */
    static final class HrView extends View {
        private final TodayModel.Day d;
        HrView(Context c, TodayModel.Day d) { super(c); this.d = d; }
        @Override protected void onDraw(Canvas cv) {
            float dn = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight() - 16f * dn;
            long t0 = d.hrT[0] - 150L, t1 = d.hrT[d.hrT.length - 1] + 150L;
            if (t1 <= t0) return;
            float lo = 1e9f, hi = -1e9f;
            for (float v : d.hrV) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            lo = (float) Math.floor((lo - 3f) / 10f) * 10f;
            hi = (float) Math.ceil((hi + 3f) / 10f) * 10f;
            if (hi - lo < 30f) hi = lo + 30f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (d.night != null) {
                float a = w * (Math.max(d.night.bedTs, t0) - t0) / (float) (t1 - t0);
                float b = w * (Math.min(d.night.endTs, t1) - t0) / (float) (t1 - t0);
                if (b > a) { p.setColor(0x227F8CFF); p.setStyle(Paint.Style.FILL); cv.drawRect(a, 0, b, h, p); }
            }
            Paint g = stroke(LINE, 1f * dn);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(MUTED);
            t.setTextSize(10f * dn);
            t.setTextAlign(Paint.Align.RIGHT);
            for (float v = lo; v <= hi + 0.1f; v += 10f) {
                float y = h * (1f - (v - lo) / (hi - lo));
                cv.drawLine(0, y, w, y, g);
                cv.drawText(String.valueOf((int) v), w, y - 2f * dn, t);
            }
            Path path = new Path();
            boolean open = false;
            long prev = 0;
            for (int i = 0; i < d.hrT.length; i++) {
                float x = w * (d.hrT[i] - t0) / (float) (t1 - t0), y = h * (1f - (d.hrV[i] - lo) / (hi - lo));
                if (!open || d.hrT[i] - prev > 900L) path.moveTo(x, y); else path.lineTo(x, y);
                open = true;
                prev = d.hrT[i];
            }
            cv.drawPath(path, stroke(HRC, 2f * dn));
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.UK);
            t.setTextAlign(Paint.Align.LEFT);
            cv.drawText(f.format(new Date(t0 * 1000L)), 0, getHeight() - 2f * dn, t);
            t.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(f.format(new Date(t1 * 1000L)), w, getHeight() - 2f * dn, t);
        }
    }
}

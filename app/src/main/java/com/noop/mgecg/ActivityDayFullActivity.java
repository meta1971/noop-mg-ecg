package com.noop.mgecg;

// FILE VERSION 0.55.0 (14 Nov 2026): the Activity day graph full screen. Opened by the Expand button on the YOUR DAY card of the Activity page.
// TOP PANEL: heart rate per minute over the whole day (04:00 to 04:00), activity periods shaded by effort, raised-but-still stretches in violet, resting heart rate dashed.
// BOTTOM PANEL: steps per minute, coloured by what the minute was (light / moderate / hard, raised but still, rest).
// Pinch to zoom the time axis (20 minutes to the whole day), drag to scroll, fling, double tap to zoom in or back out, one tap for that minute's values and the period it sits in.
// Chips: Whole day / 12 h / 6 h / 2 h / 30 min, the previous and next day, Reset, Portrait (turns it), close. Landscape by default. Reads the same day data as the Activity page (ActivityStore.loadDay); nothing is written.
// The window arithmetic is ChartMath (shared with the full-screen stress graphs, unit-tested off the phone).

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class ActivityDayFullActivity extends Activity {
    static final String EX_DAY = "day";
    private static final int BG = 0xFF0D1015, INK = 0xFFE8ECF2, MUT = 0xFF8A93A0, TRACK = 0xFF161B22, GRID = 0x22FFFFFF, QUIET = 0xFF5D6B7E;

    static void open(Context c, long dayStart) {
        try { c.startActivity(new Intent(c, ActivityDayFullActivity.class).putExtra(EX_DAY, dayStart)); } catch (Throwable ignored) { }
    }

    private float dp;
    private FrameLayout host;
    private TextView title, info, status, nextChip;
    private DayChart chart;
    private long dayStart;
    private int generation;
    private boolean landscape = true;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        try { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
        long ds = getIntent().getLongExtra(EX_DAY, 0L);
        dayStart = ds > 0 ? ds : TodayModel.dayStartOf(System.currentTimeMillis() / 1000L);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (4 * dp));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        title = tv("Your day", 16, INK, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(chip("‹ Day", new View.OnClickListener() { @Override public void onClick(View v) { go(ActivityStore.shiftDay(dayStart, -1)); } }));
        nextChip = chip("Day ›", new View.OnClickListener() { @Override public void onClick(View v) {
            long t = ActivityStore.shiftDay(dayStart, 1);
            if (t <= TodayModel.dayStartOf(System.currentTimeMillis() / 1000L)) go(t);
        } });
        bar.addView(nextChip);
        bar.addView(chip("Reset", new View.OnClickListener() { @Override public void onClick(View v) { if (chart != null) chart.reset(); } }));
        final TextView orient = chip("Portrait", null);
        orient.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            try {
                landscape = !landscape;
                setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                orient.setText(landscape ? "Portrait" : "Landscape");
            } catch (Throwable ignored) { }
        } });
        bar.addView(orient);
        bar.addView(chip("✕", new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }));
        root.addView(bar, new LinearLayout.LayoutParams(-1, (int) (40 * dp)));

        host = new FrameLayout(this);
        status = tv("Reading the day...", 14, MUT, false);
        status.setGravity(Gravity.CENTER);
        host.addView(status, new FrameLayout.LayoutParams(-1, -1));
        root.addView(host, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER);
        final String[] pn = { "Whole day", "12 h", "6 h", "2 h", "30 min" };
        final double[] ph = { 0, 12, 6, 2, 0.5 };
        for (int i = 0; i < pn.length; i++) {
            final double hh = ph[i];
            presets.addView(chip(pn[i], new View.OnClickListener() { @Override public void onClick(View v) {
                if (chart == null) return;
                if (hh <= 0) chart.reset(); else chart.setHours(hh);
            } }));
        }
        root.addView(presets, new LinearLayout.LayoutParams(-1, (int) (34 * dp)));
        info = tv("", 11, MUT, false);
        info.setPadding(0, (int) (2 * dp), 0, 0);
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        hideBars();
        load();
    }

    private void go(long newDayStart) {
        dayStart = newDayStart;
        load();
    }

    private void load() {
        final int gen = ++generation;
        final Context app = getApplicationContext();
        final long ds = dayStart;
        title.setText("Your day  ·  " + new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date((ds + 6 * 3600L) * 1000L)));
        nextChip.setAlpha(ds >= TodayModel.dayStartOf(System.currentTimeMillis() / 1000L) ? 0.35f : 1f);
        chart = null;
        host.removeAllViews();
        status.setText("Reading the day...");
        host.addView(status, new FrameLayout.LayoutParams(-1, -1));
        info.setText("");
        Thread th = new Thread(new Runnable() { @Override public void run() {
            ActivityStore.DayData d = null;
            String err = null;
            try {
                ActivityStore.Settings s = ActivityStore.settings(app);
                d = ActivityStore.loadDay(app, ds, ActivityStore.params(app, s));
            } catch (Throwable e) { err = e.toString(); }
            final ActivityStore.DayData dd = d;
            final String ee = err;
            runOnUiThread(new Runnable() { @Override public void run() {
                if (isFinishing() || gen != generation) return;
                if (ee != null) { status.setText("Could not read the day: " + ee); return; }
                if (dd == null || !dd.hasData || dd.r == null || dd.r.epochs.isEmpty()) { status.setText("No per-second records for this day."); return; }
                chart = new DayChart(ActivityDayFullActivity.this, dd);
                chart.setOnChange(new Runnable() { @Override public void run() { if (chart != null) info.setText(chart.windowText()); } });
                host.removeAllViews();
                host.addView(chart, new FrameLayout.LayoutParams(-1, -1));
                info.setText(chart.windowText());
            } });
        } }, "activity-day-full");
        th.setDaemon(true);
        th.start();
    }

    @Override protected void onDestroy() { generation++; super.onDestroy(); }

    private TextView tv(String s, float sp, int col, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(col);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView chip(String s, View.OnClickListener l) {
        TextView t = tv(s, 13, INK, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }

    @SuppressWarnings("deprecation") private void hideBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable ignored) { }
    }

    /** Colour of an active minute by its effort level (same bands as the Activity page). */
    static int levelCol(int level) {
        int b = ChartMath.levelBand(level);
        return b == 3 ? ActivityActivity.HARD : b == 2 ? ActivityActivity.MODERATE : ActivityActivity.LIGHT;
    }

    // ------------------------------------------------------------------ the zoomable graph
    static final class DayChart extends View {
        private final ActivityStore.DayData d;
        private final int n;
        private final float[] hr;
        private final short[] steps;
        private final byte[] kind, level;
        private final float rest;
        private final List<int[]> stills = new ArrayList<int[]>();
        private final double minX, maxX, now;
        private double x0, x1, cursor = Double.NaN;
        private Runnable onChange;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private final ScaleGestureDetector sd;
        private final GestureDetector gd;
        private ValueAnimator fling;
        private float plotL, plotR;

        DayChart(Context c, ActivityStore.DayData d) {
            super(c);
            this.d = d;
            dp = c.getResources().getDisplayMetrics().density;
            n = Math.max(60, (int) ((d.dayEnd - d.dayStart) / 60));
            hr = new float[n];
            steps = new short[n];
            kind = new byte[n];
            level = new byte[n];
            java.util.Arrays.fill(hr, Float.NaN);
            rest = (float) d.r.restHr;
            for (ActivityDetector.Epoch e : d.r.epochs) {
                int m = (int) ((e.start - d.dayStart) / 60);
                if (m < 0 || m >= n) continue;
                kind[m] = (byte) e.kind;
                level[m] = (byte) e.level;
                steps[m] = (short) Math.max(0, Math.min(Short.MAX_VALUE, e.steps));
                if (!Double.isNaN(e.hr)) hr[m] = (float) e.hr;
            }
            int s0 = -1;
            for (int m = 0; m <= n; m++) {
                boolean st = m < n && kind[m] == ActivityDetector.ELEVATED_STILL;
                if (st && s0 < 0) s0 = m;
                if (!st && s0 >= 0) { if (m - s0 >= 3) stills.add(new int[] { s0, m }); s0 = -1; }
            }
            minX = d.dayStart;
            maxX = d.dayEnd > d.dayStart ? d.dayEnd : d.dayStart + 86400L;
            long nowS = System.currentTimeMillis() / 1000L;
            now = nowS > minX && nowS < maxX ? nowS : Double.NaN;
            x0 = minX;
            x1 = maxX;
            sd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(ScaleGestureDetector det) { cancelFling(); return true; }
                @Override public boolean onScale(ScaleGestureDetector det) {
                    double[] w = ChartMath.zoom(x0, x1, tAt(det.getFocusX()), det.getScaleFactor(), minX, maxX);
                    set(w[0], w[1]);
                    return true;
                }
            });
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (sd.isInProgress()) return false;
                    double dt = dx / Math.max(1f, plotR - plotL) * (x1 - x0);
                    double[] w = ChartMath.pan(x0, x1, dt, minX, maxX);
                    set(w[0], w[1]);
                    return true;
                }
                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) { fling(vx); return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    double f = tAt(e.getX());
                    if (x1 - x0 > 7200.0) { double[] w = ChartMath.zoom(x0, x1, f, (x1 - x0) / 7200.0, minX, maxX); set(w[0], w[1]); }
                    else reset();
                    return true;
                }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    double t = tAt(e.getX());
                    cursor = Double.isNaN(cursor) || Math.abs(cursor - t) > 30.0 ? t : Double.NaN;
                    invalidate();
                    return true;
                }
            });
        }

        void setOnChange(Runnable r) { onChange = r; }
        private void changed() { if (onChange != null) onChange.run(); }
        void reset() { cancelFling(); x0 = minX; x1 = maxX; cursor = Double.NaN; invalidate(); changed(); }
        void setHours(double h) {
            double mid = (x0 + x1) / 2.0;
            double[] w = ChartMath.zoom(x0, x1, mid, (x1 - x0) / (h * 3600.0), minX, maxX);
            set(w[0], w[1]);
        }
        private void set(double a, double b) { x0 = a; x1 = b; invalidate(); changed(); }
        private void cancelFling() { if (fling != null) { fling.cancel(); fling = null; } }
        private void fling(float vx) {
            cancelFling();
            final double total = -vx * 0.35 / Math.max(1f, plotR - plotL) * (x1 - x0), a0 = x0, b0 = x1;
            fling = ValueAnimator.ofFloat(0f, 1f);
            fling.setDuration(450);
            fling.setInterpolator(new DecelerateInterpolator());
            fling.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator an) {
                double[] w = ChartMath.pan(a0, b0, total * (Float) an.getAnimatedValue(), minX, maxX);
                set(w[0], w[1]);
            } });
            fling.start();
        }
        private double tAt(float x) { return x0 + (x - plotL) / Math.max(1f, plotR - plotL) * (x1 - x0); }
        private float x(double t) { return (float) (plotL + (t - x0) / (x1 - x0) * (plotR - plotL)); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            sd.onTouchEvent(e);
            if (!sd.isInProgress()) gd.onTouchEvent(e);
            return true;
        }

        String windowText() {
            TimeZone tz = TimeZone.getDefault();
            SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK);
            f.setTimeZone(tz);
            double span = x1 - x0;
            String len = span >= 3600 ? String.format(Locale.UK, "%.1f h", span / 3600.0) : Math.round(span / 60.0) + " min";
            return f.format(new Date((long) (x0 * 1000))) + "  to  " + f.format(new Date((long) (x1 * 1000))) + "  (" + len + ").  Pinch to zoom, drag to scroll, tap a minute for its values, double tap to zoom in.";
        }

        @Override protected void onDraw(Canvas cv) {
            try { draw2(cv); } catch (Throwable ignored) { }
        }

        private void draw2(Canvas cv) {
            float w = getWidth(), h = getHeight();
            plotL = 44 * dp; plotR = w - 8 * dp;
            float top1 = 6 * dp, axis = 20 * dp, gap = 14 * dp, avail = h - top1 - axis - gap;
            float bot1 = top1 + avail * 0.64f, top2 = bot1 + gap, bot2 = h - axis;
            TimeZone tz = TimeZone.getDefault();
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF161B22);
            cv.drawRoundRect(new RectF(plotL, top1, plotR, bot1), 6 * dp, 6 * dp, p);
            cv.drawRoundRect(new RectF(plotL, top2, plotR, bot2), 6 * dp, 6 * dp, p);
            int m0 = Math.max(0, (int) Math.floor((x0 - d.dayStart) / 60.0)), m1 = Math.min(n - 1, (int) Math.ceil((x1 - d.dayStart) / 60.0));

            // time grid and labels
            List<double[]> ticks = ChartMath.timeTicks(x0, x1, tz, 8);
            double step = ticks.size() > 1 ? ticks.get(1)[0] - ticks.get(0)[0] : 3600.0;
            p.setStrokeWidth(1f * dp); p.setTextSize(10 * dp); p.setTextAlign(Paint.Align.CENTER);
            for (double[] tk : ticks) {
                float xx = x(tk[0]);
                if (xx < plotL || xx > plotR) continue;
                p.setColor(tk[1] > 0 ? 0x44FFFFFF : GRID);
                cv.drawLine(xx, top1, xx, bot2, p);
                p.setColor(tk[1] > 0 ? INK : MUT);
                cv.drawText(ChartMath.tickLabel(tk[0], tk[1] > 0, step, tz), xx, h - 6 * dp, p);
            }

            // top panel: heart rate
            double lo = 1e9, hi = -1e9;
            for (int m = m0; m <= m1; m++) if (!Float.isNaN(hr[m])) { lo = Math.min(lo, hr[m]); hi = Math.max(hi, hr[m]); }
            if (hi < lo) {
                p.setColor(MUT); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(12 * dp);
                cv.drawText("no heart rate in this window", (plotL + plotR) / 2f, (top1 + bot1) / 2f, p);
            } else {
                if (rest > 0) { lo = Math.min(lo, rest); hi = Math.max(hi, rest); }
                lo = Math.floor(lo - 4); hi = Math.ceil(hi + 4);
                final double fl = lo, fh = hi;
                double[] yt = ChartMath.niceTicks(lo, hi, 4);
                p.setTextSize(10 * dp);
                for (double v : yt) {
                    float yy = (float) (bot1 - (bot1 - top1) * (v - fl) / (fh - fl));
                    p.setColor(GRID); cv.drawLine(plotL, yy, plotR, yy, p);
                    p.setColor(MUT); p.setTextAlign(Paint.Align.RIGHT); cv.drawText(String.valueOf((int) Math.round(v)), plotL - 5 * dp, yy + 3.5f * dp, p);
                }
                p.setStyle(Paint.Style.FILL);
                for (int[] s : stills) {
                    float a = Math.max(plotL, x(d.dayStart + s[0] * 60.0)), c2 = Math.min(plotR, x(d.dayStart + s[1] * 60.0));
                    if (c2 > a) { p.setColor((ActivityActivity.STILL & 0x00FFFFFF) | 0x33000000); cv.drawRect(a, top1, c2, bot1, p); }
                }
                for (ActivityDetector.Bout bo : d.r.bouts) {
                    float a = Math.max(plotL, x(bo.start)), c2 = Math.min(plotR, x(bo.end));
                    if (c2 <= a) continue;
                    int col = bo.avgPct >= 0.45 ? ActivityActivity.HARD : bo.avgPct >= 0.25 ? ActivityActivity.MODERATE : ActivityActivity.LIGHT;
                    p.setColor((col & 0x00FFFFFF) | 0x2E000000);
                    cv.drawRect(a, top1, Math.max(c2, a + 2 * dp), bot1, p);
                }
                if (rest > 0) {
                    float ry = (float) (bot1 - (bot1 - top1) * (rest - fl) / (fh - fl));
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f * dp); p.setColor(0xFF6B7686);
                    p.setPathEffect(new DashPathEffect(new float[] { 6 * dp, 5 * dp }, 0));
                    cv.drawLine(plotL, ry, plotR, ry, p);
                    p.setPathEffect(null);
                }
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f * dp); p.setColor(ActivityActivity.HRC); p.setStrokeJoin(Paint.Join.ROUND);
                Path pa = new Path();
                boolean pen = false; int last = -10;
                for (int m = m0; m <= m1; m++) {
                    if (Float.isNaN(hr[m])) continue;
                    float xx = x(d.dayStart + (m + 0.5) * 60.0), yy = (float) (bot1 - (bot1 - top1) * (hr[m] - fl) / (fh - fl));
                    if (!pen || m - last > 3) pa.moveTo(xx, yy); else pa.lineTo(xx, yy);
                    pen = true; last = m;
                }
                cv.drawPath(pa, p);
                if (x1 - x0 <= 5400.0) {
                    p.setStyle(Paint.Style.FILL); p.setColor(ActivityActivity.HRC);
                    for (int m = m0; m <= m1; m++) if (!Float.isNaN(hr[m])) cv.drawCircle(x(d.dayStart + (m + 0.5) * 60.0), (float) (bot1 - (bot1 - top1) * (hr[m] - fl) / (fh - fl)), 2.2f * dp, p);
                }
            }

            // bottom panel: steps per minute
            int mx = 30;
            for (int m = m0; m <= m1; m++) mx = Math.max(mx, steps[m]);
            double[] st = ChartMath.niceTicks(0, mx, 3);
            int top = (int) Math.ceil(Math.max(mx, st[st.length - 1]));
            p.setTextSize(10 * dp); p.setStrokeWidth(1f * dp);
            for (double v : st) {
                float yy = (float) (bot2 - (bot2 - top2) * v / top);
                p.setStyle(Paint.Style.STROKE); p.setColor(GRID); cv.drawLine(plotL, yy, plotR, yy, p);
                p.setStyle(Paint.Style.FILL); p.setColor(MUT); p.setTextAlign(Paint.Align.RIGHT); cv.drawText(String.valueOf((int) Math.round(v)), plotL - 5 * dp, yy + 3.5f * dp, p);
            }
            p.setStyle(Paint.Style.FILL);
            float bw = Math.max(1f, (plotR - plotL) / (float) ((x1 - x0) / 60.0) - 0.5f);
            for (int m = m0; m <= m1; m++) {
                if (steps[m] <= 0) continue;
                float xx = x(d.dayStart + m * 60.0);
                if (xx + bw < plotL || xx > plotR) continue;
                int col = kind[m] == ActivityDetector.ACTIVE ? levelCol(level[m]) : kind[m] == ActivityDetector.ELEVATED_STILL ? ActivityActivity.STILL : QUIET;
                p.setColor(col);
                float bh = Math.max(1f, (bot2 - top2) * steps[m] / (float) top);
                cv.drawRect(Math.max(plotL, xx), bot2 - bh, Math.min(plotR, xx + bw), bot2, p);
            }
            p.setColor(MUT); p.setTextSize(9.5f * dp); p.setTextAlign(Paint.Align.LEFT);
            cv.drawText("steps a minute", plotL + 6 * dp, top2 + 12 * dp, p);

            if (!Double.isNaN(now) && now > x0 && now < x1) {
                p.setStyle(Paint.Style.STROKE); p.setColor(ActivityActivity.EFFORT); p.setStrokeWidth(1.5f * dp);
                float xx = x(now); cv.drawLine(xx, top1, xx, bot2, p);
            }
            if (!Double.isNaN(cursor) && cursor >= x0 && cursor <= x1) {
                p.setStyle(Paint.Style.STROKE); p.setColor(0xFFFFFFFF); p.setStrokeWidth(1.2f * dp);
                float xx = x(cursor); cv.drawLine(xx, top1, xx, bot2, p);
                readout(cv, xx, top1, tz);
            }
            p.setStyle(Paint.Style.FILL);
        }

        /** The words for one minute: time, heart rate, steps, what the minute was and the period it sits in. */
        List<String> minuteLines(double t, TimeZone tz) {
            SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK);
            f.setTimeZone(tz);
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.UK);
            hm.setTimeZone(tz);
            List<String> ln = new ArrayList<String>();
            int m = ChartMath.minuteIndex(d.dayStart, t, n);
            if (m < 0) { ln.add(f.format(new Date((long) (t * 1000)))); ln.add("outside this day"); return ln; }
            ln.add(f.format(new Date((d.dayStart + m * 60L) * 1000L)) + " (1 min)");
            ln.add(Float.isNaN(hr[m]) ? "heart rate: none" : "heart rate " + Math.round(hr[m]) + " bpm");
            int k = kind[m];
            String what = k == ActivityDetector.ACTIVE ? "active, " + (level[m] >= ActivityDetector.L_HARD ? "hard" : level[m] == ActivityDetector.L_MODERATE ? "moderate" : "light")
                    : k == ActivityDetector.ELEVATED_STILL ? "raised heart rate, wrist still" : k == ActivityDetector.REST ? "at rest" : "no data";
            ln.add(steps[m] + " steps, " + what);
            for (ActivityDetector.Bout bo : d.r.bouts) {
                if (t >= bo.start && t < bo.end) {
                    ln.add("period " + hm.format(new Date(bo.start * 1000L)) + " to " + hm.format(new Date(bo.end * 1000L)) + ", " + bo.durationMin + " min"
                            + (bo.label == null || bo.label.isEmpty() ? "" : "  " + bo.label));
                    break;
                }
            }
            return ln;
        }

        private void readout(Canvas cv, float xx, float top, TimeZone tz) {
            List<String> ln = minuteLines(cursor, tz);
            p.setStyle(Paint.Style.FILL); p.setTextSize(11 * dp); p.setTextAlign(Paint.Align.LEFT);
            float tw = 0;
            for (String s : ln) tw = Math.max(tw, p.measureText(s));
            float pad = 6 * dp, lh = 14 * dp, bw = tw + 2 * pad, bh = ln.size() * lh + 2 * pad;
            float bx = xx + 8 * dp;
            if (bx + bw > plotR) bx = xx - 8 * dp - bw;
            if (bx < plotL) bx = plotL;
            float by = top + 8 * dp;
            p.setColor(0xEE101419);
            cv.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), 6 * dp, 6 * dp, p);
            p.setColor(INK);
            for (int i = 0; i < ln.size(); i++) cv.drawText(ln.get(i), bx + pad, by + pad + lh * (i + 0.8f), p);
        }
    }
}

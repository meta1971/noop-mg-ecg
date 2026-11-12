package com.noop.mgecg;

// FILE VERSION 0.53.8 (12 Nov 2026): the stress graphs full screen (decode log 181). Opened by a tap on a graph of the stress panel (the day in bars, the heart rate against what is expected, the time by level).
// TIME: the heart rate against the expected heart rate (amber between them is what the score counts) over the score bars of the same 5-minute bins, 14 days; pinch to zoom the time axis (20 minutes to 14 days), drag to scroll,
// fling, double tap to zoom in or back out, one tap for the values at that time, chips for 1 h / 6 h / 24 h / 3 d / 14 d. DAYS: minutes at each level for every day, a tap for a day's numbers. Landscape by default; the
// Portrait button turns it. Nothing is written; the bins are read from the day log as the Rest page reads them.

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
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

public class StressFullActivity extends Activity {
    static final String EX_MODE = "mode", EX_HOURS = "hours";
    static final int MODE_TIME = 0, MODE_DAYS = 1;
    private static final int BG = 0xFF0D1015, INK = 0xFFE8ECF2, MUT = 0xFF8A93A0, TRACK = 0xFF161B22, GRID = 0x22FFFFFF, BLUE = 0xFF2FA8F0, AMBER = 0xFFF5B942, GREEN = 0xFF4ADE80, RED = 0xFFF2685A, VIOLET = 0xFF8E93E8;

    static void open(Context c, int mode, int hours) { try { c.startActivity(new Intent(c, StressFullActivity.class).putExtra(EX_MODE, mode).putExtra(EX_HOURS, hours)); } catch (Throwable ignored) { } }

    private float dp;
    private FrameLayout chartHost; private TextView title, info, status; private TimeView timeView; private DaysView daysView; private TextView modeBtn, orientBtn; private LinearLayout presets;
    private StressModel.Result res; private int mode, hours; private boolean landscape = true;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); dp = getResources().getDisplayMetrics().density;
        try { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
        mode = getIntent().getIntExtra(EX_MODE, MODE_TIME); hours = getIntent().getIntExtra(EX_HOURS, 24);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (4 * dp));
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        title = tv("Stress", 16, INK, true); bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        modeBtn = chip("Days", new View.OnClickListener() { @Override public void onClick(View v) { setMode(mode == MODE_TIME ? MODE_DAYS : MODE_TIME); } }); bar.addView(modeBtn);
        bar.addView(chip("Reset", new View.OnClickListener() { @Override public void onClick(View v) { if (timeView != null) timeView.reset(); } }));
        orientBtn = chip("Portrait", new View.OnClickListener() { @Override public void onClick(View v) { turn(); } }); bar.addView(orientBtn);
        bar.addView(chip("\u2715", new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }));
        root.addView(bar, new LinearLayout.LayoutParams(-1, (int) (40 * dp)));
        chartHost = new FrameLayout(this); status = tv("Reading the last 14 days...", 14, MUT, false); status.setGravity(Gravity.CENTER); chartHost.addView(status, new FrameLayout.LayoutParams(-1, -1));
        root.addView(chartHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        presets = new LinearLayout(this); presets.setOrientation(LinearLayout.HORIZONTAL); presets.setGravity(Gravity.CENTER);
        final String[] pn = { "1 h", "6 h", "24 h", "3 days", "14 days" }; final double[] ph = { 1, 6, 24, 72, 336 };
        for (int i = 0; i < pn.length; i++) { final double hh = ph[i]; presets.addView(chip(pn[i], new View.OnClickListener() { @Override public void onClick(View v) { if (timeView != null) timeView.setHours(hh); } })); }
        root.addView(presets, new LinearLayout.LayoutParams(-1, (int) (34 * dp)));
        info = tv("", 11, MUT, false); info.setPadding(0, (int) (2 * dp), 0, 0); root.addView(info, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root); hideBars();
        Thread th = new Thread(new Runnable() { @Override public void run() {
            try {
                android.database.sqlite.SQLiteDatabase db = DayLog.db(StressFullActivity.this); long newest = 0; android.database.Cursor m = db.rawQuery("SELECT MAX(ts) FROM r18", null);
                try { if (m.moveToFirst() && !m.isNull(0)) newest = m.getLong(0); } finally { m.close(); }
                if (newest <= 0) newest = System.currentTimeMillis() / 1000L;
                final StressModel.Result r = StressModel.load(db, newest, DayLog.nightStats(StressFullActivity.this));
                runOnUiThread(new Runnable() { @Override public void run() { show(r); } });
            } catch (final Throwable e) { runOnUiThread(new Runnable() { @Override public void run() { status.setText("Could not read the day log: " + e); } }); }
        } });
        th.setDaemon(true); th.start();
    }

    private void show(StressModel.Result r) {
        res = r; if (r == null || r.all.size() < 6) { status.setText("Not enough heart-rate time with the strap on yet."); return; }
        long now = r.nowT > 0 ? r.nowT : r.all.get(r.all.size() - 1).t + StressModel.BIN_S;
        timeView = new TimeView(this, r.all, now, hours); daysView = new DaysView(this, r.all);
        timeView.setOnChange(new Runnable() { @Override public void run() { if (mode == MODE_TIME) info.setText(timeView.windowText()); } });
        setMode(mode);
    }

    private void setMode(int m) {
        mode = m; if (res == null) return; chartHost.removeAllViews();
        if (m == MODE_TIME) { chartHost.addView(timeView, new FrameLayout.LayoutParams(-1, -1)); title.setText("Stress through time"); modeBtn.setText("Days"); presets.setVisibility(View.VISIBLE); info.setText(timeView.windowText()); }
        else { chartHost.addView(daysView, new FrameLayout.LayoutParams(-1, -1)); title.setText("Stress by day"); modeBtn.setText("Time"); presets.setVisibility(View.GONE); info.setText("Minutes at each level per day (scored time). Tap a day for its numbers."); }
    }

    private void turn() {
        try { landscape = !landscape; setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); orientBtn.setText(landscape ? "Portrait" : "Landscape"); } catch (Throwable ignored) { }
    }

    private TextView tv(String s, float sp, int col, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(col); if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD); return t; }
    private TextView chip(String s, View.OnClickListener l) { TextView t = tv(s, 13, INK, true); t.setGravity(Gravity.CENTER); t.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp)); t.setOnClickListener(l); return t; }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    @SuppressWarnings("deprecation") private void hideBars() { try { getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION); } catch (Throwable ignored) { } }

    static int levelColor(int s) { return s < 20 ? GREEN : s < 50 ? AMBER : RED; }

    // ------------------------------------------------------------------ the zoomable graph
    static final class TimeView extends View {
        private final List<StressModel.Bin> bins; private final double minX, maxX, now; private final int defHours;
        private double x0, x1, cursor = Double.NaN; private Runnable onChange; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final float dp;
        private final ScaleGestureDetector sd; private final GestureDetector gd; private ValueAnimator fling; private float plotL, plotR;
        TimeView(Context c, List<StressModel.Bin> bins, long now, int hours) {
            super(c); this.bins = bins; this.now = now; this.defHours = hours; dp = c.getResources().getDisplayMetrics().density;
            minX = bins.get(0).t; maxX = Math.max(now, bins.get(bins.size() - 1).t + StressModel.BIN_S) + 600.0; double[] w = ChartMath.ending(maxX, hours, minX, maxX); x0 = w[0]; x1 = w[1];
            sd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(ScaleGestureDetector d) { cancelFling(); return true; }
                @Override public boolean onScale(ScaleGestureDetector d) { double[] w2 = ChartMath.zoom(x0, x1, tAt(d.getFocusX()), d.getScaleFactor(), minX, maxX); set(w2[0], w2[1]); return true; }
            });
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) { if (sd.isInProgress()) return false; double dt = dx / Math.max(1f, plotR - plotL) * (x1 - x0); double[] w2 = ChartMath.pan(x0, x1, dt, minX, maxX); set(w2[0], w2[1]); return true; }
                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) { fling(vx); return true; }
                @Override public boolean onDoubleTap(MotionEvent e) { double f = tAt(e.getX()); if (x1 - x0 > 7200.0) { double[] w2 = ChartMath.zoom(x0, x1, f, (x1 - x0) / 10800.0, minX, maxX); set(w2[0], w2[1]); } else reset(); return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { cursor = tAt(e.getX()); invalidate(); changed(); return true; }
            });
        }
        void setOnChange(Runnable r) { onChange = r; }
        private void changed() { if (onChange != null) onChange.run(); }
        void reset() { cancelFling(); double[] w = ChartMath.ending(maxX, defHours, minX, maxX); x0 = w[0]; x1 = w[1]; cursor = Double.NaN; invalidate(); changed(); }
        void setHours(double h) { double mid = (x0 + x1) / 2.0; double[] w = ChartMath.zoom(x0, x1, mid, (x1 - x0) / (h * 3600.0), minX, maxX); set(w[0], w[1]); }
        private void set(double a, double b) { x0 = a; x1 = b; invalidate(); changed(); }
        private void cancelFling() { if (fling != null) { fling.cancel(); fling = null; } }
        private void fling(float vx) {
            cancelFling(); final double total = -vx * 0.35 / Math.max(1f, plotR - plotL) * (x1 - x0), a0 = x0, b0 = x1; fling = ValueAnimator.ofFloat(0f, 1f); fling.setDuration(450); fling.setInterpolator(new DecelerateInterpolator());
            fling.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator an) { double[] w = ChartMath.pan(a0, b0, total * (Float) an.getAnimatedValue(), minX, maxX); set(w[0], w[1]); } }); fling.start();
        }
        private double tAt(float x) { return x0 + (x - plotL) / Math.max(1f, plotR - plotL) * (x1 - x0); }
        @Override public boolean onTouchEvent(MotionEvent e) { sd.onTouchEvent(e); if (!sd.isInProgress()) gd.onTouchEvent(e); return true; }

        String windowText() {
            TimeZone tz = TimeZone.getDefault(); SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK); f.setTimeZone(tz); double span = x1 - x0;
            String len = span >= 2 * 86400 ? String.format(Locale.UK, "%.1f days", span / 86400.0) : span >= 3600 ? String.format(Locale.UK, "%.1f h", span / 3600.0) : Math.round(span / 60.0) + " min";
            return f.format(new Date((long) (x0 * 1000))) + "  to  " + f.format(new Date((long) (x1 * 1000))) + "  (" + len + ").  Pinch to zoom, drag to scroll, tap for the values, double tap to zoom in.";
        }

        private float x(double t) { return (float) (plotL + (t - x0) / (x1 - x0) * (plotR - plotL)); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(); plotL = 44 * dp; plotR = w - 8 * dp; float top1 = 6 * dp, axis = 20 * dp, gap = 14 * dp, avail = h - top1 - axis - gap; float bot1 = top1 + avail * 0.64f, top2 = bot1 + gap, bot2 = h - axis;
            TimeZone tz = TimeZone.getDefault(); p.setStyle(Paint.Style.FILL); p.setColor(TRACK); cv.drawRoundRect(new RectF(plotL, top1, plotR, bot1), 6 * dp, 6 * dp, p); cv.drawRoundRect(new RectF(plotL, top2, plotR, bot2), 6 * dp, 6 * dp, p);
            int i0 = ChartMath.firstBin(bins, x0), i1 = i0; while (i1 < bins.size() && bins.get(i1).t <= x1) i1++;
            double lo = 1e9, hi = -1e9; for (int i = i0; i < i1; i++) { StressModel.Bin b = bins.get(i); if (!Float.isNaN(b.hr)) { lo = Math.min(lo, b.hr); hi = Math.max(hi, b.hr); } if (!Float.isNaN(b.exp)) { lo = Math.min(lo, b.exp); hi = Math.max(hi, b.exp); } }
            // time grid and labels
            List<double[]> ticks = ChartMath.timeTicks(x0, x1, tz, 8); double step = ticks.size() > 1 ? ticks.get(1)[0] - ticks.get(0)[0] : 3600.0;
            p.setStrokeWidth(1f * dp); p.setTextSize(10 * dp); p.setTextAlign(Paint.Align.CENTER);
            for (double[] tk : ticks) { float xx = x(tk[0]); if (xx < plotL || xx > plotR) continue; p.setColor(tk[1] > 0 ? 0x44FFFFFF : GRID); cv.drawLine(xx, top1, xx, bot2, p); p.setColor(tk[1] > 0 ? INK : MUT); cv.drawText(ChartMath.tickLabel(tk[0], tk[1] > 0, step, tz), xx, h - 5 * dp, p); }
            // top panel: heart rate against expected
            p.setTextAlign(Paint.Align.RIGHT);
            if (hi < lo) { p.setColor(MUT); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(12 * dp); cv.drawText("no heart rate in this window", (plotL + plotR) / 2f, (top1 + bot1) / 2f, p); }
            else {
                lo = Math.floor(lo - 3); hi = Math.ceil(hi + 3); final double fl = lo, fh = hi; double[] yt = ChartMath.niceTicks(lo, hi, 4);
                p.setTextSize(10 * dp); for (double v : yt) { float yy = (float) (bot1 - (bot1 - top1) * (v - fl) / (fh - fl)); p.setColor(GRID); cv.drawLine(plotL, yy, plotR, yy, p); p.setColor(MUT); p.setTextAlign(Paint.Align.RIGHT); cv.drawText(String.valueOf((int) v), plotL - 4 * dp, yy + 3 * dp, p); }
                p.setStyle(Paint.Style.FILL); p.setColor(AMBER); p.setAlpha(110);
                for (int i = i0; i < i1; i++) { StressModel.Bin b = bins.get(i); if (Float.isNaN(b.exp) || Float.isNaN(b.hr) || b.hr <= b.exp) continue; float a = Math.max(plotL, x(b.t)), c2 = Math.min(plotR, x(b.t + StressModel.BIN_S)); if (c2 <= a) continue; cv.drawRect(a, (float) (bot1 - (bot1 - top1) * (b.hr - fl) / (fh - fl)), c2, (float) (bot1 - (bot1 - top1) * (b.exp - fl) / (fh - fl)), p); }
                p.setAlpha(255); p.setColor(VIOLET); p.setAlpha(150); for (int i = i0; i < i1; i++) { StressModel.Bin b = bins.get(i); if (!b.waking) continue; float a = Math.max(plotL, x(b.t)), c2 = Math.min(plotR, x(b.t + StressModel.BIN_S)); if (c2 > a) cv.drawRect(a, top1, c2, top1 + 4 * dp, p); } p.setAlpha(255);
                line(cv, i0, i1, 1, fl, fh, top1, bot1, 0xFFAAB2BD, 1.5f); line(cv, i0, i1, 0, fl, fh, top1, bot1, BLUE, 2.2f);
                if (i1 - i0 <= 80) { p.setStyle(Paint.Style.FILL); p.setColor(BLUE); for (int i = i0; i < i1; i++) { StressModel.Bin b = bins.get(i); if (!Float.isNaN(b.hr)) cv.drawCircle(x(b.t + StressModel.BIN_S / 2.0), (float) (bot1 - (bot1 - top1) * (b.hr - fl) / (fh - fl)), 2.6f * dp, p); } }
            }
            // bottom panel: score bars
            p.setTextSize(10 * dp); for (int v : new int[] { 20, 50, 100 }) { float yy = bot2 - (bot2 - top2) * v / 100f; p.setColor(GRID); cv.drawLine(plotL, yy, plotR, yy, p); p.setColor(MUT); p.setTextAlign(Paint.Align.RIGHT); cv.drawText(String.valueOf(v), plotL - 4 * dp, yy + 3 * dp, p); }
            p.setStyle(Paint.Style.FILL); float bw = Math.max(1f, (plotR - plotL) / (float) ((x1 - x0) / StressModel.BIN_S) - 0.6f);
            for (int i = i0; i < i1; i++) { StressModel.Bin b = bins.get(i); if (b.score < 0) continue; float xx = x(b.t); if (xx + bw < plotL || xx > plotR) continue; p.setColor(levelColor(b.score)); p.setAlpha(b.band == 0 ? 255 : 150); float bh = Math.max(1.5f * dp, (bot2 - top2) * b.score / 100f); cv.drawRect(Math.max(plotL, xx), bot2 - bh, Math.min(plotR, xx + bw), bot2, p); }
            p.setAlpha(255);
            if (now > x0 && now < x1) { p.setColor(BLUE); p.setStrokeWidth(1.5f * dp); float xx = x(now); cv.drawLine(xx, top1, xx, bot2, p); }
            if (!Double.isNaN(cursor) && cursor >= x0 && cursor <= x1) { p.setColor(0xFFFFFFFF); p.setStrokeWidth(1.2f * dp); float xx = x(cursor); cv.drawLine(xx, top1, xx, bot2, p); readout(cv, xx, top1, tz); }
        }

        private void line(Canvas cv, int i0, int i1, int which, double lo, double hi, float top, float bot, int col, float wdp) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(wdp * dp); p.setColor(col); p.setStrokeJoin(Paint.Join.ROUND); Path pa = new Path(); boolean pen = false; double lastT = -1e18;
            for (int i = i0; i < i1; i++) {
                StressModel.Bin b = bins.get(i); float v = which == 0 ? b.hr : b.exp; if (Float.isNaN(v)) { pen = false; continue; }
                if (b.t - lastT > 3 * StressModel.BIN_S) pen = false; lastT = b.t; float xx = x(b.t + StressModel.BIN_S / 2.0), yy = (float) (bot - (bot - top) * (v - lo) / (hi - lo));
                if (!pen) { pa.moveTo(xx, yy); pen = true; } else pa.lineTo(xx, yy);
            }
            cv.drawPath(pa, p); p.setStyle(Paint.Style.FILL);
        }

        private void readout(Canvas cv, float xx, float top, TimeZone tz) {
            int k = ChartMath.binAt(bins, cursor); SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK); f.setTimeZone(tz); List<String> ln = new ArrayList<String>();
            if (k < 0) { ln.add(f.format(new Date((long) (cursor * 1000)))); ln.add("no data here"); }
            else {
                StressModel.Bin b = bins.get(k); ln.add(f.format(new Date(b.t * 1000L)) + " (5 min)");
                ln.add(Float.isNaN(b.hr) ? "heart rate: none" : "heart rate " + Math.round(b.hr) + " bpm" + (Float.isNaN(b.exp) ? "" : ",  expected " + Math.round(b.exp)));
                ln.add(b.score >= 0 ? "stress " + b.score + " (" + StressModel.levelOf(b.score) + ")" : !b.awake ? "asleep: not scored" : b.waking ? "first hour after waking: not scored" : "not scored");
                ln.add(String.format(Locale.UK, "steps %.0f a minute (%s)", b.spm, StressModel.BAND[Math.max(0, Math.min(4, b.band))]));
            }
            p.setTextSize(11 * dp); p.setTextAlign(Paint.Align.LEFT); float tw = 0; for (String s : ln) tw = Math.max(tw, p.measureText(s)); float pad = 6 * dp, lh = 14 * dp, bw = tw + 2 * pad, bh = ln.size() * lh + 2 * pad;
            float bx = xx + 8 * dp; if (bx + bw > plotR) bx = xx - 8 * dp - bw; if (bx < plotL) bx = plotL; float by = top + 8 * dp;
            p.setStyle(Paint.Style.FILL); p.setColor(0xEE101419); cv.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), 6 * dp, 6 * dp, p); p.setColor(INK);
            for (int i = 0; i < ln.size(); i++) cv.drawText(ln.get(i), bx + pad, by + pad + lh * (i + 0.8f), p);
        }
    }

    // ------------------------------------------------------------------ the days
    static final class DaysView extends View {
        private final List<ChartMath.Day> days; private int sel = -1; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final float dp; private float left, right, top, bottom;
        DaysView(Context c, List<StressModel.Bin> bins) { super(c); days = ChartMath.days(bins, TimeZone.getDefault()); dp = c.getResources().getDisplayMetrics().density; }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP && !days.isEmpty()) { float cw = (right - left) / days.size(); int k = (int) ((e.getX() - left) / cw); sel = k >= 0 && k < days.size() && k != sel ? k : -1; invalidate(); } return true;
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(); left = 44 * dp; right = w - 8 * dp; top = 10 * dp; bottom = h - 24 * dp; p.setStyle(Paint.Style.FILL); p.setColor(TRACK); cv.drawRoundRect(new RectF(left, top, right, bottom), 6 * dp, 6 * dp, p);
            if (days.isEmpty()) { p.setColor(MUT); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(13 * dp); cv.drawText("no scored time yet", w / 2f, h / 2f, p); return; }
            int mx = 60; for (ChartMath.Day d : days) mx = Math.max(mx, d.low + d.med + d.high); double[] yt = ChartMath.niceTicks(0, mx, 4); mx = (int) Math.ceil(Math.max(mx, yt[yt.length - 1]));
            p.setTextSize(10 * dp); for (double v : yt) { float yy = (float) (bottom - (bottom - top) * v / mx); p.setColor(GRID); cv.drawLine(left, yy, right, yy, p); p.setColor(MUT); p.setTextAlign(Paint.Align.RIGHT); cv.drawText(v >= 60 ? HealthModelShort.h(v) : (int) v + "m", left - 4 * dp, yy + 3 * dp, p); }
            float cw = (right - left) / days.size(), bw = Math.min(cw * 0.7f, 46 * dp); SimpleDateFormat f = new SimpleDateFormat("EEE d", Locale.UK);
            for (int i = 0; i < days.size(); i++) {
                ChartMath.Day d = days.get(i); float cx = left + cw * (i + 0.5f), y = bottom; int[] v = { d.low, d.med, d.high }; int[] col = { GREEN, AMBER, RED };
                for (int k = 0; k < 3; k++) { float bh = (bottom - top) * v[k] / mx; p.setColor(col[k]); p.setAlpha(i == sel || sel < 0 ? 255 : 120); cv.drawRect(cx - bw / 2f, y - bh, cx + bw / 2f, y, p); y -= bh; }
                p.setAlpha(255); p.setColor(i == sel ? INK : MUT); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(10 * dp); cv.drawText(f.format(new Date(d.day0 * 1000L + 3600000L)), cx, h - 8 * dp, p);
                p.setColor(INK); cv.drawText(String.valueOf(Math.round(d.mean)), cx, y - 4 * dp, p);
            }
            if (sel >= 0) {
                ChartMath.Day d = days.get(sel); SimpleDateFormat g = new SimpleDateFormat("EEE d MMM", Locale.UK); String[] ln = { g.format(new Date(d.day0 * 1000L + 3600000L)) + ": mean stress " + Math.round(d.mean), "low " + HealthModelShort.h(d.low) + ", medium " + HealthModelShort.h(d.med) + ", high " + HealthModelShort.h(d.high), "scored " + HealthModelShort.h(d.low + d.med + d.high) + ", of it moving " + HealthModelShort.h(d.moving) };
                p.setTextSize(11 * dp); p.setTextAlign(Paint.Align.LEFT); float tw = 0; for (String s : ln) tw = Math.max(tw, p.measureText(s)); float pad = 6 * dp, lh = 14 * dp; float bx = left + 8 * dp, by = top + 8 * dp;
                p.setColor(0xEE101419); cv.drawRoundRect(new RectF(bx, by, bx + tw + 2 * pad, by + ln.length * lh + 2 * pad), 6 * dp, 6 * dp, p); p.setColor(INK); for (int i = 0; i < ln.length; i++) cv.drawText(ln[i], bx + pad, by + pad + lh * (i + 0.8f), p);
            }
        }
    }

    static final class HealthModelShort { static String h(double min) { int m = (int) Math.round(min); return m >= 60 ? (m / 60) + " h " + (m % 60 == 0 ? "" : (m % 60) + " m") : m + " m"; } }
}

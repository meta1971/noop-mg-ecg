package com.noop.mgecg;

// FILE VERSION 0.14.0 (26 Oct): the full-screen heart-rate graph with pinch zoom, drag, double-tap, fling and Expand. Research only.

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.Gravity;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static com.noop.mgecg.HealthViews.*;

/**
 * Heart rate over 1 hour, 6 hours, 24 hours, 3 days or 7 days, from the strap's per-second records (pulled history) and, where the
 * pulls have not yet caught up, the live notifications kept by {@link LiveHr}. Shows the spread inside every slice (lowest to highest),
 * the mean line, zones, sleep, ECG readings, the lowest five minutes, and a touch read-out. Estimates and zones are not diagnoses.
 */
final class HeartGraph {
    private HeartGraph() {}

    static final long[] RANGE_SEC = {3600L, 6L * 3600L, 24L * 3600L, 3L * 86400L, 7L * 86400L};
    static final String[] RANGE_LABEL = {"1 h", "6 h", "24 h", "3 d", "7 d"};
    static final int[] ZONE_COL = {0xFF6A73F2, 0xFF4CB4FF, 0xFF2EE0B8, 0xFFF2B35B, 0xFFFF6B6B};
    static final String[] ZONE_NAME = {"Resting", "Easy", "Steady", "Hard", "All out"};

    /** One slice of the window: mean, lowest and highest per bin, plus how much of it was asleep and how much came live. */
    static final class Data {
        long t0, t1;
        int nb;
        float[] mean, min, max, sleep, live;
        int[] cnt;
        int total;
        float dmin = Float.NaN, dmax = Float.NaN, davg = Float.NaN, low5 = Float.NaN;
        long low5T;
        float asleepH;
        int liveSecs;
    }

    interface Readout { void show(String s); }

    // ------------------------------------------------------------------ data
    static Data load(Context c, long t0, long t1, int nb) {
        Data d = new Data();
        d.t0 = t0; d.t1 = t1; d.nb = nb;
        int secs = (int) (t1 - t0);
        short[] hr = new short[secs];
        byte[] st = new byte[secs];
        boolean[] lv = new boolean[secs];
        long lastR18 = 0;
        try {
            Cursor q = DayLog.db(c).rawQuery("SELECT ts, hr, state FROM r18 WHERE ts >= ? AND ts < ? AND hr > 30 AND hr < 230", new String[] {String.valueOf(t0), String.valueOf(t1)});
            try {
                while (q.moveToNext()) {
                    long ts = q.getLong(0);
                    int i = (int) (ts - t0);
                    if (i < 0 || i >= secs) continue;
                    hr[i] = (short) q.getInt(1);
                    st[i] = (byte) (q.getInt(2) + 1);
                    if (ts > lastR18) lastR18 = ts;
                }
            } finally { q.close(); }
        } catch (Throwable ignored) { }
        try {
            long[][] s = LiveHr.seriesSec(c, Math.max(t0, lastR18 + 1), t1);
            for (int k = 0; k < s[0].length; k++) {
                int i = (int) (s[0][k] - t0);
                if (i < 0 || i >= secs || hr[i] != 0) continue;
                hr[i] = (short) s[1][k];
                lv[i] = true;
                d.liveSecs++;
            }
        } catch (Throwable ignored) { }
        d.mean = new float[nb]; d.min = new float[nb]; d.max = new float[nb]; d.sleep = new float[nb]; d.live = new float[nb]; d.cnt = new int[nb];
        float[] sum = new float[nb];
        for (int b = 0; b < nb; b++) { d.min[b] = 1e9f; d.max[b] = -1e9f; }
        long total = 0, sleepSecs = 0;
        int gmin = 1000, gmax = 0;
        long[] ps = new long[secs + 1];
        int[] pc = new int[secs + 1];
        for (int i = 0; i < secs; i++) {
            ps[i + 1] = ps[i]; pc[i + 1] = pc[i];
            int h = hr[i];
            if (h == 0) continue;
            ps[i + 1] += h; pc[i + 1]++;
            int b = (int) ((long) i * nb / secs);
            sum[b] += h; d.cnt[b]++;
            if (h < d.min[b]) d.min[b] = h;
            if (h > d.max[b]) d.max[b] = h;
            if (st[i] == 3) { d.sleep[b]++; sleepSecs++; }
            if (lv[i]) d.live[b]++;
            total += h; d.total++;
            if (h < gmin) gmin = h;
            if (h > gmax) gmax = h;
        }
        for (int b = 0; b < nb; b++) {
            if (d.cnt[b] > 0) { d.mean[b] = sum[b] / d.cnt[b]; d.sleep[b] /= d.cnt[b]; d.live[b] /= d.cnt[b]; }
        }
        if (d.total > 0) { d.dmin = gmin; d.dmax = gmax; d.davg = (float) total / d.total; }
        d.asleepH = sleepSecs / 3600f;
        double best = 1e9; long bestI = -1;
        for (int i = 0; i + 300 <= secs; i += 30) {
            int n = pc[i + 300] - pc[i];
            if (n < 240) continue;
            double m = (ps[i + 300] - ps[i]) / (double) n;
            if (m < best) { best = m; bestI = i; }
        }
        if (bestI >= 0) { d.low5 = (float) best; d.low5T = t0 + bestI + 150; }
        return d;
    }

    // ------------------------------------------------------------------ screen (0.14.0: pinch to zoom, drag to move, expand)
    private static long curT0, curT1;                 // the window being shown, seconds
    private static boolean busy, again, expanded;
    private static int rangeIdx = 2;

    private static int nearestRange(long span) {
        for (int i = 0; i < RANGE_SEC.length; i++) if (Math.abs(span - RANGE_SEC[i]) <= RANGE_SEC[i] * 0.03) return i;
        return -1;
    }

    static void show(final Activity a, int startRange, final int maxHr, final long[] ecgSec) {
        rangeIdx = Math.max(0, Math.min(RANGE_SEC.length - 1, startRange));
        final long now0 = System.currentTimeMillis() / 1000L;
        curT1 = now0; curT0 = now0 - RANGE_SEC[rangeIdx];
        busy = false; again = false; expanded = false;
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = Math.round(dp(a, 16));
        root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label(a, "Heart rate", 22, INK, 300));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        final TextView expandB = pill(a, "Expand", 0x1AFFFFFF, INK);
        top.addView(expandB);
        TextView close = pill(a, "Close", 0x1AFFFFFF, INK);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = Math.round(dp(a, 8));
        top.addView(close, cl);
        root.addView(top);

        final LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(chips, lp(a, -1, -2, 12));

        final TextView summary = label(a, "Loading\u2026", 13, MUT, 400);
        summary.setLineSpacing(0, 1.2f);
        root.addView(summary, lp(a, -1, -2, 10));

        final TextView read = label(a, "Touch the graph to read it. Pinch to zoom, drag to move.", 13, INK, 400);
        root.addView(read, lp(a, -1, -2, 6));

        final HrFullView chart = new HrFullView(a, maxHr, ecgSec);
        chart.setWindow(curT0, curT1);
        chart.setLimits(now0 - 30L * 86400L, now0);
        root.addView(chart, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(a);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        final TextView earlier = pill(a, "\u25C0", 0x1AFFFFFF, INK);
        final TextView later = pill(a, "\u25B6", 0x1AFFFFFF, INK);
        final TextView zin = pill(a, "+", 0x1AFFFFFF, INK);
        final TextView zout = pill(a, "\u2212", 0x1AFFFFFF, INK);
        final TextView nowB = pill(a, "Now", BLUE, 0xFF05131E);
        final TextView when = label(a, "", 12, MUT, 400);
        when.setGravity(Gravity.CENTER);
        nav.addView(earlier);
        nav.addView(zout);
        nav.addView(when, new LinearLayout.LayoutParams(0, -2, 1f));
        nav.addView(zin);
        nav.addView(later);
        nav.addView(nowB);
        root.addView(nav, lp(a, -1, -2, 10));

        final TextView legend = label(a, "Pinch to zoom, drag to move, double-tap to zoom in, hold and drag to read. Line = average, band = lowest to highest in each slice. Violet = asleep (the strap's own state). Teal marks = ECG readings. Coloured bands = zones for a maximum of " + maxHr + " bpm. Estimates, not a diagnosis.", 11, MUT, 400);
        legend.setLineSpacing(0, 1.2f);
        root.addView(legend, lp(a, -1, -2, 8));

        final Context app = a.getApplicationContext();
        final SimpleDateFormat fDay = new SimpleDateFormat("EEE d MMM", Locale.UK), fTime = new SimpleDateFormat("d MMM HH:mm", Locale.UK), fSec = new SimpleDateFormat("d MMM HH:mm:ss", Locale.UK);
        final Runnable[] fillChips = new Runnable[1];
        final Runnable[] doLoad = new Runnable[1];
        final Runnable[] afterWindow = new Runnable[1];
        fillChips[0] = new Runnable() {
            @Override public void run() {
                chips.removeAllViews();
                int on = nearestRange(curT1 - curT0);
                for (int i = 0; i < RANGE_LABEL.length; i++) {
                    final int ri = i;
                    TextView ch = pill(a, RANGE_LABEL[i], i == on ? BLUE : 0x1AFFFFFF, i == on ? 0xFF05131E : INK);
                    ch.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            long n = System.currentTimeMillis() / 1000L;
                            rangeIdx = ri; curT1 = n; curT0 = n - RANGE_SEC[ri];
                            chart.setLimits(n - 30L * 86400L, n);
                            chart.setWindow(curT0, curT1);
                            afterWindow[0].run(); chart.removeCallbacks(doLoad[0]); chart.post(doLoad[0]);
                        }
                    });
                    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                    l.rightMargin = Math.round(dp(a, 8));
                    chips.addView(ch, l);
                }
            }
        };
        afterWindow[0] = new Runnable() {
            @Override public void run() {
                long span = curT1 - curT0;
                SimpleDateFormat f = span >= 3L * 86400L ? fDay : span < 1800L ? fSec : fTime;
                when.setText(f.format(new Date(curT0 * 1000L)) + "  \u2192  " + f.format(new Date(curT1 * 1000L)));
            }
        };
        doLoad[0] = new Runnable() {
            @Override public void run() {
                if (busy) { again = true; return; }
                busy = true; again = false;
                final long t0 = curT0, t1 = curT1;
                final int nb = chart.binsFor(t1 - t0);
                new Thread(new Runnable() {
                    @Override public void run() {
                        Data dd = null;
                        try { dd = load(app, t0, t1, nb); } catch (Throwable ignored) { }
                        final Data fd = dd;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                busy = false;
                                if (fd == null) { summary.setText("Could not read the heart-rate records."); return; }
                                chart.setData(fd, new Readout() { @Override public void show(String s) { read.setText(s); } });
                                summary.setText(summaryText(fd));
                                fillChips[0].run();
                                // the user may have moved on while this loaded
                                if (again || Math.abs(curT0 - t0) > Math.max(1L, (t1 - t0) / 100L) || Math.abs(curT1 - t1) > Math.max(1L, (t1 - t0) / 100L)) chart.post(doLoad[0]);
                            }
                        });
                    }
                }, "hr-graph").start();
            }
        };
        chart.setWindowListener(new HrFullView.WindowListener() {
            @Override public void onWindow(double t0, double t1, boolean finished) {
                curT0 = (long) Math.floor(t0); curT1 = (long) Math.ceil(t1);
                afterWindow[0].run();
                chart.removeCallbacks(doLoad[0]);
                chart.postDelayed(doLoad[0], finished ? 60L : 280L);
                if (finished) fillChips[0].run();
            }
        });
        earlier.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chart.shift(-0.5); } });
        later.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chart.shift(0.5); } });
        zin.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chart.zoomCenter(2.0); } });
        zout.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chart.zoomCenter(0.5); } });
        nowB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long n = System.currentTimeMillis() / 1000L;
                chart.setLimits(n - 30L * 86400L, n);
                chart.goNow();
            }
        });
        expandB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                expanded = !expanded;
                int vis = expanded ? View.GONE : View.VISIBLE;
                chips.setVisibility(vis); summary.setVisibility(vis); legend.setVisibility(vis);
                expandB.setText(expanded ? "Normal" : "Expand");
            }
        });
        dlg.setContentView(root);
        dlg.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        dlg.show();
        // how far back there is data
        new Thread(new Runnable() {
            @Override public void run() {
                long mn = 0;
                try {
                    Cursor q = DayLog.db(app).rawQuery("SELECT MIN(ts) FROM r18", null);
                    try { if (q.moveToFirst() && !q.isNull(0)) mn = q.getLong(0); } finally { q.close(); }
                } catch (Throwable ignored) { }
                final long fmn = mn;
                a.runOnUiThread(new Runnable() { @Override public void run() { if (fmn > 0) chart.setLimits(Math.min(fmn, curT0), System.currentTimeMillis() / 1000L); } });
            }
        }, "hr-graph-min").start();
        fillChips[0].run();
        afterWindow[0].run();
        doLoad[0].run();
    }

    static String summaryText(Data d) {
        if (d.total == 0) return "No heart-rate records in this window.";
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.UK);
        StringBuilder sb = new StringBuilder();
        sb.append("Lowest ").append(Math.round(d.dmin)).append("  \u00b7  average ").append(Math.round(d.davg)).append("  \u00b7  highest ").append(Math.round(d.dmax)).append(" bpm");
        if (!Float.isNaN(d.low5)) sb.append("\nLowest five minutes ").append(Math.round(d.low5)).append(" at ").append(f.format(new Date(d.low5T * 1000L)));
        long span = d.t1 - d.t0;
        sb.append("\nAsleep ").append(String.format(Locale.UK, "%.1f", d.asleepH)).append(" h  \u00b7  ").append(Math.round(100.0 * d.total / span)).append("% of the window has records");
        if (d.liveSecs > 0) sb.append("  \u00b7  ").append(d.liveSecs).append(" s live");
        return sb.toString();
    }

    // ------------------------------------------------------------------ small builders
    static TextView label(Context c, String s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font(c, weight));
        return t;
    }

    static TextView pill(Context c, String s, int bg, int fg) {
        TextView t = label(c, s, 13, fg, 400);
        int h = Math.round(dp(c, 14)), v = Math.round(dp(c, 8));
        t.setPadding(h, v, h, v);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(c, 18));
        t.setBackground(g);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h);
        l.topMargin = Math.round(dp(c, topDp));
        return l;
    }

    // ------------------------------------------------------------------ the chart (0.14.0: time-based, pinch / drag / double-tap / fling)
    static final class HrFullView extends View {
        interface WindowListener { void onWindow(double t0, double t1, boolean finished); }

        static final double MIN_SPAN = 120.0, MAX_SPAN = 30.0 * 86400.0;
        private final int maxHr;
        private final long[] ecg;
        private Data d;
        private Readout readout;
        private WindowListener wl;
        private double vt0, vt1, limMin = 0, limMax = 0;
        private double cursorT = Double.NaN;
        private boolean scrub, flinging;
        private float flingV;
        private long flingLast;
        private final ScaleGestureDetector sgd;
        private final GestureDetector gd;

        HrFullView(Context c, int maxHr, long[] ecgSec) {
            super(c);
            this.maxHr = maxHr;
            this.ecg = ecgSec == null ? new long[0] : ecgSec;
            sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(ScaleGestureDetector s) { stopFling(); scrub = false; return true; }
                @Override public boolean onScale(ScaleGestureDetector s) { zoomAt(s.getFocusX(), s.getScaleFactor()); return true; }
            });
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { stopFling(); return true; }
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (sgd.isInProgress()) return false;
                    if (scrub) { setCursorX(e2.getX()); return true; }
                    panPx(dx);
                    return true;
                }
                @Override public boolean onSingleTapUp(MotionEvent e) { setCursorX(e.getX()); return true; }
                @Override public void onLongPress(MotionEvent e) { scrub = true; setCursorX(e.getX()); }
                @Override public boolean onDoubleTap(MotionEvent e) { doubleTap(e.getX()); return true; }
                @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) { if (!scrub && !sgd.isInProgress()) startFling(vx); return true; }
            });
        }

        // ---- window control (seconds, fractional)
        void setWindow(double t0, double t1) { vt0 = t0; vt1 = t1; invalidate(); }
        void setLimits(double mn, double mx) { limMin = mn; limMax = mx; }
        void setWindowListener(WindowListener l) { wl = l; }
        void setData(Data data, Readout r) { d = data; readout = r; emit(); invalidate(); }
        double span() { return vt1 - vt0; }

        int binsFor(long spanSec) {
            int wpx = getWidth() > 0 ? getWidth() : 1080;
            int nb = Math.max(60, Math.min(720, Math.round(wpx / 2f)));
            return (int) Math.max(30L, Math.min((long) nb, spanSec));
        }

        /** Pure: keeps the window inside the limits and its span between 2 minutes and 30 days. */
        static double[] clamp(double t0, double t1, double mn, double mx) {
            double span = t1 - t0;
            double maxSpan = mx > mn ? Math.min(MAX_SPAN, Math.max(MIN_SPAN * 2, (mx - mn) * 1.15)) : MAX_SPAN;
            span = Math.max(MIN_SPAN, Math.min(maxSpan, span));
            if (mx > 0 && t1 > mx) t1 = mx;
            t0 = t1 - span;
            if (mn > 0 && t0 < mn - span * 0.05) { t0 = mn - span * 0.05; t1 = t0 + span; if (mx > 0 && t1 > mx) { t1 = mx; t0 = t1 - span; } }
            return new double[] {t0, t1};
        }

        private void apply(double t0, double t1, boolean finished) {
            double[] w = clamp(t0, t1, limMin, limMax);
            vt0 = w[0]; vt1 = w[1];
            invalidate();
            if (wl != null) wl.onWindow(vt0, vt1, finished);
        }

        void zoomAt(float fx, double factor) {
            double span = vt1 - vt0, w = Math.max(1, getWidth());
            double tf = vt0 + fx / w * span;
            double ns = Math.max(MIN_SPAN, Math.min(MAX_SPAN, span / factor));
            apply(tf - fx / w * ns, tf - fx / w * ns + ns, false);
        }

        void zoomCenter(double factor) { zoomAt(getWidth() / 2f, factor); apply(vt0, vt1, true); }

        void panPx(float dx) { double span = vt1 - vt0, sh = dx / Math.max(1, getWidth()) * span; apply(vt0 + sh, vt1 + sh, false); }

        void shift(double fraction) { double span = vt1 - vt0, sh = span * fraction; apply(vt0 + sh, vt1 + sh, true); }

        void goNow() { double span = vt1 - vt0; apply(limMax - span, limMax, true); }

        private void doubleTap(float x) {
            if (vt1 - vt0 > 15 * 60) { zoomAt(x, 4.0); apply(vt0, vt1, true); }
            else { double end = limMax > 0 ? limMax : vt1; apply(end - 86400.0, end, true); }
        }

        private void startFling(float vx) {
            flingV = -vx; flinging = true; flingLast = SystemClock.uptimeMillis();
            postOnAnimation(flingStep);
        }

        private void stopFling() { if (flinging) { flinging = false; removeCallbacks(flingStep); } }

        private final Runnable flingStep = new Runnable() {
            @Override public void run() {
                if (!flinging) return;
                long now = SystemClock.uptimeMillis();
                float dt = Math.min(0.05f, (now - flingLast) / 1000f);
                flingLast = now;
                panPx(flingV * dt);
                flingV *= (float) Math.pow(0.04, dt);
                if (Math.abs(flingV) < 40f) { flinging = false; apply(vt0, vt1, true); return; }
                postOnAnimation(this);
            }
        };

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (d == null) return true;
            getParent().requestDisallowInterceptTouchEvent(true);
            sgd.onTouchEvent(e);
            gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { scrub = false; if (!flinging) apply(vt0, vt1, true); }
            return true;
        }

        // ---- cursor and read-out
        private void setCursorX(float x) {
            cursorT = vt0 + Math.max(0f, Math.min(getWidth() - 1f, x)) / Math.max(1, getWidth()) * (vt1 - vt0);
            emit();
            invalidate();
        }

        private int binOf(double t) {
            if (d == null || t < d.t0 || t >= d.t1) return -1;
            return Math.max(0, Math.min(d.nb - 1, (int) ((t - d.t0) / (d.t1 - d.t0) * d.nb)));
        }

        private void emit() {
            if (readout == null || d == null || Double.isNaN(cursorT)) return;
            int b = binOf(cursorT);
            long t = (long) cursorT;
            SimpleDateFormat f = new SimpleDateFormat((vt1 - vt0) >= 3.0 * 86400 ? "EEE d MMM HH:mm" : (vt1 - vt0) >= 2 * 3600 ? "d MMM HH:mm" : "HH:mm:ss", Locale.UK);
            if (b < 0 || d.cnt[b] == 0) { readout.show(f.format(new Date(t * 1000L)) + "  \u00b7  no record here"); return; }
            int z = HealthModel.zoneOfHr(Math.round(d.mean[b]), maxHr);
            StringBuilder sb = new StringBuilder(f.format(new Date(t * 1000L)));
            sb.append("  \u00b7  ").append(Math.round(d.mean[b])).append(" bpm");
            sb.append("  (").append(Math.round(d.min[b])).append(" to ").append(Math.round(d.max[b])).append(")");
            sb.append("  \u00b7  ").append(ZONE_NAME[z]);
            if (d.sleep[b] >= 0.5f) sb.append("  \u00b7  asleep");
            if (d.live[b] >= 0.5f) sb.append("  \u00b7  live");
            long sl = (d.t1 - d.t0) / d.nb;
            for (long te : ecg) if (te >= t - sl && te <= t + sl) { sb.append("  \u00b7  ECG reading"); break; }
            readout.show(sb.toString());
        }

        /** Pure: a tidy tick step (seconds) giving about 4 to 7 ticks across the window. */
        static long tickStep(double span) {
            long[] steps = {10, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400, 172800};
            for (long s : steps) if (span / s <= 8.0) return s;
            return 604800L;
        }

        @Override protected void onDraw(Canvas cv) {
            if (d == null) return;
            Context c = getContext();
            float w = getWidth(), h = getHeight(), ch = h - dp(c, 22);
            final double span = vt1 - vt0;
            final double binSec = (double) (d.t1 - d.t0) / d.nb;
            int b0 = (int) Math.max(0, Math.floor((vt0 - d.t0) / binSec) - 1), b1 = (int) Math.min(d.nb - 1, Math.ceil((vt1 - d.t0) / binSec) + 1);
            float gmin = 1e9f, gmax = -1e9f;
            for (int b = b0; b <= b1; b++) if (b >= 0 && b < d.nb && d.cnt[b] > 0) { gmin = Math.min(gmin, d.min[b]); gmax = Math.max(gmax, d.max[b]); }
            boolean any = gmax >= 0;
            if (!any) { gmin = 50f; gmax = 100f; }
            float lo = (float) Math.floor((gmin - 4f) / 10f) * 10f, hi = (float) Math.ceil((gmax + 4f) / 10f) * 10f;
            if (hi - lo < 30f) hi = lo + 30f;
            // zone bands
            int[] e = HealthModel.zoneEdges(maxHr);
            float[] ed = {0f, e[0], e[1], e[2], e[3], 999f};
            for (int z = 0; z < 5; z++) {
                float a = Math.max(lo, ed[z]), b = Math.min(hi, ed[z + 1]);
                if (b <= a) continue;
                float ya = ch * (1f - (b - lo) / (hi - lo)), yb = ch * (1f - (a - lo) / (hi - lo));
                cv.drawRect(0, ya, w, yb, paint(alpha(ZONE_COL[z], 0.10f)));
            }
            // sleep shading
            Paint sp = paint(0x269B8CFF);
            for (int b = b0; b <= b1; b++) if (b >= 0 && b < d.nb && d.cnt[b] > 0 && d.sleep[b] >= 0.5f)
                cv.drawRect(xOf(d.t0 + b * binSec, span, w), 0, xOf(d.t0 + (b + 1) * binSec, span, w), ch, sp);
            // horizontal grid and labels
            Paint g = paint(0x14FFFFFF); g.setStrokeWidth(dp(c, 1));
            Paint tx = paint(MUT); tx.setTextSize(dp(c, 10)); tx.setTypeface(font(c, 400));
            float stepY = (hi - lo) > 80f ? 20f : (hi - lo) > 40f ? 10f : 5f;
            tx.setTextAlign(Paint.Align.RIGHT);
            for (float lv = (float) Math.ceil(lo / stepY) * stepY; lv < hi; lv += stepY) {
                float y = ch * (1f - (lv - lo) / (hi - lo));
                cv.drawLine(0, y, w, y, g);
                cv.drawText(String.valueOf((int) lv), w - dp(c, 2), y - dp(c, 3), tx);
            }
            // time ticks
            long step = tickStep(span);
            SimpleDateFormat tf = new SimpleDateFormat(step >= 172800L ? "EEE d" : step >= 86400L ? "EEE d" : step < 60L ? "HH:mm:ss" : span > 26 * 3600 ? "EEE HH:mm" : "HH:mm", Locale.UK);
            long off = TimeZone.getDefault().getOffset((long) vt0 * 1000L) / 1000L;
            long first = (((long) vt0 + off) / step + 1) * step - off;
            tx.setTextAlign(Paint.Align.CENTER);
            for (long t = first; t < vt1; t += step) {
                float x = xOf(t, span, w);
                cv.drawLine(x, 0, x, ch, g);
                cv.drawText(tf.format(new Date(t * 1000L)), Math.max(dp(c, 18), Math.min(w - dp(c, 18), x)), h - dp(c, 5), tx);
            }
            if (!any) {
                Paint t = paint(MUT); t.setTextSize(dp(c, 14)); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 400));
                cv.drawText("Nothing logged in this window", w / 2f, ch / 2f, t);
                return;
            }
            // ECG readings
            Paint em = paint(alpha(TEAL, 0.7f)); em.setStrokeWidth(dp(c, 1.5f));
            for (long te : ecg) {
                if (te < vt0 || te >= vt1) continue;
                float x = xOf(te, span, w);
                cv.drawLine(x, ch * 0.88f, x, ch, em);
                cv.drawCircle(x, ch * 0.88f, dp(c, 3), paint(TEAL));
            }
            // band (lowest to highest) and the average line, broken where records are missing
            Paint fill = paint(alpha(ROSE, 0.20f));
            List<float[]> pts = new ArrayList<>();
            int gap = 0;
            for (int b = b0; b <= b1; b++) {
                if (b < 0 || b >= d.nb || d.cnt[b] == 0) { gap++; if (gap == 3) pts.add(null); continue; }
                gap = 0;
                pts.add(new float[] {xOf(d.t0 + (b + 0.5) * binSec, span, w), ch * (1f - (d.mean[b] - lo) / (hi - lo))});
            }
            int run0 = -1;
            for (int b = b0; b <= b1 + 1; b++) {
                boolean has = b <= b1 && b >= 0 && b < d.nb && d.cnt[b] > 0;
                if (has && run0 < 0) run0 = b;
                if (!has && run0 >= 0) {
                    Path band = new Path();
                    for (int k = run0; k < b; k++) { float x = xOf(d.t0 + (k + 0.5) * binSec, span, w), y = ch * (1f - (d.max[k] - lo) / (hi - lo)); if (k == run0) band.moveTo(x, y); else band.lineTo(x, y); }
                    for (int k = b - 1; k >= run0; k--) band.lineTo(xOf(d.t0 + (k + 0.5) * binSec, span, w), ch * (1f - (d.min[k] - lo) / (hi - lo)));
                    band.close();
                    cv.drawPath(band, fill);
                    run0 = -1;
                }
            }
            Path line = smooth(pts);
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStyle(Paint.Style.STROKE); ln.setStrokeCap(Paint.Cap.ROUND); ln.setStrokeJoin(Paint.Join.ROUND);
            ln.setShader(new LinearGradient(0, 0, w, 0, 0xFFFF7A93, 0xFFFFC36B, Shader.TileMode.CLAMP));
            ln.setStrokeWidth(dp(c, 6)); ln.setAlpha(50);
            cv.drawPath(line, ln);
            ln.setStrokeWidth(dp(c, 2.2f)); ln.setAlpha(255);
            cv.drawPath(line, ln);
            // lowest five minutes
            if (!Float.isNaN(d.low5) && d.low5T >= vt0 && d.low5T <= vt1) {
                float x = xOf(d.low5T, span, w), y = ch * (1f - (d.low5 - lo) / (hi - lo));
                cv.drawCircle(x, y, dp(c, 7), paint(0x46FF7A93));
                cv.drawCircle(x, y, dp(c, 3), paint(0xFFFFFFFF));
            }
            // cursor (kept at its time while you pan and zoom)
            if (!Double.isNaN(cursorT) && cursorT >= vt0 && cursorT <= vt1) {
                int b = binOf(cursorT);
                Paint cur = paint(0x99FFFFFF); cur.setStrokeWidth(dp(c, 1));
                float x = xOf(cursorT, span, w);
                cv.drawLine(x, 0, x, ch, cur);
                if (b >= 0 && d.cnt[b] > 0) {
                    float y = ch * (1f - (d.mean[b] - lo) / (hi - lo));
                    cv.drawCircle(x, y, dp(c, 8), paint(0x55FFFFFF));
                    cv.drawCircle(x, y, dp(c, 4), paint(0xFFFFFFFF));
                }
            }
        }

        private float xOf(double t, double span, float w) { return (float) ((t - vt0) / span * w); }
    }
}

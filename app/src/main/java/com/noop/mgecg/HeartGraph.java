package com.noop.mgecg;

// FILE VERSION 0.11.0 (25 Oct): the full-screen heart-rate graph. Research only.

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
import android.view.MotionEvent;
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

    // ------------------------------------------------------------------ screen
    private static long endSec, nowSec;
    private static int rangeIdx;
    private static boolean busy;

    static void show(final Activity a, int startRange, final int maxHr, final long[] ecgSec) {
        rangeIdx = Math.max(0, Math.min(RANGE_SEC.length - 1, startRange));
        nowSec = System.currentTimeMillis() / 1000L;
        endSec = nowSec;
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = Math.round(dp(a, 16));
        root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(a, "Heart rate", 22, INK, 300);
        top.addView(title);
        View sp = new View(a);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = pill(a, "Close", 0x1AFFFFFF, INK);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        top.addView(close);
        root.addView(top);

        final LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(chips, lp(a, -1, -2, 12));

        final TextView summary = label(a, "Loading\u2026", 13, MUT, 400);
        summary.setLineSpacing(0, 1.2f);
        root.addView(summary, lp(a, -1, -2, 10));

        final TextView read = label(a, "Touch the graph to read it.", 13, INK, 400);
        root.addView(read, lp(a, -1, -2, 6));

        final HrFullView chart = new HrFullView(a, maxHr, ecgSec);
        root.addView(chart, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(a);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        final TextView earlier = pill(a, "\u25C0  Earlier", 0x1AFFFFFF, INK);
        final TextView later = pill(a, "Later  \u25B6", 0x1AFFFFFF, INK);
        final TextView nowB = pill(a, "Now", BLUE, 0xFF05131E);
        final TextView when = label(a, "", 12, MUT, 400);
        when.setGravity(Gravity.CENTER);
        nav.addView(earlier);
        nav.addView(when, new LinearLayout.LayoutParams(0, -2, 1f));
        nav.addView(later);
        nav.addView(nowB);
        root.addView(nav, lp(a, -1, -2, 10));

        TextView legend = label(a, "Line = average, band = lowest to highest in each slice. Violet = asleep (the strap's own state). Teal marks = ECG readings. Coloured bands = zones for a maximum of " + maxHr + " bpm. Estimates, not a diagnosis.", 11, MUT, 400);
        legend.setLineSpacing(0, 1.2f);
        root.addView(legend, lp(a, -1, -2, 8));

        final Runnable[] reload = new Runnable[1];
        final Activity act = a;
        reload[0] = new Runnable() {
            @Override public void run() {
                chips.removeAllViews();
                for (int i = 0; i < RANGE_LABEL.length; i++) {
                    final int ri = i;
                    TextView ch = pill(act, RANGE_LABEL[i], i == rangeIdx ? BLUE : 0x1AFFFFFF, i == rangeIdx ? 0xFF05131E : INK);
                    ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { rangeIdx = ri; reload[0].run(); } });
                    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                    l.rightMargin = Math.round(dp(act, 8));
                    chips.addView(ch, l);
                }
                if (busy) return;
                busy = true;
                summary.setText("Loading\u2026");
                final long w = RANGE_SEC[rangeIdx];
                if (endSec > nowSec) endSec = nowSec;
                final long t1 = endSec, t0 = endSec - w;
                final Context app = act.getApplicationContext();
                new Thread(new Runnable() {
                    @Override public void run() {
                        Data dd = null;
                        try { dd = load(app, t0, t1, 360); } catch (Throwable ignored) { }
                        final Data fd = dd;
                        act.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                busy = false;
                                if (fd == null) { summary.setText("Could not read the heart-rate records."); return; }
                                chart.set(fd, new Readout() { @Override public void show(String s) { read.setText(s); } });
                                summary.setText(summaryText(fd));
                                SimpleDateFormat f = new SimpleDateFormat(w >= 3L * 86400L ? "EEE d MMM" : "d MMM HH:mm", Locale.UK);
                                when.setText(f.format(new Date(fd.t0 * 1000L)) + "  \u2192  " + f.format(new Date(fd.t1 * 1000L)));
                                read.setText("Touch the graph to read it.");
                            }
                        });
                    }
                }, "hr-graph").start();
            }
        };
        earlier.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { endSec -= RANGE_SEC[rangeIdx] / 2; reload[0].run(); } });
        later.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { endSec = Math.min(System.currentTimeMillis() / 1000L, endSec + RANGE_SEC[rangeIdx] / 2); reload[0].run(); } });
        nowB.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { nowSec = System.currentTimeMillis() / 1000L; endSec = nowSec; reload[0].run(); } });
        dlg.setContentView(root);
        dlg.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        dlg.show();
        reload[0].run();
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

    // ------------------------------------------------------------------ the chart
    static final class HrFullView extends View {
        private final int maxHr;
        private final long[] ecg;
        private Data d;
        private Readout readout;
        private float touchX = -1f;

        HrFullView(Context c, int maxHr, long[] ecgSec) { super(c); this.maxHr = maxHr; this.ecg = ecgSec == null ? new long[0] : ecgSec; }

        void set(Data data, Readout r) { d = data; readout = r; touchX = -1f; invalidate(); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (d == null) return true;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                touchX = Math.max(0f, Math.min(getWidth() - 1f, e.getX()));
                emit();
                invalidate();
            }
            return true;
        }

        private int binAt(float x) { return Math.max(0, Math.min(d.nb - 1, (int) (x / getWidth() * d.nb))); }

        private void emit() {
            if (readout == null || d == null) return;
            int b = binAt(touchX);
            long t = d.t0 + (long) ((b + 0.5) * (d.t1 - d.t0) / d.nb);
            SimpleDateFormat f = new SimpleDateFormat((d.t1 - d.t0) >= 3L * 86400L ? "EEE d MMM HH:mm" : "HH:mm:ss", Locale.UK);
            if (d.cnt[b] == 0) { readout.show(f.format(new Date(t * 1000L)) + "  \u00b7  no record here"); return; }
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

        @Override protected void onDraw(Canvas cv) {
            if (d == null) return;
            Context c = getContext();
            float w = getWidth(), h = getHeight(), ch = h - dp(c, 22);
            if (d.total == 0) {
                Paint t = paint(MUT); t.setTextSize(dp(c, 14)); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 400));
                cv.drawText("Nothing logged in this window", w / 2f, ch / 2f, t);
                return;
            }
            float ymin = (float) Math.floor((d.dmin - 4f) / 10f) * 10f, ymax = (float) Math.ceil((d.dmax + 4f) / 10f) * 10f;
            if (ymax - ymin < 30f) ymax = ymin + 30f;
            final float lo = ymin, hi = ymax;
            long span = d.t1 - d.t0;
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
            for (int b = 0; b < d.nb; b++) if (d.cnt[b] > 0 && d.sleep[b] >= 0.5f) cv.drawRect(w * b / d.nb, 0, w * (b + 1) / d.nb, ch, sp);
            // horizontal grid and labels
            Paint g = paint(0x14FFFFFF); g.setStrokeWidth(dp(c, 1));
            Paint tx = paint(MUT); tx.setTextSize(dp(c, 10)); tx.setTypeface(font(c, 400));
            float stepY = (hi - lo) > 80f ? 20f : 10f;
            tx.setTextAlign(Paint.Align.RIGHT);
            for (float lv = (float) Math.ceil(lo / stepY) * stepY; lv < hi; lv += stepY) {
                float y = ch * (1f - (lv - lo) / (hi - lo));
                cv.drawLine(0, y, w, y, g);
                cv.drawText(String.valueOf((int) lv), w - dp(c, 2), y - dp(c, 3), tx);
            }
            // time ticks
            long step = span <= 3600L ? 600L : span <= 6L * 3600L ? 3600L : span <= 24L * 3600L ? 4L * 3600L : span <= 3L * 86400L ? 12L * 3600L : 86400L;
            SimpleDateFormat tf = new SimpleDateFormat(span > 24L * 3600L ? (step >= 86400L ? "EEE d" : "EEE HH") : "HH:mm", Locale.UK);
            long off = TimeZone.getDefault().getOffset(d.t0 * 1000L) / 1000L;
            long first = ((d.t0 + off) / step + 1) * step - off;
            tx.setTextAlign(Paint.Align.CENTER);
            for (long t = first; t < d.t1; t += step) {
                float x = w * (t - d.t0) / (float) span;
                cv.drawLine(x, 0, x, ch, g);
                cv.drawText(tf.format(new Date(t * 1000L)), Math.max(dp(c, 14), Math.min(w - dp(c, 14), x)), h - dp(c, 5), tx);
            }
            // ECG readings
            Paint em = paint(alpha(TEAL, 0.7f)); em.setStrokeWidth(dp(c, 1.5f));
            for (long te : ecg) {
                if (te < d.t0 || te >= d.t1) continue;
                float x = w * (te - d.t0) / (float) span;
                cv.drawLine(x, ch * 0.88f, x, ch, em);
                cv.drawCircle(x, ch * 0.88f, dp(c, 3), paint(TEAL));
            }
            // band (lowest to highest) and the average line, broken where records are missing
            Paint fill = paint(alpha(ROSE, 0.20f));
            int i = 0;
            List<float[]> pts = new ArrayList<>();
            int gap = 0;
            for (int b = 0; b < d.nb; b++) {
                if (d.cnt[b] == 0) { gap++; if (gap == 3) pts.add(null); continue; }
                gap = 0;
                float x = w * (b + 0.5f) / d.nb;
                pts.add(new float[] {x, ch * (1f - (d.mean[b] - lo) / (hi - lo))});
            }
            int run0 = -1;
            for (int b = 0; b <= d.nb; b++) {
                boolean has = b < d.nb && d.cnt[b] > 0;
                if (has && run0 < 0) run0 = b;
                if (!has && run0 >= 0) {
                    Path band = new Path();
                    for (int k = run0; k < b; k++) { float x = w * (k + 0.5f) / d.nb, y = ch * (1f - (d.max[k] - lo) / (hi - lo)); if (k == run0) band.moveTo(x, y); else band.lineTo(x, y); }
                    for (int k = b - 1; k >= run0; k--) band.lineTo(w * (k + 0.5f) / d.nb, ch * (1f - (d.min[k] - lo) / (hi - lo)));
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
            if (!Float.isNaN(d.low5)) {
                float x = w * (d.low5T - d.t0) / (float) span, y = ch * (1f - (d.low5 - lo) / (hi - lo));
                cv.drawCircle(x, y, dp(c, 7), paint(0x46FF7A93));
                cv.drawCircle(x, y, dp(c, 3), paint(0xFFFFFFFF));
            }
            // touch cursor
            if (touchX >= 0f) {
                int b = binAt(touchX);
                Paint cur = paint(0x99FFFFFF); cur.setStrokeWidth(dp(c, 1));
                float x = w * (b + 0.5f) / d.nb;
                cv.drawLine(x, 0, x, ch, cur);
                if (d.cnt[b] > 0) {
                    float y = ch * (1f - (d.mean[b] - lo) / (hi - lo));
                    cv.drawCircle(x, y, dp(c, 8), paint(0x55FFFFFF));
                    cv.drawCircle(x, y, dp(c, 4), paint(0xFFFFFFFF));
                }
            }
        }
    }
}

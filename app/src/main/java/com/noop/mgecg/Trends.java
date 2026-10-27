package com.noop.mgecg;

// FILE VERSION 0.12.0 (26 Oct): trends over 7, 30 and 90 nights. Research only.

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

/**
 * One point per night for recovery, resting heart rate, overnight HRV, sleep, breathing, skin temperature and blood oxygen, over the
 * last 7, 30 or 90 nights, with your usual range (average plus or minus one spread of the nights shown) shaded. Each night's recovery
 * is scored against the nights before it, exactly as on the Today screen. Estimates from this strap's own records, not a medical record.
 */
final class Trends {
    private Trends() {}

    static final String[] NAME = {"Recovery", "Resting HR", "HRV", "Sleep", "Breathing", "Skin temp", "Blood oxygen"};
    static final String[] UNIT = {"%", "bpm", "ms", "h", "per min", "\u00b0C", "%"};
    static final int[] DEC = {0, 0, 0, 1, 1, 1, 0};
    static final int[] COL = {0xFF5CE5A0, 0xFFFF7A93, 0xFF4CB4FF, 0xFF9AA0F0, 0xFF2EE0B8, 0xFFF2B35B, 0xFF8E93E8};
    static final int[] RANGE_N = {7, 30, 90};
    static final String[] RANGE_LABEL = {"7 nights", "30 nights", "90 nights"};
    private static int metric = 0, range = 1;

    /** Earlier nights plus the latest one, oldest first. */
    static List<TodayModel.NightStat> allNights(HealthModel.Data d) {
        List<TodayModel.NightStat> all = new ArrayList<>(d.nights);
        if (d.day != null && d.day.night != null) {
            boolean have = false;
            for (TodayModel.NightStat s : all) if (Math.abs(s.bed - d.day.night.bedTs) < 3600L) have = true;
            if (!have) all.add(TodayModel.statOf(d.day.night, d.day.spo2));
        }
        Collections.sort(all, new Comparator<TodayModel.NightStat>() { @Override public int compare(TodayModel.NightStat a, TodayModel.NightStat b) { return Long.compare(a.bed, b.bed); } });
        return all;
    }

    /** One number per night for the metric (NaN where the night has none). */
    static double[] series(List<TodayModel.NightStat> all, int m) {
        double[] v = new double[all.size()];
        for (int i = 0; i < v.length; i++) {
            TodayModel.NightStat s = all.get(i);
            double x = Double.NaN;
            switch (m) {
                case 0: { double[] r = TodayModel.recovery(s, all.subList(0, i)); x = r == null ? Double.NaN : r[0]; break; }
                case 1: x = s.hrLow5; break;
                case 2: x = s.rmssd > 0 ? s.rmssd : Double.NaN; break;
                case 3: x = s.tstH; break;
                case 4: x = s.br; break;
                case 5: x = s.skin; break;
                default: x = s.spo2Median > 0 ? s.spo2Median : Double.NaN;
            }
            v[i] = x;
        }
        return v;
    }

    /** Mean, spread (sample SD) and count of the finite values. */
    static double[] stats(double[] v, int from, int to) {
        double s = 0; int n = 0;
        for (int i = from; i < to; i++) if (!Double.isNaN(v[i])) { s += v[i]; n++; }
        if (n == 0) return new double[] {Double.NaN, Double.NaN, 0};
        double mean = s / n, q = 0;
        for (int i = from; i < to; i++) if (!Double.isNaN(v[i])) q += (v[i] - mean) * (v[i] - mean);
        return new double[] {mean, n > 1 ? Math.sqrt(q / (n - 1)) : 0.0, n};
    }

    static String fmt(int m, double x) {
        return Double.isNaN(x) ? "\u2014" : String.format(Locale.UK, "%." + DEC[m] + "f", x) + (UNIT[m].isEmpty() ? "" : " " + UNIT[m]);
    }

    /** Sleep over the last seven nights: average, debt against the goal, and how steady the bedtime is. Pure, so it can be tested. */
    static String weekSummary(List<TodayModel.NightStat> all, double goalH) {
        int n = all.size();
        if (n < 2) return "Needs two nights of records.";
        int from = Math.max(0, n - 7);
        double sum = 0, debt = 0;
        int k = 0;
        double[] bedMin = new double[n - from];
        for (int i = from; i < n; i++) {
            TodayModel.NightStat s = all.get(i);
            sum += s.tstH; k++;
            debt += Math.max(0.0, goalH - s.tstH);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(s.bed * 1000L);
            double m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60.0 + c.get(java.util.Calendar.MINUTE);
            bedMin[i - from] = (m - 12 * 60 + 1440) % 1440;            // minutes after noon, so 23:30 and 00:30 sit an hour apart
        }
        double mean = 0; for (double x : bedMin) mean += x; mean /= bedMin.length;
        double q = 0; for (double x : bedMin) q += (x - mean) * (x - mean);
        double sd = bedMin.length > 1 ? Math.sqrt(q / (bedMin.length - 1)) : 0;
        String steady = sd <= 30 ? "steady" : sd <= 60 ? "fairly steady" : "irregular";
        return String.format(Locale.UK, "Average %s over %d nights. Sleep debt against the %.0f h goal: %s. Bedtime spread about %d min (%s).",
                HealthModel.hm(sum / k), k, goalH, HealthModel.hm(debt), Math.round(sd), steady);
    }

    // ------------------------------------------------------------------ screen
    static void show(final Activity a, final HealthModel.Data d) {
        final List<TodayModel.NightStat> all = allNights(d);
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
        top.addView(HeartGraph.label(a, "Trends", 22, INK, 300));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        top.addView(close);
        root.addView(top);

        final LinearLayout mchips = new LinearLayout(a);
        mchips.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(mchips);
        root.addView(hs, HeartGraph.lp(a, -1, -2, 12));
        final LinearLayout rchips = new LinearLayout(a);
        rchips.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(rchips, HeartGraph.lp(a, -1, -2, 8));

        final TextView summary = HeartGraph.label(a, "", 13, MUT, 400);
        summary.setLineSpacing(0, 1.2f);
        root.addView(summary, HeartGraph.lp(a, -1, -2, 10));
        final TextView read = HeartGraph.label(a, "Touch the graph to read a night.", 13, INK, 400);
        root.addView(read, HeartGraph.lp(a, -1, -2, 6));
        final TrendView chart = new TrendView(a);
        root.addView(chart, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView note = HeartGraph.label(a, "One point per night. The shaded band is your usual range for the nights shown (average plus or minus one spread). Recovery is each night against the nights before it. HRV is from the wrist's optical intervals. Estimates, not a diagnosis.", 11, MUT, 400);
        note.setLineSpacing(0, 1.2f);
        root.addView(note, HeartGraph.lp(a, -1, -2, 8));
        final TextView about = HeartGraph.label(a, "", 12.5f, INK, 400);              // 0.21.0: what the selected graph means
        about.setLineSpacing(0, 1.25f);
        root.addView(about, HeartGraph.lp(a, -1, -2, 8));

        final Runnable[] redraw = new Runnable[1];
        redraw[0] = new Runnable() {
            @Override public void run() {
                mchips.removeAllViews();
                for (int i = 0; i < NAME.length; i++) {
                    final int mi = i;
                    TextView ch = HeartGraph.pill(a, NAME[i], i == metric ? COL[i] : 0x1AFFFFFF, i == metric ? 0xFF05131E : INK);
                    ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { metric = mi; redraw[0].run(); } });
                    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                    l.rightMargin = Math.round(dp(a, 8));
                    mchips.addView(ch, l);
                }
                rchips.removeAllViews();
                for (int i = 0; i < RANGE_N.length; i++) {
                    final int ri = i;
                    TextView ch = HeartGraph.pill(a, RANGE_LABEL[i], i == range ? BLUE : 0x1AFFFFFF, i == range ? 0xFF05131E : INK);
                    ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { range = ri; redraw[0].run(); } });
                    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                    l.rightMargin = Math.round(dp(a, 8));
                    rchips.addView(ch, l);
                }
                double[] v = series(all, metric);
                int to = v.length, from = Math.max(0, to - RANGE_N[range]);
                chart.set(all, v, from, to, metric, new Readout2() { @Override public void show(String s) { read.setText(s); } });
                double[] st = stats(v, from, to);
                double last = Double.NaN;
                for (int i = to - 1; i >= from; i--) if (!Double.isNaN(v[i])) { last = v[i]; break; }
                if (st[2] == 0) summary.setText("No " + NAME[metric].toLowerCase(Locale.UK) + " numbers for these nights yet.");
                else summary.setText("Latest " + fmt(metric, last) + "  \u00b7  average " + fmt(metric, st[0]) + "  \u00b7  usual " + fmt(metric, st[0] - st[1]) + " to " + fmt(metric, st[0] + st[1]) + "\n" + (int) st[2] + " of " + (to - from) + " nights have a value.\n" + (metric == 3 ? weekSummary(all, a.getSharedPreferences("today", Context.MODE_PRIVATE).getFloat("goal", 8.0f)) : ""));
                read.setText("Touch the graph to read a night.");
                about.setText(NAME[metric] + ": " + Explain.trend(NAME[metric]));
            }
        };
        dlg.setContentView(root);
        dlg.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        dlg.show();
        redraw[0].run();
    }

    interface Readout2 { void show(String s); }

    // ------------------------------------------------------------------ the chart
    static final class TrendView extends View {
        private List<TodayModel.NightStat> nights;
        private double[] v;
        private int from, to, m;
        private Readout2 readout;
        private float touchX = -1f;

        TrendView(Context c) { super(c); }

        void set(List<TodayModel.NightStat> n, double[] vals, int f, int t, int metric, Readout2 r) { nights = n; v = vals; from = f; to = t; m = metric; readout = r; touchX = -1f; invalidate(); }

        private float xOf(int i) {
            int cnt = Math.max(1, to - from - 1);
            return dp(getContext(), 14) + (getWidth() - dp(getContext(), 28)) * (i - from) / (float) cnt;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (v == null || to - from < 1) return true;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                touchX = e.getX();
                int best = from; float bd = 1e9f;
                for (int i = from; i < to; i++) { float dd = Math.abs(xOf(i) - touchX); if (dd < bd) { bd = dd; best = i; } }
                touchX = xOf(best);
                if (readout != null) {
                    SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.UK);
                    readout.show(f.format(new Date(nights.get(best).bed * 1000L)) + "  \u00b7  " + fmt(m, v[best]));
                }
                invalidate();
            }
            return true;
        }

        @Override protected void onDraw(Canvas cv) {
            if (v == null || to - from < 1) return;
            Context c = getContext();
            float w = getWidth(), h = getHeight(), ch = h - dp(c, 22);
            double[] st = stats(v, from, to);
            if (st[2] == 0) return;
            double lo = 1e9, hi = -1e9;
            for (int i = from; i < to; i++) if (!Double.isNaN(v[i])) { lo = Math.min(lo, v[i]); hi = Math.max(hi, v[i]); }
            lo = Math.min(lo, st[0] - st[1]); hi = Math.max(hi, st[0] + st[1]);
            if (m == 0) { lo = 0; hi = 100; }
            else { double pad = Math.max((hi - lo) * 0.15, m == 3 ? 0.5 : m == 5 ? 0.2 : 1.0); lo -= pad; hi += pad; }
            final double flo = lo, fhi = hi;
            Paint g = paint(0x14FFFFFF); g.setStrokeWidth(dp(c, 1));
            Paint tx = paint(MUT); tx.setTextSize(dp(c, 10)); tx.setTypeface(font(c, 400)); tx.setTextAlign(Paint.Align.RIGHT);
            for (int k = 0; k < 5; k++) {
                float y = ch * k / 4f;
                cv.drawLine(0, y, w, y, g);
                double val = fhi - (fhi - flo) * k / 4.0;
                cv.drawText(String.format(Locale.UK, "%." + (m == 3 || m == 4 || m == 5 ? 1 : 0) + "f", val), w - dp(c, 2), y - dp(c, 3), tx);
            }
            float yb1 = (float) (ch * (1 - ((st[0] + st[1]) - flo) / (fhi - flo))), yb2 = (float) (ch * (1 - ((st[0] - st[1]) - flo) / (fhi - flo)));
            cv.drawRoundRect(new RectF(0, Math.max(0, yb1), w, Math.min(ch, yb2)), dp(c, 6), dp(c, 6), paint(alpha(COL[m], 0.14f)));
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStyle(Paint.Style.STROKE); ln.setStrokeCap(Paint.Cap.ROUND); ln.setStrokeJoin(Paint.Join.ROUND); ln.setStrokeWidth(dp(c, 2.2f)); ln.setColor(COL[m]);
            android.graphics.Path path = new android.graphics.Path();
            boolean pen = false;
            for (int i = from; i < to; i++) {
                if (Double.isNaN(v[i])) { pen = false; continue; }
                float x = xOf(i), y = (float) (ch * (1 - (v[i] - flo) / (fhi - flo)));
                if (!pen) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
            }
            cv.drawPath(path, ln);
            for (int i = from; i < to; i++) {
                if (Double.isNaN(v[i])) continue;
                float x = xOf(i), y = (float) (ch * (1 - (v[i] - flo) / (fhi - flo)));
                cv.drawCircle(x, y, dp(c, to - from > 40 ? 2.2f : 4f), paint(COL[m]));
            }
            tx.setTextAlign(Paint.Align.LEFT);
            SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.UK);
            cv.drawText(f.format(new Date(nights.get(from).bed * 1000L)), 0, h - dp(c, 5), tx);
            tx.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(f.format(new Date(nights.get(to - 1).bed * 1000L)), w, h - dp(c, 5), tx);
            if (touchX >= 0f) { Paint cur = paint(0x99FFFFFF); cur.setStrokeWidth(dp(c, 1)); cv.drawLine(touchX, 0, touchX, ch, cur); }
        }
    }
}

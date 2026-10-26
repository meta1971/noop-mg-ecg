package com.noop.mgecg;

// FILE VERSION 0.12.0 (26 Oct): the daily dashboard pieces: three rings, the health monitor against your own baseline. Research only.

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

/**
 * What a daily strap app shows first: Recovery, Effort and Sleep as three rings, a short plan for the day, and a monitor that sets
 * last night's resting heart rate, overnight HRV, breathing, SpO2 and skin temperature against YOUR usual range (the earlier nights,
 * up to 14). Every figure is an estimate from this strap's own records and is labelled that way. Not a medical device.
 */
final class DailyViews {
    private DailyViews() {}

    static final int RED = 0xFFFF5C6C, AMBER = 0xFFF2B35B, OK = 0xFF5CE5A0, GREY = 0xFF6B7585;

    /** Recovery band colour: 67 and over green, 34 to 66 amber, below that red. */
    static int recoveryColor(double r) { return Double.isNaN(r) ? GREY : r >= 67 ? OK : r >= 34 ? AMBER : RED; }

    /** A plan for the day from recovery, as an effort range on the 0 to 21 scale. An estimate, not advice. */
    static String plan(double recovery) {
        if (Double.isNaN(recovery)) return "Recovery appears after four nights are logged. Until then there is no effort target.";
        if (recovery >= 67) return "Recovered. An effort of about 14 to 18 would suit today.";
        if (recovery >= 34) return "Partly recovered. An effort of about 10 to 14 would suit today.";
        return "Not recovered. Keeping effort under about 10 would suit today.";
    }

    /** Three rings side by side, each with a big number, a label and a one-line caption. */
    static final class RingsView extends View {
        private final float[] frac;
        private final int[] col;
        private final String[] big, label, cap;
        RingsView(Context c, float[] frac, int[] col, String[] big, String[] label, String[] cap) {
            super(c);
            this.frac = frac; this.col = col; this.big = big; this.label = label; this.cap = cap;
        }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), cellW = w / 3f;
            float dia = Math.min(cellW - dp(c, 14), dp(c, 104));
            float sw = dp(c, 9);
            for (int i = 0; i < 3; i++) {
                float cx = cellW * (i + 0.5f), top = dp(c, 4);
                RectF r = new RectF(cx - dia / 2f + sw / 2f, top + sw / 2f, cx + dia / 2f - sw / 2f, top + dia - sw / 2f);
                Paint tr = new Paint(Paint.ANTI_ALIAS_FLAG);
                tr.setStyle(Paint.Style.STROKE); tr.setStrokeWidth(sw); tr.setColor(0x1FFFFFFF); tr.setStrokeCap(Paint.Cap.ROUND);
                cv.drawArc(r, 0, 360, false, tr);
                float f = Math.max(0f, Math.min(1f, frac[i]));
                if (f > 0.004f) {
                    Paint pr = new Paint(tr); pr.setColor(col[i]);
                    cv.drawArc(r, -90, 360f * f, false, pr);
                }
                Paint t = paint(INK); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 300)); t.setTextSize(dp(c, big[i].length() > 3 ? 22 : 28));
                cv.drawText(big[i], cx, top + dia / 2f + dp(c, 9), t);
                Paint l = paint(INK); l.setTextAlign(Paint.Align.CENTER); l.setTypeface(font(c, 400)); l.setTextSize(dp(c, 11)); l.setLetterSpacing(0.14f);
                cv.drawText(label[i], cx, top + dia + dp(c, 20), l);
                Paint k = paint(MUT); k.setTextAlign(Paint.Align.CENTER); k.setTypeface(font(c, 400)); k.setTextSize(dp(c, 10.5f));
                cv.drawText(cap[i], cx, top + dia + dp(c, 36), k);
            }
        }
    }

    /** One monitor line: last night's value, your usual range, and where the value sits. */
    static final class Metric {
        String name, unit;
        double now = Double.NaN, mean = Double.NaN, sd = Double.NaN;
        int n;
        boolean lowerBetter;
        int dec;
        int status() {                       // 0 in range, 1 watch, 2 outside, -1 no baseline yet
            if (Double.isNaN(now) || n < TodayModel.MIN_BASELINE || Double.isNaN(mean)) return -1;
            double z = Math.abs(now - mean) / sd;
            return z <= 1.0 ? 0 : z <= 2.0 ? 1 : 2;
        }
        String valueText() { return Double.isNaN(now) ? "\u2014" : String.format(Locale.UK, "%." + dec + "f", now) + (unit.isEmpty() ? "" : " " + unit); }
        String rangeText() {
            if (n < TodayModel.MIN_BASELINE || Double.isNaN(mean)) return "usual range after " + TodayModel.MIN_BASELINE + " nights (" + n + " so far)";
            String f = "%." + dec + "f";
            return "usual " + String.format(Locale.UK, f, mean - sd) + " to " + String.format(Locale.UK, f, mean + sd) + (unit.isEmpty() ? "" : " " + unit);
        }
        int color() { int s = status(); return s == 0 ? OK : s == 1 ? AMBER : s == 2 ? RED : GREY; }
    }

    private static void fill(Metric m, List<Double> vals, double sdFloor) {
        m.n = vals.size();
        if (m.n < 2) return;
        double s = 0;
        for (double v : vals) s += v;
        m.mean = s / m.n;
        double q = 0;
        for (double v : vals) q += (v - m.mean) * (v - m.mean);
        m.sd = Math.max(sdFloor, Math.sqrt(q / (m.n - 1)));
    }

    /** The five monitor lines for the latest night against up to 14 earlier nights. */
    static List<Metric> monitor(HealthModel.Data d) {
        List<Metric> out = new ArrayList<>();
        SleepNight.Night ln = d == null || d.day == null ? null : d.day.night;
        if (ln == null) return out;
        List<TodayModel.NightStat> earlier = new ArrayList<>();
        for (TodayModel.NightStat s : d.nights) if (s.bed < ln.bedTs) earlier.add(s);
        Collections.sort(earlier, new Comparator<TodayModel.NightStat>() { @Override public int compare(TodayModel.NightStat a, TodayModel.NightStat b) { return Long.compare(b.bed, a.bed); } });
        if (earlier.size() > TodayModel.MAX_BASELINE) earlier = earlier.subList(0, TodayModel.MAX_BASELINE);
        List<Double> rhr = new ArrayList<>(), hrv = new ArrayList<>(), br = new ArrayList<>(), sp = new ArrayList<>(), sk = new ArrayList<>();
        for (TodayModel.NightStat s : earlier) {
            if (!Double.isNaN(s.hrLow5)) rhr.add(s.hrLow5);
            if (!Double.isNaN(s.rmssd) && s.rmssd > 0) hrv.add(s.rmssd);
            if (!Double.isNaN(s.br)) br.add(s.br);
            if (s.spo2Median > 0) sp.add((double) s.spo2Median);
            if (!Double.isNaN(s.skin)) sk.add(s.skin);
        }
        Metric a = new Metric(); a.name = "Resting heart rate"; a.unit = "bpm"; a.lowerBetter = true; a.dec = 0; a.now = ln.hrLow5; fill(a, rhr, 2.0); out.add(a);
        Metric b = new Metric(); b.name = "Heart rate variability"; b.unit = "ms"; b.dec = 0; b.now = ln.rmssd; fill(b, hrv, 0.1 * (hrv.isEmpty() ? 20 : avg(hrv))); out.add(b);
        Metric c = new Metric(); c.name = "Breathing"; c.unit = "per min"; c.dec = 1; c.now = ln.brMedian; fill(c, br, 0.4); out.add(c);
        Metric e = new Metric(); e.name = "Blood oxygen"; e.unit = "%"; e.dec = 0; e.now = d.day.spo2 != null && d.day.spo2.validWindows > 0 ? d.day.spo2.median : Double.NaN; fill(e, sp, 1.0); out.add(e);
        Metric f = new Metric(); f.name = "Skin temperature"; f.unit = "\u00b0C"; f.dec = 1; f.now = ln.skinMedian; fill(f, sk, 0.3); out.add(f);
        return out;
    }

    private static double avg(List<Double> v) { double s = 0; for (double x : v) s += x; return s / v.size(); }

    /** A horizontal track with the usual range shaded and a dot where last night sits. */
    static final class BandView extends View {
        private final Metric m;
        BandView(Context c, Metric m) { super(c); this.m = m; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), y = h / 2f;
            Paint tr = paint(0x1AFFFFFF);
            cv.drawRoundRect(new RectF(0, y - dp(c, 2), w, y + dp(c, 2)), dp(c, 2), dp(c, 2), tr);
            if (Double.isNaN(m.now)) return;
            if (m.n >= TodayModel.MIN_BASELINE && !Double.isNaN(m.mean)) {
                double lo = m.mean - 3.0 * m.sd, hi = m.mean + 3.0 * m.sd;
                double v = Math.max(lo, Math.min(hi, m.now));
                float x1 = (float) (w * ((m.mean - m.sd) - lo) / (hi - lo)), x2 = (float) (w * ((m.mean + m.sd) - lo) / (hi - lo));
                cv.drawRoundRect(new RectF(x1, y - dp(c, 5), x2, y + dp(c, 5)), dp(c, 5), dp(c, 5), paint(0x3346D8A0));
                float x = (float) (w * (v - lo) / (hi - lo));
                cv.drawCircle(x, y, dp(c, 8), paint(alpha(m.color(), 0.30f)));
                cv.drawCircle(x, y, dp(c, 4.5f), paint(m.color()));
            } else {
                cv.drawCircle(w / 2f, y, dp(c, 4.5f), paint(GREY));
            }
        }
    }
}

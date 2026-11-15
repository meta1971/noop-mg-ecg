package com.noop.mgecg;

// FILE VERSION 0.62.0 (15 Nov 2026): TodayRingsView (Today: Sleep, Recovery and Effort as a hero row, Active, Stress, Fatigue and Heart Watch as four small tiles; same arcs, track, Outfit figures and colours as RingsView); Metric.deltaText().
// FILE VERSION 0.56.0 (14 Nov 2026): RING_COLORS / RING_NAMES gain the seventh ring, Heart Watch; heartColor(tier).
// FILE VERSION 0.55.0 (14 Nov 2026): a fixed colour for each of the six rings (RING_COLORS). 0.54.4: RingsView draws 4 rings to a row and more beneath (bigger rings, centred last row, height from the rows, ringAt() for taps). 0.12.0 (26 Oct): the daily dashboard pieces: three rings, the health monitor against your own baseline. Research only.

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

    /** 0.55.0: one fixed colour per ring, six clearly different hues, so each ring reads apart at a glance. How high or low it is stays in the number and the caption. Order: Recovery, Effort, Active, Sleep, Stress, Fatigue. */
    static final int RING_RECOVERY = 0xFF3DDC84, RING_EFFORT = 0xFF2FA8F0, RING_ACTIVE = 0xFFFF7A33, RING_SLEEP = 0xFF9B8CFF, RING_STRESS = 0xFFFF5C8A, RING_FATIGUE = 0xFFFFD60A;
    /** 0.56.0: the seventh ring, HEART WATCH, takes its colour from its level (usual mint, WATCH amber, CHECK red, not scored grey), see heartColor. */
    static final int RING_HEART = 0xFF33E0D0;
    static final int[] RING_COLORS = { RING_RECOVERY, RING_EFFORT, RING_ACTIVE, RING_SLEEP, RING_STRESS, RING_FATIGUE, RING_HEART };
    static final String[] RING_NAMES = { "Recovery", "Effort", "Active", "Sleep", "Stress", "Fatigue", "Heart Watch" };

    static int heartColor(int tier) { return tier == AlertEngine.CHECK ? RED : tier == AlertEngine.WATCH ? AMBER : tier == AlertEngine.GREEN ? RING_HEART : 0xFF8A93A0; }

    /** Recovery band colour: 67 and over green, 34 to 66 amber, below that red. */
    static int recoveryColor(double r) { return Double.isNaN(r) ? GREY : r >= 67 ? OK : r >= 34 ? AMBER : RED; }

    /** A plan for the day from recovery, as an effort range on the 0 to 21 scale. An estimate, not advice. */
    static String plan(double recovery) {
        if (Double.isNaN(recovery)) return "Recovery appears after four nights are logged. Until then there is no effort target.";
        if (recovery >= 67) return "Recovered. An effort of about 14 to 18 would suit today.";
        if (recovery >= 34) return "Partly recovered. An effort of about 10 to 14 would suit today.";
        return "Not recovered. Keeping effort under about 10 would suit today.";
    }

    /** Rings with a big number, a label and a one-line caption: up to three side by side, four or more four to a row with the last row centred (0.54.4). */
    static final class RingsView extends View {
        private final float[] frac;
        private final int[] col;
        private final String[] big, label, cap;
        RingsView(Context c, float[] frac, int[] col, String[] big, String[] label, String[] cap) {
            super(c);
            this.frac = frac; this.col = col; this.big = big; this.label = label; this.cap = cap;
        }
        // 0.44.0 (decode log 50.6 item 1, A2): the number of one ring pulses (about 120 ms, up to 16 % larger) when a new R marker of the live graph appears
        private static final long PULSE_MS = 120L;
        private int pulseRing = -1; private double lastPulseDue = Double.NaN; private long pulseStart;
        /** ring index whose number pulses with the live heartbeat, or -1 for none */
        void setPulse(int ring) { pulseRing = ring; lastPulseDue = Double.NaN; pulseStart = 0L; invalidate(); }
        private float pulseScale() {
            if (pulseRing < 0) return 1f;
            long now = System.currentTimeMillis();
            double due = Live500.newestBeatDue(now);
            if (!Double.isNaN(due) && (Double.isNaN(lastPulseDue) || due > lastPulseDue + 1.0)) {
                boolean fresh = now - due < 1500.0;                       // a beat first seen long after its time (page start, a stall) does not pulse
                lastPulseDue = due; if (fresh) pulseStart = now;
            }
            long age = now - pulseStart;
            postInvalidateOnAnimation();
            if (pulseStart > 0L && age >= 0L && age < PULSE_MS) return 1f + 0.16f * (float) Math.sin(Math.PI * age / (double) PULSE_MS);
            return 1f;
        }
        // 0.54.4: up to 3 rings stay in one row (as before); 4 or more go 4 to a row, bigger than the 5-in-a-row of 0.54.0, and the last row is centred
        static final int GRID_PER_ROW = 4;
        private boolean grid() { return frac.length > 3; }
        private int perRow() { return grid() ? GRID_PER_ROW : Math.max(1, frac.length); }
        private float gridDia(Context c, float w) { return Math.min(w / GRID_PER_ROW - dp(c, 8), dp(c, 92)); }
        private float rowH(Context c, float w) { return grid() ? dp(c, 4) + gridDia(c, w) + dp(c, 50) : 0f; }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = View.MeasureSpec.getSize(wSpec);
            if (grid() && View.MeasureSpec.getMode(hSpec) != View.MeasureSpec.EXACTLY) {
                int rows = (frac.length + GRID_PER_ROW - 1) / GRID_PER_ROW;
                setMeasuredDimension(w, Math.round(rows * rowH(getContext(), w) + dp(getContext(), 4)));
            } else super.onMeasure(wSpec, hSpec);
        }
        /** Index of the ring under a touch, or -1 for none. */
        int ringAt(float x, float y) {
            int nr = frac.length; float w = getWidth(); if (nr == 0 || w <= 0) return -1;
            if (!grid()) return Math.max(0, Math.min(nr - 1, (int) (x / (w / nr))));
            float rh = rowH(getContext(), w); int row = (int) (y / rh); int rows = (nr + GRID_PER_ROW - 1) / GRID_PER_ROW;
            if (row < 0 || row >= rows) return -1;
            int count = Math.min(GRID_PER_ROW, nr - row * GRID_PER_ROW); float off = (GRID_PER_ROW - count) / 2f;
            int ci = (int) Math.floor(x / (w / GRID_PER_ROW) - off);
            return ci < 0 || ci >= count ? -1 : row * GRID_PER_ROW + ci;
        }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            final float pulse = pulseScale();
            final int nr = Math.max(1, frac.length); final boolean g = grid(); final int per = perRow();
            float w = getWidth(), cellW = w / per;
            float dia = g ? gridDia(c, w) : Math.min(cellW - dp(c, 14), dp(c, 104));
            float sw = dp(c, g ? 8f : 9f), rh = rowH(c, w);
            for (int i = 0; i < nr; i++) {
                int row = i / per, colI = i % per, count = Math.min(per, nr - row * per);
                float off = (per - count) / 2f;
                float cx = cellW * (off + colI + 0.5f), top = dp(c, 4) + row * rh;
                RectF r = new RectF(cx - dia / 2f + sw / 2f, top + sw / 2f, cx + dia / 2f - sw / 2f, top + dia - sw / 2f);
                Paint tr = new Paint(Paint.ANTI_ALIAS_FLAG);
                tr.setStyle(Paint.Style.STROKE); tr.setStrokeWidth(sw); tr.setColor(0x1FFFFFFF); tr.setStrokeCap(Paint.Cap.ROUND);
                cv.drawArc(r, 0, 360, false, tr);
                float f = Math.max(0f, Math.min(1f, frac[i]));
                if (f > 0.004f) {
                    Paint pr = new Paint(tr); pr.setColor(col[i]);
                    cv.drawArc(r, -90, 360f * f, false, pr);
                }
                Paint t = paint(INK); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 300)); t.setTextSize(dp(c, g ? (big[i].length() > 3 ? 19 : 25) : (big[i].length() > 3 ? 22 : 28)) * (i == pulseRing && !"--".equals(big[i]) ? pulse : 1f));
                cv.drawText(big[i], cx, top + dia / 2f + dp(c, g ? 8 : 9), t);
                Paint l = paint(INK); l.setTextAlign(Paint.Align.CENTER); l.setTypeface(font(c, 400)); l.setTextSize(dp(c, g ? 10f : 11f)); l.setLetterSpacing(g ? 0.08f : 0.14f);
                cv.drawText(label[i], cx, top + dia + dp(c, 20), l);
                Paint k = paint(MUT); k.setTextAlign(Paint.Align.CENTER); k.setTypeface(font(c, 400)); k.setTextSize(dp(c, g ? 9.5f : 10.5f));
                cv.drawText(cap[i], cx, top + dia + dp(c, 36), k);
            }
        }
    }

    /**
     * 0.62.0: the Today rings as a hero row (Sleep, Recovery, Effort; the middle one larger) and four small tiles under it
     * (Active, Stress, Fatigue, Heart Watch). The arrays are in ring order (0 Recovery, 1 Effort, 2 Active, 3 Sleep, 4 Stress,
     * 5 Fatigue, 6 Heart Watch) and ringAt() returns that index, so the taps and the panels are the ones of RingsView.
     * Same arcs (track 0x1FFFFFFF, round caps, from the top), Outfit light figures, caps labels and colours as RingsView.
     */
    static final class TodayRingsView extends View {
        static final int[] HERO = {3, 0, 1};
        static final int[] TILES = {2, 4, 5, 6};
        private final float[] frac;
        private final int[] col;
        private final String[] big, label, cap;
        TodayRingsView(Context c, float[] frac, int[] col, String[] big, String[] label, String[] cap) {
            super(c);
            this.frac = frac; this.col = col; this.big = big; this.label = label; this.cap = cap;
        }
        /**
         * Geometry in dp for a view wDp wide. Entry i (ring order) = {left, top, right, bottom, ringCentreX, ringCentreY, ringDiameter, strokeWidth, kind}
         * with kind 0 = side hero ring, 1 = middle hero ring, 2 = tile; entry 7 = {total height}. Pure arithmetic, checked off the phone (X21).
         */
        static float[][] layout(float wDp) {
            float[][] g = new float[8][];
            float sideD = Math.min(wDp * 0.27f, 92f), midD = Math.min(wDp * 0.36f, 120f);
            float heroH = 4f + midD + 44f, cellW = wDp / 3f;
            for (int k = 0; k < 3; k++) {
                float d = k == 1 ? midD : sideD;
                g[HERO[k]] = new float[] {cellW * k, 0f, cellW * (k + 1), heroH, cellW * (k + 0.5f), 4f + midD / 2f, d, k == 1 ? 10f : 8f, k == 1 ? 1f : 0f};
            }
            float tileTop = heroH + 8f, gap = 8f, tw = (wDp - 3f * gap) / 4f, th = 104f;
            for (int k = 0; k < 4; k++) {
                float l = k * (tw + gap);
                g[TILES[k]] = new float[] {l, tileTop, l + tw, tileTop + th, l + tw / 2f, tileTop + 10f + 19f, 38f, 5f, 2f};
            }
            g[7] = new float[] {tileTop + th + 4f};
            return g;
        }
        private float density() { return getContext().getResources().getDisplayMetrics().density; }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = View.MeasureSpec.getSize(wSpec);
            float wDp = w / density();
            setMeasuredDimension(w, Math.round(layout(wDp)[7][0] * density()));
        }
        /** Index (ring order) of the ring or tile under a touch, or -1 for none. */
        int ringAt(float x, float y) {
            float dn = density(), wDp = getWidth() / dn;
            if (wDp <= 0f) return -1;
            float[][] g = layout(wDp);
            float xd = x / dn, yd = y / dn;
            for (int i = 0; i < 7; i++) if (xd >= g[i][0] && xd < g[i][2] && yd >= g[i][1] && yd < g[i][3]) return i;
            return -1;
        }
        private static String fit(String s, Paint p, float maxPx) {
            if (s == null) return "";
            if (p.measureText(s) <= maxPx) return s;
            int n = s.length();
            while (n > 1 && p.measureText(s.substring(0, n) + "\u2026") > maxPx) n--;
            return s.substring(0, n).trim() + "\u2026";
        }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float dn = density(), wDp = getWidth() / dn;
            if (wDp <= 0f || frac.length < 7) return;
            float[][] g = layout(wDp);
            for (int i = 0; i < 7; i++) {
                float[] b = g[i];
                boolean tile = b[8] == 2f;
                float cx = b[4] * dn, cy = b[5] * dn, d = b[6] * dn, sw = b[7] * dn;
                if (tile) {
                    Paint bg = paint(CARD);
                    cv.drawRoundRect(new RectF(b[0] * dn, b[1] * dn, b[2] * dn, b[3] * dn), dp(c, 16), dp(c, 16), bg);
                    Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
                    st.setStyle(Paint.Style.STROKE); st.setStrokeWidth(Math.max(1f, dp(c, 1))); st.setColor(0x0FFFFFFF);
                    cv.drawRoundRect(new RectF(b[0] * dn, b[1] * dn, b[2] * dn, b[3] * dn), dp(c, 16), dp(c, 16), st);
                }
                RectF r = new RectF(cx - d / 2f + sw / 2f, cy - d / 2f + sw / 2f, cx + d / 2f - sw / 2f, cy + d / 2f - sw / 2f);
                Paint tr = new Paint(Paint.ANTI_ALIAS_FLAG);
                tr.setStyle(Paint.Style.STROKE); tr.setStrokeWidth(sw); tr.setColor(0x1FFFFFFF); tr.setStrokeCap(Paint.Cap.ROUND);
                cv.drawArc(r, 0, 360, false, tr);
                float f = Math.max(0f, Math.min(1f, frac[i]));
                if (f > 0.004f) {
                    Paint pr = new Paint(tr); pr.setColor(col[i]);
                    cv.drawArc(r, -90, 360f * f, false, pr);
                }
                float cellCx = (b[0] + b[2]) / 2f * dn;
                if (!tile) {
                    boolean mid = b[8] == 1f;
                    float ts = dp(c, mid ? (big[i].length() > 3 ? 24 : 32) : (big[i].length() > 3 ? 20 : 26));
                    Paint t = paint(INK); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 300)); t.setTextSize(ts);
                    cv.drawText(big[i], cx, cy + ts * 0.32f, t);
                    float ringBottom = (4f + layoutMid(wDp) ) * dn;
                    Paint l = paint(INK); l.setTextAlign(Paint.Align.CENTER); l.setTypeface(font(c, 400)); l.setTextSize(dp(c, 11f)); l.setLetterSpacing(0.14f);
                    cv.drawText(label[i], cellCx, ringBottom + dp(c, 20), l);
                    Paint k = paint(MUT); k.setTextAlign(Paint.Align.CENTER); k.setTypeface(font(c, 400)); k.setTextSize(dp(c, 10.5f));
                    cv.drawText(fit(cap[i], k, (b[2] - b[0]) * dn - dp(c, 6)), cellCx, ringBottom + dp(c, 36), k);
                } else {
                    float tw = (b[2] - b[0]) * dn - dp(c, 8);
                    String v = big[i];
                    float vs = dp(c, v.length() > 5 ? 12 : 15);
                    Paint t = paint(INK); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 400)); t.setTextSize(vs);
                    float y = b[1] * dn + dp(c, 10 + 38 + 18);
                    cv.drawText(fit(v, t, tw), cellCx, y, t);
                    String lb = "HEART WATCH".equals(label[i]) ? "HEART" : label[i];
                    Paint l = paint(INK); l.setTextAlign(Paint.Align.CENTER); l.setTypeface(font(c, 400)); l.setTextSize(dp(c, 9.5f)); l.setLetterSpacing(0.08f);
                    cv.drawText(fit(lb, l, tw), cellCx, y + dp(c, 16), l);
                    Paint k = paint(MUT); k.setTextAlign(Paint.Align.CENTER); k.setTypeface(font(c, 400)); k.setTextSize(dp(c, 9f));
                    cv.drawText(fit(cap[i], k, tw), cellCx, y + dp(c, 29), k);
                }
            }
        }
        private static float layoutMid(float wDp) { return Math.min(wDp * 0.36f, 120f); }
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
        /** 0.62.0: how far last night sits from your usual, e.g. "\u25B2 5" or "\u25BC 2"; "usual" when it rounds to nothing; empty without a baseline. */
        String deltaText() {
            if (status() < 0) return "";
            double d = now - mean;
            String f = "%." + dec + "f";
            String mag = String.format(Locale.UK, f, Math.abs(d));
            if (Double.parseDouble(mag) == 0.0) return "usual";
            return (d > 0 ? "\u25B2 " : "\u25BC ") + mag;
        }
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

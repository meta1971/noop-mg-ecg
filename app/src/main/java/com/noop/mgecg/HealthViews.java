package com.noop.mgecg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The drawing for the heart-first screens: a glowing orb with a tick dial, the night laid round a ring, smooth charts, and the
 * glass navigation bar. Plain Canvas, no libraries. Glows are layered translucent strokes and radial gradients rather than blur
 * filters, so they look the same on every phone from Android 8 up.
 */
final class HealthViews {
    private HealthViews() {}

    static final int BG = 0xFF07090C, INK = 0xFFF2F6FA, MUT = 0xFF98A3B3, CARD = 0xFF0D1116;
    static final int BLUE = 0xFF2FA8F0, VIOLET = 0xFF8E93E8, ROSE = 0xFFFF8CA2, TEAL = 0xFF4EE3CB, GOLD = 0xFFF2B35B, GREEN = 0xFF5CE5A0;
    static final int[] STAGE = {0xFFF2B35B, 0xFF9AA0F0, 0xFF6A73F2, 0xFF38BDF2};          // wake, light, deep, rem

    static float dp(Context c, float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }

    private static Typeface[] fonts = new Typeface[4];

    /** Outfit at a variable weight (200, 300, 400, 500, 600 map to slots); falls back to the system's light and regular. */
    static Typeface font(Context c, int weight) {
        int slot = weight <= 200 ? 0 : weight <= 300 ? 1 : weight <= 450 ? 2 : 3;
        if (fonts[slot] == null) {
            int[] w = {200, 300, 400, 600};
            try {
                fonts[slot] = new Typeface.Builder(c.getAssets(), "fonts/Outfit.ttf").setFontVariationSettings("'wght' " + w[slot]).build();
            } catch (Throwable t) {
                fonts[slot] = null;
            }
            if (fonts[slot] == null) {
                try {
                    java.io.File ff = OutfitData.file(c);
                    if (ff != null) fonts[slot] = new Typeface.Builder(ff).setFontVariationSettings("'wght' " + w[slot]).build();
                } catch (Throwable t) {
                    fonts[slot] = null;
                }
            }
            if (fonts[slot] == null) fonts[slot] = Typeface.create(slot < 2 ? "sans-serif-light" : slot == 2 ? "sans-serif" : "sans-serif-medium", Typeface.NORMAL);
            if (fonts[slot] == null) fonts[slot] = Typeface.DEFAULT;
        }
        return fonts[slot];
    }

    static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    static int alpha(int color, float a) {
        return (Math.round(255 * a) << 24) | (color & 0x00FFFFFF);
    }

    static GradientDrawable glass(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, Math.round(dp(c, 1))), 0x0FFFFFFF);
        return g;
    }

    /** Catmull-Rom spline through the points as a cubic path. Null entries break the line. */
    static Path smooth(List<float[]> pts) {
        Path path = new Path();
        List<float[]> seg = new ArrayList<>();
        for (int k = 0; k <= pts.size(); k++) {
            float[] p = k < pts.size() ? pts.get(k) : null;
            if (p != null) { seg.add(p); continue; }
            if (seg.size() >= 2) {
                path.moveTo(seg.get(0)[0], seg.get(0)[1]);
                for (int i = 0; i < seg.size() - 1; i++) {
                    float[] p0 = i > 0 ? seg.get(i - 1) : seg.get(i), p1 = seg.get(i), p2 = seg.get(i + 1), p3 = i + 2 < seg.size() ? seg.get(i + 2) : seg.get(i + 1);
                    path.cubicTo(p1[0] + (p2[0] - p0[0]) / 6f, p1[1] + (p2[1] - p0[1]) / 6f, p2[0] - (p3[0] - p1[0]) / 6f, p2[1] - (p3[1] - p1[1]) / 6f, p2[0], p2[1]);
                }
            }
            seg.clear();
        }
        return path;
    }

    // ------------------------------------------------------------------ orb
    /** Glossy sphere, a halo, and a dial of ticks of which the first [frac] are lit. */
    static final class OrbView extends View {
        private final String big, sub;
        private final float frac;
        private final int accent, c1, c2, c3;
        OrbView(Context c, String big, String sub, float frac, int accent, int c1, int c2, int c3) {
            super(c);
            this.big = big; this.sub = sub; this.frac = Math.max(0f, Math.min(1f, frac));
            this.accent = accent; this.c1 = c1; this.c2 = c2; this.c3 = c3;
        }
        @Override protected void onMeasure(int w, int h) {
            int s = MeasureSpec.getSize(w);
            setMeasuredDimension(s, s);
        }
        @Override protected void onDraw(Canvas cv) {
            float size = getWidth(), cx = size / 2f, cy = size / 2f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(cx, cy, size * 0.47f, new int[] {alpha(c2, 0f), alpha(c2, 0.45f), alpha(c2, 0f)}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, size * 0.47f, p);
            Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
            tick.setStrokeCap(Paint.Cap.ROUND);
            int n = 64;
            for (int k = 0; k < n; k++) {
                double a = -Math.PI / 2 + 2 * Math.PI * k / n;
                boolean major = k % 8 == 0, on = k / (float) n <= frac;
                float r0 = size * 0.405f, r1 = size * (major ? 0.475f : 0.455f);
                tick.setColor(on ? accent : 0x34FFFFFF);
                tick.setStrokeWidth(dp(getContext(), major ? 2.2f : 1.5f));
                cv.drawLine(cx + (float) (r0 * Math.cos(a)), cy + (float) (r0 * Math.sin(a)), cx + (float) (r1 * Math.cos(a)), cy + (float) (r1 * Math.sin(a)), tick);
            }
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(0x14FFFFFF);
            ring.setStrokeWidth(dp(getContext(), 1));
            cv.drawCircle(cx, cy, size * 0.385f, ring);
            float R = size * 0.295f;
            Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
            s.setShader(new RadialGradient(cx - R * 0.28f, cy - R * 0.4f, R * 1.55f, new int[] {c1, c2, c3}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, R, s);
            Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
            hl.setShader(new RadialGradient(cx - size * 0.05f, cy - size * 0.1f, size * 0.2f, new int[] {0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            cv.save();
            cv.clipPath(circle(cx, cy, R));
            cv.drawCircle(cx - size * 0.05f, cy - size * 0.1f, size * 0.2f, hl);
            cv.restore();
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFFFFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(font(getContext(), 200));
            t.setTextSize(size * 0.215f);
            cv.drawText(big, cx, cy + size * 0.075f, t);
            t.setTypeface(font(getContext(), 600));
            t.setTextSize(dp(getContext(), 12));
            t.setLetterSpacing(0.22f);
            t.setColor(0xEBFFFFFF);
            cv.drawText(sub, cx, cy + size * 0.125f + dp(getContext(), 4), t);
            // marker at the lit end
            double ang = -Math.PI / 2 + 2 * Math.PI * frac;
            float px = cx + (float) (size * 0.385f * Math.cos(ang)), py = cy + (float) (size * 0.385f * Math.sin(ang));
            Path tri = new Path();
            tri.moveTo(0, -dp(getContext(), 7));
            tri.lineTo(dp(getContext(), 5.5f), dp(getContext(), 4));
            tri.lineTo(-dp(getContext(), 5.5f), dp(getContext(), 4));
            tri.close();
            cv.save();
            cv.translate(px, py);
            cv.rotate((float) Math.toDegrees(ang) + 90f);
            cv.drawPath(tri, paint(0xFFFFFFFF));
            cv.restore();
        }
        private Path circle(float cx, float cy, float r) { Path q = new Path(); q.addCircle(cx, cy, r, Path.Direction.CW); return q; }
    }

    // ------------------------------------------------------------------ stage ring
    /** Open 270-degree ring with the night's stages in time order. Segments: {stage, from 0..1, to 0..1, sure 1/0}. */
    static final class StageRingView extends View {
        private final List<float[]> segs;
        private final String big, sub;
        StageRingView(Context c, List<float[]> segs, String big, String sub) { super(c); this.segs = segs; this.big = big; this.sub = sub; }
        @Override protected void onMeasure(int w, int h) { int s = MeasureSpec.getSize(w); setMeasuredDimension(s, s); }
        @Override protected void onDraw(Canvas cv) {
            float size = getWidth(), cx = size / 2f, cy = size / 2f, r = size * 0.40f, sw = dp(getContext(), 15f);
            RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(sw);
            p.setColor(0x10FFFFFF);
            cv.drawArc(box, 135f, 270f, false, p);
            float capDeg = (float) Math.toDegrees((sw / 2f) / r), gapDeg = 1.1f;
            for (int pass = 0; pass < 4; pass++) {
                for (float[] s : segs) {
                    float a0 = 135f + 270f * s[1] + capDeg + gapDeg, a1 = 135f + 270f * s[2] - capDeg - gapDeg;
                    if (a1 - a0 < 0.8f) continue;
                    int col = STAGE[(int) s[0]];
                    boolean sure = s[3] > 0.5f;
                    if (pass < 3) {
                        p.setStrokeWidth(sw + dp(getContext(), 4 + 5 * (2 - pass)));
                        p.setColor(alpha(col, (sure ? 0.10f : 0.05f) * (pass + 1)));
                    } else {
                        p.setStrokeWidth(sw);
                        p.setColor(alpha(col, sure ? 1f : 0.62f));
                    }
                    cv.drawArc(box, a0, a1 - a0, false, p);
                }
            }
            Paint dot = paint(0xFFFFFFFF);
            double endA = Math.toRadians(135 + 270 * (segs.isEmpty() ? 0 : segs.get(segs.size() - 1)[2]));
            cv.drawCircle(cx + (float) (r * Math.cos(endA)), cy + (float) (r * Math.sin(endA)), dp(getContext(), 3.5f), dot);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextAlign(Paint.Align.CENTER);
            t.setColor(0xFFFFFFFF);
            t.setTypeface(font(getContext(), 200));
            t.setTextSize(dp(getContext(), 50));
            cv.drawText(big, cx, cy + dp(getContext(), 12), t);
            t.setTypeface(font(getContext(), 600));
            t.setTextSize(dp(getContext(), 12));
            t.setLetterSpacing(0.26f);
            t.setColor(MUT);
            cv.drawText(sub, cx, cy + dp(getContext(), 40), t);
        }
    }

    // ------------------------------------------------------------------ heart-rate chart
    static final class HrChartView extends View {
        private final long[] t;
        private final float[] v;
        private final long bed, wake;
        private final boolean labels;
        HrChartView(Context c, long[] t, float[] v, long bed, long wake, boolean labels) { super(c); this.t = t; this.v = v; this.bed = bed; this.wake = wake; this.labels = labels; }
        @Override protected void onDraw(Canvas cv) {
            if (t.length < 3) return;
            Context c = getContext();
            float w = getWidth(), h = getHeight(), ch = h - (labels ? dp(c, 16) : 0);
            long t0 = t[0] - 150, t1 = t[t.length - 1] + 150;
            float ymin = 45f, ymax = 85f;
            float lo = 1e9f, hi = -1e9f;
            for (float x : v) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
            if (lo < ymin + 2) ymin = (float) Math.floor((lo - 3) / 5f) * 5f;
            if (hi > ymax - 2) ymax = (float) Math.ceil((hi + 3) / 5f) * 5f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (wake > bed) {
                float a = Math.max(0, w * (bed - t0) / (float) (t1 - t0)), b = Math.min(w, w * (wake - t0) / (float) (t1 - t0));
                if (b > a) { p.setColor(0x1A9B8CFF); cv.drawRoundRect(new RectF(a, 0, b, ch), dp(c, 10), dp(c, 10), p); }
            }
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setColor(0x12FFFFFF); g.setStrokeWidth(dp(c, 1));
            tx.setColor(MUT); tx.setTextSize(dp(c, 10)); tx.setTextAlign(Paint.Align.RIGHT); tx.setTypeface(font(c, 400));
            for (float lv = (float) Math.ceil(ymin / 10f) * 10f; lv < ymax; lv += 10f) {
                float y = ch * (1 - (lv - ymin) / (ymax - ymin));
                cv.drawLine(0, y, w, y, g);
                if (labels) cv.drawText(String.valueOf((int) lv), w, y - dp(c, 3), tx);
            }
            List<float[]> pts = new ArrayList<>();
            long prev = 0;
            for (int i = 0; i < t.length; i++) {
                if (i > 0 && t[i] - prev > 900L) pts.add(null);
                pts.add(new float[] {w * (t[i] - t0) / (float) (t1 - t0), ch * (1 - (Math.min(Math.max(v[i], ymin), ymax) - ymin) / (ymax - ymin))});
                prev = t[i];
            }
            Path line = smooth(pts);
            Path area = new Path(line);
            float[] first = null, last = null;
            for (float[] q : pts) if (q != null) { if (first == null) first = q; last = q; }
            if (first != null) {
                area.lineTo(last[0], ch);
                area.lineTo(first[0], ch);
                area.close();
                Paint fa = new Paint(Paint.ANTI_ALIAS_FLAG);
                fa.setShader(new LinearGradient(0, 0, 0, ch, 0x57FF7A93, 0x00FF7A93, Shader.TileMode.CLAMP));
                cv.drawPath(area, fa);
            }
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStyle(Paint.Style.STROKE);
            ln.setStrokeCap(Paint.Cap.ROUND);
            ln.setStrokeJoin(Paint.Join.ROUND);
            ln.setShader(new LinearGradient(0, 0, w, 0, 0xFFFF7A93, 0xFFFFC36B, Shader.TileMode.CLAMP));
            ln.setStrokeWidth(dp(c, 6)); ln.setAlpha(60);
            cv.drawPath(line, ln);
            ln.setStrokeWidth(dp(c, 2.4f)); ln.setAlpha(255);
            cv.drawPath(line, ln);
            int lowI = 0;
            for (int i = 1; i < v.length; i++) if (v[i] < v[lowI]) lowI = i;
            float lx = w * (t[lowI] - t0) / (float) (t1 - t0), ly = ch * (1 - (Math.min(Math.max(v[lowI], ymin), ymax) - ymin) / (ymax - ymin));
            cv.drawCircle(lx, ly, dp(c, 7), paint(0x46FF7A93));
            cv.drawCircle(lx, ly, dp(c, 3), paint(0xFFFFFFFF));
            if (labels) {
                tx.setTextAlign(Paint.Align.LEFT);
                SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.UK);
                cv.drawText(f.format(new Date(t[0] * 1000L)), 0, h - dp(c, 3), tx);
                tx.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(f.format(new Date(t[t.length - 1] * 1000L)), w, h - dp(c, 3), tx);
            }
        }
    }

    // ------------------------------------------------------------------ stage rows
    /** Four rows (awake, REM, light, deep) of rounded blocks; dashed and faint where the two methods disagree. */
    static final class StageFlowView extends View {
        private final int[] bins;
        private final boolean[] sure;
        private final long start;
        StageFlowView(Context c, int[] bins, boolean[] sure, long start) { super(c); this.bins = bins; this.sure = sure; this.start = start; }
        @Override protected void onDraw(Canvas cv) {
            if (bins.length == 0) return;
            Context c = getContext();
            float w = getWidth(), h = getHeight(), lab = dp(c, 40), pw = w - lab, rh = (h - dp(c, 16)) / 4f, cw = pw / bins.length;
            int[] order = {HealthModel.WAKE, HealthModel.REM, HealthModel.LIGHT, HealthModel.DEEP};
            String[] names = {"AWAKE", "REM", "LIGHT", "DEEP"};
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setColor(0x10FFFFFF); g.setStrokeWidth(dp(c, 1));
            tx.setColor(MUT); tx.setTextSize(dp(c, 9)); tx.setTypeface(font(c, 600)); tx.setLetterSpacing(0.12f);
            for (int r = 0; r < 4; r++) {
                cv.drawLine(lab, r * rh + rh / 2, w, r * rh + rh / 2, g);
                cv.drawText(names[r], 0, r * rh + rh / 2 + dp(c, 3), tx);
            }
            int i = 0;
            while (i < bins.length) {
                int st = bins[i];
                if (st < 0) { i++; continue; }
                boolean sr = sure[i];
                int j = i;
                while (j + 1 < bins.length && bins[j + 1] == st && sure[j + 1] == sr) j++;
                int row = 0;
                for (int r = 0; r < 4; r++) if (order[r] == st) row = r;
                RectF rc = new RectF(lab + i * cw, row * rh + rh * 0.16f, lab + (j + 1) * cw - dp(c, 0.8f), row * rh + rh * 0.84f);
                float rad = Math.min(dp(c, 7), rh * 0.34f);
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(null);
                p.setColor(alpha(STAGE[st], sr ? 1f : 0.30f));
                cv.drawRoundRect(rc, rad, rad, p);
                if (!sr) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(c, 1));
                    p.setColor(alpha(STAGE[st], 0.9f));
                    p.setPathEffect(new DashPathEffect(new float[] {dp(c, 3), dp(c, 3)}, 0));
                    cv.drawRoundRect(rc, rad, rad, p);
                }
                i = j + 1;
            }
            tx.setTypeface(font(c, 400)); tx.setLetterSpacing(0f); tx.setTextSize(dp(c, 10)); tx.setTextAlign(Paint.Align.LEFT);
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.UK);
            cv.drawText(f.format(new Date(start * 1000L)), lab, h - dp(c, 2), tx);
            tx.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(f.format(new Date((start + bins.length * 300L) * 1000L)), w, h - dp(c, 2), tx);
        }
    }

    // ------------------------------------------------------------------ SpO2
    static final class SpoView extends View {
        private final List<Spo2.Window> ws;
        SpoView(Context c, List<Spo2.Window> ws) { super(c); this.ws = ws; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), ch = h - dp(c, 16);
            long t0 = Long.MAX_VALUE, t1 = 0;
            List<Spo2.Window> use = new ArrayList<>();
            for (Spo2.Window x : ws) if (x.median >= 0 && x.valid >= Spo2.MIN_VALID_SECONDS) { use.add(x); t0 = Math.min(t0, x.start); t1 = Math.max(t1, x.end); }
            if (use.size() < 2 || t1 <= t0) return;
            float lo = 90f, hi = 100f;
            Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG);
            dash.setStyle(Paint.Style.STROKE); dash.setColor(0x96F2B35B); dash.setStrokeWidth(dp(c, 1));
            dash.setPathEffect(new DashPathEffect(new float[] {dp(c, 3), dp(c, 5)}, 0));
            float y94 = ch * (1 - (94f - lo) / (hi - lo));
            cv.drawLine(0, y94, w, y94, dash);
            tx.setColor(GOLD); tx.setTextSize(dp(c, 10)); tx.setTextAlign(Paint.Align.RIGHT); tx.setTypeface(font(c, 400));
            cv.drawText("94", w, y94 - dp(c, 3), tx);
            List<float[]> pts = new ArrayList<>();
            for (Spo2.Window x : use) pts.add(new float[] {dp(c, 8) + (w - dp(c, 16)) * (x.start - t0) / (float) (t1 - t0), ch * (1 - (Math.max(lo, Math.min(hi, x.median)) - lo) / (hi - lo))});
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStyle(Paint.Style.STROKE); ln.setColor(0x6E6EC8EB); ln.setStrokeWidth(dp(c, 1.3f));
            cv.drawPath(smooth(pts), ln);
            for (float[] p : pts) {
                cv.drawCircle(p[0], p[1], dp(c, 6.5f), paint(0x2846E0C8));
                cv.drawCircle(p[0], p[1], dp(c, 3.2f), paint(0xFFFFFFFF));
            }
            tx.setColor(MUT);
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.UK);
            tx.setTextAlign(Paint.Align.LEFT);
            cv.drawText(f.format(new Date(t0 * 1000L)), 0, h - dp(c, 3), tx);
            tx.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(f.format(new Date(t1 * 1000L)), w, h - dp(c, 3), tx);
        }
    }

    // ------------------------------------------------------------------ last seven nights
    static final class WeekBarsView extends View {
        private final double[] hours;
        private final long[] bed;
        private final double goal;
        WeekBarsView(Context c, double[] hours, long[] bed, double goal) { super(c); this.hours = hours; this.bed = bed; this.goal = goal; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), gap = dp(c, 8), bw = (w - 6 * gap) / 7f, base = h - dp(c, 22), maxH = base - dp(c, 24);
            double top = Math.max(goal, 8.0);
            for (double x : hours) if (!Double.isNaN(x)) top = Math.max(top, x);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG);
            tx.setColor(MUT); tx.setTextSize(dp(c, 12)); tx.setTextAlign(Paint.Align.CENTER); tx.setTypeface(font(c, 400));
            SimpleDateFormat day = new SimpleDateFormat("EEEEE", Locale.UK);
            for (int i = 0; i < 7; i++) {
                float x0 = i * (bw + gap);
                if (Double.isNaN(hours[i])) {
                    float bh = maxH * 0.58f;
                    p.setStyle(Paint.Style.FILL); p.setColor(0x09FFFFFF); p.setShader(null);
                    cv.drawRoundRect(new RectF(x0, base - bh, x0 + bw, base), dp(c, 12), dp(c, 12), p);
                    p.setStyle(Paint.Style.STROKE); p.setColor(0x1AFFFFFF); p.setStrokeWidth(dp(c, 1));
                    cv.drawRoundRect(new RectF(x0, base - bh, x0 + bw, base), dp(c, 12), dp(c, 12), p);
                } else {
                    float bh = (float) (maxH * hours[i] / top);
                    p.setStyle(Paint.Style.FILL);
                    p.setShader(new LinearGradient(0, base - bh, 0, base, 0xFFB9BCF6, 0xFF6E73D8, Shader.TileMode.CLAMP));
                    cv.drawRoundRect(new RectF(x0, base - bh, x0 + bw, base), dp(c, 12), dp(c, 12), p);
                    p.setShader(null);
                    if (i == 6) {
                        p.setColor(0x488E93E8);
                        RectF chip = new RectF(x0 + bw - dp(c, 46), base - bh - dp(c, 24), x0 + bw, base - bh - dp(c, 4));
                        cv.drawRoundRect(chip, dp(c, 10), dp(c, 10), p);
                        Paint ct = new Paint(tx);
                        ct.setColor(INK); ct.setTextSize(dp(c, 11.5f)); ct.setTypeface(font(c, 600));
                        cv.drawText(String.format(Locale.UK, "%.1f h", hours[i]), chip.centerX(), chip.centerY() + dp(c, 4), ct);
                    }
                }
                cv.drawText(day.format(new Date(bed[i] * 1000L)), x0 + bw / 2f, h - dp(c, 4), tx);
            }
            Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
            dash.setStyle(Paint.Style.STROKE); dash.setColor(0x38FFFFFF); dash.setStrokeWidth(dp(c, 1));
            dash.setPathEffect(new DashPathEffect(new float[] {dp(c, 4), dp(c, 4)}, 0));
            float gy = base - (float) (maxH * goal / top);
            cv.drawLine(0, gy, w, gy, dash);
        }
    }

    // ------------------------------------------------------------------ zones
    static final class ZoneBarView extends View {
        private final long[] sec;
        ZoneBarView(Context c, long[] sec) { super(c); this.sec = sec; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), x = 0;
            int[] col = {0xFF6A73F2, 0xFF4CB4FF, 0xFF2EE0B8, 0xFFF2B35B, 0xFFFF6B6B};
            long tot = 0;
            for (long s : sec) tot += s;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x14FFFFFF);
            cv.drawRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, p);
            if (tot == 0) return;
            float gap = dp(c, 3);
            int shown = 0;
            for (long s : sec) if (s > 0) shown++;
            float usable = w - gap * Math.max(0, shown - 1);
            for (int i = 0; i < 5; i++) {
                if (sec[i] == 0) continue;
                float wi = usable * sec[i] / tot;
                p.setColor(col[i]);
                cv.drawRoundRect(new RectF(x, 0, x + wi, h), h / 2f, h / 2f, p);
                x += wi + gap;
            }
        }
    }

    // ------------------------------------------------------------------ navigation
    /** The glass capsule: Today, Heart, a glowing ECG button in the middle, Rest, You. The active tab is a pill in the screen's colour. */
    static final class NavBar extends View {
        interface Listener { void onTab(int tab); }
        static final String[] NAMES = {"Today", "Heart", "ECG", "Rest", "You"};
        static final int[] ACCENT = {BLUE, ROSE, TEAL, VIOLET, GOLD};
        private int active;
        private final Listener listener;
        private final float[] cxs = new float[5];
        NavBar(Context c, int active, Listener l) { super(c); this.active = active; this.listener = l; }
        void setActive(int a) { active = a; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xEC10141B);
            cv.drawRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, p);
            p.setStyle(Paint.Style.STROKE); p.setColor(0x12FFFFFF); p.setStrokeWidth(dp(c, 1));
            cv.drawRoundRect(new RectF(dp(c, 0.5f), dp(c, 0.5f), w - dp(c, 0.5f), h - dp(c, 0.5f)), h / 2f, h / 2f, p);
            float[] wid = new float[5];
            float sum = 0;
            for (int i = 0; i < 5; i++) { wid[i] = dp(c, i == 2 ? 50 : (i == active ? 112 : 44)); sum += wid[i]; }
            float pad = dp(c, 12), gap = (w - 2 * pad - sum) / 4f, x = pad, cy = h / 2f;
            for (int i = 0; i < 5; i++) {
                cxs[i] = x + wid[i] / 2f;
                if (i == 2) {
                    Paint gl = new Paint(Paint.ANTI_ALIAS_FLAG);
                    gl.setShader(new RadialGradient(cxs[i], cy, dp(c, 36), new int[] {0x732FA8F0, 0x002FA8F0}, null, Shader.TileMode.CLAMP));
                    cv.drawCircle(cxs[i], cy, dp(c, 36), gl);
                    Paint b = new Paint(Paint.ANTI_ALIAS_FLAG);
                    b.setShader(new LinearGradient(cxs[i] - dp(c, 20), cy - dp(c, 25), cxs[i] + dp(c, 20), cy + dp(c, 25), 0xFF58C4FF, 0xFF1788E8, Shader.TileMode.CLAMP));
                    cv.drawCircle(cxs[i], cy, dp(c, 25), b);
                    icon(cv, 2, cxs[i], cy, dp(c, 24), 0xFF03141F);
                } else if (i == active) {
                    Paint pill = paint(ACCENT[i]);
                    cv.drawRoundRect(new RectF(cxs[i] - wid[i] / 2f, cy - dp(c, 23), cxs[i] + wid[i] / 2f, cy + dp(c, 23)), dp(c, 23), dp(c, 23), pill);
                    int fg = (i == 3) ? 0xFF0A0D22 : 0xFF06121C;
                    icon(cv, i, cxs[i] - dp(c, 34), cy, dp(c, 19), fg);
                    Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                    t.setColor(fg); t.setTextSize(dp(c, 13.5f)); t.setTypeface(font(c, 600));
                    cv.drawText(NAMES[i], cxs[i] - dp(c, 20), cy + dp(c, 5), t);
                } else {
                    icon(cv, i, cxs[i], cy, dp(c, 21), MUT);
                }
                x += wid[i] + gap;
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                int best = 0;
                float bd = 1e9f;
                for (int i = 0; i < 5; i++) { float d = Math.abs(e.getX() - cxs[i]); if (d < bd) { bd = d; best = i; } }
                if (best != active) { active = best; invalidate(); }
                listener.onTab(best);
                performClick();
            }
            return true;
        }
        @Override public boolean performClick() { return super.performClick(); }
    }

    /** Small stroke icons: 0 sun, 1 heart, 2 pulse, 3 moon, 4 person. */
    static void icon(Canvas cv, int kind, float cx, float cy, float size, int col) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE); p.setColor(col); p.setStrokeWidth(size * 0.085f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        float k = size / 24f;
        switch (kind) {
            case 0:
                cv.drawCircle(cx, cy, 4f * k, p);
                for (int a = 0; a < 360; a += 45) {
                    double r = Math.toRadians(a);
                    cv.drawLine(cx + (float) (6.5 * k * Math.cos(r)), cy + (float) (6.5 * k * Math.sin(r)), cx + (float) (9 * k * Math.cos(r)), cy + (float) (9 * k * Math.sin(r)), p);
                }
                break;
            case 1: {
                Path h = new Path();
                h.moveTo(cx, cy + 7.5f * k);
                h.cubicTo(cx - 12f * k, cy - 0.5f * k, cx - 6f * k, cy - 9f * k, cx, cy - 3.5f * k);
                h.cubicTo(cx + 6f * k, cy - 9f * k, cx + 12f * k, cy - 0.5f * k, cx, cy + 7.5f * k);
                cv.drawPath(h, p);
                break;
            }
            case 2: {
                Path z = new Path();
                z.moveTo(cx - 9f * k, cy);
                z.lineTo(cx - 5f * k, cy);
                z.lineTo(cx - 3f * k, cy - 6f * k);
                z.lineTo(cx + 1f * k, cy + 6f * k);
                z.lineTo(cx + 3f * k, cy);
                z.lineTo(cx + 9f * k, cy);
                cv.drawPath(z, p);
                break;
            }
            case 3: {
                Path m = new Path();
                m.addCircle(cx, cy, 7.5f * k, Path.Direction.CW);
                cv.drawPath(m, p);
                Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
                q.setColor(0xFF10141B);
                cv.drawCircle(cx + 4f * k, cy - 3.5f * k, 6.5f * k, q);
                break;
            }
            default:
                cv.drawCircle(cx, cy - 4f * k, 3.6f * k, p);
                cv.drawArc(new RectF(cx - 7f * k, cy + 0.5f * k, cx + 7f * k, cy + 14f * k), 200f, 140f, false, p);
        }
    }
}

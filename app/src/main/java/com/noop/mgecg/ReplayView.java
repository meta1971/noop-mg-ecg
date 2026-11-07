package com.noop.mgecg;

// FILE VERSION 0.44.0 (7 Nov 2026): read-only replay of a whole recorded reading (decode log 50.6 item 1, A3). Drag to move, pinch to zoom, double-tap to fit the
// whole reading or come back to 8 s. Paper or dark theme as the live graph (Live500View.paper); beat markers with R-R times; quality shading per second (amber fair, grey poor,
// darker no contact); a thin overview bar with the reading's quality and the window.

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Locale;

public class ReplayView extends View {
    private ReplayData d;
    private String message = "Loading the reading\u2026";
    private double[] yr = { -0.5, 1.0 };
    private double t0, span = 8.0;
    private final Paint bg = new Paint(), minor = new Paint(Paint.ANTI_ALIAS_FLAG), major = new Paint(Paint.ANTI_ALIAS_FLAG), trace = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(), mark = new Paint(Paint.ANTI_ALIAS_FLAG), rr = new Paint(Paint.ANTI_ALIAS_FLAG), ov = new Paint(), ovWin = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG), center = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), tri = new Path();
    private final float dp;
    private final ScaleGestureDetector scaler;
    private final GestureDetector taps;
    private boolean scaling;
    private float downX, downY;

    public ReplayView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        trace.setStyle(Paint.Style.STROKE); trace.setStrokeJoin(Paint.Join.ROUND); trace.setStrokeCap(Paint.Cap.ROUND);
        minor.setStrokeWidth(1f); major.setStrokeWidth(1.6f);
        label.setTextSize(9f * dp); label.setTypeface(HealthViews.font(c, 400));
        rr.setTextSize(9f * dp); rr.setTextAlign(Paint.Align.CENTER); rr.setTypeface(HealthViews.font(c, 400));
        center.setTextSize(13f * dp); center.setTextAlign(Paint.Align.CENTER); center.setTypeface(HealthViews.font(c, 600)); center.setColor(Color.parseColor("#FFB347"));
        mark.setStyle(Paint.Style.FILL); border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(2f);
        ovWin.setStyle(Paint.Style.STROKE); ovWin.setStrokeWidth(1.6f * dp);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector g) {
                if (d == null || getWidth() == 0) return true;
                double focusT = t0 + g.getFocusX() / getWidth() * span;
                span = clampSpan(span / Math.max(0.2, Math.min(5.0, g.getScaleFactor())));
                t0 = focusT - g.getFocusX() / getWidth() * span; clampT0(); invalidate(); return true;
            }
        });
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (d == null || scaling || getWidth() == 0) return true;
                t0 += dx / getWidth() * span; clampT0(); invalidate(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (d == null || getWidth() == 0) return true;
                double tapT = t0 + e.getX() / getWidth() * span;
                if (span < d.durS * 0.9) { span = d.durS; t0 = 0; } else { span = clampSpan(8.0); t0 = tapT - span / 2; clampT0(); }
                invalidate(); return true;
            }
        });
        setContentDescription("Replay of the whole reading. Drag to move, pinch to zoom, double tap to fit.");
    }

    /** null shows the message instead of a trace. */
    void setData(ReplayData data, String msg) {
        d = data; if (msg != null) message = msg;
        if (d != null) { yr = d.yRange(); span = clampSpan(8.0); t0 = 0; }
        invalidate();
    }

    private double clampSpan(double s) { double mx = d == null ? 8.0 : Math.max(1.5, d.durS), mn = Math.min(1.5, mx); return Math.max(mn, Math.min(mx, s)); }
    private void clampT0() { if (d == null) return; t0 = Math.max(0.0, Math.min(Math.max(0.0, d.durS - span), t0)); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); scaling = false; }
        if (a == MotionEvent.ACTION_POINTER_DOWN) { scaling = true; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); }
        if (a == MotionEvent.ACTION_MOVE && getParent() != null && e.getPointerCount() == 1 && Math.abs(e.getX() - downX) > 12f * dp && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) getParent().requestDisallowInterceptTouchEvent(true);
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); }
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) taps.onTouchEvent(e);
        if (a == MotionEvent.ACTION_UP) performClick();
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }

    private void theme() {
        boolean p = Live500View.paper;
        bg.setColor(p ? Color.parseColor("#FFFCF7") : Color.parseColor("#0D1015"));
        minor.setColor(p ? Color.parseColor("#F7D9D9") : Color.parseColor("#2A1418"));
        major.setColor(p ? Color.parseColor("#EDB1B1") : Color.parseColor("#4A2028"));
        trace.setColor(p ? Color.parseColor("#14171F") : Color.parseColor("#2EE6A8")); trace.setStrokeWidth((p ? 1.6f : 1.5f) * dp);
        label.setColor(p ? Color.parseColor("#4A4F5C") : Color.parseColor("#8FA1BD"));
        rr.setColor(p ? Color.parseColor("#6A5A5A") : Color.parseColor("#7F8FA8"));
        mark.setColor(p ? Color.parseColor("#C0392B") : Color.parseColor("#FFB347"));
        border.setColor(p ? Color.parseColor("#D9C9C9") : Color.parseColor("#1F2733"));
        ovWin.setColor(p ? Color.parseColor("#14171F") : Color.parseColor("#FFFFFF"));
    }

    private int shadeColour(int cls) {
        boolean p = Live500View.paper;
        switch (cls) {
            case 1: return p ? 0x40E3A22A : 0x30F0B030;      // fair: amber
            case 2: return p ? 0x4D9AA0AB : 0x406F7A8C;      // poor: grey
            case 3: return p ? 0x33000000 : 0x55000000;      // no contact: darker
            default: return 0;
        }
    }
    private int ovColour(int cls) {
        boolean p = Live500View.paper;
        switch (cls) { case 1: return p ? 0xFFC77D0A : 0xFFF0B030; case 2: return p ? 0xFF9AA0AB : 0xFF6F7A8C; case 3: return p ? 0xFFE6E0D8 : 0xFF1F2733; default: return p ? 0xFF14171F : 0xFF2EE6A8; }
    }

    private static String fmtT(double t, boolean dec) {
        if (t >= 60.0) return String.format(Locale.UK, "%d:%02d", (int) (t / 60), (int) (t % 60));
        return dec ? String.format(Locale.UK, "%.1f s", t) : String.format(Locale.UK, "%.0f s", t);
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        theme();
        cv.drawRect(0, 0, w, h, bg);
        if (d == null) { cv.drawText(message, w / 2f, h / 2f, center); cv.drawRect(1, 1, w - 1, h - 1, border); return; }
        float top = 18f * dp, bot = h - 34f * dp, ph = bot - top;
        double lo = yr[0], hi = yr[1], ys = hi - lo;
        int i0 = Math.max(0, d.indexOf(t0)), i1 = Math.min(d.mv.length - 1, d.indexOf(t0 + span));
        // quality shading per second
        int s0 = Math.max(0, (int) Math.floor(i0 / (double) ReplayData.FS)), s1 = Math.min(d.seconds() - 1, (int) Math.floor(i1 / (double) ReplayData.FS));
        for (int s = s0; s <= s1; s++) {
            int cls = d.secCls[s]; int col = shadeColour(cls); if (col == 0) continue;
            int e = s; while (e + 1 <= s1 && d.secCls[e + 1] == cls) e++;
            float xa = xOf(d.timeOf(s * ReplayData.FS), w), xb = xOf(d.timeOf((e + 1) * ReplayData.FS), w);
            shade.setColor(col); cv.drawRect(Math.max(0f, xa), top, Math.min(w, xb), bot, shade);
            s = e;
        }
        // grid
        if (span <= 20.0) {
            double st = span <= 6.0 ? 0.04 : 0.2;
            double tStart = Math.ceil(t0 / st) * st;
            for (double t = tStart; t <= t0 + span + 1e-9; t += st) { long k = Math.round(t / 0.04); cv.drawLine(xOf(t, w), top, xOf(t, w), bot, (k % 5 == 0) ? major : minor); }
            if (span <= 6.0) for (int k = (int) Math.floor(lo * 10); k <= (int) Math.ceil(hi * 10); k++) { float y = yOf(k / 10.0, top, ph, lo, ys); cv.drawLine(0, y, w, y, k % 5 == 0 ? major : minor); }
            else for (int k = (int) Math.floor(lo * 2); k <= (int) Math.ceil(hi * 2); k++) { float y = yOf(k / 2.0, top, ph, lo, ys); cv.drawLine(0, y, w, y, major); }
        }
        // time ticks
        double[] steps = { 0.2, 0.5, 1, 2, 5, 10, 30, 60, 120 }; double tick = steps[steps.length - 1];
        for (double s : steps) if (s / span * w >= 56f * dp) { tick = s; break; }
        for (double t = Math.ceil(t0 / tick) * tick; t <= t0 + span + 1e-9; t += tick) {
            float x = xOf(t, w); cv.drawLine(x, bot, x, bot + 4f * dp, major);
            label.setTextAlign(Paint.Align.CENTER); cv.drawText(fmtT(t, tick < 1.0), Math.max(14f * dp, Math.min(w - 14f * dp, x)), bot + 14f * dp, label);
        }
        label.setTextAlign(Paint.Align.LEFT);
        // the trace: a polyline, or the min and max of each pixel column when there are many samples per pixel
        path.reset();
        double spp = (i1 - i0 + 1) / (double) Math.max(1, w);
        if (spp > 2.0) {
            int g = i0;
            for (int px = 0; px < w; px++) {
                int ge = Math.min(i1, i0 + (int) ((px + 1) * spp)); double mn = Double.NaN, mx = Double.NaN;
                for (; g <= ge; g++) { double v = d.mv[g]; if (Double.isNaN(v)) continue; if (Double.isNaN(mn) || v < mn) mn = v; if (Double.isNaN(mx) || v > mx) mx = v; }
                if (Double.isNaN(mn)) continue;
                path.moveTo(px, yOf(mn, top, ph, lo, ys)); path.lineTo(px + 0.5f, yOf(mx, top, ph, lo, ys));
            }
        } else {
            boolean down = false;
            for (int i = i0; i <= i1; i++) {
                double v = d.mv[i]; if (Double.isNaN(v)) { down = false; continue; }
                float x = xOf(d.timeOf(i), w), y = yOf(v, top, ph, lo, ys);
                if (!down) { path.moveTo(x, y); down = true; } else path.lineTo(x, y);
            }
        }
        cv.save(); cv.clipRect(0, top, w, bot); cv.drawPath(path, trace); cv.restore();
        // beats
        if (span <= 40.0) {
            int a = java.util.Arrays.binarySearch(d.beats, i0); if (a < 0) a = -a - 1;
            double prev = Double.NaN; boolean prevOk = false;
            for (int k = Math.max(0, a - 1); k < d.beats.length && d.beats[k] <= i1 + ReplayData.FS / 4; k++) {
                int p = d.beats[k]; double t = d.timeOf(p); float x = xOf(t, w);
                boolean good = d.secCls[Math.min(d.seconds() - 1, p / ReplayData.FS)] == 0;
                float amp = Math.max(0f, d.beatAmp[k]); float y = yOf(amp, top, ph, lo, ys) - 9f * dp; if (y < top + 6f * dp) y = top + 6f * dp;
                if (x >= -8f * dp && x <= w + 8f * dp) {
                    mark.setAlpha(good ? 255 : 110);
                    tri.reset(); tri.moveTo(x - 4f * dp, y - 5f * dp); tri.lineTo(x + 4f * dp, y - 5f * dp); tri.lineTo(x, y + 1f * dp); tri.close(); cv.drawPath(tri, mark);
                    if (span <= 15.0 && good && prevOk && !Double.isNaN(prev)) { double ms = (t - prev) * 1000.0; if (ms > 300 && ms < 2500) cv.drawText(String.format(Locale.UK, "%.0f", ms), (x + xOf(prev, w)) / 2f, top + 12f * dp, rr); }
                }
                prev = t; prevOk = good;
            }
        }
        // top line and overview bar
        cv.drawText(String.format(Locale.UK, "Replay  \u00b7  %s to %s of %s  \u00b7  500 Hz  \u00b7  boxes 0.04 s / 0.1 mV", fmtT(t0, true), fmtT(Math.min(d.durS, t0 + span), true), fmtT(d.durS, true)), 8f * dp, 12f * dp, label);
        float oy = h - 12f * dp, oh = 6f * dp; int ns = d.seconds();
        for (int s = 0; s < ns; s++) {
            double ta = d.timeOf(s * ReplayData.FS), tb = d.timeOf((s + 1) * ReplayData.FS);
            if (tb < 0 || ta > d.durS) continue;
            float xa = (float) (Math.max(0, ta) / d.durS * w), xb = (float) (Math.min(d.durS, tb) / d.durS * w);
            ov.setColor(ovColour(d.secCls[s])); cv.drawRect(xa, oy, Math.max(xb, xa + 1f), oy + oh, ov);
        }
        cv.drawRoundRect((float) (t0 / d.durS * w), oy - 2f * dp, (float) Math.min(w, (t0 + span) / d.durS * w), oy + oh + 2f * dp, 3f * dp, 3f * dp, ovWin);
        cv.drawRect(1, 1, w - 1, h - 1, border);
    }

    private float xOf(double t, int w) { return (float) ((t - t0) / span * w); }
    private static float yOf(double mv, float top, float ph, double lo, double ys) { float y = (float) (top + ph - (mv - lo) / ys * ph); return Math.max(top - 4f, Math.min(top + ph + 4f, y)); }
}

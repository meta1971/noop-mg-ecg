package com.noop.mgecg;

// FILE VERSION 0.41.0 (5 Nov 2026): the averaged beat drawn on the reading page, with R, the S / ST dip, the QRS bracket and the T peak marked (decode log 116).

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public class AvgBeatView extends View {
    private LiveMeasure.Out o;
    private final Paint bg = new Paint(), grid = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG), br = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;
    private static final double T0 = -300, T1 = 600;            // ms shown around the R peak

    public AvgBeatView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE); line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeCap(Paint.Cap.ROUND); line.setStrokeWidth(1.8f * dp);
        text.setTextSize(10f * dp); text.setTypeface(HealthViews.font(c, 400));
        base.setStyle(Paint.Style.STROKE); base.setStrokeWidth(1f * dp); base.setPathEffect(new DashPathEffect(new float[] { 6f * dp, 5f * dp }, 0));
        br.setStyle(Paint.Style.STROKE); br.setStrokeWidth(2f * dp);
        grid.setStrokeWidth(1f);
    }

    void set(LiveMeasure.Out out) {
        o = out; postInvalidate();
        try { setContentDescription(out == null || out.avg == null ? "Averaged heartbeat: not enough beats yet" : String.format(java.util.Locale.UK, "Averaged heartbeat from %d beats. R %.0f microvolts%s%s.", out.used, out.rUv, Double.isNaN(out.qrsMs) ? "" : String.format(java.util.Locale.UK, ", QRS %.0f milliseconds", out.qrsMs), Double.isNaN(out.postDipUv) ? "" : String.format(java.util.Locale.UK, ", S T dip %.0f microvolts", out.postDipUv))); } catch (Throwable ignored) { }
    }

    @Override protected void onDraw(Canvas cv) {
        boolean p = Live500View.paper; int w = getWidth(), h = getHeight();
        bg.setColor(p ? Color.parseColor("#FFFCF7") : Color.parseColor("#0D1015")); cv.drawRect(0, 0, w, h, bg);
        int ink = p ? Color.parseColor("#14171F") : Color.parseColor("#2EE6A8"), dim = p ? Color.parseColor("#4A4F5C") : Color.parseColor("#8FA1BD"), acc = p ? Color.parseColor("#C0392B") : Color.parseColor("#FFB347");
        grid.setColor(p ? Color.parseColor("#F1CFCF") : Color.parseColor("#2A1418"));
        line.setColor(ink); base.setColor(dim); text.setColor(dim);
        float left = 8f * dp, right = w - 8f * dp, top = 22f * dp, bot = h - 18f * dp;
        for (int ms = -200; ms <= 600; ms += 200) { float x = left + (float) ((ms - T0) / (T1 - T0)) * (right - left); cv.drawLine(x, top, x, bot, grid); }
        if (o == null || o.avg == null) {
            text.setTextAlign(Paint.Align.CENTER); cv.drawText(o != null && o.offered > 0 ? "Averaged beat: " + o.note + " (" + o.offered + " beats so far)" : "The averaged beat appears after about 10 clean beats", w / 2f, h / 2f, text); text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        double[] a = o.avg; int pre = LiveMeasure.PRE, i0 = pre + (int) (T0 / 2.0), i1 = pre + (int) (T1 / 2.0);
        double mn = 0, mx = 0; for (int i = i0; i <= i1 && i < a.length; i++) { mn = Math.min(mn, a[i]); mx = Math.max(mx, a[i]); }
        double padv = Math.max(60.0, 0.12 * (mx - mn)); mn -= padv * 1.6; mx += padv;
        float yb = bot - (float) ((0 - mn) / (mx - mn)) * (bot - top);
        cv.drawLine(left, yb, right, yb, base);
        path.reset();
        for (int i = i0; i <= i1 && i < a.length; i++) {
            float x = left + (float) (((i - pre) * 2.0 - T0) / (T1 - T0)) * (right - left), y = bot - (float) ((a[i] - mn) / (mx - mn)) * (bot - top);
            if (i == i0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        cv.drawPath(path, line);
        // R apex
        int ri = pre; for (int i = pre - 12; i <= pre + 12; i++) if (a[i] > a[ri]) ri = i;
        float rx = xAt((ri - pre) * 2.0, left, right), ry = yAt(a[ri], mn, mx, top, bot);
        dot.setColor(ink); cv.drawCircle(rx, ry, 3f * dp, dot);
        text.setColor(ink); cv.drawText(String.format(java.util.Locale.UK, "R %.0f \u00b5V", o.rUv), Math.min(rx + 6f * dp, right - 60f * dp), ry + 4f * dp, text);
        // S / ST dip (lowest point 20-120 ms after R)
        if (!Double.isNaN(o.postDipMs) && !Double.isNaN(o.postDipUv)) {
            float sx = xAt(o.postDipMs, left, right), sy = yAt(o.postDipUv, mn, mx, top, bot);
            dot.setColor(acc); cv.drawCircle(sx, sy, 3.4f * dp, dot);
            text.setColor(acc); cv.drawText(String.format(java.util.Locale.UK, "S/ST dip %.0f \u00b5V", o.postDipUv), Math.min(sx + 6f * dp, right - 92f * dp), sy + 14f * dp, text);
        }
        // QRS bracket
        if (!Double.isNaN(o.qrsOnMs) && !Double.isNaN(o.qrsOffMs) && o.qrsOffMs > o.qrsOnMs) {
            float x0 = xAt(o.qrsOnMs, left, right), x1 = xAt(o.qrsOffMs, left, right), yy = bot - 4f * dp;
            br.setColor(acc); cv.drawLine(x0, yy, x1, yy, br); cv.drawLine(x0, yy - 4f * dp, x0, yy + 2f * dp, br); cv.drawLine(x1, yy - 4f * dp, x1, yy + 2f * dp, br);
            text.setColor(acc); text.setTextAlign(Paint.Align.CENTER);
            cv.drawText(String.format(java.util.Locale.UK, "QRS %.0f ms%s", o.qrsMs, Double.isNaN(o.qrsHwMs) ? "" : String.format(java.util.Locale.UK, " (\u00b1%.0f)", o.qrsHwMs)), (x0 + x1) / 2f, yy + 13f * dp, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        // T peak
        if (!Double.isNaN(o.tPeakMs) && !Double.isNaN(o.tAmpUv) && o.tPeakMs > 60) {
            int ti = pre + (int) Math.round(o.tPeakMs / 2.0); if (ti > 0 && ti < a.length) { float tx = xAt(o.tPeakMs, left, right), ty = yAt(a[ti], mn, mx, top, bot); dot.setColor(dim); cv.drawCircle(tx, ty, 2.6f * dp, dot); text.setColor(dim); cv.drawText("T", tx - 3f * dp, ty - 6f * dp, text); }
        }
        text.setColor(dim); cv.drawText(String.format(java.util.Locale.UK, "Averaged beat  \u00b7  %d clean beats of %d%s", o.used, o.offered, o.qrsOk ? "" : "  \u00b7  still settling"), left, 14f * dp, text);
    }
    private float xAt(double ms, float l, float r) { return l + (float) ((ms - T0) / (T1 - T0)) * (r - l); }
    private float yAt(double v, double mn, double mx, float top, float bot) { return bot - (float) ((v - mn) / (mx - mn)) * (bot - top); }
}

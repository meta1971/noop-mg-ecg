package com.noop.mgecg;

// FILE VERSION 0.41.0 (5 Nov 2026): zoom (3.5 s), sweep with an erase bar, paper theme, calibration pulse, R markers with R-R times and dim beats (pro display, decode log 116); 0.18.0 (27 Oct): the 500 Hz live graph (data and smoothing in Live500.java).

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Live window of the raw ECG at 500 samples a second, on calibrated paper (small box 0.04 s and 0.1 mV, large box 0.2 s and 0.5 mV).
 * Auto scale follows the signal; tap switches to WHOOP's fixed -1 to +2 mV window. The trace is filtered for the eye only (see Live500);
 * the reports use their own zero-phase filter. Options (set from the ECG page): window length, sweep, paper theme.
 */
public class Live500View extends View {
    static volatile double windowMs = 6000.0;      // 6000 or 3500
    static volatile boolean sweep = false, paper = false;
    private static final double GAP_MS = 400.0;       // sweep: the erase bar ahead of the newest sample
    private final Paint bg = new Paint(), border = new Paint(Paint.ANTI_ALIAS_FLAG), minor = new Paint(Paint.ANTI_ALIAS_FLAG), major = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trace = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG), veil = new Paint(), cal = new Paint(Paint.ANTI_ALIAS_FLAG), rr = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] v = new float[Live500.CAP], tmp = new float[Live500.CAP];
    private final double[] t = new double[Live500.CAP];
    private final double[] bt = new double[128]; private final float[] ba = new float[128]; private final boolean[] bo = new boolean[128];
    private final Path path = new Path(), calPath = new Path();
    private boolean autoScale = true;
    private float yMin = -0.5f, yMax = 1.0f;          // millivolts, smoothed
    private long lastScaleMs;
    private Runnable staleCb;
    private boolean wasLive;
    private long staleSince;
    private final float dp;

    public Live500View(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        trace.setStyle(Paint.Style.STROKE); trace.setStrokeJoin(Paint.Join.ROUND); trace.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE); glow.setStrokeJoin(Paint.Join.ROUND); glow.setStrokeCap(Paint.Cap.ROUND);
        border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(2f);
        label.setTextSize(9f * dp); label.setTypeface(HealthViews.font(c, 400));
        rr.setTextSize(9f * dp); rr.setTextAlign(Paint.Align.CENTER); rr.setTypeface(HealthViews.font(c, 400));
        big.setColor(Color.parseColor("#FFB347")); big.setTextSize(13f * dp); big.setTextAlign(Paint.Align.CENTER); big.setTypeface(HealthViews.font(c, 600));
        cal.setStyle(Paint.Style.STROKE); cal.setStrokeWidth(1.6f * dp);
        mark.setStyle(Paint.Style.FILL);
        setOnClickListener(x -> { autoScale = !autoScale; invalidate(); });
    }

    /** Called once on the UI thread when the frames stop arriving for a few seconds, so the screen can go back to the 100 Hz graph. */
    public void setStaleCallback(Runnable r) { staleCb = r; }

    private final float[] tmp2 = new float[500];
    private float qrsMv;                                // 0.20.0: smoothed QRS height in mV, shown so finger placement can be tried live

    private void theme() {
        boolean p = paper;
        bg.setColor(p ? Color.parseColor("#FFFCF7") : Color.parseColor("#0D1015"));
        minor.setColor(p ? Color.parseColor("#F7D9D9") : Color.parseColor("#2A1418")); minor.setStrokeWidth(1f);
        major.setColor(p ? Color.parseColor("#EDB1B1") : Color.parseColor("#4A2028")); major.setStrokeWidth(1.6f);
        border.setColor(p ? Color.parseColor("#D9C9C9") : Color.parseColor("#1F2733"));
        trace.setColor(p ? Color.parseColor("#14171F") : Color.parseColor("#2EE6A8")); trace.setStrokeWidth((p ? 1.6f : 1.5f) * dp);
        glow.setColor(p ? Color.parseColor("#00000000") : Color.parseColor("#282EE6A8")); glow.setStrokeWidth(3.2f * dp);
        label.setColor(p ? Color.parseColor("#4A4F5C") : Color.parseColor("#8FA1BD"));
        rr.setColor(p ? Color.parseColor("#6A5A5A") : Color.parseColor("#7F8FA8"));
        cal.setColor(p ? Color.parseColor("#14171F") : Color.parseColor("#8FA1BD"));
        mark.setColor(p ? Color.parseColor("#C0392B") : Color.parseColor("#FFB347"));
        veil.setColor(p ? Color.parseColor("#B8FFFCF7") : Color.parseColor("#B80D1015"));
    }

    private void updateScale(long now, int m) {
        if (now - lastScaleMs < 300 || m < 250) return;
        lastScaleMs = now;
        int k = 0;
        for (int i = 0; i < m; i++) if (!Float.isNaN(v[i])) tmp[k++] = v[i] / 1000f;
        if (k < 250) return;
        java.util.Arrays.sort(tmp, 0, k);
        float hi = tmp[Math.min(k - 1, (int) (k * 0.997f))], lo = tmp[(int) (k * 0.003f)];
        // 0.22.0: robust height (see Live500.qrsHeightMv): a lifted finger or a rail spike in one second cannot move it
        float h0 = Live500.qrsHeightMv(v, m, tmp2);
        if (Float.isNaN(h0)) h0 = Math.max(hi, -lo);
        qrsMv = qrsMv <= 0f ? h0 : qrsMv * 0.6f + h0 * 0.4f;
        if (!autoScale) return;
        float wantMax = Math.max(0.8f, hi * 1.25f), wantMin = Math.min(-0.4f, lo * 1.4f);
        yMax += (wantMax - yMax) * (wantMax > yMax ? 0.6f : 0.08f);          // open up quickly, close slowly
        yMin += (wantMin - yMin) * (wantMin < yMin ? 0.6f : 0.08f);
    }

    private float xOf(double ts, long now, int w, double win) {
        if (!sweep) return (float) (w * (1.0 - (now - ts) / win));
        double m = ts % win; if (m < 0) m += win; return (float) (w * (m / win));
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        long now = System.currentTimeMillis();
        final double win = windowMs;
        theme();
        canvas.drawRect(0, 0, w, h, bg);
        int m = Live500.snapshot(now, win, v, t);
        updateScale(now, m);
        float lo = autoScale ? yMin : -1f, hi = autoScale ? yMax : 2f, span = hi - lo;

        float vx = w / (float) (win / 40.0);                           // 0.04 s boxes
        int col = 0;
        for (float x = 0; x <= w; x += vx, col++) canvas.drawLine(x, 0, x, h, (col % 5 == 0) ? major : minor);
        boolean fine = span <= 3.3f;
        for (int i = (int) Math.floor(lo * 10); i <= (int) Math.ceil(hi * 10); i++) {
            boolean big5 = i % 5 == 0;
            if (!big5 && !fine) continue;
            float y = h - (i / 10f - lo) / span * h;
            canvas.drawLine(0, y, w, y, big5 ? major : minor);
        }

        path.reset();
        boolean down = false;
        int nanTail = 0; float lastX = -1f;
        for (int i = 0; i < m; i++) {
            float mv = v[i] / 1000f;
            if (Float.isNaN(mv)) { down = false; nanTail++; continue; }
            nanTail = 0;
            if (sweep && (now - t[i]) > win - GAP_MS) { down = false; continue; }          // the erase bar
            float x = xOf(t[i], now, w, win);
            float y = h - (mv - lo) / span * h;
            if (y < -h) y = -h; else if (y > 2 * h) y = 2 * h;
            if (sweep && x < lastX) down = false;                                          // wrapped round
            lastX = x;
            if (!down) { path.moveTo(x, y); down = true; } else path.lineTo(x, y);
        }
        if (!paper) canvas.drawPath(path, glow);
        canvas.drawPath(path, trace);

        boolean pro = Live500.pro;
        if (pro && m > 0) {
            int nb = Live500.proBeats(now, win, bt, ba, bo);
            for (int i = 0; i < nb; i++) {                                                   // dim the beats that do not match the typical shape
                if (!bo[i] && !sweep) { float xa = xOf(bt[i] - 250, now, w, win), xb = xOf(bt[i] + 450, now, w, win); canvas.drawRect(Math.max(0, xa), 0, Math.min(w, xb), h, veil); }
            }
            for (int i = 0; i < nb; i++) {
                float x = xOf(bt[i], now, w, win); if (x < 0 || x > w) continue;
                float amp = ba[i] / 1000f; float y = h - ((amp >= 0 ? amp : 0f) - lo) / span * h - 9f * dp;
                if (y < 6f * dp) y = 6f * dp;
                mark.setAlpha(bo[i] ? 255 : 90);
                Path tri = new Path(); tri.moveTo(x - 4f * dp, y - 5f * dp); tri.lineTo(x + 4f * dp, y - 5f * dp); tri.lineTo(x, y + 1f * dp); tri.close();
                canvas.drawPath(tri, mark);
                if (i > 0 && bo[i] && bo[i - 1] && !sweep) {
                    double ms = bt[i] - bt[i - 1];
                    if (ms > 300 && ms < 2500) canvas.drawText(String.format(java.util.Locale.UK, "%.0f", ms), (x + xOf(bt[i - 1], now, w, win)) / 2f, 24f * dp + 20f * dp, rr);
                }
            }
        }
        // 1 mV calibration pulse, 0.2 s wide, at the left edge
        float y0 = h - (0f - lo) / span * h, y1 = h - (1f - lo) / span * h, px = 8f * dp, pw = (float) (w * 200.0 / win);
        calPath.reset(); calPath.moveTo(px, y0); calPath.lineTo(px + 5f * dp, y0); calPath.lineTo(px + 5f * dp, y1); calPath.lineTo(px + 5f * dp + pw, y1); calPath.lineTo(px + 5f * dp + pw, y0); calPath.lineTo(px + 10f * dp + pw, y0);
        canvas.drawPath(calPath, cal);

        boolean live = Live500.isLive(now);
        if (live) { wasLive = true; staleSince = 0; }
        else if (wasLive) {
            if (staleSince == 0) staleSince = now;
            else if (now - staleSince > 2500) { wasLive = false; staleSince = 0; if (staleCb != null) post(staleCb); }
        }
        if (m == 0) canvas.drawText("Waiting for the live 500 Hz frames", w / 2f, h / 2f, big);
        else if (nanTail >= 250) canvas.drawText("No finger contact", w / 2f, h / 2f, big);

        canvas.drawText(String.format(java.util.Locale.UK, "Lead I (wrist-finger)  \u00b7  500 Hz  \u00b7  %s  \u00b7  %.1f s after the beat", autoScale ? "auto scale" : "fixed -1 to +2 mV", Live500.delaySeconds()), 8f * dp, 14f * dp, label);
        canvas.drawText(String.format(java.util.Locale.UK, "boxes 0.04 s / 0.1 mV  \u00b7  %s  \u00b7  %s", pro ? "baseline: PR spline" : "baseline: 0.05 Hz filter", Live500.modeLabel()), 8f * dp, 26f * dp, label);
        if (m > 250 && qrsMv > 0f) {
            int keep = label.getColor();
            label.setColor(paper ? Color.parseColor("#1B7F5C") : Color.parseColor("#2EE6A8"));
            canvas.drawText(String.format(java.util.Locale.UK, "QRS height %.2f mV", qrsMv), 8f * dp, 38f * dp, label);
            label.setColor(keep);
        }
        canvas.drawRect(1, 1, w - 1, h - 1, border);
        postInvalidateOnAnimation();
    }
}

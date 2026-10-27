package com.noop.mgecg;

// FILE VERSION 0.18.0 (27 Oct): the 500 Hz live graph (data and smoothing in Live500.java).

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Scrolling 6 second window of the live raw ECG at 500 samples a second, on the same calibrated paper as the 100 Hz graph (small box 0.04 s and
 * 0.1 mV, large box 0.2 s and 0.5 mV). Auto scale follows the signal (R waves up, S waves down); tap switches to WHOOP's fixed -1 to +2 mV window.
 * The trace is filtered for the eye only (see Live500); the reports use their own zero-phase filter.
 */
public class Live500View extends View {
    private static final double WINDOW_MS = 6000.0;
    private final Paint bg = new Paint(), border = new Paint(Paint.ANTI_ALIAS_FLAG), minor = new Paint(Paint.ANTI_ALIAS_FLAG), major = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trace = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] v = new float[Live500.CAP], tmp = new float[Live500.CAP];
    private final double[] t = new double[Live500.CAP];
    private final Path path = new Path();
    private boolean autoScale = true;
    private float yMin = -0.5f, yMax = 1.0f;          // millivolts, smoothed
    private long lastScaleMs;
    private Runnable staleCb;
    private boolean wasLive;
    private long staleSince;

    public Live500View(Context c) {
        super(c);
        bg.setColor(Color.parseColor("#0D1015"));
        minor.setColor(Color.parseColor("#2A1418")); minor.setStrokeWidth(1f);
        major.setColor(Color.parseColor("#4A2028")); major.setStrokeWidth(1.6f);
        border.setColor(Color.parseColor("#1F2733")); border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(2f);
        trace.setColor(Color.parseColor("#2EE6A8")); trace.setStyle(Paint.Style.STROKE); trace.setStrokeWidth(2.4f); trace.setStrokeJoin(Paint.Join.ROUND); trace.setStrokeCap(Paint.Cap.ROUND);
        glow.setColor(Color.parseColor("#282EE6A8")); glow.setStyle(Paint.Style.STROKE); glow.setStrokeWidth(6f); glow.setStrokeJoin(Paint.Join.ROUND); glow.setStrokeCap(Paint.Cap.ROUND);
        label.setColor(Color.parseColor("#8FA1BD")); label.setTextSize(24f); label.setTypeface(HealthViews.font(c, 400));
        big.setColor(Color.parseColor("#FFB347")); big.setTextSize(34f); big.setTextAlign(Paint.Align.CENTER); big.setTypeface(HealthViews.font(c, 600));
        setOnClickListener(x -> { autoScale = !autoScale; invalidate(); });
    }

    /** Called once on the UI thread when the frames stop arriving for a few seconds, so the screen can go back to the 100 Hz graph. */
    public void setStaleCallback(Runnable r) { staleCb = r; }

    private void updateScale(long now, int m) {
        if (!autoScale || now - lastScaleMs < 300 || m < 250) return;
        lastScaleMs = now;
        int k = 0;
        for (int i = 0; i < m; i++) if (!Float.isNaN(v[i])) tmp[k++] = v[i] / 1000f;
        if (k < 250) return;
        java.util.Arrays.sort(tmp, 0, k);
        float hi = tmp[Math.min(k - 1, (int) (k * 0.997f))], lo = tmp[(int) (k * 0.003f)];
        float wantMax = Math.max(0.8f, hi * 1.25f), wantMin = Math.min(-0.4f, lo * 1.4f);
        yMax += (wantMax - yMax) * (wantMax > yMax ? 0.6f : 0.08f);          // open up quickly, close slowly
        yMin += (wantMin - yMin) * (wantMin < yMin ? 0.6f : 0.08f);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        long now = System.currentTimeMillis();
        canvas.drawRect(0, 0, w, h, bg);
        int m = Live500.snapshot(now, WINDOW_MS, v, t);
        updateScale(now, m);
        float lo = autoScale ? yMin : -1f, hi = autoScale ? yMax : 2f, span = hi - lo;

        float vx = w / (float) (WINDOW_MS / 40.0);                           // 0.04 s boxes
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
        int nanTail = 0;
        for (int i = 0; i < m; i++) {
            float mv = v[i] / 1000f;
            if (Float.isNaN(mv)) { down = false; nanTail++; continue; }
            nanTail = 0;
            float x = (float) (w * (1.0 - (now - t[i]) / WINDOW_MS));
            float y = h - (mv - lo) / span * h;
            if (y < -h) y = -h; else if (y > 2 * h) y = 2 * h;
            if (!down) { path.moveTo(x, y); down = true; } else path.lineTo(x, y);
        }
        canvas.drawPath(path, glow);
        canvas.drawPath(path, trace);

        boolean live = Live500.isLive(now);
        if (live) { wasLive = true; staleSince = 0; }
        else if (wasLive) {
            if (staleSince == 0) staleSince = now;
            else if (now - staleSince > 2500) { wasLive = false; staleSince = 0; if (staleCb != null) post(staleCb); }
        }
        if (m == 0) canvas.drawText("Waiting for the live 500 Hz frames", w / 2f, h / 2f, big);
        else if (nanTail >= 250) canvas.drawText("No finger contact", w / 2f, h / 2f, big);

        canvas.drawText(String.format(java.util.Locale.UK, "500 Hz live raw  \u00b7  %s  \u00b7  %s  \u00b7  %.2f s behind", Live500.modeLabel(), autoScale ? "auto scale" : "fixed -1 to +2 mV", Live500.delaySeconds()), 16f, 34f, label);
        canvas.drawRect(1, 1, w - 1, h - 1, border);
        postInvalidateOnAnimation();
    }
}

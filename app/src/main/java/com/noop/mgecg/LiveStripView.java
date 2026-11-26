package com.noop.mgecg;

// FILE VERSION 0.93.0 (26 Nov 2026): NEW. The last six seconds of the live ECG as the strip of mockup 07: a blue trace on one baseline, a gold triangle over every
// beat the live detector accepted, "last 6 s" under it. It reads the same final samples and beat list as the 500 Hz graph (Live500.snapshotQ and Live500.proBeats),
// changes nothing in them, and draws about 20 times a second only while it is on screen. A tap opens the landscape full screen graph (LiveFullActivity).
// The strap's quality colours follow the same setting as the 500 Hz graph (amber quality 2, grey quality 0-1). Not run on a device when written.

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Arrays;

final class LiveStripView extends View {
    private static final double WIN_MS = 6000.0;
    private static final int BLUE = 0xFF2FA8F0, AMBER = 0xFFF2B35B, GREYQ = 0xFF7C8798, BASE = 0xFF2A3544, MUT = 0xFF98A3B3, GOLD = 0xFFF2B35B;
    private final float dp;
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG), tr = new Paint(Paint.ANTI_ALIAS_FLAG), trA = new Paint(Paint.ANTI_ALIAS_FLAG), trG = new Paint(Paint.ANTI_ALIAS_FLAG),
            mark = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path p0 = new Path(), p1 = new Path(), p2 = new Path(), tri = new Path();
    private final float[] v = new float[Live500.CAP], sorted = new float[Live500.CAP];
    private final double[] t = new double[Live500.CAP];
    private final byte[] q = new byte[Live500.CAP];
    private final double[] bt = new double[128]; private final float[] ba = new float[128]; private final boolean[] bo = new boolean[128];
    private int snapM, nbCache; private boolean haveSnap, flip; private float hiHeld = Float.NaN;
    private boolean ticking;
    private final Runnable tick = new Runnable() { @Override public void run() { ticking = false; if (isShown()) invalidate(); schedule(); } };

    LiveStripView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        base.setColor(BASE); base.setStrokeWidth(Math.max(1f, dp));
        for (Paint p : new Paint[] { tr, trA, trG }) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f * dp); p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeCap(Paint.Cap.ROUND); }
        tr.setColor(BLUE); trA.setColor(AMBER); trG.setColor(GREYQ);
        mark.setColor(GOLD); mark.setStyle(Paint.Style.FILL);
        txt.setColor(MUT); txt.setTextSize(11f * dp); txt.setTypeface(HealthViews.font(c, 400));
        setContentDescription("Live ECG, last 6 seconds. Tap for the full screen graph.");
        setOnClickListener(x -> { try { getContext().startActivity(new Intent(getContext(), LiveFullActivity.class)); } catch (Throwable ignored) { } });
    }

    private void schedule() { if (!ticking && isAttachedToWindow()) { ticking = true; postOnAnimationDelayed(tick, 48L); } }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); schedule(); }
    @Override protected void onDetachedFromWindow() { ticking = false; removeCallbacks(tick); super.onDetachedFromWindow(); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec); setMeasuredDimension(w, (int) (120 * dp));
    }

    private static float pct(float[] s, int n, double f) { int i = (int) Math.round(f * (n - 1)); return s[Math.max(0, Math.min(n - 1, i))]; }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight(); if (w <= 0 || h <= 0) return;
        float pad = 8f * dp, topM = 20f * dp, baseY = h * 0.70f, botY = h - 22f * dp;
        long now = System.currentTimeMillis();
        int m;
        if (Live500.busy && haveSnap) m = snapM; else { m = Live500.snapshotQ(now, WIN_MS, v, t, q); snapM = m; haveSnap = true; }
        cv.drawLine(pad, baseY, w - pad, baseY, base);
        cv.drawText("last 6 s", pad, h - 6f * dp, txt);
        if (m < 20) { txt.setTextAlign(Paint.Align.CENTER); cv.drawText("waiting for the signal", w / 2f, baseY - 12f * dp, txt); txt.setTextAlign(Paint.Align.LEFT); return; }
        // baseline and scale from this window: median, then the 99.5th and 0.5th percentile of the distance from it
        int n = 0; for (int i = 0; i < m; i++) if (!Float.isNaN(v[i])) sorted[n++] = v[i];
        if (n < 20) return;
        Arrays.sort(sorted, 0, n);
        float med = sorted[n / 2], hi = pct(sorted, n, 0.995) - med, lo = pct(sorted, n, 0.005) - med;
        if (!flip && -lo > 1.5f * hi) flip = true; else if (flip && hi > -lo * 1.5f) flip = false;      // an R wave that points down is shown up, with a margin so it does not flicker
        float top = Math.max(250f, flip ? -lo : hi);
        hiHeld = Float.isNaN(hiHeld) ? top : hiHeld + (top - hiHeld) * 0.2f;
        float span = Math.max(250f, hiHeld), up = baseY - topM, down = botY - baseY;
        double msPerPx = WIN_MS / Math.max(1, w - 2 * pad), nowQ = Math.floor(now / msPerPx) * msPerPx;
        p0.reset(); p1.reset(); p2.reset();
        boolean qc = Live500View.qualityColours; int cur = -1; float px = 0f, py = 0f; boolean pen = false;
        for (int i = 0; i < m; i++) {
            if (Float.isNaN(v[i])) { pen = false; continue; }
            float x = pad + (float) ((t[i] - (nowQ - WIN_MS)) / msPerPx);
            if (x < pad - 2f || x > w - pad + 2f) { pen = false; continue; }
            float d = (flip ? -(v[i] - med) : (v[i] - med)) / span;
            float y = d >= 0 ? baseY - d * up : baseY - d * down;
            if (y < 2f) y = 2f; else if (y > h - 2f) y = h - 2f;
            int cls = !qc ? 0 : (q[i] & 0xFF) >= 3 ? 0 : (q[i] & 0xFF) == 2 ? 1 : 2;
            Path pt = cls == 0 ? p0 : cls == 1 ? p1 : p2;
            if (!pen) { pt.moveTo(x, y); pen = true; cur = cls; }
            else if (cls != cur) { pt.moveTo(px, py); pt.lineTo(x, y); cur = cls; }
            else pt.lineTo(x, y);
            px = x; py = y;
        }
        cv.drawPath(p2, trG); cv.drawPath(p1, trA); cv.drawPath(p0, tr);
        if (Live500.pro) {
            int nb = Live500.busy ? nbCache : Live500.proBeats(now, WIN_MS, bt, ba, bo); nbCache = nb;
            for (int i = 0; i < nb; i++) {
                float x = pad + (float) ((bt[i] - (nowQ - WIN_MS)) / msPerPx); if (x < pad || x > w - pad) continue;
                mark.setAlpha(bo[i] ? 235 : 90);
                tri.reset(); tri.moveTo(x - 4f * dp, 6f * dp); tri.lineTo(x + 4f * dp, 6f * dp); tri.lineTo(x, 14f * dp); tri.close();
                cv.drawPath(tri, mark);
            }
        }
    }
}

package com.noop.mgecg;

// FILE VERSION 0.93.0 (26 Nov 2026): NEW. The progress ring of the live reading screen (mockup 07): a grey arc for the settling seconds, a blue arc for the
// measuring seconds, a gold tick where the strap gave its verdict and a teal tick where its first HRV arrived, the time in the middle. Drawing only: it reads a
// LiveScreenLogic.Ring and nothing else. Not run on a device when written (no phone in the sandbox); every call is guarded by the caller.

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class LiveRingView extends View {
    private static final int TRACK = 0xFF1D2631, GREY = 0xFF98A3B3, BLUE = 0xFF2FA8F0, GOLD = 0xFFF2B35B, TEAL = 0xFF4EE3CB, INK = 0xFFF2F6FA, ROSE = 0xFFFF8CA2;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), grey = new Paint(Paint.ANTI_ALIAS_FLAG), blue = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG),
            tickG = new Paint(Paint.ANTI_ALIAS_FLAG), tickT = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private LiveScreenLogic.Ring ring;
    private final float dp;

    LiveRingView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE); track.setColor(TRACK);
        grey.setStyle(Paint.Style.STROKE); grey.setColor(GREY);
        blue.setStyle(Paint.Style.STROKE); blue.setColor(BLUE); blue.setStrokeCap(Paint.Cap.ROUND);
        halo.setStyle(Paint.Style.STROKE); halo.setColor(BLUE); halo.setAlpha(31);
        tickG.setStyle(Paint.Style.STROKE); tickG.setColor(GOLD);
        tickT.setStyle(Paint.Style.STROKE); tickT.setColor(TEAL);
        big.setColor(INK); big.setTextAlign(Paint.Align.CENTER); big.setTypeface(HealthViews.font(c, 600));
        small.setColor(GREY); small.setTextAlign(Paint.Align.CENTER); small.setTypeface(HealthViews.font(c, 400));
        setFocusable(false);
    }

    void set(LiveScreenLogic.Ring r) {
        ring = r;
        if (r != null) setContentDescription(r.desc);
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int want = (int) (230 * dp);
        int w = MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED ? want : Math.min(want, MeasureSpec.getSize(wSpec));
        setMeasuredDimension(w, w);
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(); if (w <= 0f) return;
        float k = w / 230f;                                   // the mockup is drawn on a 230 unit square
        float c = w / 2f, r = 96f * k, sw = 12f * k;
        track.setStrokeWidth(sw); grey.setStrokeWidth(sw); blue.setStrokeWidth(sw);
        halo.setStrokeWidth(2f * k); tickG.setStrokeWidth(3f * k); tickT.setStrokeWidth(3f * k);
        cv.drawCircle(c, c, r, track);
        cv.drawCircle(c, c, 112f * k, halo);
        LiveScreenLogic.Ring g = ring;
        if (g != null) {
            oval.set(c - r, c - r, c + r, c + r);
            if (g.settle > 0.001f) cv.drawArc(oval, -90f, g.settle * 360f, false, grey);
            if (g.blue > 0.001f) cv.drawArc(oval, -90f + g.settle * 360f, g.blue * 360f, false, blue);
            if (!Float.isNaN(g.verdictAt)) tick(cv, c, k, g.verdictAt, tickG);
            if (!Float.isNaN(g.hrvAt)) tick(cv, c, k, g.hrvAt, tickT);
            big.setColor(g.state == LiveScreenLogic.ST_NO_CONTACT ? ROSE : INK);
            float bs = 46f * k, ss = 13f * k;                                // k = pixels per design unit (the design is 230 units square), so these are px
            big.setTextSize(bs); small.setTextSize(ss);
            small.setColor(g.state == LiveScreenLogic.ST_NO_CONTACT ? ROSE : g.state == LiveScreenLogic.ST_MEASURING ? BLUE : GREY);
            cv.drawText(g.centre == null ? "0:00" : g.centre, c, c + bs * 0.12f, big);
            cv.drawText(g.sub == null ? "" : g.sub, c, c + bs * 0.12f + ss * 2.2f, small);
        }
    }

    /** A radial tick from 86 to 106 units out, at the given fraction of the lap (0 = twelve o'clock, clockwise). */
    private void tick(Canvas cv, float c, float k, float frac, Paint p) {
        double a = Math.toRadians(-90.0 + frac * 360.0), ca = Math.cos(a), sa = Math.sin(a);
        cv.drawLine((float) (c + 86f * k * ca), (float) (c + 86f * k * sa), (float) (c + 106f * k * ca), (float) (c + 106f * k * sa), p);
    }
}

package com.noop.mgecg;

// FILE VERSION 0.43.1 (6 Nov 2026): the glowing dial of the "Hi, Ame" pages (HealthViews.OrbView) with text and progress that can change while a reading runs:
// the big thin figure (heart rate), the small caps line under it (m:ss or BPM) and the lit ticks (progress to 60 s). Same drawing as OrbView.

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

final class EcgOrb extends View {
    private String big = "ECG", sub = "";
    private float frac;
    private int accent = HealthViews.BLUE, c1 = 0xFF8FD8FF, c2 = 0xFF1FA0EE, c3 = 0xFF0B6FD0;

    EcgOrb(Context c) { super(c); }

    void set(String big, String sub, float frac) {
        if (big.equals(this.big) && sub.equals(this.sub) && Math.abs(frac - this.frac) < 0.004f) return;
        this.big = big; this.sub = sub; this.frac = Math.max(0f, Math.min(1f, frac)); invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        int s = MeasureSpec.getSize(w);
        setMeasuredDimension(s, s);
    }

    @Override protected void onDraw(Canvas cv) {
        float size = getWidth(), cx = size / 2f, cy = size / 2f;
        Context ctx = getContext();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(cx, cy, size * 0.47f, new int[] {HealthViews.alpha(c2, 0f), HealthViews.alpha(c2, 0.45f), HealthViews.alpha(c2, 0f)}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, size * 0.47f, p);
        Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        tick.setStrokeCap(Paint.Cap.ROUND);
        int n = 64;
        for (int k = 0; k < n; k++) {
            double a = -Math.PI / 2 + 2 * Math.PI * k / n;
            boolean major = k % 8 == 0, on = k / (float) n < frac;
            float r0 = size * 0.405f, r1 = size * (major ? 0.475f : 0.455f);
            tick.setColor(on ? accent : 0x34FFFFFF);
            tick.setStrokeWidth(HealthViews.dp(ctx, major ? 2.2f : 1.5f));
            cv.drawLine(cx + (float) (r0 * Math.cos(a)), cy + (float) (r0 * Math.sin(a)), cx + (float) (r1 * Math.cos(a)), cy + (float) (r1 * Math.sin(a)), tick);
        }
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE); ring.setColor(0x14FFFFFF); ring.setStrokeWidth(HealthViews.dp(ctx, 1));
        cv.drawCircle(cx, cy, size * 0.385f, ring);
        float R = size * 0.295f;
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
        s.setShader(new RadialGradient(cx - R * 0.28f, cy - R * 0.4f, R * 1.55f, new int[] {c1, c2, c3}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, R, s);
        Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
        hl.setShader(new RadialGradient(cx - size * 0.05f, cy - size * 0.1f, size * 0.2f, new int[] {0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        cv.save();
        Path q = new Path(); q.addCircle(cx, cy, R, Path.Direction.CW);
        cv.clipPath(q);
        cv.drawCircle(cx - size * 0.05f, cy - size * 0.1f, size * 0.2f, hl);
        cv.restore();
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF); t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(HealthViews.font(ctx, 200)); t.setTextSize(size * (big.length() > 3 ? 0.16f : 0.215f));
        cv.drawText(big, cx, cy + size * 0.075f, t);
        t.setTypeface(HealthViews.font(ctx, 600)); t.setTextSize(HealthViews.dp(ctx, 12)); t.setLetterSpacing(0.22f); t.setColor(0xEBFFFFFF);
        cv.drawText(sub, cx, cy + size * 0.125f + HealthViews.dp(ctx, 4), t);
        if (frac > 0f) {
            double ang = -Math.PI / 2 + 2 * Math.PI * frac;
            float px = cx + (float) (size * 0.385f * Math.cos(ang)), py = cy + (float) (size * 0.385f * Math.sin(ang));
            Path tri = new Path();
            tri.moveTo(0, -HealthViews.dp(ctx, 7)); tri.lineTo(HealthViews.dp(ctx, 5.5f), HealthViews.dp(ctx, 4)); tri.lineTo(-HealthViews.dp(ctx, 5.5f), HealthViews.dp(ctx, 4)); tri.close();
            cv.save(); cv.translate(px, py); cv.rotate((float) Math.toDegrees(ang) + 90f); cv.drawPath(tri, HealthViews.paint(0xFFFFFFFF)); cv.restore();
        }
    }
}

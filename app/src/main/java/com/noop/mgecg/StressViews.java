package com.noop.mgecg;

// FILE VERSION 0.53.5 (12 Nov 2026): the three small charts of the stress panel (StressModel, decode log 167): the day in 5-minute bars, the heart rate against what movement and the clock explain,
// and the share of time at each level. Plain Canvas drawing; colours as the stress ring (green under 20, amber 20-49, red from 50).

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class StressViews {
    private StressViews() {}

    static final int GREEN = 0xFF4ADE80, AMBER = 0xFFF5B942, RED = 0xFFF2685A, BLUE = 0xFF2FA8F0, GREY = 0xFF8A93A0, TRACK = 0xFF161B22, LINE = 0x22FFFFFF;
    static int levelColor(int score) { return score < 20 ? GREEN : score < 50 ? AMBER : RED; }
    private static String hh(long unix) { return new SimpleDateFormat("HH", Locale.UK).format(new Date(unix * 1000L)); }
    private static String hhmm(long unix) { return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(unix * 1000L)); }

    /** The whole day (dayStart to dayStart + 24 h) in 5-minute bars: height = score, colour = level, dimmer while moving. */
    static final class DayStrip extends View {
        private final List<StressModel.Bin> bins; private final long dayStart, now; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final float dp;
        DayStrip(Context c, List<StressModel.Bin> bins, long dayStart, long now) { super(c); this.bins = bins; this.dayStart = dayStart; this.now = now; dp = c.getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), left = 30 * dp, top = 6 * dp, right = w - 4 * dp, bottom = h - 18 * dp, cw = right - left, ch = bottom - top;
            p.setStyle(Paint.Style.FILL); p.setColor(TRACK); cv.drawRoundRect(left, top, right, bottom, 6 * dp, 6 * dp, p);
            p.setStrokeWidth(1f * dp); p.setColor(LINE);
            for (int v : new int[] { 20, 50 }) { float y = bottom - ch * v / 100f; cv.drawLine(left, y, right, y, p); }
            p.setTextSize(9.5f * dp); p.setColor(GREY); p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("100", left - 4 * dp, top + 9 * dp, p); cv.drawText("50", left - 4 * dp, bottom - ch * 0.5f + 3 * dp, p); cv.drawText("20", left - 4 * dp, bottom - ch * 0.2f + 3 * dp, p);
            float bw = Math.max(1.5f * dp, cw / 288f - 0.6f * dp);
            for (StressModel.Bin b : bins) {
                if (b.score < 0) continue;
                float x = left + (b.t - dayStart) / 86400f * cw; if (x < left || x > right) continue;
                p.setColor(levelColor(b.score)); p.setAlpha(b.band == 0 ? 255 : 150);
                float bh = Math.max(1.5f * dp, ch * b.score / 100f); cv.drawRect(x, bottom - bh, x + bw, bottom, p);
            }
            p.setAlpha(255); p.setTextAlign(Paint.Align.CENTER); p.setColor(GREY);
            for (int k = 0; k <= 6; k++) { long t = dayStart + k * 4 * 3600L; float x = left + k / 6f * cw; cv.drawText(hh(t), x, h - 4 * dp, p); }
            if (now > dayStart && now < dayStart + 86400L) { float x = left + (now - dayStart) / 86400f * cw; p.setColor(BLUE); p.setStrokeWidth(1.5f * dp); cv.drawLine(x, top, x, bottom, p); }
        }
    }

    /** Heart rate (line) against the expected heart rate (grey): the part above it is shaded (what the score counts). Last [hours] hours of [bins]. */
    static final class HrAgainstExpected extends View {
        private final List<StressModel.Bin> bins; private final long now; private final int hours; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final float dp;
        HrAgainstExpected(Context c, List<StressModel.Bin> bins, long now, int hours) { super(c); this.bins = bins; this.now = now; this.hours = hours; dp = c.getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), left = 30 * dp, top = 6 * dp, right = w - 4 * dp, bottom = h - 18 * dp, cw = right - left, ch = bottom - top;
            long t0 = now - hours * 3600L; double lo = 1e9, hi = -1e9; int n = 0;
            for (StressModel.Bin b : bins) if (b.t >= t0 && !Float.isNaN(b.exp) && !Float.isNaN(b.hr)) { lo = Math.min(lo, Math.min(b.hr, b.exp)); hi = Math.max(hi, Math.max(b.hr, b.exp)); n++; }
            p.setStyle(Paint.Style.FILL); p.setColor(TRACK); cv.drawRoundRect(left, top, right, bottom, 6 * dp, 6 * dp, p);
            if (n < 2) { p.setColor(GREY); p.setTextSize(11 * dp); p.setTextAlign(Paint.Align.CENTER); cv.drawText("not enough scored time in the last " + hours + " h", w / 2f, h / 2f, p); return; }
            lo = Math.floor(lo - 3); hi = Math.ceil(hi + 3); final double fl = lo, fh = hi;
            p.setTextSize(9.5f * dp); p.setColor(GREY); p.setTextAlign(Paint.Align.RIGHT); cv.drawText(String.valueOf((int) hi), left - 4 * dp, top + 9 * dp, p); cv.drawText(String.valueOf((int) lo), left - 4 * dp, bottom, p);
            StressModel.Bin prev = null;
            for (StressModel.Bin b : bins) {
                if (b.t < t0 || Float.isNaN(b.exp) || Float.isNaN(b.hr)) { prev = null; continue; }
                if (prev != null && b.t - prev.t <= 600) {
                    float x0 = left + (prev.t - t0) / (float) (hours * 3600L) * cw, x1 = left + (b.t - t0) / (float) (hours * 3600L) * cw;
                    float y0h = (float) (bottom - (prev.hr - fl) / (fh - fl) * ch), y1h = (float) (bottom - (b.hr - fl) / (fh - fl) * ch), y0e = (float) (bottom - (prev.exp - fl) / (fh - fl) * ch), y1e = (float) (bottom - (b.exp - fl) / (fh - fl) * ch);
                    if (prev.hr > prev.exp || b.hr > b.exp) { Path q = new Path(); q.moveTo(x0, y0h); q.lineTo(x1, y1h); q.lineTo(x1, y1e); q.lineTo(x0, y0e); q.close(); p.setStyle(Paint.Style.FILL); p.setColor(AMBER); p.setAlpha(110); cv.drawPath(q, p); p.setAlpha(255); }
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f * dp); p.setColor(GREY); cv.drawLine(x0, y0e, x1, y1e, p);
                    p.setStrokeWidth(1.8f * dp); p.setColor(BLUE); cv.drawLine(x0, y0h, x1, y1h, p);
                }
                prev = b;
            }
            p.setStyle(Paint.Style.FILL); p.setColor(GREY); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(9.5f * dp);
            for (int k = 0; k <= hours; k += 2) { long t = t0 + k * 3600L; float x = left + k / (float) hours * cw; cv.drawText(hh(t), x, h - 4 * dp, p); }
        }
    }

    /** One bar split into low / medium / high minutes. */
    static final class LevelBar extends View {
        private final int low, med, high; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final float dp;
        LevelBar(Context c, int low, int med, int high) { super(c); this.low = low; this.med = med; this.high = high; dp = c.getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(); int tot = Math.max(1, low + med + high); float x = 0; float r = 6 * dp;
            p.setStyle(Paint.Style.FILL); p.setColor(TRACK); cv.drawRoundRect(0, 0, w, h, r, r, p);
            int[] v = { low, med, high }; int[] col = { GREEN, AMBER, RED };
            for (int i = 0; i < 3; i++) { if (v[i] <= 0) continue; float seg = w * v[i] / tot; p.setColor(col[i]); cv.drawRect(x, 0, x + seg - 1.5f * dp, h, p); x += seg; }
        }
    }
}

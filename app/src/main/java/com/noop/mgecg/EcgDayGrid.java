package com.noop.mgecg;

// FILE VERSION 0.72.1 (20 Nov 2026): a vertical swipe on the grid scrolls the page again (0.72.0 blocked it). 0.72.0 (20 Nov 2026): the day grid of the Trends screen. One row per ECG measure, one column per day (oldest left, today right). A cell is the day's median distance from YOUR usual of the same time of day
// (EcgSeg.grid): grey-green = within one spread, amber = above, blue = below, stronger colour = further (two spreads or more is the edge of what is usual for you). Empty = no reading that day or still learning. Tap a cell for its numbers.

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

final class EcgDayGrid extends View {
    interface Pick { void onPick(String text); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private EcgSeg.Grid g; private int[] rows = new int[0]; private int selR = -1, selC = -1; private Pick pick;
    private final float cellH, labW, headH;

    EcgDayGrid(Context c) { super(c); cellH = dp(c, 22); labW = dp(c, 78); headH = dp(c, 16); }

    void set(EcgSeg.Grid grid, Pick onPick) {
        g = grid; pick = onPick; int n = 0; int[] tmp = new int[EcgSeg.ROWS.length];
        if (g != null) for (int m : EcgSeg.ROWS) if (EcgSeg.hasRow(g, m)) tmp[n++] = m;
        rows = java.util.Arrays.copyOf(tmp, n); selR = selC = -1; requestLayout(); invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec); setMeasuredDimension(w, Math.round(headH + cellH * Math.max(1, rows.length) + dp(getContext(), 4)));
    }

    @Override protected void onDraw(Canvas cv) {
        if (g == null || rows.length == 0) return;
        Context c = getContext(); float w = getWidth(), gridW = w - labW, cw = gridW / g.days;
        SimpleDateFormat f = new SimpleDateFormat("d", Locale.UK); f.setTimeZone(g.tz);
        p.setTextSize(dp(c, 9)); p.setTextAlign(Paint.Align.CENTER);
        for (int d = 0; d < g.days; d++) {
            if (d % 3 != (g.days - 1) % 3 && d != g.days - 1) continue;
            p.setColor(d == g.days - 1 ? INK : MUT); cv.drawText(f.format(new Date(g.dayStart[d] * 1000L + 43200000L)), labW + cw * (d + 0.5f), headH - dp(c, 4), p);
        }
        p.setTextAlign(Paint.Align.LEFT); p.setTextSize(dp(c, 11));
        for (int r = 0; r < rows.length; r++) {
            int m = rows[r]; float y = headH + cellH * r;
            p.setColor(EcgSeg.WATCHED[m] ? INK : MUT); cv.drawText(EcgSeg.NAME[m], 0, y + cellH * 0.64f, p);
            for (int d = 0; d < g.days; d++) {
                float x = labW + cw * d; p.setColor(g.cnt[m][d] == 0 ? 0x0DFFFFFF : EcgSeg.colour(g.z[m][d]));
                cv.drawRoundRect(x + 1, y + 1.5f, x + cw - 1, y + cellH - 1.5f, dp(c, 2.5f), dp(c, 2.5f), p);
                if (r == selR && d == selC) { p.setStyle(Paint.Style.STROKE); p.setColor(INK); p.setStrokeWidth(dp(c, 1.5f)); cv.drawRoundRect(x + 1, y + 1.5f, x + cw - 1, y + cellH - 1.5f, dp(c, 2.5f), dp(c, 2.5f), p); p.setStyle(Paint.Style.FILL); }
            }
        }
    }

    private float downX, downY; private boolean horizontal;

    /** A tap selects a square; a mostly horizontal drag moves the selection along the grid; a vertical swipe is left to the page so it still scrolls. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (g == null || rows.length == 0) return false;
        int act = e.getAction();
        if (act == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); horizontal = false; select(e); return true; }
        if (act == MotionEvent.ACTION_MOVE) {
            float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY), slop = dp(getContext(), 8);
            if (!horizontal && dy > slop && dy > dx) return false;                 // vertical: let the ScrollView take it
            if (dx > slop && dx >= dy) { horizontal = true; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); }
            if (horizontal) select(e);
            return true;
        }
        return true;
    }

    private void select(MotionEvent e) {
        float gridW = getWidth() - labW, cw = gridW / g.days; int d = (int) ((e.getX() - labW) / cw), r = (int) ((e.getY() - headH) / cellH);
        if (e.getX() >= labW && d >= 0 && d < g.days && r >= 0 && r < rows.length) { selR = r; selC = d; invalidate(); if (pick != null) pick.onPick(EcgSeg.pick(g, rows[r], d)); }
    }
}

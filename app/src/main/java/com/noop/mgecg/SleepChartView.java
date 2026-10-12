package com.noop.mgecg;

// FILE VERSION 0.5.12 (12 Oct): night chart for the Day Logger. Research only, no sleep stages.

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One night drawn as stacked panels on a shared time axis: the band's state, heart rate, breathing rate,
 * RMSSD and skin temperature, with the restless spells shaded and a touch cursor that reads the values at a
 * minute. Pure canvas drawing, no libraries. The numbers come from SleepNight.series().
 */
public class SleepChartView extends View {

    private static final int BG = Color.parseColor("#0B1220");
    private static final int PANEL = Color.parseColor("#101826");
    private static final int GRID = Color.parseColor("#243044");
    private static final int TXT = Color.parseColor("#9FB3C8");
    private static final int TXT_HI = Color.parseColor("#E6EEF8");
    private static final int C_SLEEP = Color.parseColor("#22E6D6");
    private static final int C_STILL = Color.parseColor("#5B7FA6");
    private static final int C_WAKE = Color.parseColor("#F6B73C");
    private static final int C_UP = Color.parseColor("#FF7A59");
    private static final int C_HR = Color.parseColor("#FF5D73");
    private static final int C_BR = Color.parseColor("#7BD88F");
    private static final int C_RM = Color.parseColor("#B392F0");
    private static final int C_SK = Color.parseColor("#FFB86B");
    private static final String[] STATE_NAME = {"awake", "still", "asleep", "arm movement"};

    // panel heights in dp: state strip, heart rate, breathing, RMSSD, skin
    private static final int[] PANEL_DP = {30, 110, 70, 70, 60};
    private static final String[] PANEL_TITLE = {"Band state", "Heart rate (bpm)", "Breathing (per min)", "RMSSD (ms)", "Skin temperature (\u00B0C)"};
    private static final int HEADER_DP = 46, TITLE_DP = 18, GAP_DP = 8, AXIS_DP = 28, LEFT_DP = 34, RIGHT_DP = 8;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float dp;
    private final float sp;

    private SleepNight.Series s;
    private int cursor = -1;                       // minute index under the finger, -1 = none
    private float[] lo = new float[5], hi = new float[5];   // y range per panel (index 0 unused)
    private final float[] top = new float[5];      // top y of each panel, set in layout()
    private float plotL, plotR;

    public SleepChartView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        sp = c.getResources().getDisplayMetrics().scaledDensity;
        setBackgroundColor(BG);
    }

    void setData(SleepNight.Series series) {
        s = series;
        cursor = -1;
        if (s != null) {
            setRange(1, s.hr, 3f, 5f, 20f, false, 0.01f);
            setRange(2, s.br, 1f, 2f, 6f, false, 0.01f);
            setRange(3, s.rmssd, 0f, 10f, 40f, true, 0.01f);
            setRange(4, s.skin, 0.3f, 0.5f, 1.5f, false, 0.08f);     // skips the cold start so the night itself is readable
        }
        requestLayout();
        invalidate();
    }

    /** Range with a little padding, rounded to a step with an even number of steps (so the middle tick is round), never
     *  narrower than minSpan. loTrim of the lowest values and 1% of the highest are ignored when sizing the range. */
    private void setRange(int i, float[] v, float pad, float step, float minSpan, boolean fromZero, float loTrim) {
        float[] t = new float[v.length];
        int c = 0;
        for (float x : v) if (!Float.isNaN(x)) t[c++] = x;
        if (c == 0) { lo[i] = 0; hi[i] = minSpan; return; }
        java.util.Arrays.sort(t, 0, c);
        float a = t[(int) (loTrim * (c - 1))], b = t[(int) Math.ceil(0.99f * (c - 1))];
        float l = fromZero ? 0f : (float) Math.floor((a - pad) / step) * step;
        float h = (float) Math.ceil((b + pad) / step) * step;
        if (h - l < minSpan) { float mid = 0.5f * (h + l); l = fromZero ? 0f : (float) Math.floor((mid - minSpan / 2) / step) * step; h = l + minSpan; }
        float steps = (float) Math.ceil((h - l) / step - 1e-4);
        if (steps % 2 != 0) steps += 1;
        lo[i] = l; hi[i] = l + steps * step;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) (totalDp() * dp));
    }

    private float totalDp() {
        float t = HEADER_DP;
        for (int i = 0; i < PANEL_DP.length; i++) t += TITLE_DP + PANEL_DP[i] + GAP_DP;
        return t + AXIS_DP;
    }

    private void layout() {
        plotL = LEFT_DP * dp;
        plotR = getWidth() - RIGHT_DP * dp;
        float y = HEADER_DP * dp;
        for (int i = 0; i < PANEL_DP.length; i++) {
            y += TITLE_DP * dp;
            top[i] = y;
            y += PANEL_DP[i] * dp + GAP_DP * dp;
        }
    }

    private float xOf(float minute) { return plotL + minute * (plotR - plotL) / s.n; }
    private float yOf(int i, float v) { return top[i] + PANEL_DP[i] * dp * (1f - (v - lo[i]) / (hi[i] - lo[i])); }
    private float bottomOf(int i) { return top[i] + PANEL_DP[i] * dp; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (s == null || getWidth() == 0) return;
        layout();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());

        // header: night, then the values under the finger
        p.setStyle(Paint.Style.FILL);
        p.setColor(TXT_HI); p.setTextSize(13 * sp); p.setFakeBoldText(true);
        c.drawText(s.title, plotL - 26 * dp > 0 ? 8 * dp : 0, 17 * dp, p);
        p.setFakeBoldText(false); p.setTextSize(11 * sp); p.setColor(cursor >= 0 ? TXT_HI : TXT);
        c.drawText(cursor >= 0 ? readout(hm) : "touch the chart to read a minute", 8 * dp, 37 * dp, p);

        // panels: background, title, grid, data
        for (int i = 0; i < PANEL_DP.length; i++) {
            p.setStyle(Paint.Style.FILL); p.setColor(PANEL);
            rect.set(plotL, top[i], plotR, bottomOf(i));
            c.drawRect(rect, p);
            p.setColor(TXT); p.setTextSize(10 * sp);
            c.drawText(PANEL_TITLE[i], plotL, top[i] - 5 * dp, p);
        }
        drawHourGrid(c, hm);
        drawRestless(c);
        drawStates(c);
        drawLine(c, 1, s.hr, C_HR, 1.6f, false);
        drawScale(c, 1, 3, "%.0f");
        drawLine(c, 2, s.br, C_BR, 2.2f, true);
        drawScale(c, 2, 2, "%.0f");
        drawLine(c, 3, s.rmssd, C_RM, 2.0f, true);
        drawScale(c, 3, 3, "%.0f");
        drawLine(c, 4, s.skin, C_SK, 1.6f, false);
        drawScale(c, 4, 2, "%.1f");
        drawMarks(c, hm);

        if (cursor >= 0) {
            float x = xOf(cursor + 0.5f);
            p.setColor(TXT_HI); p.setStrokeWidth(1.2f * dp); p.setStyle(Paint.Style.STROKE);
            c.drawLine(x, top[0] - 2 * dp, x, bottomOf(PANEL_DP.length - 1), p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    private void drawHourGrid(Canvas c, SimpleDateFormat hm) {
        long off = java.util.TimeZone.getDefault().getOffset(s.t0 * 1000L) / 1000L;
        long firstHour = ((s.t0 + off) / 3600L + 1L) * 3600L - off;           // first whole local hour after the start
        float pxPerHour = (plotR - plotL) / s.n * 60f;
        int every = pxPerHour >= 40 * dp ? 1 : 2;
        int k = 0;
        p.setStrokeWidth(1f);
        for (long t = firstHour; t < s.t0 + s.n * 60L; t += 3600L, k++) {
            float x = xOf((t - s.t0) / 60f);
            p.setColor(GRID); p.setStyle(Paint.Style.STROKE);
            c.drawLine(x, top[0], x, bottomOf(PANEL_DP.length - 1), p);
            if (k % every == 0) {
                p.setStyle(Paint.Style.FILL); p.setColor(TXT); p.setTextSize(10 * sp);
                String lab = hm.format(new Date(t * 1000L));
                c.drawText(lab, x - p.measureText(lab) / 2, bottomOf(PANEL_DP.length - 1) + 16 * dp, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawRestless(Canvas c) {
        p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(46, 255, 122, 89));
        for (long[] r : s.night.restless) {
            float a = xOf((r[0] - s.t0) / 60f), b = xOf((r[0] + r[1] - s.t0) / 60f);
            rect.set(a, top[0], Math.max(b, a + 2 * dp), bottomOf(PANEL_DP.length - 1));
            c.drawRect(rect, p);
        }
    }

    private void drawStates(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        for (int m = 0; m < s.n; m++) {
            int st = s.state[m];
            if (st < 0) continue;
            p.setColor(st == 2 ? C_SLEEP : st == 1 ? C_STILL : st == 0 ? C_WAKE : C_UP);
            rect.set(xOf(m), top[0] + 2 * dp, xOf(m + 1) + 0.5f, bottomOf(0) - 2 * dp);
            c.drawRect(rect, p);
        }
    }

    /** A line with gaps where the value is missing; step = hold the value across its minute (5-minute windows). */
    private void drawLine(Canvas c, int i, float[] v, int color, float width, boolean step) {
        p.setStyle(Paint.Style.STROKE); p.setColor(color); p.setStrokeWidth(width * dp);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        boolean pen = false;
        for (int m = 0; m < s.n; m++) {
            if (Float.isNaN(v[m])) { pen = false; continue; }
            float x = step ? xOf(m) : xOf(m + 0.5f), y = yOf(i, Math.max(lo[i], Math.min(hi[i], v[m])));
            if (!pen) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
            if (step) path.lineTo(xOf(m + 1), y);
        }
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawScale(Canvas c, int i, int ticks, String fmt) {
        p.setStyle(Paint.Style.FILL); p.setColor(TXT); p.setTextSize(9 * sp);
        for (int k = 0; k < ticks; k++) {
            float v = lo[i] + (hi[i] - lo[i]) * k / (ticks - 1);
            float y = yOf(i, v);
            p.setColor(GRID); p.setStyle(Paint.Style.STROKE);
            c.drawLine(plotL, y, plotR, y, p);
            p.setStyle(Paint.Style.FILL); p.setColor(TXT);
            String lab = String.format(Locale.US, fmt, v);
            c.drawText(lab, plotL - 4 * dp - p.measureText(lab), y + 3 * dp, p);
        }
    }

    /** Bed, first sleep and wake lines, the lowest 5-minute heart rate, and where a partial night is cut off by the log. */
    private void drawMarks(Canvas c, SimpleDateFormat hm) {
        SleepNight.Night n = s.night;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f * dp); p.setColor(TXT_HI);
        long[] ts = {n.bedTs, n.firstSleepTs, n.endTs};
        String[] nm = {"bed", "asleep", "up"};
        for (int k = 0; k < 3; k++) {
            if (k == 0 && n.partialStart) continue;
            float x = xOf((ts[k] - s.t0) / 60f);
            c.drawLine(x, top[0] - 2 * dp, x, top[0] + PANEL_DP[0] * dp, p);
            p.setStyle(Paint.Style.FILL); p.setTextSize(9 * sp);
            String lab = nm[k];
            float tx = (k == 2 || k == 0) ? x - p.measureText(lab) - 3 * dp : x + 3 * dp;
            c.drawText(lab, tx, top[0] + 10 * dp, p);
            p.setStyle(Paint.Style.STROKE);
        }
        if (n.partialStart || n.partialEnd) {
            p.setStyle(Paint.Style.FILL); p.setColor(C_WAKE); p.setTextSize(9 * sp);
            String lab = n.partialStart ? "log starts here: earlier part not recorded" : "log ends here: night incomplete";
            float x = n.partialStart ? xOf((n.bedTs - s.t0) / 60f) + 3 * dp : xOf((n.endTs - s.t0) / 60f) - p.measureText(lab) - 3 * dp;
            c.drawText(lab, x, top[0] + 22 * dp, p);
        }
        if (n.hrLow5Ts > 0 && !Double.isNaN(n.hrLow5)) {
            float x = xOf((n.hrLow5Ts - s.t0) / 60f), y = yOf(1, (float) Math.max(lo[1], Math.min(hi[1], n.hrLow5)));
            p.setStyle(Paint.Style.STROKE); p.setColor(TXT_HI); p.setStrokeWidth(1.4f * dp);
            c.drawCircle(x, y, 4 * dp, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** The values at the touched minute, for the second header line. */
    String readout(SimpleDateFormat hm) {
        if (s == null || cursor < 0 || cursor >= s.n) return "";
        StringBuilder b = new StringBuilder();
        b.append(hm.format(new Date((s.t0 + cursor * 60L) * 1000L)));
        int st = s.state[cursor];
        b.append("  ").append(st < 0 ? "no data" : STATE_NAME[st]);
        b.append("  HR ").append(val(s.hr[cursor], "%.0f"));
        b.append("  breathing ").append(val(s.br[cursor], "%.1f"));
        b.append("  RMSSD ").append(val(s.rmssd[cursor], "%.0f"));
        b.append("  skin ").append(val(s.skin[cursor], "%.1f"));
        return b.toString();
    }

    private static String val(float v, String fmt) { return Float.isNaN(v) ? "-" : String.format(Locale.US, fmt, v); }

    /** Minute index for a touch x, or -1 outside the plot. */
    int minuteAt(float x) {
        if (s == null || x < plotL || x > plotR) return -1;
        int m = (int) ((x - plotL) / (plotR - plotL) * s.n);
        return Math.max(0, Math.min(s.n - 1, m));
    }

    private float downX, downY;

    /** A tap or a sideways drag reads a minute; an up-down drag is left to the page so it still scrolls. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (s == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                cursor = minuteAt(e.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY) && getParent() != null)
                    getParent().requestDisallowInterceptTouchEvent(true);
                cursor = minuteAt(e.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}

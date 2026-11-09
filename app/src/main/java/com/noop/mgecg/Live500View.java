package com.noop.mgecg;

// FILE VERSION 0.48.0 (9 Nov 2026): polished look. Slate grid instead of the maroon one, a soft plot background, a trace that fades in from the left with a layered glow and a bright leading dot, small amber R ticks, rounded card, header tiles (heart rate with a pulse dot, small-caps labels over bold values, a progress bar for the clean beats), a footer for the picture notes and the noise advice. Everything is still outside the waveform: header above, footer below.
// FILE VERSION 0.47.0 (9 Nov 2026): a calmer, bigger graph. The waveform has its own plot area; the information sits in a band above and a band below it instead of over the trace. The scroll is snapped to whole pixels (no sub-pixel shimmer), the scale eases smoothly (fast to open, slow to close, with a dead band), the per-frame allocations are gone.
// FILE VERSION 0.44.0 (7 Nov 2026): the trace is coloured by the strap's per-second quality (3 normal, 2 amber, 0-1 grey, lead-off gap) with a small legend; tap opens the landscape full screen (long press switches auto / fixed scale); own window length for the full screen.

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Live window of the raw ECG at 500 samples a second, on calibrated paper (small box 0.04 s and 0.1 mV, large box 0.2 s and 0.5 mV).
 * Auto scale follows the signal; tap opens the full screen. The trace is filtered for the eye only (see Live500);
 * the reports use their own zero-phase filter. Options (set from the ECG page): window length, sweep, paper theme.
 */
public class Live500View extends View {
    static volatile double windowMs = 6000.0;      // 6000 or 3500
    static volatile boolean sweep = false, paper = false;
    static volatile boolean qualityColours = true;        // 0.44.0: You > Settings > ECG quality colours
    private static final double GAP_MS = 400.0;       // sweep: the erase bar ahead of the newest sample
    private final Paint bg = new Paint(), border = new Paint(Paint.ANTI_ALIAS_FLAG), minor = new Paint(Paint.ANTI_ALIAS_FLAG), major = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trace = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), glow2 = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG), veil = new Paint(), cal = new Paint(Paint.ANTI_ALIAS_FLAG), rr = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] v = new float[Live500.CAP], tmp = new float[Live500.CAP];
    private final double[] t = new double[Live500.CAP];
    private final double[] bt = new double[128]; private final float[] ba = new float[128]; private final boolean[] bo = new boolean[128];
    private final Path path = new Path(), calPath = new Path(), pathA = new Path(), pathG = new Path(), tri = new Path(), clipP = new Path();
    private final RectF rect = new RectF();
    private final byte[] qv = new byte[Live500.CAP];
    private final Paint trA = new Paint(Paint.ANTI_ALIAS_FLAG), trG = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hdrBg = new Paint(), plotBg = new Paint(), divP = new Paint(), barBg = new Paint(Paint.ANTI_ALIAS_FLAG), barFg = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG), zeroP = new Paint(), capP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hrP = new Paint(Paint.ANTI_ALIAS_FLAG), valP = new Paint(Paint.ANTI_ALIAS_FLAG), unitP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader shT, shA, shG, shPlot; private int shW = -1, shH = -1; private boolean shPaper;
    private double winOverrideMs;                          // 0.44.0: the full screen shows 10 s; 0 = the setting
    private boolean openFull = true;                       // 0.44.0: tap opens the full screen (there a tap switches the scale)
    private boolean autoScale = true;
    private float yMin = -0.5f, yMax = 1.0f;          // millivolts, smoothed
    private long lastScaleMs, lastEaseMs; private float tgtMax = 1.0f, tgtMin = -0.5f;
    private Runnable staleCb;
    private boolean wasLive;
    private long staleSince;
    private final float dp;
    private long hdrMs; private String hrS = "--", rrTxt = "--", qrsTxt = "--", nzTxt = "--", nzNote = "", cleanTxt = "--", armTxt = ""; private int nzCol; private float cleanFrac; private double lastRrMs = Double.NaN; private boolean armMoving;
    private float pt, pb, ph;                           // plot area: top, bottom, height (px)
    private String footLeft = ""; private int footLeftCol; private float fMaxLeft = 200f;
    private static final String[] NM = { "good", "fair", "poor" };
    private final int[] cc = new int[3]; private final String[] capsA = new String[5], valsA = new String[5]; private final int[] kindA = new int[5];

    public Live500View(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        trace.setStyle(Paint.Style.STROKE); trace.setStrokeJoin(Paint.Join.ROUND); trace.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE); glow.setStrokeJoin(Paint.Join.ROUND); glow.setStrokeCap(Paint.Cap.ROUND);
        glow2.setStyle(Paint.Style.STROKE); glow2.setStrokeJoin(Paint.Join.ROUND); glow2.setStrokeCap(Paint.Cap.ROUND);
        border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(1.2f * dp);
        label.setTextSize(10.5f * dp); label.setTypeface(HealthViews.font(c, 400)); label.setSubpixelText(true);
        rr.setTextSize(9.5f * dp); rr.setTextAlign(Paint.Align.CENTER); rr.setTypeface(HealthViews.font(c, 400)); rr.setSubpixelText(true);
        big.setColor(Color.parseColor("#FFB347")); big.setTextSize(13f * dp); big.setTextAlign(Paint.Align.CENTER); big.setTypeface(HealthViews.font(c, 600));
        cal.setStyle(Paint.Style.STROKE); cal.setStrokeWidth(1.4f * dp);
        mark.setStyle(Paint.Style.FILL);
        trA.setStyle(Paint.Style.STROKE); trA.setStrokeJoin(Paint.Join.ROUND); trA.setStrokeCap(Paint.Cap.ROUND);
        trG.setStyle(Paint.Style.STROKE); trG.setStrokeJoin(Paint.Join.ROUND); trG.setStrokeCap(Paint.Cap.ROUND);
        dot.setStyle(Paint.Style.FILL); halo.setStyle(Paint.Style.FILL); barBg.setStyle(Paint.Style.FILL); barFg.setStyle(Paint.Style.FILL);
        divP.setStrokeWidth(1f); zeroP.setStrokeWidth(1f); zeroP.setStyle(Paint.Style.STROKE);
        hrP.setTextSize(31f * dp); hrP.setTypeface(HealthViews.font(c, 700)); hrP.setSubpixelText(true);
        unitP.setTextSize(11f * dp); unitP.setTypeface(HealthViews.font(c, 500));
        valP.setTextSize(14f * dp); valP.setTypeface(HealthViews.font(c, 600)); valP.setSubpixelText(true);
        capP.setTextSize(8.5f * dp); capP.setTypeface(HealthViews.font(c, 600)); capP.setLetterSpacing(0.10f);
        setOnClickListener(x -> { if (openFull) openFullScreen(); else { autoScale = !autoScale; invalidate(); } });
        setOnLongClickListener(x -> { if (!openFull) return false; autoScale = !autoScale; invalidate(); return true; });
    }

    /** 0.44.0: window length for this view in ms (0 = the You > Settings value). */
    void setWindowMs(double ms) { winOverrideMs = ms; }
    /** 0.44.0: true (default) = a tap opens the landscape full screen; false = a tap switches auto / fixed scale. */
    void setTapOpensFull(boolean on) { openFull = on; }

    private void openFullScreen() {
        try {
            Context c = getContext(); Intent i = new Intent(c, LiveFullActivity.class);
            if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) { }
    }

    /** Called once on the UI thread when the frames stop arriving for a few seconds, so the screen can go back to the 100 Hz graph. */
    public void setStaleCallback(Runnable r) { staleCb = r; }

    private final float[] tmp2 = new float[500];
    private float qrsMv;                                // 0.20.0: smoothed QRS height in mV, shown so finger placement can be tried live

    private static int a(int color, int alpha) { return (alpha << 24) | (color & 0xFFFFFF); }

    private void theme() {
        boolean p = paper;
        bg.setColor(p ? 0xFFFFFCF7 : 0xFF0B0F15);
        hdrBg.setColor(p ? 0xFFF6F0E6 : 0xFF111720);
        minor.setColor(p ? 0xFFF7D9D9 : 0xFF151C27); minor.setStrokeWidth(0.8f * dp);
        major.setColor(p ? 0xFFEDB1B1 : 0xFF212B3A); major.setStrokeWidth(1.2f * dp);
        zeroP.setColor(p ? 0xFFD8B9B9 : 0xFF2C384A);
        divP.setColor(p ? 0x22000000 : 0x24FFFFFF);
        border.setColor(p ? 0xFFD9C9C9 : 0xFF1C2431);
        int tc = p ? 0xFF14171F : 0xFF35E0A1, ac = p ? 0xFFC77D0A : 0xFFF2B84B, gc = p ? 0xFF9AA0AB : 0xFF7C8798;
        trace.setColor(tc); trace.setStrokeWidth(1.8f * dp);
        glow.setColor(p ? 0x00000000 : a(tc, 54)); glow.setStrokeWidth(4.2f * dp);
        glow2.setColor(p ? 0x00000000 : a(tc, 22)); glow2.setStrokeWidth(8f * dp);
        label.setColor(p ? 0xFF4A4F5C : 0xFF8795AB);
        rr.setColor(p ? 0xFF6A5A5A : 0xFF73839C);
        cal.setColor(p ? 0x9914171F : 0x998795AB);
        mark.setColor(p ? 0xFFC0392B : 0xFFF2B84B);
        veil.setColor(p ? 0xB8FFFCF7 : 0xB80B0F15);
        trA.setColor(ac); trA.setStrokeWidth(trace.getStrokeWidth());
        trG.setColor(gc); trG.setStrokeWidth(trace.getStrokeWidth());
        barBg.setColor(p ? 0x18000000 : 0x26FFFFFF); barFg.setColor(tc);
        capP.setColor(p ? 0xFF6B7080 : 0xFF74829A);
    }

    /** The fade-in shaders and the plot gradient; rebuilt when the size or the theme changes. */
    private void shaders(int w, int h) {
        if (w == shW && h == shH && shPaper == paper && shT != null) return;
        shW = w; shH = h; shPaper = paper;
        int tc = paper ? 0xFF14171F : 0xFF35E0A1, ac = paper ? 0xFFC77D0A : 0xFFF2B84B, gc = paper ? 0xFF9AA0AB : 0xFF7C8798;
        float[] pos = { 0f, 0.5f, 1f };
        shT = new LinearGradient(0, 0, w, 0, new int[] { a(tc, 70), tc, tc }, pos, Shader.TileMode.CLAMP);
        shA = new LinearGradient(0, 0, w, 0, new int[] { a(ac, 70), ac, ac }, pos, Shader.TileMode.CLAMP);
        shG = new LinearGradient(0, 0, w, 0, new int[] { a(gc, 70), gc, gc }, pos, Shader.TileMode.CLAMP);
        shPlot = new LinearGradient(0, 0, 0, Math.max(1, h), paper ? 0xFFFFFDF9 : 0xFF0F151D, paper ? 0xFFFAF4EA : 0xFF0B0F15, Shader.TileMode.CLAMP);
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
        // 0.47.0: only the TARGETS change here; easeScale() moves the visible scale a little every frame. A target opens at once but closes only when it is more than 12 % of the range away (a dead band: no hunting).
        float wantMax = Math.max(0.8f, hi * 1.25f), wantMin = Math.min(-0.4f, lo * 1.4f), span0 = Math.max(0.5f, tgtMax - tgtMin);
        if (wantMax > tgtMax || wantMax < tgtMax - 0.12f * span0) tgtMax = wantMax;
        if (wantMin < tgtMin || wantMin > tgtMin + 0.12f * span0) tgtMin = wantMin;
    }

    private void easeScale(long now) {
        if (!autoScale) return;
        float dt = lastEaseMs == 0 ? 0.016f : Math.max(0.001f, Math.min(0.1f, (now - lastEaseMs) / 1000f)); lastEaseMs = now;
        float kOpen = 1f - (float) Math.exp(-dt / 0.12f), kClose = 1f - (float) Math.exp(-dt / 1.6f);
        yMax += (tgtMax - yMax) * (tgtMax > yMax ? kOpen : kClose);
        yMin += (tgtMin - yMin) * (tgtMin < yMin ? kOpen : kClose);
    }

    /** Time to x. nowQ is the clock snapped to whole pixels of scroll (win / width ms), so every frame is an exact whole-pixel shift of the last. */
    private float xOf(double ts, double nowQ, int w, double win) {
        if (!sweep) return (float) (w * (1.0 - (nowQ - ts) / win));
        double m = ts % win; if (m < 0) m += win; return (float) (w * (m / win));
    }

    private float yOf(float mv, float lo, float span) { return pb - (mv - lo) / span * ph; }

    /** The header strings, rebuilt four times a second (no per-frame formatting). */
    private void refreshHeader(long now) {
        if (now - hdrMs < 250) return; hdrMs = now;
        java.util.Locale L = java.util.Locale.UK;
        double rrS = Live500.medianRrS();
        hrS = (rrS > 0.25 && rrS < 2.5) ? String.format(L, "%.0f", 60.0 / rrS) : "--";
        rrTxt = !Double.isNaN(lastRrMs) ? String.format(L, "%.0f ms", lastRrMs) : "--";
        qrsTxt = qrsMv > 0f ? String.format(L, "%.2f mV", qrsMv) : "--";
        double[] b = LiveBands.rms(); double n = b == null ? Double.NaN : b[1];
        if (Double.isNaN(n)) { nzTxt = "--"; nzNote = ""; nzCol = 0; }
        else { nzTxt = String.format(L, "%.0f \u00b5V", n); nzNote = n <= 27 ? "" : n <= 40 ? "Signal fair: relax your hand, keep still" : "Signal noisy: rest the whole forearm, keep still"; nzCol = n <= 27 ? 0xFF3FB950 : n <= 40 ? 0xFFD4A72C : 0xFFE5534B; }
        int cb = Live500.cleanBeats(); cleanTxt = String.format(L, "%d/300", Math.min(cb, 999)); cleanFrac = Math.min(1f, cb / 300f);
        armTxt = LiveMotion.fresh(now) ? (LiveMotion.moving() ? "moving" : "still") : ""; armMoving = LiveMotion.fresh(now) && LiveMotion.moving();
        if (nzNote.length() > 0) { footLeft = nzNote; footLeftCol = nzCol; }
        else {
            String[] parts = { "Lead I", "500 Hz", Live500.modeLabel(), String.format(L, "%.1f s delay", Live500.delaySeconds()), autoScale ? "auto scale" : "fixed -1 to +2 mV" };
            int np = parts.length; String left;
            while (true) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < np; i++) { if (i > 0) sb.append("  \u00b7  "); sb.append(parts[i]); } left = sb.toString(); if (np <= 2 || label.measureText(left) <= fMaxLeft) break; np--; }
            footLeft = left; footLeftCol = 0;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        long now = System.currentTimeMillis();
        final double win = winOverrideMs > 0 ? winOverrideMs : windowMs;
        theme(); shaders(w, h);
        float radius = winOverrideMs > 0 ? 0f : 14f * dp;
        canvas.save();
        if (radius > 0f) { clipP.reset(); rect.set(0, 0, w, h); clipP.addRoundRect(rect, radius, radius, Path.Direction.CW); canvas.clipPath(clipP); }
        canvas.drawRect(0, 0, w, h, bg);
        float rightPad = winOverrideMs > 0 ? 58f * dp : 12f * dp;                  // the full screen has its close button top right
        float headH = Math.min(58f * dp, 0.22f * h), footH = Math.min(22f * dp, 0.09f * h);
        pt = headH; pb = h - footH; ph = Math.max(10f, pb - pt);
        int m = Live500.snapshotQ(now, win, v, t, qv);
        updateScale(now, m); easeScale(now);
        float lo = autoScale ? yMin : -1f, hi = autoScale ? yMax : 2f, span = Math.max(0.3f, hi - lo);
        double msPerPx = win / Math.max(1, w); final double nowQ = Math.floor(now / msPerPx) * msPerPx;

        // ---- plot area
        canvas.save(); canvas.clipRect(0, pt, w, pb);
        plotBg.setShader(shPlot); canvas.save(); canvas.translate(0, pt); canvas.drawRect(0, 0, w, ph, plotBg); canvas.restore(); plotBg.setShader(null);
        float vx = w / (float) (win / 40.0);                           // 0.04 s boxes
        int col = 0;
        for (float x = 0; x <= w; x += vx, col++) canvas.drawLine(x, pt, x, pb, (col % 5 == 0) ? major : minor);
        boolean fine = span <= 3.3f;
        for (int i = (int) Math.floor(lo * 10); i <= (int) Math.ceil(hi * 10); i++) {
            boolean big5 = i % 5 == 0;
            if (!big5 && !fine) continue;
            float y = yOf(i / 10f, lo, span);
            canvas.drawLine(0, y, w, y, big5 ? major : minor);
        }
        { float yz = yOf(0f, lo, span); canvas.drawLine(0, yz, w, yz, zeroP); }                   // the 0 mV line, a little stronger than the grid

        path.reset(); pathA.reset(); pathG.reset();
        boolean down = false, qc = qualityColours;
        int nanTail = 0, curCls = 0; float lastX = -1f, pvx = 0f, pvy = 0f; int lastCls = -1;
        for (int i = 0; i < m; i++) {
            float mv = v[i] / 1000f;
            if (Float.isNaN(mv)) { down = false; nanTail++; continue; }
            nanTail = 0;
            if (sweep && (now - t[i]) > win - GAP_MS) { down = false; continue; }          // the erase bar
            float x = xOf(t[i], nowQ, w, win);
            float y = yOf(mv, lo, span);
            if (y < pt - ph) y = pt - ph; else if (y > pb + ph) y = pb + ph;
            if (sweep && x < lastX) down = false;                                          // wrapped round
            lastX = x;
            int cls = !qc ? 0 : (qv[i] & 0xFF) >= 3 ? 0 : (qv[i] & 0xFF) == 2 ? 1 : 2;      // 0.44.0: 3 normal, 2 amber, 0-1 grey
            Path tp = cls == 0 ? path : cls == 1 ? pathA : pathG;
            if (!down) { tp.moveTo(x, y); down = true; curCls = cls; }
            else if (cls != curCls) { tp.moveTo(pvx, pvy); tp.lineTo(x, y); curCls = cls; }      // the new colour carries on from the last point
            else tp.lineTo(x, y);
            pvx = x; pvy = y; lastCls = cls;
        }
        if (!paper) { glow2.setShader(shT); glow.setShader(shT); canvas.drawPath(path, glow2); canvas.drawPath(path, glow); }
        if (qc) { trG.setShader(shG); trA.setShader(shA); canvas.drawPath(pathG, trG); canvas.drawPath(pathA, trA); }
        trace.setShader(shT); canvas.drawPath(path, trace);

        boolean pro = Live500.pro; lastRrMs = Double.NaN;
        if (pro && m > 0) {
            int nb = Live500.proBeats(now, win, bt, ba, bo);
            for (int i = 0; i < nb; i++) {                                                   // dim the beats that do not match the typical shape
                if (!bo[i] && !sweep) { float xa = xOf(bt[i] - 250, nowQ, w, win), xb = xOf(bt[i] + 450, nowQ, w, win); canvas.drawRect(Math.max(0, xa), pt, Math.min(w, xb), pb, veil); }
            }
            if (nb >= 2 && bo[nb - 1] && bo[nb - 2]) { double ms = bt[nb - 1] - bt[nb - 2]; if (ms > 300 && ms < 2500) lastRrMs = ms; }
            for (int i = 0; i < nb; i++) {
                float x = xOf(bt[i], nowQ, w, win); if (x < 0 || x > w) continue;
                float amp = ba[i] / 1000f; float y = yOf(amp >= 0 ? amp : 0f, lo, span) - 8f * dp;
                if (y < pt + 7f * dp) y = pt + 7f * dp;
                mark.setAlpha(bo[i] ? 235 : 80);
                tri.reset(); tri.moveTo(x - 3.2f * dp, y - 4.4f * dp); tri.lineTo(x + 3.2f * dp, y - 4.4f * dp); tri.lineTo(x, y + 0.5f * dp); tri.close();
                canvas.drawPath(tri, mark);
                if (i > 0 && bo[i] && bo[i - 1] && !sweep) {
                    double ms = bt[i] - bt[i - 1];
                    if (ms > 300 && ms < 2500) canvas.drawText(Integer.toString((int) Math.round(ms)), (x + xOf(bt[i - 1], nowQ, w, win)) / 2f, pt + 15f * dp, rr);
                }
            }
        }
        // the leading dot: the newest sample, so the eye finds where "now" is
        if (!sweep && m > 0 && lastCls == 0 && pvx > w * 0.85f) {
            int tc = trace.getColor(); halo.setColor(a(tc, paper ? 40 : 60)); canvas.drawCircle(pvx, pvy, 7f * dp, halo); dot.setColor(tc); canvas.drawCircle(pvx, pvy, 2.8f * dp, dot);
        }
        // 1 mV calibration pulse, 0.2 s wide, at the left edge
        float y0 = yOf(0f, lo, span), y1 = yOf(1f, lo, span), px = 8f * dp, pw = (float) (w * 200.0 / win);
        calPath.reset(); calPath.moveTo(px, y0); calPath.lineTo(px + 5f * dp, y0); calPath.lineTo(px + 5f * dp, y1); calPath.lineTo(px + 5f * dp + pw, y1); calPath.lineTo(px + 5f * dp + pw, y0); calPath.lineTo(px + 10f * dp + pw, y0);
        canvas.drawPath(calPath, cal);

        boolean live = Live500.isLive(now);
        if (live) { wasLive = true; staleSince = 0; }
        else if (wasLive) {
            if (staleSince == 0) staleSince = now;
            else if (now - staleSince > 2500) { wasLive = false; staleSince = 0; if (staleCb != null) post(staleCb); }
        }
        if (m == 0) canvas.drawText("Waiting for the live 500 Hz frames", w / 2f, (pt + pb) / 2f, big);
        else if (nanTail >= 250) canvas.drawText("No finger contact", w / 2f, (pt + pb) / 2f, big);
        canvas.restore();

        // ---- header band
        float legW = 0f; if (qc && m > 250) { for (String s2 : NM) legW += 6f * dp + 4f * dp + label.measureText(s2) + 8f * dp; }
        fMaxLeft = w - rightPad - legW - 28f * dp;
        refreshHeader(now);
        boolean pp = paper; int ink = pp ? 0xFF14171F : 0xFFE8EEF7, muted = pp ? 0xFF4A4F5C : 0xFF8795AB, tcol = pp ? 0xFF1B7F5C : 0xFF35E0A1;
        canvas.drawRect(0, 0, w, headH, hdrBg);
        canvas.drawLine(0, headH, w, headH, divP);
        float pad = 12f * dp, base = headH - 13f * dp;
        double due = Live500.newestBeatDue(now); float pulse = 0f; if (!Double.isNaN(due)) { double age = now - due; if (age >= 0 && age < 420) pulse = (float) (1.0 - age / 420.0); }
        halo.setColor(a(tcol, (int) (70 * pulse))); canvas.drawCircle(pad + 5f * dp, headH * 0.52f, (5f + 5f * pulse) * dp, halo);
        dot.setColor(tcol); canvas.drawCircle(pad + 5f * dp, headH * 0.52f, (3.4f + 1.2f * pulse) * dp, dot);
        float x = pad + 16f * dp;
        hrP.setColor(tcol); canvas.drawText(hrS, x, base + 1f * dp, hrP);
        float hw = hrP.measureText(hrS); unitP.setColor(muted); canvas.drawText("bpm", x + hw + 4f * dp, base + 1f * dp, unitP);
        x += hw + unitP.measureText("bpm") + 18f * dp;
        float avail = w - rightPad;
        int nt = 0;
        capsA[nt] = "NOISE"; valsA[nt] = nzTxt; kindA[nt++] = 0;
        capsA[nt] = "CLEAN"; valsA[nt] = cleanTxt; kindA[nt++] = 1;
        if (armTxt.length() > 0) { capsA[nt] = "ARM"; valsA[nt] = armTxt; kindA[nt++] = 4; }
        capsA[nt] = "QRS"; valsA[nt] = qrsTxt; kindA[nt++] = 2;
        capsA[nt] = "R-R"; valsA[nt] = rrTxt; kindA[nt++] = 3;
        for (int c = 0; c < nt; c++) {
            int kind = kindA[c];
            float cw = Math.max(valP.measureText(valsA[c]), capP.measureText(capsA[c]));
            if (kind == 1) cw = Math.max(cw, 46f * dp);
            if (x + cw > avail) break;
            canvas.drawLine(x - 9f * dp, headH * 0.22f, x - 9f * dp, headH * 0.78f, divP);
            canvas.drawText(capsA[c], x, headH * 0.36f, capP);
            valP.setColor(kind == 0 && nzCol != 0 ? nzCol : kind == 4 && armMoving ? 0xFFE5534B : ink);
            canvas.drawText(valsA[c], x, headH * 0.70f, valP);
            if (kind == 1) { float by = headH * 0.84f, bh = 2.6f * dp; rect.set(x, by, x + cw, by + bh); canvas.drawRoundRect(rect, bh / 2, bh / 2, barBg); rect.set(x, by, x + Math.max(bh, cw * cleanFrac), by + bh); canvas.drawRoundRect(rect, bh / 2, bh / 2, barFg); }
            x += cw + 20f * dp;
        }
        // ---- footer band: advice when the signal is not good, otherwise what the picture is; the colour legend on the right
        float fy = h - footH * 0.32f;
        label.setColor(footLeftCol != 0 ? footLeftCol : muted); canvas.drawText(footLeft, pad, fy, label);
        if (qc && m > 250) {                                                                // 0.44.0 legend, in the footer
            cc[0] = trace.getColor(); cc[1] = trA.getColor(); cc[2] = trG.getColor();
            float gap = 8f * dp, rad = 3f * dp, lx = w - rightPad - legW + gap; label.setColor(muted);
            for (int i = 0; i < 3; i++) { dot.setColor(cc[i]); canvas.drawCircle(lx + rad, fy - 3.5f * dp, rad, dot); lx += rad * 2f + 4f * dp; canvas.drawText(NM[i], lx, fy, label); lx += label.measureText(NM[i]) + gap; }
        }
        canvas.restore();
        if (radius > 0f) { rect.set(0.6f * dp, 0.6f * dp, w - 0.6f * dp, h - 0.6f * dp); canvas.drawRoundRect(rect, radius, radius, border); } else canvas.drawRect(1, 1, w - 1, h - 1, border);
        postInvalidateOnAnimation();
    }
}

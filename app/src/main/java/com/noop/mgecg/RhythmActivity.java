package com.noop.mgecg;

// FILE VERSION 0.94.0 (26 Nov 2026): NEW. The Rhythm screen of one reading (opened from the Strip screen and from the ECG card on the phone). Beat timing only: the word the report gave (read from its meta tag, never recomputed here),
// the gaps between the beats marked on the strip (RhythmLogic) as a time plot and a steadiness plot (each gap against the next), the HRV figure the report gave, and the report's own count of early beats when it carries one.
// Nothing here writes a report or a file. Read-only; the raw file is read with EcgR16Analyzer.stripQuiet like the Strip screen.

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RhythmActivity extends Activity {
    private static final int BG = HealthViews.BG, INK = HealthViews.INK, MUT = HealthViews.MUT, CARD = HealthViews.CARD, BLUE = HealthViews.BLUE, GOLD = HealthViews.GOLD;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG); } catch (Throwable ignored) { }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        final String path = getIntent() == null ? null : getIntent().getStringExtra("path");
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(4));
        TextView back = text("‹", 30, INK, 600);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(CARD, 22));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text("Rhythm" + (path == null ? "" : "  ·  " + whenOf(path)), 18, INK, 600);
        title.setPadding(dp(14), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(4), dp(12), dp(24));
        sv.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        body.addView(text("Beat timing only", 13, MUT, 400), lp(-2, 2));
        status = text("Reading the stored samples…", 13, MUT, 400);
        body.addView(status, lp(-2, 10));
        if (path == null) { status.setText("No reading was given."); return; }
        final File report = new File(path);
        new Thread(new Runnable() {
            @Override public void run() {
                String head = readHead(report, 16000), err = null;
                EcgR16Analyzer.Strip s = null;
                try {
                    File bin = ProReportMaker.findBin(report.getParentFile(), ProReportMaker.unixOf(report));
                    if (bin == null) err = "The raw file for this reading is not on this phone, so the timing plots are not drawn.";
                    else {
                        List<byte[]> mine = new ArrayList<>();
                        for (byte[] f : ProAnalysis.frames(readAll(bin))) if (f.length == 1584 && (f[9] & 0xFF) == 16 && ProAnalysis.crcOk(f)) mine.add(f);
                        if (mine.size() < 5) err = "The raw file holds too few records (" + mine.size() + ") to draw.";
                        else s = EcgR16Analyzer.stripQuiet(mine);
                    }
                } catch (Throwable t) { err = "Could not read the stored samples: " + t.getClass().getSimpleName(); }
                RhythmLogic.Gaps g = null;
                try { if (s != null) g = RhythmLogic.of(s.beats, 500.0); } catch (Throwable t) { g = null; err = "Could not read the beat timing."; }
                final RhythmLogic.Gaps gg = g; final String ee = err, hh = head;
                ui.post(new Runnable() { @Override public void run() { if (!isFinishing()) fill(hh, gg, ee); } });
            }
        }, "rhythm-load").start();
    }

    private void fill(String head, RhythmLogic.Gaps g, String err) {
        body.removeView(status);
        String word = OwnRange.meta(head == null ? "" : head, "ecg-rhythm"), hrv = OwnRange.meta(head == null ? "" : head, "ecg-hrv"), early = OwnRange.meta(head == null ? "" : head, "ecg-early");
        boolean haveWord = word != null && !word.equals("-") && !word.isEmpty();

        LinearLayout c = card();
        c.addView(caps("THE REPORT SAYS"));
        c.addView(text(haveWord ? word : "No rhythm word in this report", 30, INK, 600), lp(-2, 4));
        c.addView(text(RhythmLogic.line(g), 13, MUT, 400), lp(-2, 6));
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(stat("HRV (RMSSD)", hrv == null || hrv.equals("-") ? "–" : hrv + " ms", "from the report"), new LinearLayout.LayoutParams(0, -2, 1f));
        stats.addView(stat("Median gap", g != null && g.n >= 3 ? Math.round(g.med) + " ms" : "–", "between beats"), new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(stats, lp(-1, 14));
        if (early != null && !early.equals("-"))
            c.addView(text("The report counts " + early + " early " + (early.equals("1") ? "beat" : "beats") + " in the whole reading. The count above is from the beats marked on the strip.", 11.5f, MUT, 400), lp(-2, 10));
        body.addView(c, lp(-1, 10));

        if (g == null || g.n < 3) {
            body.addView(text(err != null ? err : "Too few beats are marked on the strip to draw the timing. Beats are marked only in clean stretches of 5 s or more.", 13, MUT, 400), lp(-2, 12));
        } else {
            LinearLayout t = card();
            t.addView(caps("TIME BETWEEN BEATS"));
            t.addView(new GapPlot(this, g), lp(-1, 8, 190));
            t.addView(text("Each point is the gap before one marked beat. The dashed line is the median. " + (g.earlyCount == 0 ? "Early beats would show as a gold triangle; there are none." : "Early beats are the gold triangles.")
                    + " " + RhythmLogic.rangeLine(g), 12, MUT, 400), lp(-2, 8));
            body.addView(t, lp(-1, 12));
            LinearLayout q = card();
            q.addView(caps("STEADINESS PLOT"));
            q.addView(new Poincare(this, g), lp(-1, 8, -3));
            q.addView(text("Each point is one gap against the gap after it. A tight cluster on the dashed line means steady timing. Gold points involve an early beat.", 12, MUT, 400), lp(-2, 8));
            body.addView(q, lp(-1, 12));
        }
        TextView d = text("This screen reads timing only. It cannot see rhythms that keep a steady beat, such as atrial flutter. Regular is not the same as healthy.", 12, MUT, 400);
        d.setLineSpacing(0, 1.15f);
        body.addView(d, lp(-2, 16));
    }

    // ------------------------------------------------------------------ plots

    /** The gap before every marked beat against time, median dashed, early beats as gold triangles. */
    static final class GapPlot extends View {
        private final RhythmLogic.Gaps g;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        GapPlot(Context c, RhythmLogic.Gaps g) { super(c); this.g = g; }
        private float dpx(float v) { return HealthViews.dp(getContext(), v); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float left = dpx(34), right = dpx(6), top = dpx(10), bot = h - dpx(18), pw = w - left - right;
            double[] ax = RhythmLogic.axis(g);
            double t0 = g.t[0], t1 = Math.max(g.t[g.n - 1], t0 + 1.0);
            p.setStyle(Paint.Style.FILL); p.setColor(HealthViews.MUT); p.setTextSize(dpx(10));
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(String.valueOf(Math.round(ax[1])), left - dpx(4), top + dpx(8), p);
            cv.drawText(String.valueOf(Math.round(ax[0])), left - dpx(4), bot, p);
            p.setTextAlign(Paint.Align.LEFT); cv.drawText(StripViewport.clock(t0).replaceAll("\\.0$", ""), left, h - dpx(3), p);
            p.setTextAlign(Paint.Align.RIGHT); cv.drawText(StripViewport.clock(t1).replaceAll("\\.0$", ""), w - right, h - dpx(3), p);
            p.setTextAlign(Paint.Align.LEFT);
            // median
            float ym = y(g.med, ax, top, bot);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dpx(1)); p.setColor(0x66FFFFFF); p.setPathEffect(new DashPathEffect(new float[] { dpx(5), dpx(4) }, 0));
            cv.drawLine(left, ym, w - right, ym, p); p.setPathEffect(null);
            // line between neighbours of one unbroken chain, then the points
            p.setColor(0x552FA8F0); p.setStrokeWidth(dpx(1.2f));
            path.reset(); boolean pen = false;
            for (int k = 0; k < g.n; k++) {
                float x = left + (float) ((g.t[k] - t0) / (t1 - t0) * pw), yy = y(g.ms[k], ax, top, bot);
                if (!pen || !g.adj[k]) path.moveTo(x, yy); else path.lineTo(x, yy);
                pen = true;
            }
            cv.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            for (int k = 0; k < g.n; k++) {
                float x = left + (float) ((g.t[k] - t0) / (t1 - t0) * pw), yy = y(g.ms[k], ax, top, bot);
                if (g.early[k]) {
                    p.setColor(GOLD); float r = dpx(6);
                    path.reset(); path.moveTo(x, yy - dpx(2)); path.lineTo(x - r, yy - dpx(2) - r * 1.5f); path.lineTo(x + r, yy - dpx(2) - r * 1.5f); path.close(); cv.drawPath(path, p);
                }
                p.setColor(g.early[k] ? GOLD : BLUE); cv.drawCircle(x, yy, dpx(2.6f), p);
            }
        }
        private float y(double v, double[] ax, float top, float bot) { float y = (float) (bot - (v - ax[0]) / (ax[1] - ax[0]) * (bot - top)); return y < top ? top : (y > bot ? bot : y); }
    }

    /** Each gap against the gap after it (both from one unbroken chain), the diagonal dashed. A square, as wide as the card. */
    static final class Poincare extends View {
        private final RhythmLogic.Gaps g;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Poincare(Context c, RhythmLogic.Gaps g) { super(c); this.g = g; }
        private float dpx(float v) { return HealthViews.dp(getContext(), v); }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = View.MeasureSpec.getSize(wSpec);
            int h = Math.min(w, (int) dpx(300));
            setMeasuredDimension(w, h);
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float left = dpx(34), right = dpx(8), top = dpx(8), bot = h - dpx(18);
            float side = Math.min(w - left - right, bot - top);
            float x0 = left + (w - left - right - side) / 2f, y1 = bot, y0 = bot - side;
            double[] ax = RhythmLogic.axis(g);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dpx(1)); p.setColor(0x22FFFFFF);
            cv.drawRect(x0, y0, x0 + side, y1, p);
            p.setColor(0x66FFFFFF); p.setPathEffect(new DashPathEffect(new float[] { dpx(5), dpx(4) }, 0));
            cv.drawLine(x0, y1, x0 + side, y0, p); p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            for (int k = 1; k < g.n; k++) {
                if (!g.adj[k]) continue;
                float x = x0 + (float) ((g.ms[k - 1] - ax[0]) / (ax[1] - ax[0]) * side), y = y1 - (float) ((g.ms[k] - ax[0]) / (ax[1] - ax[0]) * side);
                p.setColor((g.early[k] || g.early[k - 1]) ? GOLD : 0xB32FA8F0);
                cv.drawCircle(Math.max(x0, Math.min(x0 + side, x)), Math.max(y0, Math.min(y1, y)), dpx(2.6f), p);
            }
            p.setColor(HealthViews.MUT); p.setTextSize(dpx(10));
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(String.valueOf(Math.round(ax[1])), x0 - dpx(4), y0 + dpx(8), p);
            cv.drawText(String.valueOf(Math.round(ax[0])), x0 - dpx(4), y1, p);
            p.setTextAlign(Paint.Align.LEFT); cv.drawText(String.valueOf(Math.round(ax[0])), x0, h - dpx(3), p);
            p.setTextAlign(Paint.Align.RIGHT); cv.drawText(Math.round(ax[1]) + " ms  gap before a beat", x0 + side, h - dpx(3), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ------------------------------------------------------------------ small helpers

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(CARD, 22));
        return c;
    }

    private TextView caps(String s) { TextView t = text(s, 10.5f, MUT, 600); t.setLetterSpacing(0.12f); return t; }

    private View stat(String label, String value, String sub) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(text(label, 11.5f, MUT, 400));
        l.addView(text(value, 20, INK, 600));
        l.addView(text(sub, 11, MUT, 400));
        return l;
    }

    private LinearLayout.LayoutParams lp(int w, int topDp) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, -2); l.topMargin = dp(topDp); return l; }

    /** height -3 = the view measures itself (square). */
    private LinearLayout.LayoutParams lp(int w, int topDp, int hDp) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, hDp == -3 ? -2 : dp(hDp)); l.topMargin = dp(topDp); return l; }

    private static String whenOf(String path) {
        long u = ProReportMaker.unixOf(new File(path));
        return u <= 0 ? "" : new java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(new java.util.Date(u * 1000L));
    }

    private static String readHead(File f, int max) {
        try {
            FileInputStream in = new FileInputStream(f);
            try { byte[] b = new byte[max]; int n = in.read(b); return n <= 0 ? null : new String(b, 0, n, "UTF-8"); } finally { in.close(); }
        } catch (Exception e) { return null; }
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0, k;
            while (off < b.length && (k = in.read(b, off, b.length - off)) > 0) off += k;
            return b;
        } finally { in.close(); }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        try { t.setTypeface(HealthViews.font(this, weight)); } catch (Throwable ignored) { t.setTypeface(weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); }
        return t;
    }

    private GradientDrawable round(int color, int rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(rDp));
        return g;
    }
}

package com.noop.mgecg;

// FILE VERSION 0.92.0 (25 Nov 2026): NEW. The interactive strip of one reading: drag to move through the recording, pinch to zoom from 1 s to 30 s, tap a beat for the gap before it, double-tap to go back
// to the first clean 6 seconds. Under the trace a quality ribbon (strap quality per sample) and, where they happened, the strap's recovery windows (amber) and rail hits (red). Read-only: it rebuilds the trace
// from the reading's own stored raw file (realtime_ecg_bin_*.bin) with EcgR16Analyzer.stripQuiet, which neither pools the beats nor rewrites the report. Opened from the report (Strip button) and the ECG tab.

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class StripActivity extends Activity {
    private static final int BG = HealthViews.BG, INK = HealthViews.INK, MUT = HealthViews.MUT, CARD = HealthViews.CARD;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView info, hint;
    private StripView view;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG); } catch (Throwable ignored) { }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(4));
        TextView back = text("‹", 30, INK, 600);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(CARD, 22));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        final String path = getIntent() == null ? null : getIntent().getStringExtra("path");
        TextView title = text("Strip" + (path == null ? "" : "  ·  " + whenOf(path)), 18, INK, 600);
        title.setPadding(dp(14), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        info = text("Reading the stored samples…", 13, MUT, 400);
        info.setPadding(dp(16), dp(4), dp(16), dp(8));
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        view = new StripView(this);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, 0, 1f);
        vp.leftMargin = dp(8); vp.rightMargin = dp(8);
        root.addView(view, vp);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(8), dp(10), dp(8), dp(4));
        chips.addView(chip("3 s", 3.0)); chips.addView(chip("6 s", 6.0)); chips.addView(chip("12 s", 12.0)); chips.addView(chip("30 s", 30.0));
        TextView flip = text("Flip", 14, INK, 600);
        flip.setGravity(Gravity.CENTER);
        flip.setBackground(round(CARD, 22));
        flip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { view.flip(); } });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(40), 1f); fp.leftMargin = dp(8);
        chips.addView(flip, fp);
        root.addView(chips, new LinearLayout.LayoutParams(-1, -2));
        hint = text("Drag to move. Pinch to zoom. Tap a beat for the gap before it. Double-tap to start again.", 12, MUT, 400);
        hint.setPadding(dp(16), dp(2), dp(16), dp(10));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        view.listener = new StripView.Listener() { @Override public void changed(String line) { info.setText(line); } };
        if (path == null) { info.setText("No reading was given."); return; }
        final File report = new File(path);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null; EcgR16Analyzer.Strip s = null;
                try {
                    File bin = ProReportMaker.findBin(report.getParentFile(), ProReportMaker.unixOf(report));
                    if (bin == null) err = "The raw file for this reading is not on this phone, so there is no trace to show.";
                    else {
                        List<byte[]> mine = new ArrayList<>();
                        for (byte[] f : ProAnalysis.frames(readAll(bin))) if (f.length == 1584 && (f[9] & 0xFF) == 16 && ProAnalysis.crcOk(f)) mine.add(f);
                        if (mine.size() < 5) err = "The raw file holds too few records (" + mine.size() + ") to draw.";
                        else s = EcgR16Analyzer.stripQuiet(mine);
                    }
                } catch (Throwable t) { err = "Could not read the stored samples: " + t.getClass().getSimpleName(); }
                final EcgR16Analyzer.Strip ss = s; final String ee = err;
                ui.post(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    if (ss == null) info.setText(ee == null ? "No trace." : ee); else view.setStrip(ss);
                } });
            }
        }, "strip-load").start();
    }

    private View chip(String label, final double seconds) {
        TextView t = text(label, 14, INK, 600);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(CARD, 22));
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { view.setSpan(seconds); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private static String whenOf(String path) {
        long u = ProReportMaker.unixOf(new File(path));
        return u <= 0 ? "" : new java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(new java.util.Date(u * 1000L));
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

    // ------------------------------------------------------------------ the view

    static final class StripView extends View {
        interface Listener { void changed(String line); }
        Listener listener;
        private static final double FS = 500.0;
        private EcgR16Analyzer.Strip s;
        private StripViewport vp;
        private int sel = -1;
        private double start0;
        private boolean flipped;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final GestureDetector gd;
        private final ScaleGestureDetector sd;

        StripView(Context c) {
            super(c);
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (vp == null || getWidth() == 0) return true;
                    vp.pan(dx / getWidth() * vp.span); report(); invalidate(); return true;
                }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    if (vp == null || s == null || getWidth() == 0) return true;
                    double t = vp.timeAt(e.getX(), getWidth()), tol = HealthViews.dp(getContext(), 28) / getWidth() * vp.span;
                    sel = StripViewport.nearestBeat(s.beats, FS, t, tol);
                    report(); invalidate(); return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (vp == null) return true;
                    sel = -1; vp.set(start0, 6.0); report(); invalidate(); return true;
                }
            });
            sd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    if (vp == null || getWidth() == 0) return true;
                    vp.zoom(d.getScaleFactor(), d.getFocusX() / getWidth()); report(); invalidate(); return true;
                }
            });
        }

        void setStrip(EcgR16Analyzer.Strip strip) {
            s = strip;
            double total = strip.mv.length / FS;
            start0 = StripViewport.firstClean(strip.quality, strip.mv, FS);
            vp = new StripViewport(total, 6.0, start0);
            flipped = StripViewport.looksInverted(strip.beats, strip.mv, FS);
            report(); invalidate();
        }

        void setSpan(double seconds) {
            if (vp == null) return;
            double mid = vp.t0 + vp.span / 2.0;
            vp.set(mid - seconds / 2.0, seconds); report(); invalidate();
        }

        void flip() { flipped = !flipped; invalidate(); }

        private void report() {
            if (listener == null || vp == null || s == null) return;
            double a = vp.t0, b = vp.t0 + vp.span;
            double[] st = StripViewport.stats(s.beats, FS, a, b);
            StringBuilder sb = new StringBuilder(StripViewport.clock(a) + " to " + StripViewport.clock(b) + "  ·  " + String.format(Locale.UK, "%.1f s", vp.span) + "  ·  " + (int) st[0] + (st[0] == 1 ? " beat" : " beats"));
            if (!Double.isNaN(st[1])) sb.append(String.format(Locale.UK, "  ·  about %.0f bpm from the gaps (median gap %.0f ms)", st[1], st[2]));
            if (sel >= 0 && sel < s.beats.length) {
                double rr = StripViewport.rrBefore(s.beats, sel, FS);
                sb.append("\nBeat at ").append(StripViewport.clock(s.beats[sel] / FS)).append(Double.isNaN(rr) ? ": no usable gap before it" : String.format(Locale.UK, ": gap before it %.0f ms (%.0f bpm)", rr, 60000.0 / rr));
            }
            listener.changed(sb.toString());
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            sd.onTouchEvent(e);
            if (!sd.isInProgress()) gd.onTouchEvent(e);
            return true;
        }

        private float yOf(double mv, double lo, double hi, float top, float bot) {
            double v = flipped ? -mv : mv;
            float y = (float) (bot - (v - lo) / (hi - lo) * (bot - top));
            return y < top ? top : (y > bot ? bot : y);
        }

        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL); p.setColor(HealthViews.CARD);
            cv.drawRoundRect(new RectF(0, 0, w, h), HealthViews.dp(c, 22), HealthViews.dp(c, 22), p);
            if (s == null || vp == null) return;
            float ribbonH = HealthViews.dp(c, 9), axisH = HealthViews.dp(c, 20), top = HealthViews.dp(c, 18), bot = h - ribbonH - axisH - HealthViews.dp(c, 8);
            float left = HealthViews.dp(c, 8), pw = w - 2 * left;
            int n = s.mv.length;
            int i0 = Math.max(0, (int) Math.floor(vp.t0 * FS)), i1 = Math.min(n - 1, (int) Math.ceil((vp.t0 + vp.span) * FS));
            if (i1 <= i0) return;

            // vertical scale from the visible, present samples (2nd to 98th percentile), always holding the baseline
            int stp = Math.max(1, (i1 - i0) / 500);
            double[] sm = new double[(i1 - i0) / stp + 1]; int m = 0;
            for (int i = i0; i <= i1; i += stp) { double v = flipped ? -s.mv[i] : s.mv[i]; if (!Double.isNaN(v)) sm[m++] = v; }
            double lo = -0.6, hi = 1.0;
            if (m > 10) { double[] q = Arrays.copyOf(sm, m); Arrays.sort(q); lo = Math.max(-3.0, Math.min(-0.5, q[(int) (0.02 * (m - 1))] - 0.2)); hi = Math.min(3.0, Math.max(0.8, q[(int) (0.98 * (m - 1))] + 0.2)); }
            final double flo = lo, fhi = hi;      // lo and hi are in displayed (already flipped) volts; yOf flips each sample the same way

            // grid: horizontal every 0.5 mV, vertical by zoom
            p.setStrokeWidth(1f); p.setTextSize(HealthViews.dp(c, 10)); p.setColor(0x14FFFFFF);
            for (double mv = Math.ceil(lo * 2) / 2.0; mv <= hi; mv += 0.5) {
                float y = (float) (bot - (mv - lo) / (hi - lo) * (bot - top));
                p.setColor(Math.abs(mv) < 1e-9 ? 0x33FFFFFF : 0x14FFFFFF); p.setStyle(Paint.Style.STROKE); cv.drawLine(left, y, w - left, y, p);
                p.setStyle(Paint.Style.FILL); p.setColor(HealthViews.MUT); cv.drawText(mv == 0 ? "0" : String.format(Locale.US, "%.1f", mv), left + 2, y - 3, p);
            }
            double gs = vp.span <= 3 ? 0.2 : vp.span <= 8 ? 0.5 : vp.span <= 16 ? 1.0 : 5.0, lab = vp.span <= 8 ? 1.0 : vp.span <= 16 ? 2.0 : 5.0;
            for (double t = Math.ceil(vp.t0 / gs) * gs; t <= vp.t0 + vp.span; t += gs) {
                float x = left + vp.xAt(t, pw);
                boolean major = Math.abs(t / lab - Math.rint(t / lab)) < 1e-6;
                p.setStyle(Paint.Style.STROKE); p.setColor(major ? 0x24FFFFFF : 0x0DFFFFFF); cv.drawLine(x, top, x, bot, p);
                if (major) { p.setStyle(Paint.Style.FILL); p.setColor(HealthViews.MUT); p.setTextAlign(Paint.Align.CENTER); cv.drawText(StripViewport.clock(t).replaceAll("\\.0$", ""), x, h - HealthViews.dp(c, 5), p); p.setTextAlign(Paint.Align.LEFT); }
            }

            // recovery windows (amber wash) and rail hits (red tick on the ribbon)
            if (s.flags != null && s.flags.length == n) {
                int i = i0;
                while (i <= i1) {
                    byte f = s.flags[i];
                    if (f == 0) { i++; continue; }
                    int j = i; while (j <= i1 && s.flags[j] == f) j++;
                    float xa = left + vp.xAt(i / FS, pw), xb = left + vp.xAt(j / FS, pw);
                    p.setStyle(Paint.Style.FILL);
                    if (f == 2) { p.setColor(0x33F2B35B); cv.drawRect(xa, top, Math.max(xb, xa + 2), bot, p); }
                    else { p.setColor(0xFFFF8CA2); cv.drawRect(xa, bot + HealthViews.dp(c, 2), Math.max(xb, xa + 2), bot + HealthViews.dp(c, 2) + ribbonH, p); }
                    i = j;
                }
            }

            // the trace: min and max per pixel column when zoomed out, a line when zoomed in
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(HealthViews.dp(c, 1.6f)); p.setColor(HealthViews.GREEN); p.setStrokeJoin(Paint.Join.ROUND);
            double perPx = (i1 - i0) / (double) pw;
            if (perPx > 2) {
                for (int px = 0; px < (int) pw; px++) {
                    int a = i0 + (int) (px * perPx), b = Math.min(i1, i0 + (int) ((px + 1) * perPx));
                    double mn = 1e9, mx = -1e9;
                    for (int i = a; i <= b; i++) { double v = s.mv[i]; if (Double.isNaN(v)) continue; if (v < mn) mn = v; if (v > mx) mx = v; }
                    if (mn > 1e8) continue;
                    float x = left + px;
                    cv.drawLine(x, yOf(mn, flo, fhi, top, bot), x, yOf(mx, flo, fhi, top, bot) + 0.5f, p);
                }
            } else {
                path.reset(); boolean pen = false;
                for (int i = i0; i <= i1; i++) {
                    double v = s.mv[i];
                    if (Double.isNaN(v)) { pen = false; continue; }
                    float x = left + vp.xAt(i / FS, pw), y = yOf(v, flo, fhi, top, bot);
                    if (!pen) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
                }
                cv.drawPath(path, p);
            }

            // beats
            p.setStyle(Paint.Style.FILL);
            for (int k = 0; k < s.beats.length; k++) {
                int b = s.beats[k];
                if (b < i0 || b > i1) continue;
                float x = left + vp.xAt(b / FS, pw), r = HealthViews.dp(c, k == sel ? 6 : 4);
                p.setColor(HealthViews.GOLD);
                path.reset(); path.moveTo(x - r, top - r * 1.6f); path.lineTo(x + r, top - r * 1.6f); path.lineTo(x, top - 1f); path.close(); cv.drawPath(path, p);
                if (k == sel) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(HealthViews.dp(c, 1f)); cv.drawLine(x, top, x, bot, p); p.setStyle(Paint.Style.FILL); }
            }

            // quality ribbon: strap quality per column (3 good, 2 fair, 1 poor, 0 none)
            float ry = bot + HealthViews.dp(c, 2);
            for (int px = 0; px < (int) pw; px += 2) {
                int i = i0 + (int) (px * perPx); if (i > i1) break;
                int q = s.quality[Math.max(0, Math.min(n - 1, i))];
                if (s.flags != null && s.flags.length == n && s.flags[i] == 1) continue;
                p.setColor(q >= 3 ? HealthViews.GREEN : q == 2 ? HealthViews.GOLD : 0xFF46566A);
                p.setAlpha(q >= 3 ? 170 : 220);
                cv.drawRect(left + px, ry, left + px + 2, ry + ribbonH, p);
            }
            p.setAlpha(255);
        }
    }
}

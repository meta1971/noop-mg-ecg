package com.noop.mgecg;

// FILE VERSION 0.94.0 (26 Nov 2026): under the trace the whole recording in one strip (OverviewView: trace in miniature, why each stretch is or is not clean, wrist-movement ticks, rail and recovery marks, a gold box for the window above, drag it to scrub),
// two buttons ("Why greyed", "Recovery windows") that open the reasons and the window table (StripSegments), and a tap on a grey stretch of the trace says why it is grey. Nothing else changed.
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
    private OverviewView overview;
    private byte[] kinds, moves;
    private List<StripSegments.Win> wins;

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

        overview = new OverviewView(this, view);
        view.moved = new Runnable() { @Override public void run() { overview.invalidate(); } };
        LinearLayout.LayoutParams ovp = new LinearLayout.LayoutParams(-1, dp(76));
        ovp.leftMargin = dp(8); ovp.rightMargin = dp(8); ovp.topMargin = dp(8);
        root.addView(overview, ovp);

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
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setPadding(dp(8), dp(2), dp(8), dp(2));
        pills.addView(pill("Why greyed", new View.OnClickListener() { @Override public void onClick(View v) { showWhy(); } }, true));
        pills.addView(pill("Windows", new View.OnClickListener() { @Override public void onClick(View v) { showWindows(); } }, true));
        pills.addView(pill("Rhythm", new View.OnClickListener() { @Override public void onClick(View v) { if (path != null) startActivity(new android.content.Intent(StripActivity.this, RhythmActivity.class).putExtra("path", path)); } }, false));
        root.addView(pills, new LinearLayout.LayoutParams(-1, -2));
        hint = text("Drag to move. Pinch to zoom. Tap a beat for the gap before it, or a grey stretch for its reason. Double-tap to start again.", 12, MUT, 400);
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
                byte[] kk = null, mm = null; List<StripSegments.Win> ww = null;
                try {
                    if (ss != null) {
                        kk = StripSegments.kinds(ss.quality, ss.mv, ss.flags, 500.0);
                        ww = StripSegments.windows(ss.flags, kk, ss.beats, 500.0);
                        mm = StripSegments.moving(ss.motionMg, EcgAux.MOTION_FS);
                    }
                } catch (Throwable t) { kk = null; mm = null; ww = null; }          // the trace itself must still show
                final byte[] kf = kk, mf = mm; final List<StripSegments.Win> wf = ww;
                ui.post(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    if (ss == null) info.setText(ee == null ? "No trace." : ee);
                    else { kinds = kf; moves = mf; wins = wf; view.setStrip(ss, kf); overview.set(ss, kf, mf); }
                } });
            }
        }, "strip-load").start();
    }

    private View pill(String label, View.OnClickListener l, boolean first) {
        TextView t = text(label, 14, INK, 600);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(CARD, 22));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        if (first) lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** The colour of a kind on the overview and in the lists. */
    static int colour(int kind) {
        switch (kind) {
            case StripSegments.CLEAN: return HealthViews.BLUE;
            case StripSegments.SETTLING: return 0xFF8FA1B8;
            case StripSegments.FAIR: return HealthViews.GOLD;
            case StripSegments.POOR: return 0xFFB07A3C;
            case StripSegments.NONE: return 0xFF46566A;
            case StripSegments.RAIL: return HealthViews.ROSE;
            case StripSegments.RECOVERY: return HealthViews.GOLD;
            case StripSegments.GAP: return 0xFF2A3440;
            case StripSegments.LIFT: return HealthViews.ROSE;
            default: return 0x00000000;
        }
    }

    private void showWhy() {
        if (kinds == null || kinds.length == 0) { info.setText("The recording is not read yet."); return; }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        for (StripSegments.Line l : StripSegments.linesK(kinds, 500.0)) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            if (l.kind >= 0) { sb.append("\u25A0  "); sb.setSpan(new android.text.style.ForegroundColorSpan(colour(l.kind)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
            sb.append(l.text);
            TextView t = text("", 13.5f, INK, 400);
            t.setText(sb); t.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
            body.addView(t, lp);
        }
        int mv = 0; if (moves != null) for (byte b : moves) if (b == 1) mv++;
        String note = "Beats are marked only inside clean stretches of 5 s or more. Tap a grey stretch on the trace for its reason."
                + (moves != null && moves.length > 0 ? "\n\nViolet ticks on the overview: wrist moving, " + mv + " of " + moves.length + " s (40 mg or more in that second, the same cut as the report)." : "");
        TextView n = text(note, 12, MUT, 400); n.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2); np.topMargin = dp(14);
        body.addView(n, np);
        sheet("Why a stretch is greyed", body);
    }

    private void showWindows() {
        if (kinds == null || wins == null) { info.setText("The recording is not read yet."); return; }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView sum = text(StripSegments.windowSummary(wins), 13.5f, INK, 400);
        body.addView(sum, new LinearLayout.LayoutParams(-1, -2));
        if (!wins.isEmpty()) {
            body.addView(winRow("Window", "Starts", "Length", "Beats", true), rowLp(12));
            int shown = 0;
            for (StripSegments.Win w : wins) {
                if (shown >= StripSegments.MAX_WINDOWS) break;
                body.addView(winRow(w.label, String.format(Locale.UK, "%.2f s", w.start), String.format(Locale.UK, "%.1f s", w.len), String.valueOf(w.beats), false), rowLp(6));
                shown++;
            }
            if (wins.size() > shown) {
                TextView more = text("and " + (wins.size() - shown) + " more.", 12, MUT, 400);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2); mp.topMargin = dp(8);
                body.addView(more, mp);
            }
        }
        TextView why = text("The strap opens a recovery window 63 samples (126 ms) after the signal first hits the amplifier's limit and holds it for 250 samples (0.5 s), longer while the signal is still clipped. "
                + "The strap flags these samples itself; they are not estimated here. The same windows are tinted gold on the trace.", 12, MUT, 400);
        why.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2); wp.topMargin = dp(14);
        body.addView(why, wp);
        sheet("Recovery windows", body);
    }

    private LinearLayout.LayoutParams rowLp(int top) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = dp(top); return l; }

    private View winRow(String a, String b, String c, String d, boolean head) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        int col = head ? MUT : INK; float sz = head ? 11.5f : 13.5f;
        r.addView(text(a, sz, col, head ? 600 : 400), new LinearLayout.LayoutParams(0, -2, 1.5f));
        r.addView(text(b, sz, col, head ? 600 : 400), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(text(c, sz, col, head ? 600 : 400), new LinearLayout.LayoutParams(0, -2, 0.9f));
        r.addView(text(d, sz, col, head ? 600 : 400), new LinearLayout.LayoutParams(0, -2, 0.7f));
        return r;
    }

    /** A dark card over the strip with a scrolling body and a Close button. */
    private void sheet(String title, View body) {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        box.setBackground(round(CARD, 22));
        box.addView(text(title, 17, INK, 600), new LinearLayout.LayoutParams(-1, -2));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(body, new android.widget.FrameLayout.LayoutParams(-1, -2));
        int winW = (int) (getResources().getDisplayMetrics().widthPixels * 0.94f);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        body.measure(View.MeasureSpec.makeMeasureSpec(winW - dp(36), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Math.min(maxH, body.getMeasuredHeight()));
        sp.topMargin = dp(4);
        box.addView(sv, sp);
        TextView close = text("Close", 14, INK, 600);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(BG, 22));
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(44)); cp.topMargin = dp(12);
        box.addView(close, cp);
        d.setContentView(box);
        try {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            d.getWindow().setLayout(winW, -2);
        } catch (Throwable ignored) { }
        d.show();
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

    // ------------------------------------------------------------------ the overview (0.94.0)

    /** The whole recording in one strip. Top: wrist-movement ticks. Middle: the trace in miniature. Bottom: a band of why each stretch is or is not clean, with rail (rose) and recovery (gold) marks. Gold box = the window above; drag to scrub. */
    static final class OverviewView extends View {
        private static final double FS = 500.0;
        private final StripView target;
        private EcgR16Analyzer.Strip s;
        private byte[] kinds, moves, colKind;
        private float[] lo, hi;
        private double vLo = -0.6, vHi = 1.0;
        private int cols;
        private final List<double[]> marks = new ArrayList<>();      // {t0, t1, kind} of rail and recovery runs
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        OverviewView(Context c, StripView target) { super(c); this.target = target; }

        void set(EcgR16Analyzer.Strip strip, byte[] k, byte[] mv) {
            s = strip; kinds = k; moves = mv; cols = 0; marks.clear();
            if (k != null) for (StripSegments.Seg g : StripSegments.runs(k, FS)) if (g.kind == StripSegments.RAIL || g.kind == StripSegments.RECOVERY) marks.add(new double[] { g.t0, g.t1, g.kind });
            invalidate();
        }

        private float dpx(float v) { return HealthViews.dp(getContext(), v); }

        private void prepare(int pw) {
            int n = s.mv.length;
            cols = Math.max(1, pw);
            lo = new float[cols]; hi = new float[cols]; colKind = new byte[cols];
            int[] cnt = new int[9];
            double[] los = new double[cols], his = new double[cols]; int m = 0;
            for (int c = 0; c < cols; c++) {
                int a = (int) ((long) c * n / cols), b = (int) ((long) (c + 1) * n / cols);
                if (b <= a) b = Math.min(n, a + 1);
                float mn = Float.NaN, mx = Float.NaN;
                Arrays.fill(cnt, 0);
                for (int i = a; i < b; i++) {
                    double v = s.mv[i];
                    if (!Double.isNaN(v)) { if (Float.isNaN(mn) || v < mn) mn = (float) v; if (Float.isNaN(mx) || v > mx) mx = (float) v; }
                    if (kinds != null && i < kinds.length && kinds[i] >= 0 && kinds[i] < cnt.length) cnt[kinds[i]]++;
                }
                lo[c] = mn; hi[c] = mx;
                if (!Float.isNaN(mn)) { los[m] = mn; his[m] = mx; m++; }
                int best = 0; for (int q = 1; q < cnt.length; q++) if (cnt[q] > cnt[best]) best = q;
                colKind[c] = (byte) best;
            }
            vLo = -0.6; vHi = 1.0;
            if (m > 10) {
                double[] a = Arrays.copyOf(los, m), b = Arrays.copyOf(his, m);
                Arrays.sort(a); Arrays.sort(b);
                vLo = Math.max(-3.0, Math.min(-0.3, a[(int) (0.01 * (m - 1))]));
                vHi = Math.min(3.0, Math.max(0.5, b[(int) (0.99 * (m - 1))]));
            }
        }

        private float yOf(double v, boolean fl, float top, float bot) {
            double d = fl ? -v : v, l = fl ? -vHi : vLo, u = fl ? -vLo : vHi;
            float y = (float) (bot - (d - l) / (u - l) * (bot - top));
            return y < top ? top : (y > bot ? bot : y);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL); p.setColor(HealthViews.CARD);
            cv.drawRoundRect(new RectF(0, 0, w, h), dpx(16), dpx(16), p);
            if (s == null || s.mv.length < 2) return;
            float left = dpx(8), pw = w - 2 * left, top = dpx(12), bandH = dpx(8), labH = dpx(15);
            float bandTop = h - labH - bandH, traceBot = bandTop - dpx(4);
            int cw = (int) pw;
            if (cols != cw || lo == null) prepare(cw);
            double total = s.mv.length / FS;
            boolean fl = target.isFlipped();

            // wrist movement ticks (top edge)
            if (moves != null) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dpx(2)); p.setColor(HealthViews.VIOLET);
                for (int i = 0; i < moves.length; i++) {
                    if (moves[i] != 1) continue;
                    float x = left + (float) ((i + 0.5) / total * pw);
                    cv.drawLine(x, dpx(3), x, dpx(9), p);
                }
            }
            // the trace in miniature
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1f); p.setColor(0xAAB8C8DC);
            for (int c = 0; c < cols; c++) {
                if (Float.isNaN(lo[c])) continue;
                float x = left + c + 0.5f;
                cv.drawLine(x, yOf(lo[c], fl, top, traceBot), x, yOf(hi[c], fl, top, traceBot) + 0.5f, p);
            }
            // why each stretch is or is not clean
            p.setStyle(Paint.Style.FILL);
            for (int c = 0; c < cols; c++) {
                int col = colour(colKind[c]);
                if (colKind[c] == StripSegments.CLEAN) col = (col & 0x00FFFFFF) | 0xB0000000;
                p.setColor(col);
                cv.drawRect(left + c, bandTop, left + c + 1f, bandTop + bandH, p);
            }
            // rail and recovery marks, always at least 2 dp wide so a 0.5 s window is visible on a long reading
            for (double[] m : marks) {
                float x0 = left + (float) (m[0] / total * pw), x1 = left + (float) (m[1] / total * pw);
                p.setColor(colour((int) m[2]));
                cv.drawRect(x0, bandTop - dpx(3), Math.max(x1, x0 + dpx(2)), bandTop + bandH + dpx(3), p);
            }
            // the window shown above
            float x0 = left + (float) (target.t0() / total * pw), x1 = left + (float) ((target.t0() + target.span()) / total * pw);
            if (x1 - x0 < dpx(8)) { float mid = (x0 + x1) / 2f; x0 = mid - dpx(4); x1 = mid + dpx(4); }
            RectF box = new RectF(x0, dpx(1), x1, bandTop + bandH + dpx(5));
            p.setStyle(Paint.Style.FILL); p.setColor(0x26F2B35B); cv.drawRoundRect(box, dpx(4), dpx(4), p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dpx(2)); p.setColor(HealthViews.GOLD); cv.drawRoundRect(box, dpx(4), dpx(4), p);
            // time labels
            p.setStyle(Paint.Style.FILL); p.setColor(HealthViews.MUT); p.setTextSize(dpx(10));
            p.setTextAlign(Paint.Align.LEFT); cv.drawText("0", left, h - dpx(4), p);
            p.setTextAlign(Paint.Align.RIGHT); cv.drawText(StripViewport.clock(total).replaceAll("\\.0$", ""), w - left, h - dpx(4), p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (s == null) return true;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                float left = dpx(8), pw = getWidth() - 2 * left;
                double total = s.mv.length / FS, t = (e.getX() - left) / pw * total;
                target.scrubTo(Math.max(0.0, Math.min(total, t)));
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ the view

    static final class StripView extends View {
        interface Listener { void changed(String line); }
        Listener listener;
        Runnable moved;                  // 0.94.0: called whenever the window moves, so the overview can follow
        private byte[] kinds;            // 0.94.0: StripSegments kind of every sample (null = no reasons)
        private String tapNote;          // 0.94.0: the reason for the grey stretch last tapped
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
                    tapNote = sel < 0 ? StripSegments.reasonAt(kinds, FS, t) : null;
                    report(); invalidate(); return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (vp == null) return true;
                    sel = -1; tapNote = null; vp.set(start0, 6.0); report(); invalidate(); return true;
                }
            });
            sd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    if (vp == null || getWidth() == 0) return true;
                    vp.zoom(d.getScaleFactor(), d.getFocusX() / getWidth()); report(); invalidate(); return true;
                }
            });
        }

        void setStrip(EcgR16Analyzer.Strip strip, byte[] k) {
            s = strip; kinds = k; tapNote = null;
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
            if (tapNote != null) sb.append("\n").append(tapNote);
            listener.changed(sb.toString());
            if (moved != null) moved.run();
        }

        // 0.94.0: for the overview
        double t0() { return vp == null ? 0.0 : vp.t0; }
        double span() { return vp == null ? 0.0 : vp.span; }
        boolean isFlipped() { return flipped; }
        void scrubTo(double centre) { if (vp == null) return; vp.set(centre - vp.span / 2.0, vp.span); report(); invalidate(); }

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

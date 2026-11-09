package com.noop.mgecg;

// FILE VERSION 0.49.0 (9 Nov 2026): the Trends screen, rebuilt. Personal first: every card compares the newest value with YOUR usual (the robust median and spread of your own earlier values), says in words whether it is in your usual range, higher or lower, and
// whether your own line is rising, falling or steady; the ECG measures are judged against your usual for the same time of day. Nights and ECG readings sit side by side. Tap a card for the full graph.
// Different devices give different absolute numbers; what carries over is your own trend, so nothing here is compared with a population or another device.

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

final class TrendsScreen {
    private TrendsScreen() {}

    static final double[] RANGE_D = { 7, 30, 90 };
    static final String[] RANGE_L = { "7 days", "30 days", "90 days" };
    private static int range = 1;

    // ------------------------------------------------------------------ words (TrendText holds the wording, pure Java)
    static String fmt(TrendSeries.Series s, double x) { return TrendText.fmt(s, x); }
    static String fmtU(TrendSeries.Series s, double x) { return TrendText.fmtU(s, x); }
    static String signed(TrendSeries.Series s, double x) { return TrendText.signed(s, x); }
    static int favour(TrendSeries.Series s, double sign) { return TrendText.favour(s, sign); }
    static String trendText(TrendSeries.Series s, TrendStats.Result r, double winDays) { return TrendText.trendText(s, r, winDays); }
    static String summary(List<TrendSeries.Series> all, List<TrendSeries.Judged> js, double winDays) { return TrendText.summary(all, js, winDays); }

    /** {text, colour} of the status chip. */
    static Object[] chip(TrendSeries.Series s, TrendStats.Result r) {
        Object[] c = TrendText.chip(s, r); int kind = (Integer) c[1];
        return new Object[] { c[0], kind == TrendText.LEARNING ? MUT : kind == TrendText.USUAL ? TEAL : kind == TrendText.BETTER ? GREEN : kind == TrendText.WARN ? ROSE : GOLD };
    }

    // ------------------------------------------------------------------ screen
    static void show(final Activity a, final HealthModel.Data d) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView sv = new ScrollView(a); sv.setBackgroundColor(BG); sv.setVerticalScrollBarEnabled(false);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(dp(a, 16)); col.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 28)));
        sv.addView(col);

        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tt = new LinearLayout(a); tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(HeartGraph.label(a, "Trends", 28, INK, 300));
        tt.addView(HeartGraph.label(a, "Your numbers against your own usual", 13, MUT, 400));
        top.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        top.addView(close); col.addView(top);

        final LinearLayout seg = new LinearLayout(a); seg.setOrientation(LinearLayout.HORIZONTAL); seg.setBackground(glass(a, 16)); seg.setPadding(Math.round(dp(a, 4)), Math.round(dp(a, 4)), Math.round(dp(a, 4)), Math.round(dp(a, 4)));
        col.addView(seg, HeartGraph.lp(a, -1, -2, 16));

        final LinearLayout sumCard = card(a); final TextView sumHead = HeartGraph.label(a, "YOUR PATTERN", 11, MUT, 400); final TextView sumBody = HeartGraph.label(a, "", 14, INK, 400); sumBody.setLineSpacing(0, 1.3f);
        sumCard.addView(sumHead); sumCard.addView(sumBody, HeartGraph.lp(a, -1, -2, 8)); col.addView(sumCard, HeartGraph.lp(a, -1, -2, 14));

        col.addView(HeartGraph.label(a, "NIGHTS", 11, MUT, 400), HeartGraph.lp(a, -1, -2, 20));
        final LinearLayout nightsBox = new LinearLayout(a); nightsBox.setOrientation(LinearLayout.VERTICAL); col.addView(nightsBox, HeartGraph.lp(a, -1, -2, 8));
        col.addView(HeartGraph.label(a, "ECG READINGS", 11, MUT, 400), HeartGraph.lp(a, -1, -2, 20));
        final TextView ecgStatus = HeartGraph.label(a, "Reading your ECG readings\u2026", 13, MUT, 400); col.addView(ecgStatus, HeartGraph.lp(a, -1, -2, 8));
        final LinearLayout ecgBox = new LinearLayout(a); ecgBox.setOrientation(LinearLayout.VERTICAL); col.addView(ecgBox, HeartGraph.lp(a, -1, -2, 4));

        LinearLayout foot = card(a);
        foot.addView(HeartGraph.label(a, "WHY YOUR OWN LINE", 11, MUT, 400));
        TextView ft = HeartGraph.label(a, "Different devices give different absolute numbers for the same night or the same reading, so a number is never compared with a population or another device here. Each card compares you with your own usual: the middle of your earlier values, with a band for how much you normally vary. What carries over is the direction of your own line. ECG measures are compared with your usual for the same time of day. Estimates from this strap's own records, not a medical record.", 12.5f, MUT, 400);
        ft.setLineSpacing(0, 1.25f); foot.addView(ft, HeartGraph.lp(a, -1, -2, 6)); col.addView(foot, HeartGraph.lp(a, -1, -2, 20));

        final List<TrendSeries.Series> night = TrendSeries.nights(d);
        final List<TrendSeries.Series>[] ecgHolder = new List[] { null };
        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() { @Override public void run() {
            seg.removeAllViews();
            for (int i = 0; i < RANGE_L.length; i++) {
                final int ri = i; TextView t = HeartGraph.label(a, RANGE_L[i], 13, i == range ? 0xFF05131E : INK, i == range ? 600 : 400); t.setGravity(Gravity.CENTER);
                GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(a, 12)); g.setColor(i == range ? BLUE : 0x00000000); t.setBackground(g); t.setPadding(0, Math.round(dp(a, 9)), 0, Math.round(dp(a, 9)));
                t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { range = ri; render[0].run(); } });
                seg.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            double win = RANGE_D[range];
            List<TrendSeries.Series> all = new ArrayList<>(night); if (ecgHolder[0] != null) all.addAll(ecgHolder[0]);
            List<TrendSeries.Judged> js = new ArrayList<>(); for (TrendSeries.Series s : all) js.add(TrendSeries.judge(s, win));
            sumBody.setText(summary(all, js, win));
            nightsBox.removeAllViews(); ecgBox.removeAllViews();
            boolean anyN = false, anyE = false;
            for (int i = 0; i < all.size(); i++) {
                TrendSeries.Series s = all.get(i); final TrendSeries.Judged j = js.get(i); if (j.r.n == 0) continue;
                final TrendSeries.Series sf = s; View c = card(a, s, j, win, new Runnable() { @Override public void run() { detail(a, sf); } });
                LinearLayout box = s.ecg ? ecgBox : nightsBox; box.addView(c, HeartGraph.lp(a, -1, -2, 10)); if (s.ecg) anyE = true; else anyN = true;
            }
            if (!anyN) { TextView t = HeartGraph.label(a, "No nights in this period yet.", 13, MUT, 400); nightsBox.addView(t); }
            if (ecgHolder[0] != null) { ecgStatus.setVisibility(anyE ? View.GONE : View.VISIBLE); if (!anyE) ecgStatus.setText("No graded ECG readings in this period yet. Take one (Table tag, Auto-stop) to start your ECG trends."); }
        } };
        dlg.setContentView(sv);
        dlg.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        dlg.show(); render[0].run();
        new Thread(new Runnable() { @Override public void run() {
            List<TrendSeries.Series> e = null; try { e = TrendSeries.ecg(TrendSeries.loadEcg(a, d, 400)); } catch (Throwable ignored) { }
            final List<TrendSeries.Series> ee = e == null ? new ArrayList<TrendSeries.Series>() : e;
            a.runOnUiThread(new Runnable() { @Override public void run() { try { ecgHolder[0] = ee; if (dlg.isShowing()) render[0].run(); } catch (Throwable ignored) { } } });
        } }, "trends-ecg").start();
    }

    private static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(glass(c, 20)); int p = Math.round(dp(c, 16)); l.setPadding(p, Math.round(dp(c, 14)), p, Math.round(dp(c, 14))); return l;
    }

    /** One metric card: name and status chip, the newest value large with its change against your usual, a sparkline over your usual band, and one line on the trend. */
    private static View card(final Activity a, final TrendSeries.Series s, final TrendSeries.Judged j, double win, final Runnable open) {
        final TrendStats.Result r = j.r; Object[] ch = chip(s, r); final int cc = (Integer) ch[1];
        LinearLayout c = card(a); c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { open.run(); } });
        LinearLayout row1 = new LinearLayout(a); row1.setOrientation(LinearLayout.HORIZONTAL); row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.addView(HeartGraph.label(a, s.name.toUpperCase(Locale.UK), 11, MUT, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView pill = HeartGraph.pill(a, (String) ch[0], alpha(cc, 0.16f), cc); pill.setTextSize(11); row1.addView(pill); c.addView(row1);
        LinearLayout row2 = new LinearLayout(a); row2.setOrientation(LinearLayout.HORIZONTAL); row2.setGravity(Gravity.BOTTOM);
        TextView big = HeartGraph.label(a, fmt(s, r.last), 34, INK, 300); row2.addView(big);
        TextView un = HeartGraph.label(a, " " + s.unit, 13, MUT, 400); un.setPadding(0, 0, 0, Math.round(dp(a, 6))); row2.addView(un);
        row2.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        String dl = r.enough ? signed(s, r.delta) + " vs your usual " + fmt(s, r.base) : "your usual needs " + Math.max(0, TrendStats.MIN_BASE - r.nBase) + " more";
        TextView dtv = HeartGraph.label(a, dl, 12.5f, r.enough && r.status != 0 ? cc : MUT, 400); dtv.setGravity(Gravity.RIGHT); dtv.setPadding(0, 0, 0, Math.round(dp(a, 8))); row2.addView(dtv);
        c.addView(row2, HeartGraph.lp(a, -1, -2, 2));
        Spark sp = new Spark(a); sp.set(j.used, r, win, s.color, cc); c.addView(sp, HeartGraph.lp(a, -1, Math.round(dp(a, 56)), 4));
        String tx = trendText(s, r, win); if (!j.ctx.isEmpty()) tx += "  \u00b7  vs your usual " + j.ctx;
        double ageD = System.currentTimeMillis() / 86400000.0 - r.lastDay; if (ageD > (s.ecg ? 10 : 3)) tx += "  \u00b7  latest " + (int) ageD + " days ago";
        TextView tr = HeartGraph.label(a, tx, 12, r.trend == 0 ? MUT : (favour(s, r.slopePerWeek) > 0 ? GREEN : favour(s, r.slopePerWeek) < 0 ? GOLD : INK), 400); tr.setLineSpacing(0, 1.2f); c.addView(tr, HeartGraph.lp(a, -1, -2, 8));
        return c;
    }

    // ------------------------------------------------------------------ sparkline
    static final class Spark extends View {
        private TrendSeries.Series s; private TrendStats.Result r; private double win; private int color, dotColor;
        Spark(Context c) { super(c); }
        void set(TrendSeries.Series s, TrendStats.Result r, double win, int color, int dot) { this.s = s; this.r = r; this.win = win; this.color = color; this.dotColor = dot; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            if (s == null || r == null || r.n == 0) return; Context c = getContext(); float w = getWidth(), h = getHeight(), padY = dp(c, 6);
            double tEnd = r.lastDay, t0 = tEnd - win; int n = 0; for (int i = 0; i < s.size(); i++) if (s.day[i] >= t0 && s.day[i] <= tEnd) n++;
            if (n == 0) return; float[] xs = new float[n], ys = new float[n]; double[] vv = new double[n]; int k = 0;
            double lo = 1e300, hi = -1e300; for (int i = 0; i < s.size(); i++) if (s.day[i] >= t0 && s.day[i] <= tEnd) { vv[k++] = s.v[i]; lo = Math.min(lo, s.v[i]); hi = Math.max(hi, s.v[i]); }
            if (r.enough) { lo = Math.min(lo, r.lo); hi = Math.max(hi, r.hi); }
            double span = Math.max(hi - lo, 1e-6), padv = span * 0.12; lo -= padv; hi += padv; span = hi - lo;
            k = 0; for (int i = 0; i < s.size(); i++) if (s.day[i] >= t0 && s.day[i] <= tEnd) { xs[k] = (float) (dp(c, 3) + (w - dp(c, 6)) * (s.day[i] - t0) / win); ys[k] = (float) (padY + (h - 2 * padY) * (1 - (s.v[i] - lo) / span)); k++; }
            if (r.enough) { float y1 = (float) (padY + (h - 2 * padY) * (1 - (r.hi - lo) / span)), y2 = (float) (padY + (h - 2 * padY) * (1 - (r.lo - lo) / span)); cv.drawRoundRect(new RectF(0, y1, w, y2), dp(c, 6), dp(c, 6), paint(alpha(color, 0.13f)));
                Paint mid = paint(alpha(color, 0.35f)); mid.setStrokeWidth(dp(c, 1)); mid.setPathEffect(new android.graphics.DashPathEffect(new float[] { dp(c, 3), dp(c, 4) }, 0)); float ym = (float) (padY + (h - 2 * padY) * (1 - (r.base - lo) / span)); cv.drawLine(0, ym, w, ym, mid); }
            Path path = new Path(); Trends.smoothPath(path, xs, ys, n);
            if (n >= 2) { Path fill = new Path(path); fill.lineTo(xs[n - 1], h); fill.lineTo(xs[0], h); fill.close(); Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG); fp.setShader(new LinearGradient(0, 0, 0, h, alpha(color, 0.28f), alpha(color, 0.0f), Shader.TileMode.CLAMP)); cv.drawPath(fill, fp); }
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG); ln.setStyle(Paint.Style.STROKE); ln.setStrokeCap(Paint.Cap.ROUND); ln.setStrokeJoin(Paint.Join.ROUND); ln.setStrokeWidth(dp(c, 2.2f)); ln.setColor(color); cv.drawPath(path, ln);
            if (n <= 40) for (int i = 0; i < n; i++) cv.drawCircle(xs[i], ys[i], dp(c, n > 20 ? 1.8f : 2.6f), paint(alpha(color, 0.75f)));
            cv.drawCircle(xs[n - 1], ys[n - 1], dp(c, 7), paint(alpha(dotColor, 0.22f))); cv.drawCircle(xs[n - 1], ys[n - 1], dp(c, 3.6f), paint(dotColor));
        }
    }

    // ------------------------------------------------------------------ detail
    private static int dBucket = -1;       // -1 all times of day

    static void detail(final Activity a, final TrendSeries.Series s0) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen); dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView sv = new ScrollView(a); sv.setBackgroundColor(BG); sv.setVerticalScrollBarEnabled(false);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); int pad = Math.round(dp(a, 16)); col.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 28))); sv.addView(col);
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, s0.name, 26, INK, 300), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = HeartGraph.pill(a, "Back", 0x1AFFFFFF, INK); close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } }); top.addView(close); col.addView(top);
        final LinearLayout chips = new LinearLayout(a); chips.setOrientation(LinearLayout.HORIZONTAL); HorizontalScrollView hs = new HorizontalScrollView(a); hs.setHorizontalScrollBarEnabled(false); hs.addView(chips); col.addView(hs, HeartGraph.lp(a, -1, -2, 12));
        final TextView headline = HeartGraph.label(a, "", 14, INK, 400); headline.setLineSpacing(0, 1.3f); col.addView(headline, HeartGraph.lp(a, -1, -2, 12));
        final TextView read = HeartGraph.label(a, "Touch the graph to read a point.", 13, MUT, 400); col.addView(read, HeartGraph.lp(a, -1, -2, 8));
        final DetailChart chart = new DetailChart(a); col.addView(chart, HeartGraph.lp(a, -1, Math.round(dp(a, 250)), 4));
        final LinearLayout tiles = new LinearLayout(a); tiles.setOrientation(LinearLayout.VERTICAL); col.addView(tiles, HeartGraph.lp(a, -1, -2, 14));
        LinearLayout about = card(a); about.addView(HeartGraph.label(a, "WHAT THIS MEANS", 11, MUT, 400));
        TextView at = HeartGraph.label(a, Explain.trend(s0.name) + "\n\nOnly your own line matters here: another device or method will read higher or lower for the same you, but it should rise and fall with it.", 13, INK, 400); at.setLineSpacing(0, 1.28f); about.addView(at, HeartGraph.lp(a, -1, -2, 6)); col.addView(about, HeartGraph.lp(a, -1, -2, 14));
        dBucket = -1;
        final Runnable[] redraw = new Runnable[1];
        redraw[0] = new Runnable() { @Override public void run() {
            chips.removeAllViews();
            for (int i = 0; i < RANGE_L.length; i++) { final int ri = i; TextView t = HeartGraph.pill(a, RANGE_L[i], i == range ? BLUE : 0x1AFFFFFF, i == range ? 0xFF05131E : INK); t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { range = ri; redraw[0].run(); } }); LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.rightMargin = Math.round(dp(a, 8)); chips.addView(t, l); }
            if (s0.ecg) {
                String[] nm = { "All times", "Morning", "Afternoon", "Evening" };
                for (int i = 0; i < nm.length; i++) { final int bi = i - 1; boolean on = dBucket == bi; TextView t = HeartGraph.pill(a, nm[i], on ? s0.color : 0x1AFFFFFF, on ? 0xFF05131E : INK); t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dBucket = bi; redraw[0].run(); } }); LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.rightMargin = Math.round(dp(a, 8)); if (i == 0) l.leftMargin = Math.round(dp(a, 6)); chips.addView(t, l); }
            }
            TrendSeries.Series s = s0.ecg && dBucket >= 0 ? s0.onlyBucket(dBucket) : s0; double win = RANGE_D[range];
            TrendStats.Result r = TrendStats.analyse(s.day, s.v, win, 60.0, s.minSpread, s.slopeUnit);
            String ctx = s0.ecg && dBucket >= 0 ? " (" + TrendSeries.BUCKET[dBucket] + " readings)" : "";
            if (r.n == 0) headline.setText("No values in this period yet."); else {
                Object[] ch = chip(s, r); String h = "Latest " + fmtU(s, r.last) + ".  ";
                h += r.enough ? "Your usual" + ctx + " is " + fmt(s, r.lo) + " to " + fmt(s, r.hi) + " " + s.unit + ", so this is: " + ((String) ch[0]).toLowerCase(Locale.UK) + "." : "Your usual is still being learned: it needs 5 earlier values (" + r.nBase + " so far).";
                headline.setText(h);
            }
            chart.set(s, r, win, new Readout() { @Override public void show(String t) { read.setText(t); } });
            read.setText("Touch the graph to read a point.");
            tiles.removeAllViews();
            LinearLayout r1 = new LinearLayout(a), r2 = new LinearLayout(a); r1.setOrientation(LinearLayout.HORIZONTAL); r2.setOrientation(LinearLayout.HORIZONTAL);
            r1.addView(tile(a, "YOUR USUAL", r.enough ? fmtU(s, r.base) : "\u2014", r.enough ? "\u00b1 " + fmt(s, r.spread) : "learning"), new LinearLayout.LayoutParams(0, -2, 1f)); r1.addView(tile(a, "CHANGE", r.enough ? signed(s, r.delta) : "\u2014", "recent vs usual"), new LinearLayout.LayoutParams(0, -2, 1f)); r1.addView(tile(a, "TREND", r.trend != 0 ? (r.trend > 0 ? "\u2191 " : "\u2193 ") + String.format(Locale.UK, "%.1f", Math.abs(r.slopePerWeek)) : r.n >= 6 ? "Steady" : "\u2014", r.trend != 0 ? s.unit + " a week" : "over " + (int) win + " days"), new LinearLayout.LayoutParams(0, -2, 1f));
            r2.addView(tile(a, "LOWEST", fmtU(s, r.min), "in " + (int) win + " days"), new LinearLayout.LayoutParams(0, -2, 1f)); r2.addView(tile(a, "HIGHEST", fmtU(s, r.max), "in " + (int) win + " days"), new LinearLayout.LayoutParams(0, -2, 1f)); r2.addView(tile(a, s.ecg ? "READINGS" : "NIGHTS", String.valueOf(r.n), "in this period"), new LinearLayout.LayoutParams(0, -2, 1f));
            tiles.addView(r1); tiles.addView(r2, HeartGraph.lp(a, -1, -2, 8));
        } };
        dlg.setContentView(sv); dlg.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT); dlg.show(); redraw[0].run();
    }

    private static View tile(Context c, String cap, String val, String note) {
        LinearLayout t = new LinearLayout(c); t.setOrientation(LinearLayout.VERTICAL); t.setBackground(glass(c, 16)); int p = Math.round(dp(c, 12)); t.setPadding(p, p, p, p);
        t.addView(HeartGraph.label(c, cap, 10, MUT, 400)); t.addView(HeartGraph.label(c, val, 18, INK, 300), HeartGraph.lp(c, -1, -2, 4)); t.addView(HeartGraph.label(c, note, 11, MUT, 400), HeartGraph.lp(c, -1, -2, 2));
        LinearLayout w = new LinearLayout(c); w.setPadding(Math.round(dp(c, 3)), 0, Math.round(dp(c, 3)), 0); w.addView(t, new LinearLayout.LayoutParams(-1, -2)); return w;
    }

    interface Readout { void show(String s); }

    static final class DetailChart extends View {
        private TrendSeries.Series s; private TrendStats.Result r; private double win; private Readout ro; private float touchX = -1f;
        DetailChart(Context c) { super(c); }
        void set(TrendSeries.Series s, TrendStats.Result r, double win, Readout ro) { this.s = s; this.r = r; this.win = win; this.ro = ro; touchX = -1f; invalidate(); }
        private float xOf(double day, double t0, float w) { return (float) (dp(getContext(), 8) + (w - dp(getContext(), 16)) * (day - t0) / win); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (s == null || r == null || r.n == 0) return true; int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true); double t0 = r.lastDay - win; float w = getWidth(); int best = -1; float bd = 1e9f;
                for (int i = 0; i < s.size(); i++) { if (s.day[i] < t0 || s.day[i] > r.lastDay) continue; float dd = Math.abs(xOf(s.day[i], t0, w) - e.getX()); if (dd < bd) { bd = dd; best = i; } }
                if (best >= 0) { touchX = xOf(s.day[best], t0, w); SimpleDateFormat f = new SimpleDateFormat(s.ecg ? "EEE d MMM, HH:mm" : "EEE d MMM", Locale.UK); String z = r.enough ? "   (" + (s.v[best] >= r.base ? "+" : "\u2212") + fmt(s, Math.abs(s.v[best] - r.base)) + " vs usual)" : "";
                    if (ro != null) ro.show(f.format(new Date(s.ts[best] * 1000L)) + "  \u00b7  " + fmtU(s, s.v[best]) + (s.ecg && s.bucket[best] >= 0 ? "  \u00b7  " + TrendSeries.BUCKET[s.bucket[best]] : "") + z); invalidate(); }
            }
            return true;
        }
        @Override protected void onDraw(Canvas cv) {
            if (s == null || r == null || r.n == 0) return; Context c = getContext(); float w = getWidth(), h = getHeight(), ch = h - dp(c, 22), top = dp(c, 8); double t0 = r.lastDay - win;
            double lo = r.min, hi = r.max; if (r.enough) { lo = Math.min(lo, r.lo); hi = Math.max(hi, r.hi); } double sp = Math.max(hi - lo, 1e-6); lo -= sp * 0.15; hi += sp * 0.15; sp = hi - lo; final double flo = lo, fsp = sp;
            Paint g = paint(0x14FFFFFF); g.setStrokeWidth(dp(c, 1)); Paint tx = paint(MUT); tx.setTextSize(dp(c, 10)); tx.setTypeface(font(c, 400)); tx.setTextAlign(Paint.Align.RIGHT);
            for (int k = 0; k < 5; k++) { float y = top + (ch - top) * k / 4f; cv.drawLine(0, y, w, y, g); cv.drawText(String.format(Locale.UK, "%." + Math.max(0, s.dec) + "f", lo + sp * (4 - k) / 4.0), w - dp(c, 2), y - dp(c, 3), tx); }
            if (r.enough) { float y1 = (float) (top + (ch - top) * (1 - (r.hi - flo) / fsp)), y2 = (float) (top + (ch - top) * (1 - (r.lo - flo) / fsp)); cv.drawRoundRect(new RectF(0, y1, w, y2), dp(c, 8), dp(c, 8), paint(alpha(s.color, 0.14f)));
                Paint mid = paint(alpha(s.color, 0.4f)); mid.setStrokeWidth(dp(c, 1)); mid.setPathEffect(new android.graphics.DashPathEffect(new float[] { dp(c, 4), dp(c, 5) }, 0)); float ym = (float) (top + (ch - top) * (1 - (r.base - flo) / fsp)); cv.drawLine(0, ym, w, ym, mid); }
            int n = 0; for (int i = 0; i < s.size(); i++) if (s.day[i] >= t0 && s.day[i] <= r.lastDay) n++;
            float[] xs = new float[n], ys = new float[n]; int k2 = 0; for (int i = 0; i < s.size(); i++) if (s.day[i] >= t0 && s.day[i] <= r.lastDay) { xs[k2] = xOf(s.day[i], t0, w); ys[k2] = (float) (top + (ch - top) * (1 - (s.v[i] - flo) / fsp)); k2++; }
            if (n >= 6 && !Double.isNaN(r.slopePerWeek)) {          // the Theil-Sen line through the window
                double sl = r.slopePerWeek / 7.0; double[] ic = new double[n]; k2 = 0; for (int i = 0; i < s.size(); i++) if (s.day[i] >= t0 && s.day[i] <= r.lastDay) ic[k2++] = s.v[i] - sl * (s.day[i] - t0);
                double b0 = TrendStats.median(ic, n); float ya = (float) (top + (ch - top) * (1 - (b0 - flo) / fsp)), yb = (float) (top + (ch - top) * (1 - (b0 + sl * win - flo) / fsp));
                Paint tl = paint(alpha(INK, 0.5f)); tl.setStrokeWidth(dp(c, 1.4f)); tl.setPathEffect(new android.graphics.DashPathEffect(new float[] { dp(c, 7), dp(c, 5) }, 0)); cv.drawLine(xOf(t0, t0, w), ya, xOf(t0 + win, t0, w), yb, tl);
            }
            Path path = new Path(); Trends.smoothPath(path, xs, ys, n); Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG); ln.setStyle(Paint.Style.STROKE); ln.setStrokeCap(Paint.Cap.ROUND); ln.setStrokeJoin(Paint.Join.ROUND); ln.setStrokeWidth(dp(c, 2.2f)); ln.setColor(alpha(s.color, 0.9f)); cv.drawPath(path, ln);
            k2 = 0; for (int i = 0; i < s.size(); i++) if (s.day[i] >= t0 && s.day[i] <= r.lastDay) { boolean out = r.enough && (s.v[i] < r.lo || s.v[i] > r.hi); int dc = !out ? s.color : (s.good == 0 ? GOLD : ((s.good * (s.v[i] - r.base) > 0) ? GREEN : ROSE)); cv.drawCircle(xs[k2], ys[k2], dp(c, n > 45 ? 2.4f : 4.2f), paint(dc)); k2++; }
            if (n > 0) { cv.drawCircle(xs[n - 1], ys[n - 1], dp(c, 8), paint(alpha(s.color, 0.2f))); }
            tx.setTextAlign(Paint.Align.LEFT); SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.UK); cv.drawText(f.format(new Date((long) (t0 * 86400000.0))), 0, h - dp(c, 5), tx); tx.setTextAlign(Paint.Align.RIGHT); cv.drawText(f.format(new Date((long) (r.lastDay * 86400000.0))), w, h - dp(c, 5), tx);
            if (touchX >= 0f) { Paint cur = paint(0x99FFFFFF); cur.setStrokeWidth(dp(c, 1)); cv.drawLine(touchX, top, touchX, ch, cur); }
        }
    }
}

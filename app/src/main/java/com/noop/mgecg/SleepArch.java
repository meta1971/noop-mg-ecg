package com.noop.mgecg;

// FILE VERSION 0.34.0 (1 Nov 2026): sleep architecture: stage confidence by stage, the age check, architecture metrics (REM latency, REM episodes, deep and REM by half of the night, waking in the second half)
// for the last night in the Sleep tab, and a screen with the same numbers for the last nights next to the drinks you logged (the alcohol pattern check). The stages are the app's own estimate (heart rate and
// movement, with a second pass using the strap's beat intervals to mark disagreement); nothing here is a polysomnography measurement.

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

final class SleepArch {
    private SleepArch() {}

    static final int WAKE = 0, LIGHT = 1, DEEP = 2, REM = 3;

    static final class Arch {
        final double[] mins = new double[4];                      // wake, light, deep, REM (minutes)
        double remLatency = Double.NaN, deep1, deep2, rem1, rem2, waso, waso2; int remEpisodes;
        double sleepMin() { return mins[LIGHT] + mins[DEEP] + mins[REM]; }
        double share(int stage) { double s = sleepMin(); return s <= 0 ? Double.NaN : 100.0 * mins[stage] / s; }
    }

    private static int idx(String s) { return "wake".equals(s) ? WAKE : "deep".equals(s) ? DEEP : "rem".equals(s) ? REM : LIGHT; }

    /** Architecture of one night from stage segments (stage strings wake / light / deep / rem) between bed and end (unix seconds). */
    static Arch of(List<SleepStager.Segment> seg, long bed, long end) {
        Arch a = new Arch(); if (seg == null || seg.isEmpty()) return a;
        long onset = -1;
        for (SleepStager.Segment s : seg) if (!"wake".equals(s.stage) && Math.min(s.end, end) - Math.max(s.start, bed) >= 300) { onset = Math.max(s.start, bed); break; }
        if (onset < 0) onset = bed;
        long mid = (onset + end) / 2;
        for (SleepStager.Segment s : seg) {
            long s0 = Math.max(s.start, bed), s1 = Math.min(s.end, end); if (s1 <= s0) continue; int k = idx(s.stage); a.mins[k] += (s1 - s0) / 60.0;
            long h1 = Math.min(s1, mid) - s0, h2 = s1 - Math.max(s0, mid); if (h1 < 0) h1 = 0; if (h2 < 0) h2 = 0;
            if (k == DEEP) { a.deep1 += h1 / 60.0; a.deep2 += h2 / 60.0; }
            if (k == REM) { a.rem1 += h1 / 60.0; a.rem2 += h2 / 60.0; if (s1 - s0 >= 180) { a.remEpisodes++; if (Double.isNaN(a.remLatency)) a.remLatency = (s0 - onset) / 60.0; } }
            if (k == WAKE && s0 >= onset) { a.waso += (s1 - s0) / 60.0; a.waso2 += h2 / 60.0; }
        }
        return a;
    }

    // ------------------------------------------------------------------ staging of a past night (same recipe as the Sleep tab)
    private static List<SleepStager.Sec> secsFrom(List<byte[]> recs, boolean withRr) {
        List<SleepStager.Sec> out = new ArrayList<SleepStager.Sec>(recs.size()); long last = -1;
        for (byte[] f : recs) {
            if (!R18Codec.isR18(f)) continue; long t = R18Codec.ts(f); if (t == last) continue; last = t;
            SleepStager.Sec s = new SleepStager.Sec(); s.ts = t; int h = R18Codec.hr(f); if (h > 30) s.hr = h;
            s.gx = R18Codec.f32(f, 45); s.gy = R18Codec.f32(f, 49); s.gz = R18Codec.f32(f, 53);
            if (Double.isNaN(s.gx) || Double.isInfinite(s.gx) || Double.isNaN(s.gy) || Double.isNaN(s.gz)) { s.gx = s.gy = s.gz = Double.NaN; }
            if (withRr) { int n = Math.min(4, R18Codec.u8(f, 23)); double[] rr = new double[n]; int k = 0; for (int i = 0; i < n; i++) { double v = R18Codec.rr(f, i); if (v > 300 && v < 2000) rr[k++] = v; } s.rr = Arrays.copyOf(rr, k); }
            out.add(s);
        }
        return out;
    }

    static final class Night {
        long bed, end; double tibH, tstH, agreePct, settle = Double.NaN, hrFirst3 = Double.NaN, hrLast3 = Double.NaN, rmFirst3 = Double.NaN, rmLast3 = Double.NaN; Arch arch = new Arch(); int drinks, dry;
    }

    /** Stages one night from its per-second records (with a margin either side); null when no night is found. */
    static Night analyse(List<byte[]> recs) {
        SleepNight.Night nt = SleepNight.analyse(recs); if (nt == null) return null;
        List<byte[]> night = new ArrayList<byte[]>(); for (byte[] f : recs) { long t = R18Codec.ts(f); if (t >= nt.bedTs && t <= nt.endTs) night.add(f); }
        if (night.size() < 1800) return null;
        List<SleepStager.Segment> a = SleepStager.stage(nt.bedTs, nt.endTs, secsFrom(night, false)), b = SleepStager.stage(nt.bedTs, nt.endTs, secsFrom(night, true));
        Night n = new Night(); n.bed = nt.bedTs; n.end = nt.endTs; n.tibH = nt.tibH; n.tstH = nt.tstH; n.arch = of(a, nt.bedTs, nt.endTs); n.settle = nt.settleMin; n.hrFirst3 = nt.hrFirst3; n.hrLast3 = nt.hrLast3; n.rmFirst3 = nt.rmFirst3; n.rmLast3 = nt.rmLast3;
        int ag = 0, tot = 0; for (long t = nt.bedTs + 15; t < nt.endTs; t += 30) { int x = at(a, t), y = at(b, t); if (x < 0) continue; tot++; if (x == y) ag++; } n.agreePct = tot == 0 ? Double.NaN : 100.0 * ag / tot;
        return n;
    }
    private static int at(List<SleepStager.Segment> seg, long t) { for (SleepStager.Segment s : seg) if (t >= s.start && t < s.end) return idx(s.stage); return -1; }

    /** The last nights from the day logger with the drinks logged around each (call off the main thread). */
    static List<Night> load(Context c, List<TodayModel.NightStat> nights, int max) {
        List<Night> out = new ArrayList<Night>(); List<Long> drinks = new ArrayList<Long>(), dry = new ArrayList<Long>();
        try { HrvScreen.SQLiteHelper.markers(c, "alcohol", drinks); HrvScreen.SQLiteHelper.markers(c, "no_alcohol", dry); } catch (Throwable ignored) { }
        List<TodayModel.NightStat> ns = new ArrayList<TodayModel.NightStat>(nights); Collections.sort(ns, new java.util.Comparator<TodayModel.NightStat>() { @Override public int compare(TodayModel.NightStat x, TodayModel.NightStat y) { return Long.compare(y.bed, x.bed); } });
        int cnt = 0;
        for (TodayModel.NightStat s : ns) {
            if (cnt++ >= max) break;
            try {
                Cursor cu = DayLog.db(c).rawQuery("SELECT rec FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts", new String[] { String.valueOf(s.bed - 7200L), String.valueOf(s.bed + 13 * 3600L) });
                List<byte[]> recs = new ArrayList<byte[]>(); try { while (cu.moveToNext()) { byte[] b = cu.getBlob(0); if (b != null) recs.add(b); } } finally { cu.close(); }
                Night n = analyse(recs); if (n == null) continue;
                for (long m : drinks) if (m / 1000L >= n.bed - 10L * 3600L && m / 1000L <= n.bed + 3600L) n.drinks++;
                for (long m : dry) if (m / 1000L >= n.bed - 10L * 3600L && m / 1000L <= n.bed + 3600L) n.dry++;
                out.add(n);
            } catch (Throwable ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ Sleep tab rows for the last night
    /** Rows {label, value} for the stage confidence and age check card. */
    static List<String[]> confidenceRows(HealthModel.Data d, double age) {
        List<String[]> r = new ArrayList<String[]>(); if (d == null || d.binStage == null || d.binStage.length == 0) return r;
        int[] sure = new int[4], all = new int[4]; int sb = 0, ab = 0;
        for (int i = 0; i < d.binStage.length; i++) { int k = d.binStage[i]; if (k < 0) continue; all[k]++; ab++; if (d.binSure[i]) { sure[k]++; sb++; } }
        String[] nm = { "Awake", "Light", "Deep", "REM" }; int[] order = { DEEP, REM, LIGHT, WAKE };
        for (int k : order) { double tot = d.stageSecs[k] / 60.0; r.add(new String[] { nm[k] + ", total (sure)", String.format(Locale.UK, "%.0f min  (%.0f min sure)", tot, Math.min(tot, sure[k] * 5.0)) }); }
        r.add(new String[] { "The two methods agree on", ab == 0 ? "-" : Math.round(100.0 * sb / ab) + " % of the night" });
        double sleep = (d.stageSecs[LIGHT] + d.stageSecs[DEEP] + d.stageSecs[REM]) / 60.0;
        if (sleep > 60) {
            double dp = 100.0 * d.stageSecs[DEEP] / 60.0 / sleep, rp = 100.0 * d.stageSecs[REM] / 60.0 / sleep;
            r.add(new String[] { "Share of sleep: deep / REM", String.format(Locale.UK, "%.0f %% / %.0f %%", dp, rp) });
            double dc = 20.0 - 0.375 * (age - 22.5), dLo = Math.max(2, dc - 3.5), dHi = Math.max(6, dc + 3.5);      // slow-wave share of sleep by age: about 20 / 17 / 12 / 8 % at 20s / 30s / 40s / 50s (secondary summary of Ohayon 2004; about 3 points fewer per decade from 40)
            r.add(new String[] { "Usual at your age: deep / REM", String.format(Locale.UK, "about %.0f to %.0f %% / 20 to 25 %%", dLo, dHi) });
            StringBuilder v = new StringBuilder();
            if (dp > dHi + 5) v.append("Deep is above the usual range. "); if (rp > 32) v.append("REM is above the usual range. "); if (rp < 12) v.append("REM is below the usual range. ");
            r.add(new String[] { "Reading", v.length() == 0 ? "Within the usual ranges." : v.toString().trim() });
        }
        return r;
    }

    /** Rows {label, value} for the architecture card (computed from the stages the Sleep tab already holds). */
    static List<String[]> architectureRows(HealthModel.Data d, long bed, long end) {
        List<String[]> r = new ArrayList<String[]>(); if (d == null || d.stages == null || d.stages.isEmpty()) return r; Arch a = of(d.stages, bed, end);
        r.add(new String[] { "Awake after sleep onset: total / second half", Math.round(a.waso) + " / " + Math.round(a.waso2) + " min" });
        r.add(new String[] { "REM latency (stager's estimate)", Double.isNaN(a.remLatency) ? "no REM found" : Math.round(a.remLatency) + " min" });
        r.add(new String[] { "REM episodes of 3 min or more", String.valueOf(a.remEpisodes) });
        r.add(new String[] { "Deep: first half / second half of the night", Math.round(a.deep1) + " / " + Math.round(a.deep2) + " min" });
        r.add(new String[] { "REM: first half / second half", Math.round(a.rem1) + " / " + Math.round(a.rem2) + " min" });
        r.add(new String[] { "Read with care", "The stager assumes deep sleep early and REM late (its cycle prior), so these halves and the REM latency reflect that assumption and cannot confirm the pattern. The awake time and your heart rate and HRV across the night are measured, not assumed." });
        return r;
    }

    // ------------------------------------------------------------------ screen: the last nights and the alcohol pattern check
    private static String f0(double v) { return Double.isNaN(v) ? "-" : String.format(Locale.UK, "%.0f", v); }
    private static double med(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> b = new ArrayList<Double>(v); Collections.sort(b); int n = b.size(); return n % 2 == 1 ? b.get(n / 2) : 0.5 * (b.get(n / 2 - 1) + b.get(n / 2)); }

    static void show(final Activity a, final HealthModel.Data d) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen); dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); int pad = Math.round(dp(a, 16)); root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "Sleep architecture", 22, INK, 300)); top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK); close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } }); top.addView(close); root.addView(top);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); ScrollView sv = new ScrollView(a); sv.addView(col); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        final TextView status = HeartGraph.label(a, "Staging the last nights from the day logger...", 13, MUT, 400); col.addView(status, HeartGraph.lp(a, -1, -2, 14)); dlg.setContentView(root); dlg.show();
        final List<TodayModel.NightStat> nights = new ArrayList<TodayModel.NightStat>(d.nights);
        new Thread(new Runnable() { @Override public void run() {
            List<Night> r0 = null; try { r0 = load(a, nights, 12); } catch (Throwable ignored) { }
            final List<Night> r = r0;
            a.runOnUiThread(new Runnable() { @Override public void run() { try { fill(a, col, status, r); } catch (Throwable ignored) { } } });
        } }, "sleep-arch").start();
    }

    private static TextView text(Context a, String t, int sp, int color) { TextView x = HeartGraph.label(a, t, sp, color, 400); x.setLineSpacing(0, 1.25f); return x; }

    private static void fill(Activity a, LinearLayout col, TextView status, List<Night> nights) {
        if (nights == null || nights.isEmpty()) { status.setText("No staged nights yet."); return; }
        col.removeView(status); SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.UK); StringBuilder sb = new StringBuilder();
        sb.append("EACH NIGHT\nin bed h | asleep h | settle min | awake in 2nd half min | heart rate first 3 h / last 3 h | HRV first 3 h / last 3 h (optical) | drinks\n");
        for (Night n : nights) {
            Arch x = n.arch; sb.append(f.format(new Date(n.bed * 1000L))).append(":  ").append(String.format(Locale.UK, "%.1f", n.tibH)).append(" | ").append(String.format(Locale.UK, "%.1f", n.tstH)).append(" | ").append(f0(n.settle)).append(" | ").append(f0(x.waso2)).append(" | ")
              .append(f0(n.hrFirst3)).append("/").append(f0(n.hrLast3)).append(" | ").append(f0(n.rmFirst3)).append("/").append(f0(n.rmLast3)).append(" | ")
              .append(n.drinks > 0 ? String.valueOf(n.drinks) : n.dry > 0 ? "none (marked)" : "-").append('\n');
        }
        TextView t1 = text(a, sb.toString().trim(), 11, INK); t1.setTypeface(android.graphics.Typeface.MONOSPACE); col.addView(t1, HeartGraph.lp(a, -1, -2, 8));
        List<List<Double>> dg = new ArrayList<List<Double>>(), yg = new ArrayList<List<Double>>(); for (int i = 0; i < 5; i++) { dg.add(new ArrayList<Double>()); yg.add(new ArrayList<Double>()); } int nd = 0, ny = 0;
        for (Night n : nights) {
            double[] v = { n.settle, n.arch.waso2, n.hrFirst3, n.rmFirst3, n.rmLast3 };
            if (n.drinks > 0) { nd++; for (int i = 0; i < 5; i++) if (!Double.isNaN(v[i])) dg.get(i).add(v[i]); }
            else if (n.dry > 0) { ny++; for (int i = 0; i < 5; i++) if (!Double.isNaN(v[i])) yg.get(i).add(v[i]); }
        }
        String cmp = (nd >= 2 && ny >= 2) ? String.format(Locale.UK, "After drinks (%d nights): settle %s min, awake in the second half %s min, heart rate in the first 3 h %s, HRV first 3 h %s and last 3 h %s ms.\nAlcohol-free (%d nights): %s, %s, %s, %s, %s.", nd, f0(med(dg.get(0))), f0(med(dg.get(1))), f0(med(dg.get(2))), f0(med(dg.get(3))), f0(med(dg.get(4))), ny, f0(med(yg.get(0))), f0(med(yg.get(1))), f0(med(yg.get(2))), f0(med(yg.get(3))), f0(med(yg.get(4))))
            : String.format(Locale.UK, "Your own comparison needs at least 2 nights of each. Logged so far: %d with drinks, %d alcohol-free. Use the A drink and No alcohol today buttons on the HRV screen.", nd, ny);
        LinearLayout c = new LinearLayout(a); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(glass(a, 18)); int p = Math.round(dp(a, 16)); c.setPadding(p, p, p, p);
        c.addView(HeartGraph.label(a, "ALCOHOL PATTERN CHECK", 11, MUT, 400)); c.addView(text(a, cmp, 13, INK), HeartGraph.lp(a, -1, -2, 8));
        c.addView(text(a, "What a review of 20 studies in healthy volunteers found (Ebrahim 2013): at every dose alcohol shortens the time to fall asleep, makes the first half more consolidated and the second half more disrupted, delays the first REM period, raises slow-wave sleep in the first half, and lowers total REM at moderate and high doses. The table uses what the strap measures (settling time, waking in the second half, heart rate and HRV). Deep and REM timing are left out: the stager assumes deep early and REM late, so it cannot test that part of the pattern.", 12, MUT), HeartGraph.lp(a, -1, -2, 10));
        col.addView(c, HeartGraph.lp(a, -1, -2, 12));
        TextView lim = text(a, "Stage totals are the app's own estimates from heart rate and movement, not polysomnography. On your 8 nights of 21 to 31 Oct they put deep at 11 to 26 % and REM at 10 to 45 % of sleep, above the usual ranges for your age, so look at changes between nights rather than the totals.", 12, MUT); col.addView(lim, HeartGraph.lp(a, -1, -2, 6));
    }
}

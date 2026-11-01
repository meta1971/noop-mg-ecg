package com.noop.mgecg;

// FILE VERSION 0.33.0 (1 Nov 2026): ECG-grade HRV from the Polar H10's own R-R files (h10_rr_*.csv in the app folder), by local clock hour, for the HRV screen; also the H10 link health
// (gaps, link losses, silent-stream restarts). Beat intervals are filtered (more than 20 % from the median of the previous 11 are dropped) and scored in 5-minute windows with at least 200 beats.

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

final class H10Hrv {
    private H10Hrv() {}

    static final class Result {
        final double[][] hours = new double[24][2];          // median RMSSD (ms) and window count per local hour
        double nightRmssd = Double.NaN, nightHr = Double.NaN, opticalRatio = Double.NaN, hoursCovered;
        int nightWindows, ratioHours, windows, gaps, lost, silent, files; double gapMin;
    }

    private static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> b = new ArrayList<Double>(v); Collections.sort(b); int n = b.size(); return n % 2 == 1 ? b.get(n / 2) : 0.5 * (b.get(n / 2 - 1) + b.get(n / 2)); }

    /** Pure: beats = {time (unix seconds), interval (ms)} sorted by time. */
    static Result analyse(List<double[]> beats, long sinceSec, long nowSec, TimeZone tz) {
        Result R = new Result(); int n = beats.size(); for (int h = 0; h < 24; h++) R.hours[h][0] = Double.NaN;
        if (n < 200) return R;
        double[] t = new double[n], rr = new double[n]; for (int i = 0; i < n; i++) { t[i] = beats.get(i)[0]; rr[i] = beats.get(i)[1]; }
        boolean[] keep = new boolean[n];
        for (int j = 0; j < n; j++) { int lo = Math.max(0, j - 11); double ref; if (j - lo >= 5) { double[] w = Arrays.copyOfRange(rr, lo, j); Arrays.sort(w); int m = w.length; ref = m % 2 == 1 ? w[m / 2] : 0.5 * (w[m / 2 - 1] + w[m / 2]); } else ref = rr[j]; keep[j] = Math.abs(rr[j] - ref) <= 0.2 * ref; }
        List<List<Double>> byHour = new ArrayList<List<Double>>(); for (int h = 0; h < 24; h++) byHour.add(new ArrayList<Double>());
        List<Double> nr = new ArrayList<Double>(), nh = new ArrayList<Double>(); Calendar cal = Calendar.getInstance(tz);
        int i = 0;
        while (i < n) {
            long w = ((long) t[i] / 300L) * 300L; int j = i; List<Double> x = new ArrayList<Double>();
            while (j < n && t[j] < w + 300) { if (keep[j] && t[j] >= sinceSec) x.add(rr[j]); j++; }
            i = j;
            if (x.size() < 200) continue;
            double sq = 0, sum = x.get(0); for (int k = 1; k < x.size(); k++) { double d = x.get(k) - x.get(k - 1); sq += d * d; sum += x.get(k); }
            double rm = Math.sqrt(sq / (x.size() - 1)), hr = 60000.0 / (sum / x.size()); R.windows++;
            cal.setTimeInMillis(w * 1000L); int hod = cal.get(Calendar.HOUR_OF_DAY); byHour.get(hod).add(rm); if (hod <= 6) { nr.add(rm); nh.add(hr); }
        }
        for (int h = 0; h < 24; h++) { R.hours[h][1] = byHour.get(h).size(); R.hours[h][0] = byHour.get(h).isEmpty() ? Double.NaN : median(byHour.get(h)); }
        R.nightRmssd = median(nr); R.nightHr = median(nh); R.nightWindows = nr.size();
        for (int k = 1; k < n; k++) if (t[k] >= nowSec - 24 * 3600L && t[k] - t[k - 1] > 20) { R.gaps++; R.gapMin += (t[k] - t[k - 1]) / 60.0; }
        R.hoursCovered = n * 0.9 / 3600.0;
        return R;
    }

    private static void parse(File f, long sinceMs, List<double[]> out) {
        try (BufferedReader br = new BufferedReader(new FileReader(f), 1 << 16)) {
            String ln = br.readLine(); long cur = -1; List<Double> grp = new ArrayList<Double>();
            while ((ln = br.readLine()) != null) {
                int a = ln.indexOf(','), b = ln.lastIndexOf(','); if (a < 0 || b <= a) continue;
                long ms; double rr; try { ms = Long.parseLong(ln.substring(0, a)); rr = Double.parseDouble(ln.substring(b + 1)); } catch (Exception e) { continue; }
                if (ms < sinceMs) continue;
                if (ms != cur) { flush(cur, grp, out); cur = ms; grp.clear(); } grp.add(rr);
            }
            flush(cur, grp, out);
        } catch (Throwable ignored) { }
    }

    private static void flush(long ms, List<Double> grp, List<double[]> out) {
        if (ms < 0 || grp.isEmpty()) return; double acc = 0;
        for (int k = grp.size() - 1; k >= 0; k--) { out.add(new double[] { ms / 1000.0 - acc, grp.get(k) }); acc += grp.get(k) / 1000.0; }
    }

    private static long stamp(String name) { try { return Long.parseLong(name.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0L; } }

    /** Reads the last 3 days of H10 files from the app folder (call off the main thread). */
    static Result load(Context c) {
        long now = System.currentTimeMillis() / 1000L, since = now - 72 * 3600L; File dir = c.getExternalFilesDir(null);
        if (dir == null) return new Result();
        List<double[]> beats = new ArrayList<double[]>(); int files = 0, lost = 0, silent = 0;
        File[] all = dir.listFiles(); if (all == null) return new Result();
        for (File f : all) {
            String n = f.getName(); long st = stamp(n);
            if (n.startsWith("h10_rr_") && n.endsWith(".csv") && st >= since - 12 * 3600L) { parse(f, since * 1000L, beats); files++; }
            else if (n.startsWith("h10_log_") && n.endsWith(".txt") && st >= now - 30 * 3600L) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) { String ln; while ((ln = br.readLine()) != null) { int tab = ln.indexOf('\t'); if (tab < 0) continue; long ms; try { ms = Long.parseLong(ln.substring(0, tab)); } catch (Exception e) { continue; } if (ms / 1000L < now - 24 * 3600L) continue; String m = ln.substring(tab + 1); if (m.startsWith("LOST")) lost++; else if (m.contains("silent, reconnecting")) silent++; } } catch (Throwable ignored) { }
            }
        }
        Collections.sort(beats, new java.util.Comparator<double[]>() { @Override public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); } });
        Result r = analyse(beats, since, now, TimeZone.getDefault()); r.files = files; r.lost = lost; r.silent = silent;
        try {                                                                                  // the strap's optical value in the same hours, last 48 h
            double[][] op = IrregularWatch.hourly(c); List<Double> q = new ArrayList<Double>();
            for (int h = 0; h < 24; h++) if (!Double.isNaN(op[h][0]) && op[h][1] >= 2 && !Double.isNaN(r.hours[h][0]) && r.hours[h][1] >= 2 && r.hours[h][0] > 0) q.add(op[h][0] / r.hours[h][0]);
            r.ratioHours = q.size(); if (q.size() >= 3) r.opticalRatio = median(q);
        } catch (Throwable ignored) { }
        return r;
    }
}

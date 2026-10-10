package com.noop.mgecg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Night summary from the strap's per-second records (R18). Pure Java so it can be tested off the phone.
 * Only what was validated against a second strap is reported: the sleep window and sleep time from the band's own
 * state, restless spells, heart rate, overnight RMSSD from the strap's R-R words, skin temperature, and the byte-82
 * measurement windows. NO sleep stages: state UP means arm movement while lying down (heart rate and posture stay
 * as in sleep), not standing, and no staging method has been validated here. RESEARCH ONLY.
 */
final class SleepNight {
    private SleepNight() {}

    static final class Night {
        long bedTs, firstSleepTs, endTs;
        double tibH, tstH, effPct, settleMin;
        final List<long[]> restless = new ArrayList<>();      // {startTs, seconds}
        double hrMean = Double.NaN, hrLow5 = Double.NaN, hrFirst30 = Double.NaN, hrLast30 = Double.NaN;
        long hrLow5Ts;
        double rmssd = Double.NaN;
        double[] rmssdByHour = new double[0];
        double skinMedian = Double.NaN, skinMin = Double.NaN, skinMax = Double.NaN;
        int windows, validN, b82Median, b82Min;
    }

    /** Clusters of sleep: {startTs, endTs, sleepSeconds}. Input: timestamps of every record whose band state is SLEEP, sorted. */
    static List<long[]> clusters(long[] sleepTs) {
        List<long[]> blocks = new ArrayList<>();
        int i = 0;
        while (i < sleepTs.length) {
            int j = i;
            while (j + 1 < sleepTs.length && sleepTs[j + 1] - sleepTs[j] <= 600) j++;
            if (sleepTs[j] - sleepTs[i] >= 600) blocks.add(new long[] {sleepTs[i], sleepTs[j], j - i + 1});
            i = j + 1;
        }
        List<long[]> out = new ArrayList<>();
        long[] cur = null;
        for (long[] b : blocks) {
            if (cur != null && b[0] - cur[1] <= 10800) { cur[1] = b[1]; cur[2] += b[2]; }
            else { if (cur != null) out.add(cur); cur = new long[] {b[0], b[1], b[2]}; }
        }
        if (cur != null) out.add(cur);
        List<long[]> keep = new ArrayList<>();
        for (long[] c : out) if (c[1] - c[0] >= 3 * 3600 && c[2] >= 2 * 3600) keep.add(c);
        return keep;
    }

    private static double median(double[] v, int from, int to) {
        double[] a = Arrays.copyOfRange(v, from, to);
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2.0;
    }

    /** Records must be sorted by time and cover the night with a little margin either side. */
    static Night analyse(List<byte[]> recs) {
        int n = recs.size();
        if (n < 3600) return null;
        long[] ts = new long[n];
        int[] st = new int[n];
        for (int i = 0; i < n; i++) { ts[i] = R18Codec.ts(recs.get(i)); st[i] = R18Codec.state(recs.get(i)); }
        // contiguous SLEEP runs of 10 minutes or more
        int first = -1, last = -1;
        int i = 0;
        while (i < n) {
            if (st[i] == 2) {
                int j = i;
                while (j + 1 < n && st[j + 1] == 2 && ts[j + 1] - ts[j] <= 2) j++;
                if (j - i + 1 >= 600) { if (first < 0) first = i; last = j; }
                i = j + 1;
            } else i++;
        }
        if (first < 0) return null;
        int bed = first;
        while (bed > 0 && (st[bed - 1] == 1 || st[bed - 1] == 2) && ts[bed] - ts[bed - 1] <= 2) bed--;
        int end = last;
        Night nt = new Night();
        nt.bedTs = ts[bed]; nt.firstSleepTs = ts[first]; nt.endTs = ts[end];
        int len = end - bed + 1;
        nt.tibH = (ts[end] - ts[bed] + 1) / 3600.0;
        int asleep = 0;
        for (int k = bed; k <= end; k++) if (st[k] == 2) asleep++;
        nt.tstH = asleep / 3600.0;
        nt.effPct = 100.0 * nt.tstH / nt.tibH;
        nt.settleMin = (ts[first] - ts[bed]) / 60.0;
        // restless spells: 2 minutes or more outside SLEEP that include WAKE or UP
        int k = bed;
        while (k <= end) {
            if (st[k] != 2) {
                int j = k; boolean act = false;
                while (j + 1 <= end && st[j + 1] != 2) j++;
                for (int q = k; q <= j; q++) if (st[q] == 0 || st[q] == 3) { act = true; break; }
                if (j - k + 1 >= 120 && act) nt.restless.add(new long[] {ts[k], j - k + 1});
                k = j + 1;
            } else k++;
        }
        // heart rate
        List<Double> hv = new ArrayList<>(); List<Long> ht = new ArrayList<>();
        for (int q = bed; q <= end; q++) { int h = R18Codec.hr(recs.get(q)); if (h > 0) { hv.add((double) h); ht.add(ts[q]); } }
        if (hv.size() > 2400) {
            double[] h = new double[hv.size()];
            double sum = 0;
            for (int q = 0; q < h.length; q++) { h[q] = hv.get(q); sum += h[q]; }
            nt.hrMean = sum / h.length;
            double best = 1e9; int bi = 1800;
            for (int q = 1800; q + 300 <= h.length; q += 60) { double m = median(h, q, q + 300); if (m < best) { best = m; bi = q; } }
            nt.hrLow5 = best; nt.hrLow5Ts = ht.get(bi);
            nt.hrFirst30 = median(h, 0, 1800); nt.hrLast30 = median(h, h.length - 1800, h.length);
        }
        // R-R words -> RMSSD overall and by hour
        List<double[]> rr = new ArrayList<>();
        for (int q = bed; q <= end; q++) {
            byte[] f = recs.get(q);
            int c = Math.min(R18Codec.u8(f, 23), 4);
            for (int w = 0; w < c; w++) { double v = R18Codec.rr(f, w); if (v > 300 && v < 2000) rr.add(new double[] {ts[q] + w * 0.01, v}); }
        }
        nt.rmssd = rmssd(rr, 0, rr.size(), 100);
        int hours = (int) nt.tibH + 1;
        nt.rmssdByHour = new double[hours];
        for (int hh = 0; hh < hours; hh++) {
            long a = ts[bed] + hh * 3600L, b = a + 3600;
            int lo = -1, hi = -1;
            for (int q = 0; q < rr.size(); q++) { double t = rr.get(q)[0]; if (t >= a && t < b) { if (lo < 0) lo = q; hi = q + 1; } }
            nt.rmssdByHour[hh] = (lo >= 0 && hi - lo > 200) ? rmssd(rr, lo, hi, 100) : Double.NaN;
        }
        // skin temperature after the first 45 minutes
        if (len > 2800) {
            double[] sk = new double[len - 2700];
            double mn = 1e9, mx = -1e9;
            for (int q = 0; q < len; q++) { double s = R18Codec.skinC(recs.get(bed + q)); mn = Math.min(mn, s); mx = Math.max(mx, s); if (q >= 2700) sk[q - 2700] = s; }
            nt.skinMedian = median(sk, 0, sk.length); nt.skinMin = mn; nt.skinMax = mx;
        }
        // byte-82 windows
        List<Integer> vals = new ArrayList<>();
        int prev = 0, wins = 0;
        for (int q = bed; q <= end; q++) {
            byte[] f = recs.get(q);
            boolean w = R18Codec.spo2Window(f);
            if (w && (prev == 0)) wins++;
            prev = w ? 1 : 0;
            if (w) { int v = R18Codec.u8(f, 82); if (v >= 70 && v <= 100) vals.add(v); }
        }
        nt.windows = wins; nt.validN = vals.size();
        if (!vals.isEmpty()) {
            double[] v = new double[vals.size()]; int mn = 1000;
            for (int q = 0; q < v.length; q++) { v[q] = vals.get(q); mn = Math.min(mn, vals.get(q)); }
            nt.b82Median = (int) Math.round(median(v, 0, v.length)); nt.b82Min = mn;
        }
        return nt;
    }

    /** RMSSD over rr[from..to): successive differences kept when under 20% of the interval and under 2.5 s apart. */
    static double rmssd(List<double[]> rr, int from, int to, int minDiffs) {
        double s = 0; int c = 0;
        for (int q = from + 1; q < to; q++) {
            double d = rr.get(q)[1] - rr.get(q - 1)[1];
            if (Math.abs(d) < 0.2 * rr.get(q)[1] && rr.get(q)[0] - rr.get(q - 1)[0] < 2.5) { s += d * d; c++; }
        }
        return c > minDiffs ? Math.sqrt(s / c) : Double.NaN;
    }
}

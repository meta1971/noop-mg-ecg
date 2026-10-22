package com.noop.mgecg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Night summary from the strap's per-second records (R18). Pure Java so it can be tested off the phone.
 * Only what was validated against a second strap is reported: the sleep window and sleep time from the band's own
 * state, restless spells, heart rate (overall and by hour), overnight RMSSD from the strap's R-R words, breathing rate
 * from the R-R tachogram (matched a WHOOP 5 at 9.4 per minute over one 3.5 h stretch), skin temperature, and the
 * byte-82 measurement windows. A night is flagged PARTIAL when the log starts or ends while the band is still in
 * sleep, because the totals then cover only the logged part. NO sleep stages: state UP means arm movement while
 * lying down (heart rate and posture stay as in sleep), not standing, and no staging method has been validated
 * here. RESEARCH ONLY.
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
        boolean partialStart, partialEnd;                     // log begins / ends while the band is still in sleep
        double coveragePct = 100.0;                           // records present over the seconds between bed and end
        double minStill, minWake, minUp;                      // minutes between bed and end in each non-SLEEP band state
        double[] hrByHour = new double[0];
        double brMedian = Double.NaN;                         // breaths per minute, median of the clear 5-minute windows
        int brWindows;
        double[] brByHour = new double[0];
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
    static Night analyse(List<byte[]> recs) { return analyse(recs, 0L, 0L); }

    /** logStartTs / logEndTs: first and last record time in the whole log (0 = unknown, no partial-night check). */
    static Night analyse(List<byte[]> recs, long logStartTs, long logEndTs) {
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
        nt.partialStart = logStartTs > 0 && bed == 0 && ts[0] <= logStartTs + 120 && (st[0] == 1 || st[0] == 2);
        nt.partialEnd = logEndTs > 0 && end == n - 1 && ts[n - 1] >= logEndTs - 120;
        nt.coveragePct = Math.min(100.0, 100.0 * len / (double) (ts[end] - ts[bed] + 1));
        int cStill = 0, cWake = 0, cUp = 0;
        for (int q = bed; q <= end; q++) { if (st[q] == 1) cStill++; else if (st[q] == 0) cWake++; else if (st[q] == 3) cUp++; }
        nt.minStill = cStill / 60.0; nt.minWake = cWake / 60.0; nt.minUp = cUp / 60.0;
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
        // heart rate by hour (median per clock hour counted from bed)
        int hrHours = (int) nt.tibH + 1;
        nt.hrByHour = new double[hrHours];
        for (int hh = 0; hh < hrHours; hh++) {
            long a = ts[bed] + hh * 3600L, b = a + 3600;
            List<Double> in = new ArrayList<>();
            for (int q = 0; q < ht.size(); q++) { long t = ht.get(q); if (t >= a && t < b) in.add(hv.get(q)); }
            if (in.size() > 600) { double[] w = new double[in.size()]; for (int q = 0; q < w.length; q++) w[q] = in.get(q); nt.hrByHour[hh] = median(w, 0, w.length); }
            else nt.hrByHour[hh] = Double.NaN;
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
        // breathing rate from the R-R tachogram
        List<double[]> clear = breathing(rr, ts[bed], ts[end] + 1);
        nt.brWindows = clear.size();
        if (clear.size() >= 3) {
            double[] bv = new double[clear.size()];
            for (int q = 0; q < bv.length; q++) bv[q] = clear.get(q)[1];
            nt.brMedian = median(bv, 0, bv.length);
        }
        nt.brByHour = new double[hours];
        for (int hh = 0; hh < hours; hh++) {
            long a = ts[bed] + hh * 3600L, b = a + 3600;
            List<Double> in = new ArrayList<>();
            for (double[] w : clear) if (w[0] >= a && w[0] < b) in.add(w[1]);
            if (in.size() >= 2) { double[] w = new double[in.size()]; for (int q = 0; q < w.length; q++) w[q] = in.get(q); nt.brByHour[hh] = median(w, 0, w.length); }
            else nt.brByHour[hh] = Double.NaN;
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

    /**
     * Breathing rate from the R-R tachogram. Walks 5-minute windows from t0 to t1; a window needs at least 144 beats and no
     * gap over 8 s. The R-R series is resampled to 4 Hz, de-trended, and a Welch spectrum (512-sample Hann segments, half
     * overlap) is searched between 0.12 and 0.40 Hz. A window counts only when its peak is more than 4 times the median of
     * that band. Returns {windowStartTs, breathsPerMinute, peakRatio} for the clear windows.
     */
    static List<double[]> breathing(List<double[]> rr, long t0, long t1) {
        List<double[]> out = new ArrayList<>();
        final int win = 300, fs = 4, seg = 512, kLo = 16, kHi = 51;
        final double[] cosT = new double[seg], sinT = new double[seg];
        for (int k = 0; k < seg; k++) { cosT[k] = Math.cos(2 * Math.PI * k / seg); sinT[k] = Math.sin(2 * Math.PI * k / seg); }
        int lo = 0;
        for (long s = t0; s + win <= t1; s += win) {
            while (lo < rr.size() && rr.get(lo)[0] < s) lo++;
            int hi = lo;
            while (hi < rr.size() && rr.get(hi)[0] < s + win) hi++;
            int cnt = hi - lo;
            if (cnt < 144) continue;
            double maxGap = 0;
            for (int q = lo + 1; q < hi; q++) maxGap = Math.max(maxGap, rr.get(q)[0] - rr.get(q - 1)[0]);
            if (maxGap > 8.0) continue;
            int m = win * fs;
            double[] y = new double[m];
            int p = lo;
            for (int k = 0; k < m; k++) {
                double tt = s + k / (double) fs;
                while (p + 1 < hi && rr.get(p + 1)[0] <= tt) p++;
                double v;
                if (tt <= rr.get(lo)[0]) v = rr.get(lo)[1];
                else if (tt >= rr.get(hi - 1)[0]) v = rr.get(hi - 1)[1];
                else {
                    double[] a = rr.get(p), b = rr.get(p + 1);
                    v = a[1] + (b[1] - a[1]) * (tt - a[0]) / (b[0] - a[0]);
                }
                y[k] = v;
            }
            // linear de-trend
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int k = 0; k < m; k++) { sx += k; sy += y[k]; sxx += (double) k * k; sxy += k * y[k]; }
            double den = m * sxx - sx * sx, slope = (m * sxy - sx * sy) / den, icpt = (sy - slope * sx) / m;
            for (int k = 0; k < m; k++) y[k] -= icpt + slope * k;
            int nseg = (m - seg) / 256 + 1;
            double[] pw = new double[kHi - kLo + 1];
            for (int g = 0; g < nseg; g++) {
                int off = g * 256;
                double mean = 0;
                for (int k = 0; k < seg; k++) mean += y[off + k];
                mean /= seg;
                double[] z = new double[seg];
                for (int k = 0; k < seg; k++) z[k] = (y[off + k] - mean) * (0.5 - 0.5 * Math.cos(2 * Math.PI * k / seg));
                for (int bin = kLo; bin <= kHi; bin++) {
                    double re = 0, im = 0;
                    for (int k = 0; k < seg; k++) { int ph = (bin * k) % seg; re += z[k] * cosT[ph]; im -= z[k] * sinT[ph]; }
                    pw[bin - kLo] += (re * re + im * im) / nseg;
                }
            }
            int best = 0;
            for (int q = 1; q < pw.length; q++) if (pw[q] > pw[best]) best = q;
            double med = median(pw, 0, pw.length);
            double ratio = pw[best] / med;
            if (ratio > 4.0) out.add(new double[] {s, (kLo + best) * (double) fs / seg * 60.0, ratio});
        }
        return out;
    }

    private static String f0(double v) { return Double.isNaN(v) ? "n/a" : String.format(java.util.Locale.US, "%.0f", v); }
    private static String f1(double v) { return Double.isNaN(v) ? "n/a" : String.format(java.util.Locale.US, "%.1f", v); }

    /** Values by hour, without a trailing part-hour that had too little data to report. */
    private static int shown(double[] v) {
        int k = v.length;
        while (k > 0 && Double.isNaN(v[k - 1])) k--;
        return k;
    }

    /** The text block for one night, exactly as the SLEEP REPORT screen shows it. */
    static String describe(Night n, java.text.SimpleDateFormat day, java.text.SimpleDateFormat hm) {
        StringBuilder s = new StringBuilder();
        s.append(day.format(new java.util.Date(n.bedTs * 1000L))).append(" to ").append(hm.format(new java.util.Date(n.endTs * 1000L))).append('\n');
        if (n.partialStart)
            s.append("  PARTIAL NIGHT: the log starts at ").append(hm.format(new java.util.Date(n.bedTs * 1000L)))
                    .append(" with the band already asleep, so the earlier part of this night is not in the log. The figures below cover only the logged part.\n");
        if (n.partialEnd)
            s.append("  PARTIAL NIGHT: the log ends at ").append(hm.format(new java.util.Date(n.endTs * 1000L)))
                    .append(" with the band still asleep. Pull the strap's history again to complete it.\n");
        if (n.coveragePct < 98.0)
            s.append(String.format(java.util.Locale.US, "  records present: %.0f%% of the seconds (the log has gaps)\n", n.coveragePct));
        s.append(String.format(java.util.Locale.US, "  %s %.1f h, asleep %.1f h (%.0f%%), %s\n",
                n.partialStart ? "logged" : "in bed", n.tibH, n.tstH, n.effPct,
                n.partialStart ? "settle-in not known" : String.format(java.util.Locale.US, "settled in %.0f min", n.settleMin)));
        s.append(String.format(java.util.Locale.US, "  not asleep while in bed: still %.0f min, awake %.0f min, arm movement %.0f min\n", n.minStill, n.minWake, n.minUp));
        s.append("  restless: ");
        if (n.restless.isEmpty()) s.append("none");
        for (long[] x : n.restless) s.append(hm.format(new java.util.Date(x[0] * 1000L))).append(' ').append(x[1] / 60).append(" min  ");
        s.append('\n');
        s.append("  heart rate: mean ").append(f0(n.hrMean)).append(", lowest 5 min ").append(f0(n.hrLow5));
        if (n.hrLow5Ts > 0) s.append(" at ").append(hm.format(new java.util.Date(n.hrLow5Ts * 1000L)));
        s.append(", first 30 min ").append(f0(n.hrFirst30)).append(", last 30 min ").append(f0(n.hrLast30)).append('\n');
        s.append("  heart rate by hour");
        for (int q = 0; q < shown(n.hrByHour); q++) s.append(' ').append(f0(n.hrByHour[q]));
        s.append('\n');
        s.append("  breathing ").append(f1(n.brMedian)).append(" per min (").append(n.brWindows).append(" clear 5-min windows); by hour");
        for (int q = 0; q < shown(n.brByHour); q++) s.append(' ').append(f0(n.brByHour[q]));
        s.append('\n');
        s.append("  RMSSD ").append(f1(n.rmssd)).append(" ms; by hour");
        for (int q = 0; q < shown(n.rmssdByHour); q++) s.append(' ').append(f0(n.rmssdByHour[q]));
        s.append('\n');
        s.append("  skin ").append(String.format(java.util.Locale.US, "%.2f", n.skinMedian)).append(" C after warm-up (").append(f1(n.skinMin)).append("-").append(f1(n.skinMax)).append(")\n");
        s.append("  byte 82: ").append(n.windows).append(" windows, ").append(n.validN).append(" valid values, median ").append(n.b82Median).append(", lowest ").append(n.b82Min).append("\n\n");
        return s.toString();
    }

    /** One night as per-minute series for the chart, from bed minus 20 minutes to the end plus 20 minutes. */
    static final class Series {
        long t0;                       // epoch seconds of minute 0
        int n;                         // number of minutes
        float[] hr, rmssd, br, skin;   // NaN where there is no value (rmssd and br hold the 5-minute window value in each of its minutes)
        byte[] state;                  // -1 no data, else 0 WAKE, 1 STILL, 2 SLEEP, 3 UP (arm movement)
        Night night;
        int index, count;              // this night's place among all nights found (0 = oldest)
        String title = "", report = "";
    }

    /** Builds the chart series for a night analysed from the same records. Returns null when there is nothing to draw. */
    static Series series(List<byte[]> recs, Night nt) {
        if (recs == null || recs.isEmpty() || nt == null) return null;
        long t0 = (nt.bedTs - 1200L) / 60L * 60L, t1 = nt.endTs + 1200L;
        int n = (int) ((t1 - t0) / 60L) + 1;
        if (n < 30 || n > 1800) return null;
        Series se = new Series();
        se.t0 = t0; se.n = n; se.night = nt;
        se.hr = new float[n]; se.rmssd = new float[n]; se.br = new float[n]; se.skin = new float[n]; se.state = new byte[n];
        float[][] hv = new float[n][60], kv = new float[n][60];
        int[] hc = new int[n], kc = new int[n];
        int[][] sc = new int[n][4];
        List<double[]> rr = new ArrayList<>();
        for (byte[] f : recs) {
            long t = R18Codec.ts(f);
            if (t < t0 || t > t1) continue;
            int m = (int) ((t - t0) / 60L);
            if (m < 0 || m >= n) continue;
            int h = R18Codec.hr(f);
            if (h > 30 && hc[m] < 60) hv[m][hc[m]++] = h;
            double k = R18Codec.skinC(f);
            if (k > 20 && k < 45 && kc[m] < 60) kv[m][kc[m]++] = (float) k;
            sc[m][R18Codec.state(f)]++;
            int c = Math.min(R18Codec.u8(f, 23), 4);
            for (int w = 0; w < c; w++) { double v = R18Codec.rr(f, w); if (v > 300 && v < 2000) rr.add(new double[] {t + w * 0.01, v}); }
        }
        for (int m = 0; m < n; m++) {
            se.hr[m] = hc[m] >= 20 ? medianF(hv[m], hc[m]) : Float.NaN;
            se.skin[m] = kc[m] >= 20 ? medianF(kv[m], kc[m]) : Float.NaN;
            int best = -1, bc = 0;
            for (int q = 0; q < 4; q++) if (sc[m][q] > bc) { bc = sc[m][q]; best = q; }
            se.state[m] = (byte) (bc >= 20 ? best : -1);
            se.rmssd[m] = Float.NaN; se.br[m] = Float.NaN;
        }
        // RMSSD in 5-minute windows
        int lo = 0;
        for (int b = 0; b + 5 <= n; b += 5) {
            long a = t0 + b * 60L, e = a + 300L;
            while (lo < rr.size() && rr.get(lo)[0] < a) lo++;
            int hi = lo;
            while (hi < rr.size() && rr.get(hi)[0] < e) hi++;
            double v = hi - lo > 100 ? rmssd(rr, lo, hi, 100) : Double.NaN;
            if (!Double.isNaN(v)) for (int q = b; q < b + 5; q++) se.rmssd[q] = (float) v;
        }
        // breathing rate in the clear 5-minute windows (the same estimator as the report)
        for (double[] w : breathing(rr, t0, t1)) {
            int b = (int) ((((long) w[0]) - t0) / 60L);
            for (int q = b; q < b + 5 && q < n; q++) if (q >= 0) se.br[q] = (float) w[1];
        }
        return se;
    }

    private static float medianF(float[] v, int c) {
        float[] t = java.util.Arrays.copyOf(v, c);
        java.util.Arrays.sort(t);
        return c % 2 == 1 ? t[c / 2] : 0.5f * (t[c / 2 - 1] + t[c / 2]);
    }

    /** Successive differences kept when under this share of the interval. 0.12 (was 0.20): on the night of 21-22 Oct 2026, against an
     *  H10 chest strap in 97 five-minute windows, the strap's optical intervals gave RMSSD +26.6 ms too high with 0.20 and +7.2 ms
     *  with 0.12 (correlation 0.69 either way). The value is still an optical estimate that reads high; see TodayModel. */
    static final double RMSSD_KEEP_SHARE = 0.12;

    /** RMSSD over rr[from..to): successive differences kept when under [RMSSD_KEEP_SHARE] of the interval and under 2.5 s apart. */
    static double rmssd(List<double[]> rr, int from, int to, int minDiffs) {
        double s = 0; int c = 0;
        for (int q = from + 1; q < to; q++) {
            double d = rr.get(q)[1] - rr.get(q - 1)[1];
            if (Math.abs(d) < RMSSD_KEEP_SHARE * rr.get(q)[1] && rr.get(q)[0] - rr.get(q - 1)[0] < 2.5) { s += d * d; c++; }
        }
        return c > minDiffs ? Math.sqrt(s / c) : Double.NaN;
    }
}

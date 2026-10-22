package com.noop.mgecg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;

/**
 * What the Today screen shows, computed from the per-second records (R18). Pure Java so it can be tested off the phone.
 *
 * Everything here is an ESTIMATE built from the strap's own records and is labelled that way on screen:
 *  - sleep: the band's own sleep state (SleepNight), against a goal in hours;
 *  - recovery: tonight's resting heart rate and overnight RMSSD against YOUR previous nights (needs at least
 *    {@link #MIN_BASELINE} of them; until then it is not shown). RMSSD from the strap's optical intervals reads
 *    about 1.5 times an ECG strap's value on the validation night, which a within-wearer baseline absorbs;
 *  - effort: heart-rate zone minutes as a percentage of an assumed maximum heart rate, weighted 1-5 (the Edwards
 *    TRIMP idea) and squashed onto 0-21 with 21 * (1 - exp(-TRIMP / 150)). This is NOT WHOOP's strain.
 * Research only, not a medical record.
 */
final class TodayModel {
    private TodayModel() {}

    static final int MIN_BASELINE = 4, MAX_BASELINE = 14;
    static final double TRIMP_SCALE = 150.0;

    /** Per-night numbers kept so the recovery baseline does not have to re-read old nights. */
    static final class NightStat {
        long bed;
        double tstH = Double.NaN, hrLow5 = Double.NaN, rmssd = Double.NaN, skin = Double.NaN, br = Double.NaN;
        int spo2Median = -1;
    }

    static final class Day {
        SleepNight.Night night;                 // latest complete night, or null
        Spo2.Summary spo2;                      // that night's SpO2 windows, or null
        double sleepGoalH = 8.0, sleepPct = Double.NaN;
        double restingHr = Double.NaN;          // lowest 5-minute median heart rate in the last 24 h
        long[] hrT = new long[0];               // 5-minute medians over the last 24 h
        float[] hrV = new float[0];
        int[] zoneMin = new int[6];             // minutes in zones 0 (<50 %), 1 (50-60 %) ... 5 (90 % and over)
        double trimp, effort;
        int steps, walkMin, runMin;
        long dayStart;                          // effort and steps count from here
        double loggedH;
        long newestTs, oldestTs;
        double recovery = Double.NaN;           // 1-99, or NaN while the baseline is too short
        int baselineNights;
        double zRhr = Double.NaN, zHrv = Double.NaN;
        int maxHr = 167;
    }

    /** Local 04:00 on or before [nowSec]. */
    static long dayStartOf(long nowSec) {
        GregorianCalendar c = new GregorianCalendar();
        c.setTimeInMillis(nowSec * 1000L);
        c.set(GregorianCalendar.MILLISECOND, 0);
        c.set(GregorianCalendar.SECOND, 0);
        c.set(GregorianCalendar.MINUTE, 0);
        c.set(GregorianCalendar.HOUR_OF_DAY, 4);
        long s = c.getTimeInMillis() / 1000L;
        if (s > nowSec) s -= 86400L;
        return s;
    }

    static int zoneOf(double hr, int maxHr) {
        double p = hr / maxHr;
        if (p >= 0.90) return 5;
        if (p >= 0.80) return 4;
        if (p >= 0.70) return 3;
        if (p >= 0.60) return 2;
        if (p >= 0.50) return 1;
        return 0;
    }

    static double effortOf(double trimp) { return 21.0 * (1.0 - Math.exp(-Math.max(0, trimp) / TRIMP_SCALE)); }

    static double median(double[] v, int n) {
        double[] t = Arrays.copyOf(v, n);
        Arrays.sort(t);
        return n % 2 == 1 ? t[n / 2] : 0.5 * (t[n / 2 - 1] + t[n / 2]);
    }

    /** Standard normal CDF (Abramowitz-Stegun 26.2.17), good to about 1e-7. */
    static double phi(double z) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double d = 0.3989423 * Math.exp(-z * z / 2.0);
        double p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
        return z > 0 ? 1.0 - p : p;
    }

    /**
     * Recovery estimate for a night against earlier nights. Returns {score 1-99, zRhr, zHrv, n} or null when
     * there are fewer than {@link #MIN_BASELINE} earlier nights with both numbers.
     */
    static double[] recovery(NightStat now, List<NightStat> earlier) {
        if (now == null || Double.isNaN(now.hrLow5) || Double.isNaN(now.rmssd) || now.rmssd <= 0) return null;
        List<NightStat> use = new ArrayList<>();
        for (NightStat s : earlier) {
            if (s.bed >= now.bed) continue;
            if (Double.isNaN(s.hrLow5) || Double.isNaN(s.rmssd) || s.rmssd <= 0) continue;
            use.add(s);
        }
        Collections.sort(use, new Comparator<NightStat>() {
            @Override public int compare(NightStat a, NightStat b) { return Long.compare(b.bed, a.bed); }
        });
        if (use.size() > MAX_BASELINE) use = use.subList(0, MAX_BASELINE);
        int n = use.size();
        if (n < MIN_BASELINE) return null;
        double mr = 0, ml = 0;
        for (NightStat s : use) { mr += s.hrLow5; ml += Math.log(s.rmssd); }
        mr /= n; ml /= n;
        double vr = 0, vl = 0;
        for (NightStat s : use) { vr += (s.hrLow5 - mr) * (s.hrLow5 - mr); double d = Math.log(s.rmssd) - ml; vl += d * d; }
        double sr = Math.max(2.0, Math.sqrt(vr / Math.max(1, n - 1)));        // floors keep a few similar nights from
        double sl = Math.max(0.15, Math.sqrt(vl / Math.max(1, n - 1)));       // making every small change look large
        double zr = (mr - now.hrLow5) / sr;                                   // a LOWER resting rate is better
        double zh = (Math.log(now.rmssd) - ml) / sl;                          // a HIGHER RMSSD is better
        double z = 0.5 * zr + 0.5 * zh;
        double score = Math.min(99.0, Math.max(1.0, 100.0 * phi(z)));
        return new double[] {score, zr, zh, n};
    }

    static NightStat statOf(SleepNight.Night n, Spo2.Summary sp) {
        NightStat s = new NightStat();
        s.bed = n.bedTs;
        s.tstH = n.tstH; s.hrLow5 = n.hrLow5; s.rmssd = n.rmssd; s.skin = n.skinMedian; s.br = n.brMedian;
        s.spo2Median = sp == null ? -1 : sp.median;
        return s;
    }

    /**
     * Today's numbers. [recs] are R18 records covering at least the last 24 h plus the latest night (any order, duplicates
     * ignored); [earlier] are stats of previous nights for the recovery baseline.
     */
    static Day compute(List<byte[]> recs, List<NightStat> earlier, long nowSec, int maxHr, double sleepGoalH) {
        Day d = new Day();
        d.maxHr = maxHr;
        d.sleepGoalH = sleepGoalH;
        List<byte[]> r = new ArrayList<>();
        for (byte[] f : recs) if (R18Codec.isR18(f)) r.add(f);
        Collections.sort(r, new Comparator<byte[]>() {
            @Override public int compare(byte[] a, byte[] b) { return Long.compare(R18Codec.ts(a), R18Codec.ts(b)); }
        });
        // drop duplicate seconds
        List<byte[]> u = new ArrayList<>(r.size());
        long last = -1;
        for (byte[] f : r) { long t = R18Codec.ts(f); if (t != last) { u.add(f); last = t; } }
        if (u.isEmpty()) return d;
        d.oldestTs = R18Codec.ts(u.get(0));
        d.newestTs = R18Codec.ts(u.get(u.size() - 1));

        // latest complete night
        int ns = 0;
        long[] st = new long[u.size()];
        for (byte[] f : u) if (R18Codec.state(f) == 2) st[ns++] = R18Codec.ts(f);
        List<long[]> cl = SleepNight.clusters(Arrays.copyOf(st, ns));
        if (!cl.isEmpty()) {
            long[] cc = cl.get(cl.size() - 1);
            List<byte[]> nr = new ArrayList<>();
            for (byte[] f : u) { long t = R18Codec.ts(f); if (t >= cc[0] - 7200L && t <= cc[1] + 600L) nr.add(f); }
            d.night = SleepNight.analyse(nr, d.oldestTs, d.newestTs);
            if (d.night != null) {
                d.spo2 = Spo2.summarize(Spo2.windows(nr));
                d.sleepPct = 100.0 * d.night.tstH / sleepGoalH;
                NightStat now = statOf(d.night, d.spo2);
                double[] rec = recovery(now, earlier);
                if (rec != null) { d.recovery = rec[0]; d.zRhr = rec[1]; d.zHrv = rec[2]; d.baselineNights = (int) rec[3]; }
                else {
                    int c = 0;
                    for (NightStat s : earlier) if (s.bed < now.bed && !Double.isNaN(s.hrLow5) && !Double.isNaN(s.rmssd)) c++;
                    d.baselineNights = c;
                }
            }
        }

        // last 24 h: 5-minute median heart rate, resting heart rate, logged hours
        long from24 = d.newestTs - 86400L;
        int nBins = 288;
        double[][] bins = new double[nBins][];
        int[] cnt = new int[nBins];
        for (int i = 0; i < nBins; i++) bins[i] = new double[300];
        int logged = 0;
        for (byte[] f : u) {
            long t = R18Codec.ts(f);
            if (t < from24) continue;
            logged++;
            int h = R18Codec.hr(f);
            if (h <= 30) continue;
            int b = (int) ((t - from24) / 300L);
            if (b >= 0 && b < nBins && cnt[b] < 300) bins[b][cnt[b]++] = h;
        }
        d.loggedH = logged / 3600.0;
        List<Long> ht = new ArrayList<>();
        List<Float> hv = new ArrayList<>();
        double rest = Double.NaN;
        for (int i = 0; i < nBins; i++) {
            if (cnt[i] < 120) continue;
            double m = median(bins[i], cnt[i]);
            ht.add(from24 + i * 300L + 150L);
            hv.add((float) m);
            if (Double.isNaN(rest) || m < rest) rest = m;
        }
        d.restingHr = rest;
        d.hrT = new long[ht.size()];
        d.hrV = new float[hv.size()];
        for (int i = 0; i < d.hrT.length; i++) { d.hrT[i] = ht.get(i); d.hrV[i] = hv.get(i); }

        // today: from the later of wake-up and local 04:00
        long ds = dayStartOf(d.newestTs);
        if (d.night != null && d.night.endTs > ds && d.night.endTs < d.newestTs) ds = d.night.endTs;
        d.dayStart = ds;
        int minutes = (int) Math.max(0, (d.newestTs - ds) / 60L);
        double[][] mb = new double[Math.max(1, minutes + 1)][];
        int[] mc = new int[mb.length];
        for (int i = 0; i < mb.length; i++) mb[i] = new double[60];
        int prevSteps = -1;
        long walk = 0, run = 0;
        for (byte[] f : u) {
            long t = R18Codec.ts(f);
            if (t < ds) { prevSteps = R18Codec.steps(f); continue; }
            int h = R18Codec.hr(f);
            int m = (int) ((t - ds) / 60L);
            if (h > 30 && m >= 0 && m < mb.length && mc[m] < 60) mb[m][mc[m]++] = h;
            int sc = R18Codec.steps(f);
            if (prevSteps >= 0) { int dl = sc - prevSteps; if (dl < 0) dl += 65536; if (dl >= 0 && dl <= 2000) d.steps += dl; }
            prevSteps = sc;
            int act = R18Codec.u8(f, 63);
            if (act == 1) walk++; else if (act == 2) run++;
        }
        d.walkMin = (int) (walk / 60L); d.runMin = (int) (run / 60L);
        for (int i = 0; i < mb.length; i++) {
            if (mc[i] < 20) continue;
            int z = zoneOf(median(mb[i], mc[i]), maxHr);
            d.zoneMin[z]++;
            d.trimp += z;               // zone 0 weighs 0, zones 1-5 weigh 1-5 per minute
        }
        d.effort = effortOf(d.trimp);
        return d;
    }
}

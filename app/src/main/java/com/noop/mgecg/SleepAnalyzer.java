package com.noop.mgecg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sleep onset / wake detection from R18 records (1 per second: heart rate,
 * accelerometer). Pure Java (no Android) so it can be tested off-device.
 *
 * Rules validated 27 Sep 2026 on two nights against the wearer's reported
 * times (bed 23:30 / 00:30, up 05:45 / 07:40): detected 23:32 -> 05:45 and
 * 00:15 -> 07:39.
 *  - movement per minute = mean |delta| of accelerometer magnitude (g);
 *    still < 0.002 g, strong > 0.01 g; a minute with no data counts as still
 *  - evening rest HR = median HR of still minutes 20:00-23:00 local
 *  - onset = first minute where >= 18 of the next 25 are still AND the
 *    21-min median HR <= evening rest HR - 3 (movement alone mistakes still
 *    evenings for sleep)
 *  - final wake = first minute >= onset + 90 with strong movement in >= 5 of
 *    8 minutes and no return to a >= 30-min >=90%-still stretch within the
 *    next 90 min (HR is not used: it drifts up toward morning; single-minute
 *    twitches 0.002-0.01 g are restless sleep, not waking)
 *  - wake-ups = runs of strong movement (>= 2 of 3 minutes) inside the period
 *  - lowest-HR still stretches: HR in the night's lowest quartile, still,
 *    >= 10 consecutive minutes. A pattern, not sleep staging.
 * Revised 28 Sep 2026 after a third night (bed 22:00, awake 02:30 for ~30 min,
 * up 05:45; the strap went on at 21:51, so there was no awake evening):
 *  - coverage is judged up to the end of the data (a 06:00 pull can never
 *    cover until noon), needing >= 300 covered minutes and >= 70% of the span
 *  - fallback onset: if the evening rule finds no onset, the reference is the
 *    median HR of the first 10 still minutes of the rest period (first point
 *    with >= 18 of 25 still) and the drop needed is 2 bpm; flagged lower
 *    confidence
 *  - a final wake within 98 min of the end of the data is accepted if at
 *    least 10 min of data follow it, and marked "at end of data"
 *  - wake-ups <= 15 min apart are merged when the median HR between them is
 *    >= the night's sleeping median + 5 bpm (awake but lying still)
 * Three nights, detected vs reported: 23:32/23:30 -> 05:45/05:45,
 * 00:15/00:30 -> 07:39/07:40, 21:56/22:00 -> 05:45/05:45; the 02:30 episode
 * -> one 23-min wake-up (02:26).
 * Limits: three nights, self-reported reference times; not validated against
 * polysomnography.
 */
public final class SleepAnalyzer {

    public static final int WINDOW_MIN = 960;          // 20:00 -> 12:00 local
    public static final double STILL_G = 0.002;
    public static final double STRONG_G = 0.01;

    private SleepAnalyzer() {}

    public static final class Result {
        public boolean ok;
        public String reason = "";
        public long windowStart;
        public int coveredMinutes;
        public double eveningHr = Double.NaN;
        public int onsetMin = -1, wakeMin = -1;
        public int periodMin, asleepMin, wakeUpMin, twitchMin;
        public List<int[]> wakeUps = new ArrayList<>();
        public double sleepHrMedian = Double.NaN, sleepHrLowest = Double.NaN;
        public int sleepHrLowestMin = -1;
        public List<int[]> lowHrStretches = new ArrayList<>();
        public int lowHrStretchMin;
        public boolean coverageOk, onsetLowConfidence, wakeAtEndOfData;
        public int dataEndMin;
        public TimeZone tz;

        public String clock(int minute) {
            Calendar c = Calendar.getInstance(tz, Locale.UK);
            c.setTimeInMillis((windowStart + 60L * minute) * 1000L);
            return String.format(Locale.UK, "%02d:%02d",
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        }

        public String night() {
            Calendar a = Calendar.getInstance(tz, Locale.UK);
            a.setTimeInMillis(windowStart * 1000L);
            Calendar b = Calendar.getInstance(tz, Locale.UK);
            b.setTimeInMillis((windowStart + 86400L) * 1000L);
            return String.format(Locale.UK, "%d->%d %s", a.get(Calendar.DAY_OF_MONTH),
                    b.get(Calendar.DAY_OF_MONTH),
                    b.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.UK));
        }

        public String toLog() {
            if (!ok) return "SLEEP_ANALYSIS none reason=" + reason;
            return String.format(Locale.US,
                    "SLEEP_ANALYSIS night=%s onset=%s wake=%s periodMin=%d asleepMin=%d " +
                            "wakeUps=%d wakeUpMin=%d twitchMin=%d hrMedian=%.0f hrLowest=%.0f@%s " +
                            "lowHrStretchMin=%d eveningHr=%.0f coveredMin=%d onsetLowConf=%b wakeAtEnd=%b",
                    night(), clock(onsetMin), clock(wakeMin), periodMin, asleepMin,
                    wakeUps.size(), wakeUpMin, twitchMin, sleepHrMedian, sleepHrLowest,
                    clock(sleepHrLowestMin), lowHrStretchMin, eveningHr, coveredMinutes,
                    onsetLowConfidence, wakeAtEndOfData);
        }

        public String toHtml() {
            if (!ok) return "<b>No sleep result</b><br><small>" + reason + "</small>";
            StringBuilder w = new StringBuilder();
            for (int[] b : wakeUps) {
                if (w.length() > 0) w.append(", ");
                w.append(clock(b[0])).append(" (").append(b[1] - b[0]).append(" min)");
            }
            StringBuilder d = new StringBuilder();
            for (int[] s : lowHrStretches) {
                if (d.length() > 0) d.append(", ");
                d.append(clock(s[0])).append("&ndash;").append(clock(s[1]));
            }
            return String.format(Locale.UK,
                    "<b>Night %s</b><br><br>" +
                            "<big><b>%s &rarr; %s</b></big>%s<br>" +
                            "Asleep about <b>%dh %02dm</b> of %dh %02dm (%d%%)<br><br>" +
                            "Wake-ups: %d, %d min total%s<br>" +
                            "Restless minutes (twitches, not waking): %d<br><br>" +
                            "Sleeping heart rate: median %.0f, lowest %.0f at %s<br>" +
                            "%s: %.0f<br><br>" +
                            "Lowest-heart-rate, stillest stretches: %s (%d min)<br>" +
                            "<small>These usually fall where deep sleep concentrates " +
                            "&mdash; a pattern, not measured sleep stages.</small><br><br>" +
                            "<small>Method validated on 2 nights against your own times " +
                            "(errors 0&ndash;17 min). Not a medical sleep test.</small>",
                    night(), clock(onsetMin), clock(wakeMin),
                    (onsetLowConfidence ? "<br><small>Sleep onset lower confidence: no awake " +
                            "evening in the data, heart-rate drop was small.</small>" : "") +
                            (wakeAtEndOfData ? "<br><small>Wake-up close to the end of the " +
                                    "data (pulled soon after).</small>" : ""),
                    asleepMin / 60, asleepMin % 60, periodMin / 60, periodMin % 60,
                    Math.round(100.0 * asleepMin / Math.max(1, periodMin)),
                    wakeUps.size(), wakeUpMin, wakeUps.isEmpty() ? "" : ": " + w,
                    twitchMin, sleepHrMedian, sleepHrLowest, clock(sleepHrLowestMin),
                    onsetLowConfidence ? "Resting heart rate at the start of the night"
                            : "Evening resting heart rate",
                    eveningHr, d.length() == 0 ? "none" : d.toString(), lowHrStretchMin);
        }
    }

    /** Latest night (20:00-12:00 local) with >= 70% minute coverage. */
    public static Result analyzeLatestNight(long[] unix, int[] hr, float[] mag, int n, TimeZone tz) {
        Result none = new Result();
        none.tz = tz;
        if (n < 600) {
            none.reason = "Not enough history in this pull (" + n + " seconds).";
            return none;
        }
        long latest = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) latest = Math.max(latest, unix[i]);
        Calendar c = Calendar.getInstance(tz, Locale.UK);
        c.setTimeInMillis(latest * 1000L);
        c.set(Calendar.HOUR_OF_DAY, 20);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int back = 0; back < 4; back++) {
            long start = c.getTimeInMillis() / 1000L;
            Result r = analyzeNightStarting(start, unix, hr, mag, n, tz);
            if (r.coverageOk) return r;
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        none.reason = "No night in this pull had enough data (at least 5 hours, " +
                "70% of 20:00 to the time of the pull) - wear the strap overnight " +
                "and pull next morning.";
        return none;
    }

    public static Result analyzeNightStarting(long start, long[] unix, int[] hr, float[] mag,
                                              int n, TimeZone tz) {
        Result r = new Result();
        r.tz = tz;
        r.windowStart = start;
        int N = WINDOW_MIN;

        // gather samples per minute, in time order
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(unix[a], unix[b]));
        double[] hrMin = new double[N];
        double[] mov = new double[N];
        Arrays.fill(hrMin, Double.NaN);
        int[] cnt = new int[N];
        List<List<Integer>> perMin = new ArrayList<>();
        for (int k = 0; k < N; k++) perMin.add(new ArrayList<>());
        for (Integer i : order) {
            long m = (unix[i] - start) / 60;
            if (unix[i] >= start && m < N) perMin.get((int) m).add(i);
        }
        int firstMin = -1;
        for (int k = 0; k < N; k++) {
            List<Integer> s = perMin.get(k);
            cnt[k] = s.size();
            if (cnt[k] < 20) continue;
            List<Double> h = new ArrayList<>();
            double sum = 0;
            for (int j = 0; j < s.size(); j++) {
                if (hr[s.get(j)] > 0) h.add((double) hr[s.get(j)]);
                if (j > 0) sum += Math.abs(mag[s.get(j)] - mag[s.get(j - 1)]);
            }
            hrMin[k] = median(h);
            mov[k] = sum / (s.size() - 1);
            r.coveredMinutes++;
            if (firstMin < 0) firstMin = k;
        }
        // coverage judged up to the end of the data, not the end of the window
        int Nend = 0;
        for (int k = 0; k < N; k++) if (!perMin.get(k).isEmpty()) Nend = k + 1;
        r.dataEndMin = Nend;
        r.coverageOk = firstMin >= 0 && r.coveredMinutes >= 300 &&
                r.coveredMinutes >= 0.7 * (Nend - firstMin);
        if (!r.coverageOk) {
            r.reason = "Only " + r.coveredMinutes + " minutes covered.";
            return r;
        }

        boolean[] still = new boolean[Nend], strong = new boolean[Nend];
        for (int k = 0; k < Nend; k++) {        // no data -> movement 0 -> still
            still[k] = mov[k] < STILL_G;
            strong[k] = mov[k] > STRONG_G;
        }
        double[] hrN = Arrays.copyOf(hrMin, Nend);
        double[] hr21 = rollingNanMedian(hrN, 21);

        List<Double> ev = new ArrayList<>();
        for (int k = 0; k < Math.min(180, Nend); k++)
            if (still[k] && !Double.isNaN(hrN[k])) ev.add(hrN[k]);
        if (ev.size() >= 20) {
            r.eveningHr = median(ev);
            r.onsetMin = firstQualifyingWindow(still, hr21, r.eveningHr - 3, 0, Nend);
        }
        if (r.onsetMin < 0) {
            // fallback: no usable awake evening - compare with the start of the rest period
            int rest = -1;
            for (int k = 0; k < Nend; k++) {
                int st = 0;
                for (int j = k; j < Math.min(Nend, k + 25); j++) if (still[j]) st++;
                if (st >= 18) { rest = k; break; }
            }
            if (rest < 0) { r.reason = "No rest period found."; return r; }
            List<Double> ref = new ArrayList<>();
            for (int k = rest; k < Nend && ref.size() < 10; k++)
                if (still[k] && !Double.isNaN(hrN[k])) ref.add(hrN[k]);
            if (ref.size() < 10) {
                r.reason = "Not enough still data to set a resting heart rate.";
                return r;
            }
            r.eveningHr = median(ref);
            r.onsetLowConfidence = true;
            r.onsetMin = firstQualifyingWindow(still, hr21, r.eveningHr - 2, rest, Nend);
        }
        if (r.onsetMin < 0) {
            r.reason = "No sleep onset found (still + heart rate below resting level).";
            return r;
        }

        for (int k = r.onsetMin + 90; k < Nend - 10; k++) {
            if (!strong[k]) continue;
            int s = 0;
            for (int j = k; j < Math.min(Nend, k + 8); j++) if (strong[j]) s++;
            if (s < 5) continue;
            boolean backToSleep = false;
            for (int j = 0; j < 60 && !backToSleep; j++) {
                int a = k + 8 + j;
                if (a + 30 > Nend) break;          // only full 30-min windows inside the data
                int st = 0;
                for (int q = a; q < a + 30; q++) if (still[q]) st++;
                if (st >= 0.9 * 30) backToSleep = true;
            }
            if (!backToSleep) {
                r.wakeMin = k;
                r.wakeAtEndOfData = k + 98 > Nend;
                break;
            }
        }
        if (r.wakeMin < 0) {
            r.reason = "Sleep onset found at " + r.clock(r.onsetMin) +
                    " but no final wake-up before the end of the data.";
            return r;
        }

        List<int[]> bouts = new ArrayList<>();
        int k = r.onsetMin;
        while (k < r.wakeMin) {
            int s3 = 0;
            for (int j = k; j < Math.min(Nend, k + 3); j++) if (strong[j]) s3++;
            if (strong[k] && s3 >= 2) {
                int j = k;
                while (j < r.wakeMin && (strong[j] || (j + 1 < Nend && strong[j + 1]))) j++;
                bouts.add(new int[]{k, j});
                k = j;
            } else k++;
        }
        List<Double> sleepHr = new ArrayList<>();
        for (int q = r.onsetMin; q < r.wakeMin; q++) if (!Double.isNaN(hrN[q])) sleepHr.add(hrN[q]);
        double hmed = median(sleepHr);
        for (int[] b : bouts) {             // merge: awake but lying still between two bursts
            if (!r.wakeUps.isEmpty()) {
                int[] prev = r.wakeUps.get(r.wakeUps.size() - 1);
                List<Double> gap = new ArrayList<>();
                for (int q = prev[1]; q < b[0]; q++) if (!Double.isNaN(hrN[q])) gap.add(hrN[q]);
                if (b[0] - prev[1] <= 15 && median(gap) >= hmed + 5) { prev[1] = b[1]; continue; }
            }
            r.wakeUps.add(new int[]{b[0], b[1]});
        }
        for (int[] b : r.wakeUps) r.wakeUpMin += b[1] - b[0];
        r.periodMin = r.wakeMin - r.onsetMin;
        r.asleepMin = r.periodMin - r.wakeUpMin;
        for (int q = r.onsetMin; q < r.wakeMin; q++)
            if (mov[q] > STILL_G && mov[q] <= STRONG_G) r.twitchMin++;

        double[] hs = Arrays.copyOfRange(hrN, r.onsetMin, r.wakeMin);
        List<Double> hv = new ArrayList<>();
        for (double v : hs) if (!Double.isNaN(v)) hv.add(v);
        r.sleepHrMedian = median(hv);
        double[] r15 = rollingNanMedian(hs, 15);
        double lo = Double.POSITIVE_INFINITY;
        for (int q = 0; q < r15.length; q++)
            if (!Double.isNaN(r15[q]) && r15[q] < lo) { lo = r15[q]; r.sleepHrLowestMin = r.onsetMin + q; }
        r.sleepHrLowest = lo;
        double q25 = percentile(hv, 25);
        int q = 0;
        while (q < hs.length) {
            if (!Double.isNaN(hs[q]) && hs[q] <= q25 && still[r.onsetMin + q]) {
                int j = q;
                while (j < hs.length && !Double.isNaN(hs[j]) && hs[j] <= q25 && still[r.onsetMin + j]) j++;
                if (j - q >= 10) {
                    r.lowHrStretches.add(new int[]{r.onsetMin + q, r.onsetMin + j});
                    r.lowHrStretchMin += j - q;
                }
                q = j;
            } else q++;
        }
        r.ok = true;
        return r;
    }

    /** first k in [from, to) where >= 18 of the 25 minutes from k are still with HR21 <= limit */
    static int firstQualifyingWindow(boolean[] still, double[] hr21, double limit, int from, int to) {
        for (int k = from; k < to; k++) {
            int s = 0;
            for (int j = k; j < Math.min(to, k + 25); j++) if (still[j] && hr21[j] <= limit) s++;
            if (s >= 18) return k;
        }
        return -1;
    }

    static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2.0;
    }

    static double percentile(List<Double> v, double p) {   // numpy 'linear'
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        double pos = p / 100.0 * (a.length - 1);
        int f = (int) Math.floor(pos);
        int c = Math.min(f + 1, a.length - 1);
        return a[f] + (a[c] - a[f]) * (pos - f);
    }

    static double[] rollingNanMedian(double[] x, int w) {
        double[] out = new double[x.length];
        for (int k = 0; k < x.length; k++) {
            List<Double> s = new ArrayList<>();
            for (int j = Math.max(0, k - w / 2); j < Math.min(x.length, k + w / 2 + 1); j++)
                if (!Double.isNaN(x[j])) s.add(x[j]);
            out[k] = median(s);
        }
        return out;
    }
}

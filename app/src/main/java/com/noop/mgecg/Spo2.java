package com.noop.mgecg;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The strap's sleep-only SpO2 measurement windows, read from the per-second records (R18). Pure Java.
 *
 * While the wearer sleeps the strap runs a measurement of about 30 s roughly every 20 minutes. During it the
 * AGC words 2 and 3 leave their constants ({@link R18Codec#spo2Window}) and byte 82 carries a percentage-like
 * value (93-100 on this strap), with a few other small codes (2, 8, 16, 32, 128-160) that are flags, not
 * percentages. On the night of 21-22 Oct 2026 there were 19 windows from 22:46 to 05:32 with window medians of
 * 94-99 (median 96). No calibration against a reference oximeter has been done, so these are the strap's own
 * estimates: a trend and an alarm-free overview, not a measurement to act on. Research only.
 */
final class Spo2 {
    private Spo2() {}

    /** One measurement window. [median]/[min]/[max] are over the percentage-like values (70-100) inside it. */
    static final class Window {
        long start, end;       // unix seconds of the first and last second inside the window
        int seconds;           // seconds in the window
        int valid;             // seconds carrying a 70-100 value
        int median = -1, min = -1, max = -1;
        int flagSeconds;       // seconds whose byte 82 was a status code rather than a percentage
    }

    static final class Summary {
        int windows, validWindows, median = -1, min = -1, max = -1, below94, below90;
        long first, last;
    }

    static final int LO = 70, HI = 100, MAX_GAP_S = 3, MIN_VALID_SECONDS = 8;

    /** Windows from R18 records in any order. Records that are not R18 are ignored. */
    static List<Window> windows(List<byte[]> recs) {
        List<byte[]> r = new ArrayList<>();
        for (byte[] f : recs) if (R18Codec.isR18(f)) r.add(f);
        Collections.sort(r, new Comparator<byte[]>() {
            @Override public int compare(byte[] a, byte[] b) { return Long.compare(R18Codec.ts(a), R18Codec.ts(b)); }
        });
        List<Window> out = new ArrayList<>();
        Window cur = null;
        int[] vals = new int[600];
        int nv = 0;
        long lastTs = 0;
        for (byte[] f : r) {
            if (!R18Codec.spo2Window(f)) continue;
            long t = R18Codec.ts(f);
            if (cur != null && t - lastTs > MAX_GAP_S) { close(cur, vals, nv, out); cur = null; nv = 0; }
            if (cur == null) { cur = new Window(); cur.start = t; }
            cur.end = t; cur.seconds++;
            int b = R18Codec.u8(f, 82);
            if (b >= LO && b <= HI) { if (nv < vals.length) vals[nv++] = b; cur.valid++; }
            else if (b != 0) cur.flagSeconds++;
            lastTs = t;
        }
        if (cur != null) close(cur, vals, nv, out);
        return out;
    }

    private static void close(Window w, int[] vals, int nv, List<Window> out) {
        if (nv > 0) {
            int[] v = Arrays.copyOf(vals, nv);
            Arrays.sort(v);
            w.min = v[0]; w.max = v[nv - 1];
            w.median = nv % 2 == 1 ? v[nv / 2] : (int) Math.round(0.5 * (v[nv / 2 - 1] + v[nv / 2]));
        }
        out.add(w);
    }

    /** Summary over windows that have at least {@link #MIN_VALID_SECONDS} valid seconds. */
    static Summary summarize(List<Window> ws) {
        Summary s = new Summary();
        s.windows = ws.size();
        List<Integer> med = new ArrayList<>();
        for (Window w : ws) {
            if (w.valid < MIN_VALID_SECONDS || w.median < 0) continue;
            s.validWindows++;
            med.add(w.median);
            if (s.first == 0 || w.start < s.first) s.first = w.start;
            if (w.end > s.last) s.last = w.end;
            if (w.median < 94) s.below94++;
            if (w.median < 90) s.below90++;
        }
        if (!med.isEmpty()) {
            Collections.sort(med);
            int n = med.size();
            s.median = n % 2 == 1 ? med.get(n / 2) : (int) Math.round(0.5 * (med.get(n / 2 - 1) + med.get(n / 2)));
            s.min = med.get(0); s.max = med.get(n - 1);
        }
        return s;
    }

    private static String hm(long unix) {
        return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(unix * 1000L));
    }

    /** One plain line for the summaries. */
    static String describe(Summary s) {
        if (s.validWindows == 0) return "SpO2 (strap estimate): no sleep measurement windows yet";
        return String.format(Locale.UK, "SpO2 (strap estimate, not calibrated): %d windows %s-%s, median %d%%, window range %d-%d%%, below 94%%: %d",
                s.validWindows, hm(s.first), hm(s.last), s.median, s.min, s.max, s.below94);
    }
}

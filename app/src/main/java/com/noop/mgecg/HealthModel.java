package com.noop.mgecg;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the heart-first screens show, gathered in one place. Pure computation lives in {@link #stagesFor} and
 * {@link #zonesFor}; {@link #load} is the thin database wrapper. Research only.
 */
final class HealthModel {
    private HealthModel() {}

    static final int WAKE = 0, LIGHT = 1, DEEP = 2, REM = 3;

    /** One ECG reading saved by the app, as listed on the ECG tab. */
    static final class Reading {
        File file;
        long timeMs;
        String when, hr = "-", hrv = "-", rhythm = "-", grade = "-";
    }

    static final class Data {
        TodayModel.Day day;
        int nowHr;                                   // latest per-second heart rate, 0 when none
        long nowTs;
        // sleep stages for the latest night (estimate)
        List<SleepStager.Segment> stages = new ArrayList<>();
        long[] stageSecs = new long[4];              // wake, light, deep, rem
        int[] binStage = new int[0];                 // one per 5-minute bin from bed, -1 when none
        boolean[] binSure = new boolean[0];          // true where the two staging variants agree
        long binStart;                               // unix seconds of bin 0
        // heart-rate time in zones over the last 24 h logged, seconds: resting, easy, steady, hard, all out
        long[] zoneSec = new long[5];
        long hr24Min = 0, hr24Max = 0;
        double hr24Mean = Double.NaN;
        // nights for the last-seven bars, oldest first, tst in hours or NaN
        double[] lastSeven = new double[7];
        long[] lastSevenBed = new long[7];
        List<TodayModel.NightStat> nights = new ArrayList<>();
        List<Reading> readings = new ArrayList<>();
        List<Spo2.Window> spo2Windows = new ArrayList<>();
    }

    // ------------------------------------------------------------------ pure parts
    private static List<SleepStager.Sec> secsFrom(List<byte[]> recs, boolean withRr) {
        List<SleepStager.Sec> out = new ArrayList<>(recs.size());
        long last = -1;
        for (byte[] f : recs) {
            if (!R18Codec.isR18(f)) continue;
            long t = R18Codec.ts(f);
            if (t == last) continue;
            last = t;
            SleepStager.Sec s = new SleepStager.Sec();
            s.ts = t;
            int h = R18Codec.hr(f);
            if (h > 30) s.hr = h;
            s.gx = R18Codec.f32(f, 45);
            s.gy = R18Codec.f32(f, 49);
            s.gz = R18Codec.f32(f, 53);
            if (Double.isNaN(s.gx) || Double.isInfinite(s.gx) || Double.isNaN(s.gy) || Double.isNaN(s.gz)) { s.gx = s.gy = s.gz = Double.NaN; }
            if (withRr) {
                int n = Math.min(4, R18Codec.u8(f, 23));
                double[] rr = new double[n];
                int k = 0;
                for (int i = 0; i < n; i++) {
                    double v = R18Codec.rr(f, i);                  // already milliseconds
                    if (v > 300 && v < 2000) rr[k++] = v;
                }
                s.rr = java.util.Arrays.copyOf(rr, k);
            }
            out.add(s);
        }
        return out;
    }

    private static int stageIndex(String s) {
        return "wake".equals(s) ? WAKE : "deep".equals(s) ? DEEP : "rem".equals(s) ? REM : LIGHT;
    }

    /**
     * Main labels come from the heart-rate-and-movement variant (no beat intervals). A second pass that adds the strap's optical
     * beat intervals is run only to mark where the two disagree; a bin is "sure" when at least 60% of its epochs agree.
     */
    static void stagesFor(List<byte[]> nightRecs, long bed, long wake, Data d) {
        if (wake - bed < 1800L) return;
        List<SleepStager.Segment> a = SleepStager.stage(bed, wake, secsFrom(nightRecs, false));
        List<SleepStager.Segment> b = SleepStager.stage(bed, wake, secsFrom(nightRecs, true));
        d.stages = a;
        for (SleepStager.Segment s : a) d.stageSecs[stageIndex(s.stage)] += s.end - s.start;
        int bins = (int) ((wake - bed + 299) / 300);
        d.binStart = bed;
        d.binStage = new int[bins];
        d.binSure = new boolean[bins];
        for (int i = 0; i < bins; i++) {
            long t0 = bed + i * 300L, t1 = Math.min(wake, t0 + 300L);
            int[] cnt = new int[4];
            int agree = 0, tot = 0;
            for (long t = t0 + 15; t < t1; t += 30) {
                int sa = labelAt(a, t), sb = labelAt(b, t);
                if (sa < 0) continue;
                cnt[sa]++;
                tot++;
                if (sa == sb) agree++;
            }
            int best = -1, bc = 0;
            for (int k = 0; k < 4; k++) if (cnt[k] > bc) { bc = cnt[k]; best = k; }
            d.binStage[i] = best;
            d.binSure[i] = tot > 0 && agree >= 0.6 * tot;
        }
    }

    private static int labelAt(List<SleepStager.Segment> segs, long t) {
        for (SleepStager.Segment s : segs) if (t >= s.start && t < s.end) return stageIndex(s.stage);
        return -1;
    }

    /** Zone edges in bpm for a maximum heart rate: round(max x 0.37, 0.575, 0.77, 0.91), i.e. 62, 96, 129, 152 at 167. */
    static int[] zoneEdges(int maxHr) {
        return new int[] {(int) Math.round(maxHr * 0.37), (int) Math.round(maxHr * 0.575), (int) Math.round(maxHr * 0.77), (int) Math.round(maxHr * 0.91)};
    }

    static int zoneOfHr(int hr, int maxHr) {
        int[] e = zoneEdges(maxHr);
        if (hr >= e[3]) return 4;
        if (hr >= e[2]) return 3;
        if (hr >= e[1]) return 2;
        if (hr >= e[0]) return 1;
        return 0;
    }

    // ------------------------------------------------------------------ database wrapper (call off the main thread)
    static Data load(Context c, int maxHr, double goalH) {
        Data d = new Data();
        d.day = DayLog.today(c, maxHr, goalH);
        try {
            SQLiteDatabase db = DayLog.db(c);
            long newest = 0;
            Cursor q = db.rawQuery("SELECT MAX(ts) FROM r18", null);
            if (q.moveToFirst()) newest = q.getLong(0);
            q.close();
            d.nowTs = newest;
            if (newest > 0) {
                q = db.rawQuery("SELECT hr FROM r18 WHERE ts = ?", new String[] {String.valueOf(newest)});
                if (q.moveToFirst()) d.nowHr = q.getInt(0);
                q.close();
                // zones, min, mean and max over the last 24 h
                q = db.rawQuery("SELECT hr FROM r18 WHERE ts >= ? AND hr > 30", new String[] {String.valueOf(newest - 86400L)});
                long sum = 0, cnt = 0, mn = Long.MAX_VALUE, mx = 0;
                while (q.moveToNext()) {
                    int h = q.getInt(0);
                    d.zoneSec[zoneOfHr(h, maxHr)]++;
                    sum += h; cnt++;
                    if (h < mn) mn = h;
                    if (h > mx) mx = h;
                }
                q.close();
                if (cnt > 0) { d.hr24Mean = sum / (double) cnt; d.hr24Min = mn; d.hr24Max = mx; }
            }
            // stages for the latest night
            if (d.day != null && d.day.night != null) {
                SleepNight.Night n = d.day.night;
                q = db.rawQuery("SELECT rec FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts",
                        new String[] {String.valueOf(n.bedTs - 600L), String.valueOf(n.endTs + 600L)});
                List<byte[]> recs = new ArrayList<>();
                while (q.moveToNext()) recs.add(q.getBlob(0));
                q.close();
                stagesFor(recs, n.bedTs, n.endTs, d);
                d.spo2Windows = Spo2.windows(recs);
            }
            // last seven nights
            d.nights = DayLog.nightStats(c);
            if (d.day != null && d.day.night != null) d.nights.add(TodayModel.statOf(d.day.night, d.day.spo2));
            java.util.Arrays.fill(d.lastSeven, Double.NaN);
            Collections.sort(d.nights, new Comparator<TodayModel.NightStat>() {
                @Override public int compare(TodayModel.NightStat a, TodayModel.NightStat b) { return Long.compare(a.bed, b.bed); }
            });
            if (!d.nights.isEmpty()) {
                long lastDay = dayIndex(d.nights.get(d.nights.size() - 1).bed);
                for (TodayModel.NightStat s : d.nights) {
                    int slot = 6 - (int) (lastDay - dayIndex(s.bed));
                    if (slot >= 0 && slot < 7) { d.lastSeven[slot] = s.tstH; d.lastSevenBed[slot] = s.bed; }
                }
                for (int i = 0; i < 7; i++) if (d.lastSevenBed[i] == 0) d.lastSevenBed[i] = d.nights.get(d.nights.size() - 1).bed - (6 - i) * 86400L;
            }
        } catch (Throwable ignored) { }
        d.readings = readings(c);
        return d;
    }

    private static long dayIndex(long unix) {
        return (unix + new java.util.GregorianCalendar().getTimeZone().getOffset(unix * 1000L) / 1000L - 12 * 3600L) / 86400L;
    }

    // ------------------------------------------------------------------ ECG readings saved by the app
    static List<Reading> readings(Context c) {
        List<Reading> out = new ArrayList<>();
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return out;
        File[] all = dir.listFiles();
        if (all == null) return out;
        for (File f : all) {
            String n = f.getName();
            if (!(n.startsWith("ecg_report_") && n.endsWith(".html"))) continue;
            Reading r = new Reading();
            r.file = f;
            long t = f.lastModified();
            try {
                long unix = Long.parseLong(n.substring("ecg_report_".length(), n.length() - ".html".length()));
                if (unix >= 1672531200L && unix <= 1924992000L) t = unix * 1000L;
            } catch (Exception ignored) { }
            r.timeMs = t;
            r.when = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(new Date(t));
            String head = readHead(f, 9000);
            String v = meta(head, "ecg-hr");
            if (v != null) r.hr = v;
            v = meta(head, "ecg-hrv");
            if (v != null) r.hrv = v;
            v = meta(head, "ecg-rhythm");
            if (v != null) r.rhythm = v;
            v = meta(head, "ecg-grade");
            if (v != null) r.grade = v;
            int tot = fragmentSeconds(head);                       // 0.15.2: the late tail of a reading ("0 of 3 seconds"): not a reading
            if (tot >= 0 && tot < 20) continue;
            out.add(r);
        }
        Collections.sort(out, new Comparator<Reading>() {
            @Override public int compare(Reading a, Reading b) { return Long.compare(b.timeMs, a.timeMs); }
        });
        return out;
    }

    /** Total seconds a report says it covers ("54 of 61 seconds", "for 40 of 62 seconds"), or -1 when the text is not there. Pure. */
    static int fragmentSeconds(String head) {
        Matcher m = Pattern.compile("(\\d+) of (\\d+) seconds").matcher(head == null ? "" : head);
        if (!m.find()) return -1;
        try { return Integer.parseInt(m.group(2)); } catch (Exception e) { return -1; }
    }

    private static String readHead(File f, int max) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[max];
                int n = in.read(b);
                return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String meta(String head, String name) {
        Matcher m = Pattern.compile("<meta\\s+name=\"" + Pattern.quote(name) + "\"\\s+content=\"([^\"]*)\"").matcher(head);
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------------ formatting helpers
    static String hm(double hours) {
        if (Double.isNaN(hours)) return "\u2014";
        int m = (int) Math.round(hours * 60.0);
        return (m / 60) + "h " + String.format(Locale.UK, "%02d", m % 60) + "m";
    }

    static String hmShort(long seconds) {
        int m = (int) Math.round(seconds / 60.0);
        return (m / 60) + ":" + String.format(Locale.UK, "%02d", m % 60);
    }
}

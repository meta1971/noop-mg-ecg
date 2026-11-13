package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. How much to trust each hour of a day, and each night's beat intervals.
// Per hour, from the day log: seconds of the strap's per-second record (r18), seconds the phone also heard live (live_hr, the strap's heart-rate notifications while connected), and seconds with the
// battery pack attached (R18 byte 10 bit 1, decode log 169/172).
//   LIVE     at least 80 % of the record's seconds were also heard live: the strap was in range
//   FILLED   at most 20 % were heard live: the strap was out of range (or the phone was not listening) and the record came from the strap's stored history afterwards
//   MIXED    in between
//   GAP      under 60 s of record in the hour: nothing stored yet (not pulled, strap off, or flat)
// The hour also carries PACK when half or more of its seconds had the pack attached. The times the history was pulled come from the 'ingest' events. OBSERVED data only: no clock time of the original
// recording is lost, because the record keeps the strap's own second stamps. Call off the main thread.

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TrustData {
    private TrustData() {}

    static final int GAP = 0, LIVE = 1, FILLED = 2, MIXED = 3;

    static final class Day {
        long dayStart;                                   // local 04:00, as the Today screen counts days
        final int[] cls = new int[24];                   // by hour since dayStart
        final boolean[] pack = new boolean[24];
        final int[] rec = new int[24], live = new int[24];
        int liveHours, filledHours, mixedHours, gapHours, packHours;
        boolean partial;                                 // the day is not over: hours after the newest record are not counted as gaps
        int lastHour = 23;
        /** ranges of consecutive hours of one class, as "02:00-05:00" */
        String ranges(int which) {
            StringBuilder b = new StringBuilder();
            int h = 0;
            while (h <= lastHour) {
                if (cls[h] != which) { h++; continue; }
                int e = h; while (e + 1 <= lastHour && cls[e + 1] == which) e++;
                if (b.length() > 0) b.append(", ");
                b.append(hh(dayStart, h)).append("-").append(hh(dayStart, e + 1));
                h = e + 1;
            }
            return b.toString();
        }
        String packRanges() {
            StringBuilder b = new StringBuilder();
            int h = 0;
            while (h <= lastHour) {
                if (!pack[h]) { h++; continue; }
                int e = h; while (e + 1 <= lastHour && pack[e + 1]) e++;
                if (b.length() > 0) b.append(", ");
                b.append(hh(dayStart, h)).append("-").append(hh(dayStart, e + 1));
                h = e + 1;
            }
            return b.toString();
        }
        String summary() {
            int tot = liveHours + filledHours + mixedHours + gapHours;
            return liveHours + " h in range, " + filledHours + " h filled from the strap's history" + (mixedHours > 0 ? ", " + mixedHours + " h mixed" : "") + (gapHours > 0 ? ", " + gapHours + " h with no record" : "")
                    + (packHours > 0 ? ", " + packHours + " h with the battery pack" : "") + (partial ? " (so far today, " + tot + " h)" : "");
        }
    }

    private static String hh(long dayStart, int h) { return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date((dayStart + h * 3600L) * 1000L)); }

    /** days: how many days back from today (1 = today only). Newest first. */
    static List<Day> days(Context c, int days) {
        List<Day> out = new ArrayList<Day>();
        SQLiteDatabase d = DayLog.db(c);
        long nowS = System.currentTimeMillis() / 1000L;
        long today = TodayModel.dayStartOf(nowS);
        long newest = newest(d);
        for (int k = 0; k < days; k++) out.add(forDay(d, ActivityStore.shiftDay(today, -k), nowS, newest));
        return out;
    }

    /** One day by its local 04:00 start (any day in the log). Call off the main thread. */
    static Day forDay(Context c, long dayStart) {
        SQLiteDatabase d = DayLog.db(c);
        return forDay(d, dayStart, System.currentTimeMillis() / 1000L, newest(d));
    }

    private static long newest(SQLiteDatabase d) {
        long newest = 0;
        Cursor q = d.rawQuery("SELECT MAX(ts) FROM r18", null);
        if (q.moveToFirst() && !q.isNull(0)) newest = q.getLong(0);
        q.close();
        return newest;
    }

    private static Day forDay(SQLiteDatabase d, long ds, long nowS, long newest) {
        long de = ActivityStore.shiftDay(ds, 1);
        Day x = new Day(); x.dayStart = ds;
        x.partial = nowS < de;
        int[] pk = new int[24];
        Cursor q = d.rawQuery("SELECT (ts-?)/3600, hex(substr(rec,11,1)), COUNT(*) FROM r18 WHERE ts >= ? AND ts < ? GROUP BY 1, 2", new String[] {String.valueOf(ds), String.valueOf(ds), String.valueOf(de)});
        while (q.moveToNext()) {
            int h = q.getInt(0); if (h < 0 || h > 23) continue;
            int n = q.getInt(2); x.rec[h] += n;
            try { if ((Integer.parseInt(q.getString(1), 16) & 0x02) != 0) pk[h] += n; } catch (Exception ignored) { }
        }
        q.close();
        q = d.rawQuery("SELECT (ms/1000-?)/3600, COUNT(DISTINCT ms/1000) FROM live_hr WHERE ms >= ? AND ms < ? GROUP BY 1", new String[] {String.valueOf(ds), String.valueOf(ds * 1000L), String.valueOf(de * 1000L)});
        while (q.moveToNext()) { int h = q.getInt(0); if (h >= 0 && h <= 23) x.live[h] = q.getInt(1); }
        q.close();
        int last = 23;
        if (x.partial) last = (int) Math.max(0, Math.min(23, (nowS - ds) / 3600L));
        x.lastHour = last;
        for (int h = 0; h <= last; h++) {
            int n = x.rec[h];
            if (n < 60) { x.cls[h] = GAP; boolean afterNewest = newest > 0 && ds + h * 3600L > newest && x.partial; if (!afterNewest) x.gapHours++; continue; }
            double f = x.live[h] / (double) n;
            x.cls[h] = f >= 0.80 ? LIVE : f <= 0.20 ? FILLED : MIXED;
            if (x.cls[h] == LIVE) x.liveHours++; else if (x.cls[h] == FILLED) x.filledHours++; else x.mixedHours++;
            if (pk[h] * 2 >= n) { x.pack[h] = true; x.packHours++; }
        }
        return x;
    }

    /** The newest history pulls: {ms, text}. */
    static List<String> ingests(Context c, int max) {
        List<String> out = new ArrayList<String>();
        try {
            Cursor q = DayLog.db(c).rawQuery("SELECT ms, text FROM events WHERE kind = 'ingest' ORDER BY ms DESC LIMIT " + max, null);
            SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK);
            while (q.moveToNext()) {
                String t = q.getString(1);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) new per-second records").matcher(t == null ? "" : t);
                out.add(f.format(new Date(q.getLong(0))) + "  ·  " + (m.find() ? String.format(Locale.UK, "%,d", Long.parseLong(m.group(1))) + " new seconds" : "history pulled"));
            }
            q.close();
        } catch (Throwable ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ per-night beat intervals
    private static final Map<Long, RrHygiene.Result> RR_CACHE = new HashMap<Long, RrHygiene.Result>();

    /** The beat-interval check of one night (strap sleep state between bed and end). Cached per bed time. Call off the main thread. */
    static synchronized RrHygiene.Result nightRr(Context c, long bedTs, long endTs) {
        RrHygiene.Result hit = RR_CACHE.get(bedTs);
        if (hit != null) return hit;
        RrHygiene.Result res = new RrHygiene.Result();
        try {
            Cursor q = DayLog.db(c).rawQuery("SELECT ts, hr, state, rec FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts", new String[] {String.valueOf(bedTs), String.valueOf(endTs)});
            int cap = Math.max(16, q.getCount());
            long[] ts = new long[cap]; int[] hr = new int[cap], cnt = new int[cap], sl = new int[cap * 4]; boolean[] use = new boolean[cap];
            int n = 0;
            while (q.moveToNext() && n < cap) {
                byte[] rec = q.isNull(3) ? null : q.getBlob(3);
                if (rec == null || !R18Codec.isR18(rec)) continue;
                ts[n] = q.getLong(0); hr[n] = q.isNull(1) ? 0 : q.getInt(1);
                int k = R18Codec.u8(rec, 23); cnt[n] = k > 4 ? 0 : k;
                for (int i = 0; i < 4; i++) sl[4 * n + i] = R18Codec.u16(rec, 24 + 2 * i);
                use[n] = !q.isNull(2) && q.getInt(2) == 2;
                n++;
            }
            q.close();
            res = RrHygiene.analyse(java.util.Arrays.copyOf(ts, n), java.util.Arrays.copyOf(hr, n), java.util.Arrays.copyOf(cnt, n), java.util.Arrays.copyOf(sl, 4 * n), java.util.Arrays.copyOf(use, n));
        } catch (Throwable ignored) { }
        RR_CACHE.put(bedTs, res);
        return res;
    }
}

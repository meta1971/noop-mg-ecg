package com.noop.mgecg;

// FILE VERSION 0.13.0 (26 Oct): every strap event kept in the day-log database, and the ECG end-of-reading events decoded as they arrive. Research only.

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Strap events (frame type 0x30) used to live only in the app's text log. This keeps them in daylog.db table strap_events (30 days) so the
 * Strap screen can show wrist on/off, charging, taps and so on at once, and decodes the two ECG end-of-reading events (118 and 119) into
 * their fields the moment they arrive. Layouts (decode log sections 21.1, 24.4, 25, 39):
 *   payload from frame byte 11: 00 | unix u32 | sub-second u16 (1/32768 s) | inner length u16 | inner bytes
 *   119 inner (40): 01 | N u32, n1 u32, n2 u32 | pct1 f32, pct2 f32 | q0 q1 q2 q3 f32 | 00 00 00
 *   118 inner (40): 02 | 00 64 1E 00 | A u32 | B u32 | C u16, D u16, E u16 | N u32, n1 u32, n2 u32 | pct1 f32, pct2 f32 | 02
 * VERIFIED: pct1 = 100 n1 / N, pct2 = 100 n2 / N; q0..q3 = the share of the 30 s window at live quality 0..3; E tracks the strap's own beat
 * amplitude (E = 1.326 x R-to-S peak-to-peak - 107, r = 0.994 over 16 sessions). A, B, C, D and n2 are only partly explained.
 */
final class StrapEvents {
    private StrapEvents() {}

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static boolean made = false;

    static String name(int id) {
        switch (id) {
            case 1: return "Error"; case 2: return "Console output"; case 3: return "Battery level"; case 4: return "System control";
            case 7: return "Charging on"; case 8: return "Charging off"; case 9: return "Wrist on"; case 10: return "Wrist off";
            case 11: return "Bluetooth up"; case 12: return "Bluetooth down"; case 13: return "Clock lost"; case 14: return "Double tap";
            case 15: return "Boot"; case 16: return "Clock set"; case 17: return "Temperature level"; case 23: return "Bonded";
            case 26: return "Trim data"; case 27: return "Trim ended"; case 28: return "Flash ready"; case 29: return "Condition report";
            case 30: return "Boot report"; case 32: return "Touch threshold"; case 33: return "Live heart rate on"; case 34: return "Live heart rate off";
            case 35: return "Accelerometer reset"; case 36: return "Optical reset"; case 40: return "Channel 1 saturated"; case 41: return "Channel 2 saturated";
            case 42: return "Accelerometer saturated"; case 43: return "Bluetooth reset"; case 44: return "Bluetooth on"; case 45: return "Bluetooth ready";
            case 46: return "Raw data on"; case 47: return "Raw data off"; case 60: return "Haptics fired"; case 63: return "Extended battery";
            case 96: return "Fast sync prompt"; case 97: return "Fast sync on"; case 98: return "Fast sync off"; case 108: return "Session parameter";
            case 109: return "Battery pack identity"; case 110: return "Ten-minute report"; case 113: return "ECG session start"; case 114: return "ECG session stop";
            case 115: return "ECG record start"; case 116: return "ECG record stop"; case 117: return "Charging state (inferred)"; case 118: return "ECG end result A";
            case 119: return "ECG end result B"; case 120: return "Two-hour report"; case 123: return "Firmware event"; case 124: return "Collection policy change";
            default: return "Event " + id;
        }
    }

    /** Events too frequent to keep (they stay in the text log). */
    static boolean skip(int id) { return id == 3 || id == 63 || id == 110 || id == 120 || id == 124; }          // 0.16.0: 29 (condition report, every 10 min) and 109 (battery pack) are now decoded and kept

    static int u8(byte[] b, int o) { return b[o] & 0xff; }
    static int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    static long u32(byte[] b, int o) { return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24); }
    static float f32(byte[] b, int o) { return Float.intBitsToFloat((int) u32(b, o)); }

    static byte[] inner(byte[] payload) {
        if (payload == null || payload.length < 9) return new byte[0];
        int n = Math.min(u16(payload, 7), payload.length - 9);
        byte[] in = new byte[Math.max(0, n)];
        System.arraycopy(payload, 9, in, 0, in.length);
        return in;
    }

    /** One line with the fields of event 118 or 119; empty for every other event. */
    static String decode(int id, byte[] payload) {
        byte[] in = inner(payload);
        Locale L = Locale.UK;
        if (id == 119 && in.length >= 37 && in[0] == 1)
            return String.format(L, "N=%d n1=%d n2=%d pct1=%.1f pct2=%.1f quality 0/1/2/3 = %.1f/%.1f/%.1f/%.1f %%",
                    u32(in, 1), u32(in, 5), u32(in, 9), f32(in, 13), f32(in, 17), f32(in, 21), f32(in, 25), f32(in, 29), f32(in, 33));
        if (id == 118 && in.length >= 39 && in[0] == 2)
            return String.format(L, "window %d s, progress %d; A=%d B=%d C=%d D=%d E=%d; N=%d n1=%d n2=%d pct1=%.1f pct2=%.1f",
                    u8(in, 3), u8(in, 2), u32(in, 5), u32(in, 9), u16(in, 13), u16(in, 15), u16(in, 17), u32(in, 19), u32(in, 23), u32(in, 27), f32(in, 31), f32(in, 35));
        // 0.16.0: from the vendor app's own parsers (WHOOP 5.469.0), checked on 188 logged reports
        if (id == 29 && in.length >= 11)
            return String.format(L, "backlog %d pages (%.1f %%), battery %.1f %%, flash status %d, charging %d, wrist %d", u32(in, 0), u16(in, 4) / 10.0, u16(in, 6) / 10.0, u8(in, 8), u8(in, 9), u8(in, 10));
        // 0.27.1: battery level (OpenStrap control.dart: level u32/10 at 1, millivolts u32 at 5, charger flag at 9), extended battery (checked on 221 events:
        // currents in 0.01 mA follow a charge taper of 136 to 49 mA; the temperatures equal the per-second cell and ambient temperatures exactly), firmware sub-ids
        if (id == 3 && in.length >= 10)
            return String.format(L, "battery %.1f %%, %d mV, charger %d", u32(in, 1) / 10.0, u32(in, 5), u8(in, 9));
        if (id == 63 && in.length >= 11)
            return String.format(L, "current %.2f mA average, %.2f mA now (positive = charging); temperatures %.1f and %.1f C; remaining %d (x0.1 mAh, inferred)",
                    (short) u16(in, 1) / 100.0, (short) u16(in, 3) / 100.0, (short) u16(in, 5) / 10.0, (short) u16(in, 7) / 10.0, u16(in, 9));
        if (id == 123 && in.length >= 3) {
            int sid = u16(in, 1);
            String nm = sid == 1 ? "Bluetooth link " + (in.length > 4 && u8(in, 4) == 1 ? "up" : "down") : sid == 4 ? "sensor mode change" : sid == 6 ? "power pack detected" : sid == 7 && in.length >= 8 ? String.format(L, "value %.1f", f32(in, 4)) : "";
            return "sub-id " + sid + (nm.isEmpty() ? "" : ": " + nm);
        }
        if (id == 124 && in.length >= 5) return "phase " + u8(in, 1) + " (1 = ECG start, 2 = stop), transition " + u8(in, 3) + ", value " + u8(in, 4);
        if (id == 117 && in.length >= 2) return "state " + u8(in, 1);
        if (id == 109 && in.length >= 27)
            return String.format(L, "battery pack: battery %.1f %%, colourway %d, type %d (its address and name are not shown)", u16(in, 23) / 10.0, u8(in, 25), u8(in, 26));
        return "";
    }

    /** The newest decoded text of one event id as "dd HH:mm  text", or null. */
    static String lastDec(Context c, int id) {
        try {
            SQLiteDatabase d = DayLog.db(c);
            ensure(d);
            Cursor q = d.rawQuery("SELECT ts, dec FROM strap_events WHERE id = ? ORDER BY ts DESC LIMIT 1", new String[] {String.valueOf(id)});
            try { if (q.moveToFirst()) return new java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.UK).format(new java.util.Date(q.getLong(0) * 1000L)) + "  " + q.getString(1); } finally { q.close(); }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02X", x & 0xff));
        return s.toString();
    }

    private static synchronized void ensure(SQLiteDatabase d) {
        if (made) return;
        d.execSQL("CREATE TABLE IF NOT EXISTS strap_events (ts INTEGER, id INTEGER, ms INTEGER, inner TEXT, dec TEXT, PRIMARY KEY (ts, id, inner))");
        d.execSQL("DELETE FROM strap_events WHERE ms < " + (System.currentTimeMillis() - 30L * 86400000L));
        made = true;
    }

    /** Called for every strap event frame; [payload] starts at frame byte 11. Never throws. */
    static void store(final Context c, final int id, final byte[] payload) {
        if (payload == null || payload.length < 9 || skip(id)) return;
        final Context app = c.getApplicationContext();
        EXEC.execute(new Runnable() {
            @Override public void run() {
                try {
                    long now = System.currentTimeMillis();
                    long ts = u32(payload, 1);
                    boolean plausible = ts > 1700000000L && ts < now / 1000L + 86400L;
                    byte[] in = inner(payload);
                    String dec = decode(id, payload);
                    if (!plausible) { dec = (dec.isEmpty() ? "" : dec + "; ") + "strap clock " + ts + " not plausible, stored at phone time"; ts = now / 1000L; }
                    SQLiteDatabase d = DayLog.db(app);
                    ensure(d);
                    d.execSQL("INSERT OR IGNORE INTO strap_events (ts, id, ms, inner, dec) VALUES (?, ?, ?, ?, ?)", new Object[] {ts, id, now, hex(in), dec});
                } catch (Throwable ignored) { }
            }
        });
    }

    /** 0.20.1: newest first, only the given event ids, so a day's 800 other events cannot push the ones wanted out of the row limit. {ts, id, name, decoded}. */
    static List<String[]> byIds(Context c, long sinceSec, int limit, int... ids) {
        List<String[]> out = new ArrayList<>();
        if (ids == null || ids.length == 0) return out;
        try {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < ids.length; i++) in.append(i == 0 ? "" : ",").append(ids[i]);
            SQLiteDatabase d = DayLog.db(c);
            ensure(d);
            Cursor q = d.rawQuery("SELECT ts, id, dec FROM strap_events WHERE ts >= ? AND id IN (" + in + ") ORDER BY ts DESC LIMIT " + limit, new String[] {String.valueOf(sinceSec)});
            while (q.moveToNext()) out.add(new String[] {String.valueOf(q.getLong(0)), String.valueOf(q.getInt(1)), name(q.getInt(1)), q.getString(2)});
            q.close();
        } catch (Throwable ignored) { }
        return out;
    }

    /** Newest first: {ts, id, name, decoded}. */
    static List<String[]> recent(Context c, long sinceSec, int limit) {
        List<String[]> out = new ArrayList<>();
        try {
            SQLiteDatabase d = DayLog.db(c);
            ensure(d);
            Cursor q = d.rawQuery("SELECT ts, id, dec FROM strap_events WHERE ts >= ? ORDER BY ts DESC LIMIT " + limit, new String[] {String.valueOf(sinceSec)});
            while (q.moveToNext()) out.add(new String[] {String.valueOf(q.getLong(0)), String.valueOf(q.getInt(1)), name(q.getInt(1)), q.getString(2)});
            q.close();
        } catch (Throwable ignored) { }
        return out;
    }
}

package com.noop.mgecg;

// FILE VERSION 0.20.0 (27 Oct 2026): what the strap is sending, live. Pure Java (no Android), tested off the phone on real frames.

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Counts and remembers every notification the strap sends, so the "Live frames" screen can show the packets arriving (type 43 live raw ECG, type 40 live heart rate,
 * type 47 history, 48 to 50 events and logs, 36 command replies). One call per notification, cheap: a counter, a small ring of arrival times for the rate, and a copy of
 * the first 36 bytes for the line shown on screen (formatted only when the screen is open). Live types and history types keep separate line rings so a history pull
 * (hundreds of frames a second) cannot push the live lines off the screen.
 */
final class FrameMonitor {
    private FrameMonitor() {}

    static final int HEAD = 36, LIVE_LINES = 120, HIST_LINES = 80, RATE_N = 12;

    static final class Entry {
        final long ms; final String ch; final int len; final byte[] head;
        Entry(long ms, String ch, int len, byte[] head) { this.ms = ms; this.ch = ch; this.len = len; this.head = head; }
    }

    static final class Row {
        final int key;
        long total, lastMs;
        final long[] ring = new long[RATE_N];
        int rn;
        Entry last;
        Row(int key) { this.key = key; }
    }

    /** What the screen shows for one row. */
    static final class RowInfo {
        String name, last;
        long total;
        double perSecond, agoSeconds;
        boolean active;
        int type;
    }

    private static final Object LOCK = new Object();
    private static final HashMap<Integer, Row> rows = new HashMap<>();
    private static final ArrayDeque<Entry> live = new ArrayDeque<>(), hist = new ArrayDeque<>();
    private static long sinceMs = System.currentTimeMillis();

    static void clear() {
        synchronized (LOCK) { rows.clear(); live.clear(); hist.clear(); sinceMs = System.currentTimeMillis(); }
    }

    static long sinceMs() { synchronized (LOCK) { return sinceMs; } }

    private static boolean isWhoop(byte[] f) { return f != null && f.length >= 12 && (f[0] & 0xFF) == 0xAA && f[1] == 1; }

    static int typeOf(byte[] f) { return isWhoop(f) ? (f[8] & 0xFF) : 0; }

    /** Key: type, then layout (types 43 and 47) or command (type 36), then length. */
    static int keyOf(byte[] f) {
        int type = typeOf(f), sub = 0;
        if (type == 43 || type == 47) sub = f[9] & 0xFF;
        else if (type == 36 && f.length > 10) sub = f[10] & 0xFF;
        return (type << 24) | (sub << 16) | (f.length & 0xFFFF);
    }

    /** Call once per notification with the arrival time, the short characteristic id (for example "0005") and the value. */
    // 0.25.0: raw capture of frames that are not decoded yet (console log type 50 and the device-info channel 0007), to unknown_frames_<date>.hex
    private static volatile java.io.File capDir; private static int cap50, cap07; private static long capBytes;
    static void captureTo(java.io.File dir) { if (dir != null) capDir = dir; }
    private static void capture(long nowMs, String ch, byte[] f, boolean t50) {
        java.io.File dir = capDir; if (dir == null) return;
        synchronized (LOCK) {
            if (t50) { if (cap50 >= 500) return; cap50++; } else { if (cap07 >= 300) return; cap07++; }
            if (capBytes > 1200000L) return;
            try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "unknown_frames_" + new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.UK).format(new java.util.Date(nowMs)) + ".hex"), true)) {
                StringBuilder sb = new StringBuilder(); for (byte b : f) sb.append(String.format("%02X", b & 0xFF)); String ln = nowMs + " " + (ch.length() >= 8 ? ch.substring(4, 8) : ch) + " " + f.length + " " + sb + "\n"; w.write(ln); capBytes += ln.length();
            } catch (Exception ignored) { }
        }
    }
    static void note(long nowMs, String ch, byte[] f) {
        if (f == null || f.length == 0) return;
        int key = keyOf(f);
        if (capDir != null && ch != null) { int ty = key >>> 24; if (ty == 50) capture(nowMs, ch, f, true); else if (ch.length() >= 8 && ch.substring(4, 8).equals("0007")) capture(nowMs, ch, f, false); }
        Entry e = new Entry(nowMs, ch, f.length, Arrays.copyOf(f, Math.min(f.length, HEAD)));
        int type = key >>> 24;
        synchronized (LOCK) {
            Row r = rows.get(key);
            if (r == null) { r = new Row(key); rows.put(key, r); }
            r.total++; r.lastMs = nowMs; r.last = e;
            r.ring[r.rn % RATE_N] = nowMs; r.rn++;
            ArrayDeque<Entry> q = (type == 47 || type == 36) ? hist : live;
            q.addFirst(e);
            int cap = q == live ? LIVE_LINES : HIST_LINES;
            while (q.size() > cap) q.removeLast();
        }
    }

    static String layoutName(int type, int layout) {
        if (type == 43) {
            switch (layout) {
                case 16: return "live raw ECG 500 Hz";
                case 17: return "live filtered ECG 100 Hz";
                default: return "live raw layout " + layout;
            }
        }
        switch (layout) {
            case 16: return "history: raw ECG 500 Hz";
            case 17: return "history: filtered ECG";
            case 18: return "history: per-second summary";
            case 20: return "history: raw optical 50 Hz";
            case 21: return "history: raw motion 100 Hz";
            case 26: return "history: pulse packet";
            default: return "history layout " + layout;
        }
    }

    static String cmdName(int c) {
        switch (c) {
            case 3: return "live heart rate on/off";
            case 10: return "set clock";
            case 11: return "get clock";
            case 20: return "abort history";
            case 22: return "send history";
            case 23: return "history acknowledge";
            case 34: return "data range";
            case 123: return "select wrist";
            case 124: return "ECG generation";
            case 125: return "raw save";
            case 126: return "live raw ECG on/off";
            case 127: return "save filtered ECG";
            case 139: return "filtered ECG";
            case 145: return "hello";
            default: return "command " + c;
        }
    }

    static String nameOf(int key) {
        int type = key >>> 24, sub = (key >>> 16) & 0xFF, len = key & 0xFFFF;
        switch (type) {
            case 0: return "other (not a strap frame), " + len + " B";
            case 36: return "36 reply: " + cmdName(sub) + ", " + len + " B";
            case 40: return "40 live heart rate, " + len + " B";
            case 43: return "43 " + layoutName(43, sub) + ", " + len + " B";
            case 47: return "47 " + layoutName(47, sub) + ", " + len + " B";
            case 48: return "48 event, " + len + " B";
            case 49: return "49 metadata, " + len + " B";
            case 50: return "50 console log, " + len + " B";
            default: return type + " type " + type + ", " + len + " B";
        }
    }

    private static long u32(byte[] f, int o) { return (f[o] & 0xFFL) | ((f[o + 1] & 0xFFL) << 8) | ((f[o + 2] & 0xFFL) << 16) | ((f[o + 3] & 0xFFL) << 24); }
    private static int u16(byte[] f, int o) { return (f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8); }

    /** The fields worth seeing at a glance, from the stored head of the frame. */
    static String detail(Entry e) {
        byte[] f = e.head;
        int type = typeOf(f);
        if (type == 0) return "";
        StringBuilder s = new StringBuilder();
        if ((type == 43 || type == 47) && f.length >= 26) {
            int layout = f[9] & 0xFF;
            s.append("ctr ").append(u32(f, 11));
            if (layout == 16 && f.length >= 34) s.append("  n ").append(u16(f, 32));
            if (layout == 17 && f.length >= 34) s.append("  n ").append(u16(f, 32));
            if (layout == 16 || layout == 17) s.append("  q ").append(f[21] & 0xFF).append("  st ").append(f[24] & 0xFF).append("  prog ").append(f[25] & 0xFF);
            if (layout == 16 && (f[24] & 0xFF) == 2) s.append("  result ").append(f[23] & 0xFF).append("  avgHR ").append(f[27] & 0xFF);
            if (layout == 18 && f.length >= 23) s.append("  HR ").append(f[22] & 0xFF);
            if (f.length >= 21) {
                double rec = u32(f, 15) + u16(f, 19) / 32768.0;
                double d = e.ms / 1000.0 - rec;
                if (d > -5 && d < 100000) s.append(type == 43 ? "  lag " : "  age ").append(String.format(Locale.UK, "%.2f s", d));
            }
        } else if (type == 40 && f.length >= 17) {
            s.append("HR ").append(f[16] & 0xFF).append(" bpm");
        } else if (type == 36 && f.length >= 13) {
            s.append("result ").append(f[12] & 0xFF);
        } else if (type == 48 && f.length >= 11) {
            s.append("id ").append(f[10] & 0xFF);
        }
        return s.toString();
    }

    static String line(Entry e) {
        int key = keyOf(e.head.length == e.len ? e.head : padded(e));
        return new SimpleDateFormat("HH:mm:ss.S", Locale.UK).format(new Date(e.ms)) + "  " + e.ch + "  " + shortName(key) + "  " + detail(e);
    }

    private static byte[] padded(Entry e) { byte[] b = Arrays.copyOf(e.head, e.len); return b; }

    static String shortName(int key) {
        int type = key >>> 24, sub = (key >>> 16) & 0xFF, len = key & 0xFFFF;
        if (type == 0) return "other " + len + "B";
        if (type == 43 || type == 47) return type + " L" + sub + " " + len + "B";
        if (type == 36) return "36 c" + sub + " " + len + "B";
        return type + " " + len + "B";
    }

    static List<RowInfo> rows(long nowMs) {
        List<RowInfo> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Row r : rows.values()) {
                RowInfo i = new RowInfo();
                i.type = r.key >>> 24;
                i.name = nameOf(r.key);
                i.total = r.total;
                i.agoSeconds = (nowMs - r.lastMs) / 1000.0;
                int n = Math.min(r.rn, RATE_N);
                double rate = 0;
                if (n >= 2 && i.agoSeconds < 12) {
                    long newest = r.ring[(r.rn - 1) % RATE_N], oldest = r.ring[(r.rn - n) % RATE_N];
                    if (newest > oldest) rate = (n - 1) * 1000.0 / (newest - oldest);
                }
                i.perSecond = rate;
                i.active = i.agoSeconds < 2.5;
                i.last = r.last == null ? "" : detail(r.last);
                out.add(i);
            }
        }
        Collections.sort(out, new Comparator<RowInfo>() {
            @Override public int compare(RowInfo a, RowInfo b) {
                int ra = rank(a.type), rb = rank(b.type);
                if (ra != rb) return Integer.compare(ra, rb);
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    /** Live types first (43, 40), then events and logs, then history, then replies. */
    private static int rank(int type) {
        switch (type) { case 43: return 0; case 40: return 1; case 48: return 2; case 49: return 3; case 50: return 4; case 47: return 5; case 36: return 6; default: return 7; }
    }

    static List<String> lines(boolean history, int max) {
        List<String> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Entry e : history ? hist : live) { if (out.size() >= max) break; out.add(line(e)); }
        }
        return out;
    }

    /** The table as plain text (used by the Copy button and the tests). */
    static String table(long nowMs) {
        StringBuilder s = new StringBuilder();
        for (RowInfo i : rows(nowMs)) {
            s.append(String.format(Locale.UK, "%-44s %8d %6.1f/s %7.1fs  %s\n", i.name, i.total, i.perSecond, i.agoSeconds, i.last));
        }
        return s.toString();
    }
}

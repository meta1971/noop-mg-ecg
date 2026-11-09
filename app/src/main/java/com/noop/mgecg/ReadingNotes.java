package com.noop.mgecg;

// FILE VERSION 0.41.0 (5 Nov 2026): what the ECG page knows about a reading that the strap files do not: where, which run, why noisy, noise by band, verdict timing.
// One line per fact in reading_notes.csv (unix seconds of the reading start, key, value). Read by the readings table CSV. Pure Java.

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

final class ReadingNotes {
    private ReadingNotes() {}

    static void put(File dir, long startSec, String key, String value) {
        if (dir == null || key == null) return;
        try (FileWriter w = new FileWriter(new File(dir, "reading_notes.csv"), true)) {
            w.write(startSec + "," + key.replace(',', ' ') + "," + (value == null ? "" : value.replace(',', ';').replace('\n', ' ')) + "\n");
        } catch (Exception ignored) { }
    }

    /** 0.42.3: the raw lines (start second, key, value) of the reading that started within 30 s of tsSec, for the Full pull. */
    static String linesFor(File dir, long tsSec) {
        StringBuilder sb = new StringBuilder("start_unix_phone,key,value\n");
        if (dir == null) return sb.toString();
        File f = new File(dir, "reading_notes.csv"); if (!f.exists() || f.length() > 4000000) return sb.toString();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln; while ((ln = br.readLine()) != null) { int a = ln.indexOf(','); if (a < 1) continue; try { long s = Long.parseLong(ln.substring(0, a)); if (Math.abs(s - tsSec) <= 30) sb.append(ln).append('\n'); } catch (NumberFormatException ignored) { } }
        } catch (Exception ignored) { }
        return sb.toString();
    }

    /** 0.49.0: every reading's notes in one pass (start second -> latest value of each key), for the Trends screen. Empty when there is no file or it is over 4 MB. */
    static Map<Long, Map<String, String>> all(File dir) {
        Map<Long, Map<String, String>> by = new HashMap<>();
        if (dir == null) return by;
        File f = new File(dir, "reading_notes.csv"); if (!f.exists() || f.length() > 4000000) return by;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln; while ((ln = br.readLine()) != null) {
                int a = ln.indexOf(','), b = ln.indexOf(',', a + 1); if (a < 1 || b < 0) continue;
                long s; try { s = Long.parseLong(ln.substring(0, a)); } catch (NumberFormatException e) { continue; }
                Map<String, String> m = by.get(s); if (m == null) { m = new HashMap<>(); by.put(s, m); }
                m.put(ln.substring(a + 1, b), ln.substring(b + 1).trim());
            }
        } catch (Exception ignored) { }
        return by;
    }

    /** The latest value of every key noted for a reading that started within 30 s of tsSec (empty map when none). */
    static Map<String, String> forReading(File dir, long tsSec) {
        Map<String, String> out = new HashMap<>();
        if (dir == null) return out;
        File f = new File(dir, "reading_notes.csv"); if (!f.exists() || f.length() > 4000000) return out;
        long bestStart = Long.MIN_VALUE; Map<Long, Map<String, String>> by = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln; while ((ln = br.readLine()) != null) {
                int a = ln.indexOf(','), b = ln.indexOf(',', a + 1); if (a < 1 || b < 0) continue;
                long s; try { s = Long.parseLong(ln.substring(0, a)); } catch (NumberFormatException e) { continue; }
                if (Math.abs(s - tsSec) > 30) continue;
                Map<String, String> m = by.get(s); if (m == null) { m = new HashMap<>(); by.put(s, m); }
                m.put(ln.substring(a + 1, b), ln.substring(b + 1).trim());
            }
        } catch (Exception ignored) { }
        long bd = Long.MAX_VALUE; for (Map.Entry<Long, Map<String, String>> e : by.entrySet()) { long d = Math.abs(e.getKey() - tsSec); if (d < bd) { bd = d; out = e.getValue(); } }
        return out;
    }
}

package com.noop.mgecg;

// FILE VERSION 0.51.0 (10 Nov 2026): firmware console catalogue and register-line parser (build brief 0.51.0 item 2). Pure Java (no Android), tested off the phone.

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Console frames (type 50, 76 bytes): byte 9 = sequence, bytes 21-70 = up to 50 ASCII characters (NUL ends the piece), a line may continue in the next frame
 * (decode log 138.2; the Full pull rebuilds firmware_console.txt the same way). Pieces are joined in sequence order and cut at '\n' / '\r'; a sequence gap
 * ends the line being built. Each finished line:
 *  - is normalised to a template (the "36, <tick>:" prefix dropped; 0x.. hex, decimals and integers -> #; MAC and strap serial masked) and counted in
 *    console_templates.csv (template, count, first_ms, last_ms, example; at most 600 templates; kept across days);
 *  - when it is a register line ("FIFO Config 1: 0xB2", "Sys Config 1 0x20", "ECG Config 1 0x82", "MAX86176: Status 5: 0x02") goes to afe_regs.csv
 *    (strap_s, name, value_hex, decode = AfeDecode) and to the reading's note afe_<name> (last value); ECG Config 1 also gives afe_ecg_cfg1_decode.
 * Fed only inside the existing capture window (a reading and 150 s after it).
 */
final class ConsoleCatalog {
    private ConsoleCatalog() {}

    static final int MAX_TEMPLATES = 600, MAX_NEW = 20;
    static final String FILE = "console_templates.csv", REGS = "afe_regs.csv";

    static final class T { String tpl, example; long count, first, last; }

    private static final Object LOCK = new Object();
    private static File dir; private static boolean loaded;
    private static final LinkedHashMap<String, T> cat = new LinkedHashMap<>();
    private static final StringBuilder piece = new StringBuilder();
    private static int lastSeq = -1; private static long lastStrapS;
    private static long readingStart; private static int newN, fragments; private static boolean cutNext;
    private static final List<String> newList = new ArrayList<>();
    private static final LinkedHashMap<String, String> regsNow = new LinkedHashMap<>();
    private static boolean dirty; private static long lastSaveMs;

    static void begin(File d, long startSec) {
        synchronized (LOCK) { if (d != null) dir = d; load(); readingStart = startSec; newN = 0; fragments = 0; newList.clear(); regsNow.clear(); }
    }
    static int templates() { synchronized (LOCK) { load(); return cat.size(); } }

    /** One notification (whole frame). nowMs = phone time. */
    static void feed(File d, byte[] f, long nowMs) {
        if (f == null || f.length < 72 || (f[8] & 0xFF) != 50) return;
        synchronized (LOCK) {
            if (d != null && dir == null) dir = d;
            load();
            int seq = f[9] & 0xFF;
            if (lastSeq >= 0 && seq == lastSeq) return;                                     // the same frame twice (live and pull copy)
            if (lastSeq >= 0 && ((seq - lastSeq) & 0xFF) != 1 && piece.length() > 0) { cutNext = true; finishLine(piece.toString(), nowMs); piece.setLength(0); }      // a lost frame: the line is cut
            lastSeq = seq; lastStrapS = ProAnalysis.u32(f, 12);
            int e = 21; while (e < 71 && f[e] != 0) e++;
            for (int i = 21; i < e; i++) {
                char ch = (char) (f[i] & 0xFF);
                if (ch == '\n' || ch == '\r') { if (piece.length() > 0) { finishLine(piece.toString(), nowMs); piece.setLength(0); } }
                else if (piece.length() < 400) piece.append(ch);
            }
            splitAtPrefix(nowMs);
            if (dirty && nowMs - lastSaveMs > 20000L) save(nowMs);
        }
    }

    /** Lines that come without a newline: a second "<n>, <tick>:" record prefix inside the piece ends the line before it. */
    static final Pattern NEXT = Pattern.compile("\\d{1,3}, ?\\d{4,}: ");
    private static void splitAtPrefix(long nowMs) {
        while (true) {
            Matcher m = NEXT.matcher(piece); int cut = -1;
            while (m.find()) { if (m.start() > 0 && !Character.isDigit(piece.charAt(m.start() - 1))) { cut = m.start(); break; } }
            if (cut < 0) return;
            finishLine(piece.substring(0, cut), nowMs); piece.delete(0, cut);
        }
    }

    static final Pattern PREFIX = Pattern.compile("^\\s*\\d{1,3}\\s*,\\s*\\d+\\s*:\\s*");
    static final Pattern MAC = Pattern.compile("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");
    static final Pattern SERIAL = Pattern.compile("5AM[0-9]{6}");
    static final Pattern HEX = Pattern.compile("0[xX][0-9A-Fa-f]+");
    static final Pattern HEXTOK = Pattern.compile("(?<![A-Za-z0-9_])(?:[0-9A-Fa-f]{8,}|(?=[0-9A-Fa-f]*[0-9])(?=[0-9A-Fa-f]*[A-Fa-f])[0-9A-Fa-f]{4,})(?![A-Za-z0-9_])");      // bare hex words (trim cursors 0000c54b, ffffffff)
    static final Pattern MAC12 = Pattern.compile("(?<![0-9A-Fa-f])[0-9A-Fa-f]{12}(?![0-9A-Fa-f])");      // a Bluetooth address without colons (NFC COMMS: BT addr : ...)
    static final Pattern NUM = Pattern.compile("(?<![A-Za-z0-9_.])[-+]?\\d+(\\.\\d+)?(?![A-Za-z])");

    static String mask(String s) { return MAC12.matcher(SERIAL.matcher(MAC.matcher(s).replaceAll("XX:XX:XX:XX:XX:XX")).replaceAll("5AM******")).replaceAll("XXXXXXXXXXXX"); }

    static String body(String line) { return PREFIX.matcher(line).replaceFirst(""); }

    static String template(String line) {
        String s = mask(body(line)).trim();
        s = HEX.matcher(s).replaceAll("#");
        s = HEXTOK.matcher(s).replaceAll("#");
        s = NUM.matcher(s).replaceAll("#");
        return s.replaceAll("\\s+", " ");
    }

    /** Register line: the last token is 0x.. and the text before it (after the last ':' that is followed by more text) names a register. */
    static final Pattern REG = Pattern.compile("^(?:.*?:\\s*)?([A-Za-z][A-Za-z0-9 _/\\-]{1,40}?)\\s*[:=]?\\s*(0[xX][0-9A-Fa-f]{1,4})\\s*\\.?\\s*$");
    static final Pattern REG_WORD = Pattern.compile("(?i)\\b(config|status|reg|ctrl|control|mode|gain|thresh|cal|enable|setting|setup)\\b");

    /** {name, VALUE_HEX} or null. */
    static String[] register(String line) {
        String b = body(line).trim();
        Matcher m = REG.matcher(b); if (!m.matches()) return null;
        String name = m.group(1).trim(); if (!REG_WORD.matcher(name).find()) return null;
        String v = m.group(2); return new String[] { name, "0x" + v.substring(2).toUpperCase(Locale.US) };
    }

    private static void finishLine(String line, long nowMs) {
        String l = line.trim(); if (l.isEmpty()) return;
        String[] reg0 = register(l);
        boolean cut = cutNext; cutNext = false;
        if (cut || !PREFIX.matcher(l).lookingAt() || l.length() < 8) { fragments++; if (reg0 != null) noteReg(reg0); return; }      // a piece after a lost frame: counted, not catalogued
        String tpl = template(l); if (tpl.isEmpty()) return;
        T t = cat.get(tpl);
        if (t == null) {
            if (cat.size() >= MAX_TEMPLATES) return;
            t = new T(); t.tpl = tpl; t.example = mask(body(l)).trim(); t.first = nowMs; cat.put(tpl, t);
            if (readingStart > 0) { newN++; if (newList.size() < MAX_NEW) newList.add(t.example); }
        }
        t.count++; t.last = nowMs; dirty = true;
        if (reg0 != null) noteReg(reg0);
    }

    private static void noteReg(String[] r) {
        if (dir != null) {
            regsNow.put(r[0], r[1]);
            try (FileWriter w = new FileWriter(new File(dir, REGS), true)) {
                File rf = new File(dir, REGS); if (rf.length() == 0) w.write("strap_s,name,value_hex,decode\n");
                w.write(lastStrapS + "," + r[0].replace(',', ' ') + "," + r[1] + "," + csvEsc(AfeDecode.describe(r[0], r[1])) + "\n");
            } catch (Exception ignored) { }
        }
    }

    static String csvEsc(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }

    private static void load() {
        if (loaded || dir == null) return; loaded = true;
        File f = new File(dir, FILE); if (!f.exists() || f.length() > 2000000) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln = br.readLine();
            while ((ln = br.readLine()) != null) {
                List<String> c = splitCsv(ln); if (c.size() < 5) continue;
                T t = new T(); t.tpl = c.get(0); try { t.count = Long.parseLong(c.get(1)); t.first = Long.parseLong(c.get(2)); t.last = Long.parseLong(c.get(3)); } catch (NumberFormatException e) { continue; }
                t.example = c.get(4); if (cat.size() < MAX_TEMPLATES) cat.put(t.tpl, t);
            }
        } catch (Exception ignored) { }
    }

    static List<String> splitCsv(String ln) {
        List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false;
        for (int i = 0; i < ln.length(); i++) {
            char c = ln.charAt(i);
            if (q) { if (c == '"') { if (i + 1 < ln.length() && ln.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false; } else cur.append(c); }
            else if (c == '"') q = true; else if (c == ',') { out.add(cur.toString()); cur.setLength(0); } else cur.append(c);
        }
        out.add(cur.toString()); return out;
    }

    private static void save(long nowMs) {
        if (dir == null) return;
        StringBuilder sb = new StringBuilder("template,count,first_ms,last_ms,example\n");
        for (T t : cat.values()) sb.append(csvEsc(t.tpl)).append(',').append(t.count).append(',').append(t.first).append(',').append(t.last).append(',').append(csvEsc(t.example)).append('\n');
        try { File tmp = new File(dir, FILE + ".tmp"); try (FileWriter w = new FileWriter(tmp, false)) { w.write(sb.toString()); } if (!tmp.renameTo(new File(dir, FILE))) { try (FileWriter w = new FileWriter(new File(dir, FILE), false)) { w.write(sb.toString()); } } } catch (Exception ignored) { }
        dirty = false; lastSaveMs = nowMs;
    }

    /** Saves the catalogue and returns the reading's notes (console_templates_n, console_new_n, afe_<name>); also writes console_new_<start>.txt. */
    static Map<String, String> endReading(File d, long startSec, long nowMs) {
        Map<String, String> m = new LinkedHashMap<>();
        synchronized (LOCK) {
            if (d != null) dir = d; load();
            if (piece.length() > 0 && nowMs > 0) { /* keep a half line: it may continue */ }
            save(nowMs);
            m.put("console_templates_n", String.valueOf(cat.size()));
            if (readingStart == startSec) {
                m.put("console_new_n", String.valueOf(newN)); m.put("console_fragments_n", String.valueOf(fragments));
                for (Map.Entry<String, String> e : regsNow.entrySet()) { m.put("afe_" + e.getKey().trim().replaceAll("[^A-Za-z0-9]+", "_"), e.getValue()); { String nk = AfeDecode.norm(e.getKey()); for (int q = 1; q <= 3; q++) if (nk.endsWith("ecg config " + q)) m.put("afe_ecg_cfg" + q + "_decode", AfeDecode.describe(e.getKey(), e.getValue())); } }
                if (dir != null && !newList.isEmpty()) { try (FileWriter w = new FileWriter(new File(dir, "console_new_" + startSec + ".txt"), false)) { for (String s : newList) w.write(s + "\n"); } catch (Exception ignored) { } }
            }
        }
        return m;
    }
}

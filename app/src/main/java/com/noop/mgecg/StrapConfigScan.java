package com.noop.mgecg;

// FILE VERSION 0.51.0 (10 Nov 2026): read-only scan of the strap's own setting names and values (build brief 0.51.0 item 1). Pure Java (no Android), tested off the phone against a fake strap.

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sends ONLY 115 / 116 / 121 (device-config namespace: count + cursor reset, next name, value by name) and 117 / 118 / 128 (feature flags, the same three).
 * Nothing is ever written to the strap. 150 ms between commands, 3 s timeout per command ("no response" and go on), stop after 5 timeouts in a row; no
 * command is retried. Name walk ends at the announced count, a repeated name, an empty or refused reply, the strap's 0xFF end marker, or 400 names.
 * Record layouts (MainActivity key walk, ryanbr/noop FeatureFlagProbe): 115 / 117 reply record [rev][count u16 LE]; 116 / 118 [rev][index][validKey][ASCII\0];
 * 121 / 128 [01][32-byte NUL-padded key][value]. Record = frame bytes 13..len-4, byte 12 = result code (1 = success). When a name reply does not fit the
 * layout the longest printable run of 3+ characters is taken; the raw hex is always kept. Output: strap_config.csv (newest scan only).
 * NOTE (10 Nov, repo check): the app already ran the 115 / 116 walk on 26 Sep (7 device-config names, MainActivity DEVICE_CONFIG_KEYS_50_40_1_0) and has the
 * 117 / 118 walk as a research button; command 128 was never answered on this strap (decode log 100.5). This scan only puts both walks and the values in one file.
 */
final class StrapConfigScan {
    interface Io { void send(int cmd, byte[] body, String name); void later(Runnable r, long ms); void cancel(Runnable r); }

    static final String FILE = "strap_config.csv";
    static final int GAP_MS = 150, TIMEOUT_MS = 3000, MAX_TIMEOUTS = 5, MAX_KEYS = 400;

    static final class Row { String ns, name = "", valueHex = "", valueAscii = "", raw = "", nameRaw = ""; int idx; long phoneS; }

    private static volatile StrapConfigScan cur;
    static volatile String lastSummary = "";
    static volatile boolean lastDone;

    private final File dir; private final Io io;
    private int phase;                                    // 0 cfg count, 1 cfg names, 2 cfg values, 3 flag count, 4 flag names, 5 flag values, 6 done
    private int announced = -1, step, timeouts, valueIdx;
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> nsRows = new ArrayList<>();
    private int awaitCmd = -1;
    private Runnable timer;
    private String stopWhy = "";
    private final StringBuilder log = new StringBuilder();

    private StrapConfigScan(File dir, Io io) { this.dir = dir; this.io = io; }

    static boolean active() { StrapConfigScan c = cur; return c != null && c.phase < 6; }

    /** Starts a scan; false when one is already running. */
    static synchronized boolean start(File dir, Io io) {
        if (active()) return false;
        lastDone = false; lastSummary = "";
        StrapConfigScan s = new StrapConfigScan(dir, io); cur = s;
        s.begin(0);
        return true;
    }

    /** Every type-36 (0x24) reply frame; byte 10 = command. Cheap when no scan runs. */
    static void onReply(int cmd, byte[] f) { StrapConfigScan c = cur; if (c != null && c.phase < 6) c.reply(cmd, f); }

    private static String ns(int phase) { return phase < 3 ? "cfg" : "flag"; }
    private static int cmdOf(int phase) { switch (phase) { case 0: return 115; case 1: return 116; case 2: return 121; case 3: return 117; case 4: return 118; default: return 128; } }

    private synchronized void begin(int ph) {
        phase = ph; announced = -1; step = 0; valueIdx = 0; nsRows.clear();
        if (ph >= 6) { finish(); return; }
        sendNow();
    }

    private void sendNow() {
        final int cmd = cmdOf(phase); byte[] body;
        if (phase == 2 || phase == 5) {
            if (valueIdx >= nsRows.size()) { begin(phase + 1); return; }
            body = keyBody(nsRows.get(valueIdx).name);
        } else body = new byte[] { 0x01 };
        awaitCmd = cmd;
        io.send(cmd, body, "STRAP_CONFIG_SCAN(" + cmd + ")");
        final Runnable t = new Runnable() { @Override public void run() { timedOut(this); } };
        timer = t; io.later(t, TIMEOUT_MS);
    }

    static byte[] keyBody(String key) { byte[] p = new byte[33]; p[0] = 0x01; byte[] k = key.getBytes(java.nio.charset.StandardCharsets.US_ASCII); System.arraycopy(k, 0, p, 1, Math.min(32, k.length)); return p; }

    private synchronized void timedOut(Runnable which) {
        if (which != timer || phase >= 6) return;
        timeouts++; log.append("no response to ").append(awaitCmd).append(" (phase ").append(phase).append(")\n");
        if ((phase == 2 || phase == 5) && valueIdx < nsRows.size()) nsRows.get(valueIdx).valueAscii = "no response";
        if (timeouts >= MAX_TIMEOUTS) { stopWhy = MAX_TIMEOUTS + " timeouts in a row"; phase = 6; finish(); return; }
        if (phase == 2 || phase == 5) { valueIdx++; next(); }
        else if (phase == 0 || phase == 3) { Row r = new Row(); r.ns = ns(phase); r.idx = -1; r.name = "(count)"; r.valueAscii = "no response"; r.phoneS = nowS(); rows.add(r); phase++; step = 0; next(); }
        else { begin(phase + 1); }
    }

    private void next() { io.later(new Runnable() { @Override public void run() { synchronized (StrapConfigScan.this) { if (phase < 6) sendNow(); } } }, GAP_MS); }

    private static long nowS() { return System.currentTimeMillis() / 1000L; }

    static String hex(byte[] b, int a, int e) { StringBuilder s = new StringBuilder(); for (int i = Math.max(0, a); i < Math.min(b.length, e); i++) s.append(String.format("%02X", b[i] & 0xFF)); return s.toString(); }

    /** Longest printable ASCII run of 3 or more characters inside [a, e). */
    static String longestAscii(byte[] b, int a, int e) {
        String best = ""; StringBuilder cur = new StringBuilder();
        for (int i = Math.max(0, a); i <= Math.min(b.length, e); i++) {
            int c = i < Math.min(b.length, e) ? b[i] & 0xFF : 0;
            if (c >= 32 && c <= 126) cur.append((char) c); else { if (cur.length() >= 3 && cur.length() > best.length()) best = cur.toString(); cur.setLength(0); }
        }
        return best;
    }

    private synchronized void reply(int cmd, byte[] f) {
        if (phase >= 6 || cmd != awaitCmd || f == null || f.length < 17) return;
        if (timer != null) { io.cancel(timer); timer = null; }
        timeouts = 0; awaitCmd = -1;
        int res = f[12] & 0xFF, recA = 13, recE = f.length - 4;
        if (phase == 0 || phase == 3) {
            Row r = new Row(); r.ns = ns(phase); r.idx = -1; r.name = "(count)"; r.raw = hex(f, 0, f.length); r.phoneS = nowS();
            if (res == 1 && recE - recA >= 3) { announced = (f[14] & 0xFF) | ((f[15] & 0xFF) << 8); r.valueAscii = String.valueOf(announced); r.valueHex = hex(f, 14, 16); }
            else r.valueAscii = "result " + res;
            rows.add(r);
            if (res == 3) { begin(phase + 3); return; }                                   // unsupported: skip this namespace
            phase++; step = 0; next(); return;
        }
        if (phase == 1 || phase == 4) {
            step++;
            int index = recE - recA >= 2 ? f[14] & 0xFF : -1;
            boolean valid = recE - recA >= 3 && (f[15] & 0xFF) != 0;
            String name = "";
            if (recE - recA >= 4) { StringBuilder kb = new StringBuilder(); for (int i = 16; i < recE; i++) { int b = f[i] & 0xFF; if (b == 0) break; if (b < 32 || b > 126) { kb.setLength(0); break; } kb.append((char) b); if (kb.length() > 40) { kb.setLength(0); break; } } name = kb.toString(); }
            if (name.length() < 3) name = longestAscii(f, recA, recE);
            boolean end = res != 1 || index == 0xFF || name.isEmpty();
            boolean repeat = false; for (Row q : nsRows) if (q.name.equals(name)) { repeat = true; break; }
            if (!end && !repeat && (valid || index >= 0)) {
                Row r = new Row(); r.ns = ns(phase); r.idx = index; r.name = name; r.nameRaw = hex(f, 0, f.length); r.phoneS = nowS(); rows.add(r); nsRows.add(r);
            } else if (end && res != 1) log.append("name walk ").append(cmd).append(" ended on result ").append(res).append('\n');
            boolean stop = end || repeat || nsRows.size() >= MAX_KEYS || (announced > 0 && nsRows.size() >= announced) || step >= MAX_KEYS + 8;
            if (stop) { phase++; valueIdx = 0; next(); } else next();
            return;
        }
        // value replies (121 / 128)
        Row r = nsRows.get(valueIdx);
        r.raw = hex(f, 0, f.length);
        if (res == 1 && recE - recA >= 33 && f[13] == 0x01) {
            int a = 13 + 1 + 32; r.valueHex = hex(f, a, recE);
            StringBuilder vb = new StringBuilder(); for (int i = a; i < recE; i++) { int b = f[i] & 0xFF; if (b == 0) break; vb.append(b >= 32 && b <= 126 ? (char) b : '?'); } r.valueAscii = vb.toString();
        } else r.valueAscii = "result " + res;
        valueIdx++; next();
    }

    private void finish() {
        phase = 6;
        if (timer != null) { io.cancel(timer); timer = null; }
        int cfg = 0, fl = 0; for (Row r : rows) if (r.idx >= 0) { if ("cfg".equals(r.ns)) cfg++; else fl++; }
        StringBuilder sb = new StringBuilder("ns,idx,name,value_hex,value_ascii,raw_hex,name_raw_hex,phone_s\n");
        for (Row r : rows) sb.append(r.ns).append(',').append(r.idx).append(',').append(ConsoleCatalog.csvEsc(r.name)).append(',').append(r.valueHex).append(',').append(ConsoleCatalog.csvEsc(r.valueAscii)).append(',').append(r.raw).append(',').append(r.nameRaw).append(',').append(r.phoneS).append('\n');
        if (dir != null) { try (FileWriter w = new FileWriter(new File(dir, FILE), false)) { w.write(sb.toString()); } catch (Exception ignored) { } }
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.UK, "Device-config names: %d. Feature flags: %d.%s\n", cfg, fl, stopWhy.isEmpty() ? "" : " Stopped: " + stopWhy + "."));
        s.append(interesting(rows));
        s.append("\nRead only: nothing was written to the strap. Saved as strap_config.csv (in the Full pull).");
        lastSummary = s.toString(); lastDone = true;
    }

    static final String[] WORDS = { "ecg", "afe", "gain", "cal", "dec", "filter", "labrador", "hpf", "lpf", "notch", "sample", "test" };

    static String interesting(List<Row> rows) {
        StringBuilder s = new StringBuilder(); int n = 0;
        for (Row r : rows) { if (r.idx < 0) continue; String l = r.name.toLowerCase(Locale.US); boolean hit = false; for (String w : WORDS) if (l.contains(w)) { hit = true; break; } if (!hit) continue; s.append(r.ns).append("  ").append(r.name).append(" = ").append(r.valueAscii.isEmpty() ? r.valueHex : r.valueAscii).append('\n');
            String dv = r.valueAscii.trim(); if (dv.matches("(0[xX])?[0-9A-Fa-f]{1,2}") && l.matches(".*(ecg.?cfg|ecg.?config|cal.?cfg|cal.?config).*[1-4].*")) { String dd = AfeDecode.describe("ecg config " + l.replaceAll(".*([1-4]).*", "$1"), dv.startsWith("0") && dv.length() > 2 ? dv : "0x" + dv); if (!dd.isEmpty()) s.append("    ").append(dd).append('\n'); }
            if (l.contains("cal") || l.contains("test")) s.append("    (calibration / self-test name: tell Ame before any write is considered; nothing is written here)\n");
            n++; }
        return n == 0 ? "No name contains ecg, afe, gain, cal, dec, filter, labrador, hpf, lpf, notch, sample or test.\n" : "Names of interest:\n" + s;
    }

    /** {config names, flag names, file time in unix seconds} of the newest scan, or null. */
    static long[] fileStats(File dir) {
        File f = new File(dir, FILE); if (!f.exists()) return null;
        long c = 0, fl = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) { String ln = br.readLine(); while ((ln = br.readLine()) != null) { if (ln.startsWith("cfg,") && !ln.startsWith("cfg,-1,")) c++; else if (ln.startsWith("flag,") && !ln.startsWith("flag,-1,")) fl++; } } catch (Exception ignored) { }
        return new long[] { c, fl, f.lastModified() / 1000L };
    }
}

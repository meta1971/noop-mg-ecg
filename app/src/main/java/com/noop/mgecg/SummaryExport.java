package com.noop.mgecg;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Export summary": writes small CSV files into Download/MGsummary so the big raw history files never have to leave the phone.
 * Files: mg_nights.csv and mg_ecg_readings.csv (same columns as the project files of the same name), mg_idx_runs.csv (record counter
 * against strap clock), mg_strap_events.csv (strap events and 0x1D timer messages from the app log), mg_v26.csv (one row per optical
 * pulse record), mg_ecg_status.csv (one row per stored-ECG second: status block, lead-off, contact, fast-recovery share, noise proxy),
 * mg_export_info.csv (what was read). The row logic is in the pure nested class {@link Rows} so it can be tested off the phone.
 * RESEARCH ONLY.
 */
final class SummaryExport {
    private SummaryExport() {}

    static final String FOLDER = "MGsummary";

    static final class Result {
        String note = "";
        final ArrayList<Uri> uris = new ArrayList<>();
    }

    // ------------------------------------------------------------------ pure row logic (no Android types)
    static final class Rows {
        private Rows() {}

        static final String V26_HEADER = "ts,idx,ctr,u32_23,i32_23,first,last,min,max,mean,sd,sum,mean_abs_step,acc_delta_g,agc79,b81,b82,flags10,"
                + "r18_hr,r18_det106,r18_psnr108,r18_psnr109";
        static final String V16_HEADER = "ts,sub_s,idx,b10,quality,b22,result,state,progress,mask,avg_hr,live_hr,hrv,n_leadoff,lead_i,lead_q,"
                + "contact_frac,fastrec_frac,hf_uv";
        static final String EVENT_HEADER = "log_ms,id,name,seq,src,strap_ts,sub_s,phone_minus_strap_s,payload_len,payload_hex";
        static final String IDX_HEADER = "start_ts,end_ts,n,missing_s,idx_first,idx_last,offset_first,offset_min,offset_max,reason,dt_prev_s,offset_step";
        static final String NIGHTS_HEADER = "night_ending,bed_local,wake_local,bed_unix,wake_unix,asleep_h,in_bed_h,efficiency_pct,lowest5_hr,mean_hr,"
                + "rmssd_optical_ms,skin_c,breathing_per_min,restless_spells,spo2_windows,spo2_median,spo2_min,spo2_max,stage_wake_min,"
                + "stage_light_min,stage_deep_min,stage_rem_min,solid_bins,bins_total,logged_h,source_pull";
        static final String READINGS_HEADER = "start_local,start_unix,hr_bpm,hrv_ms,rhythm,grade,source_pull";

        /** Event ids left out because they are frequent and already decoded well enough: 3 battery, 29 condition report, 63 extended battery, 124 ECG state. */
        static boolean skipEvent(int id) { return id == 3 || id == 29 || id == 63 || id == 124; }

        static String num(double v, int dec) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return "";
            return String.format(Locale.US, "%." + dec + "f", v);
        }

        static String q(String s) {
            if (s == null) return "";
            if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }

        static int u8(byte[] f, int o) { return f[o] & 0xff; }
        static int u16(byte[] f, int o) { return (f[o] & 0xff) | ((f[o + 1] & 0xff) << 8); }
        static int i16(byte[] f, int o) { return (short) u16(f, o); }
        static long u32(byte[] f, int o) {
            return (f[o] & 0xffL) | ((f[o + 1] & 0xffL) << 8) | ((f[o + 2] & 0xffL) << 16) | ((f[o + 3] & 0xffL) << 24);
        }

        static boolean isV26(byte[] f) { return f != null && f.length == 88 && u8(f, 8) == 47 && u8(f, 9) == 26; }
        static boolean isV16(byte[] f) { return f != null && f.length == 1584 && u8(f, 8) == 47 && u8(f, 9) == 16; }

        /** v26 optical pulse record: the columns up to flags10 (the R18 columns are appended by the caller). */
        static String v26(byte[] f) {
            long ts = u32(f, 15), idx = u32(f, 11);
            long u = u32(f, 23);
            int[] v = new int[24];
            long sum = 0;
            int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
            for (int k = 0; k < 24; k++) {
                v[k] = i16(f, 27 + 2 * k);
                sum += v[k];
                if (v[k] < mn) mn = v[k];
                if (v[k] > mx) mx = v[k];
            }
            double mean = sum / 24.0, ss = 0, st = 0;
            for (int k = 0; k < 24; k++) {
                ss += (v[k] - mean) * (v[k] - mean);
                if (k > 0) st += Math.abs(v[k] - v[k - 1]);
            }
            StringBuilder b = new StringBuilder(160);
            b.append(ts).append(',').append(idx).append(',').append(u16(f, 21)).append(',').append(u).append(',').append((int) u)
                    .append(',').append(v[0]).append(',').append(v[23]).append(',').append(mn).append(',').append(mx)
                    .append(',').append(num(mean, 1)).append(',').append(num(Math.sqrt(ss / 24.0), 1)).append(',').append(sum)
                    .append(',').append(num(st / 23.0, 1))
                    .append(',').append(num(Float.intBitsToFloat((int) u32(f, 75)), 4))
                    .append(',').append(u16(f, 79)).append(',').append(u8(f, 81)).append(',').append(u8(f, 82)).append(',').append(u8(f, 10));
            return b.toString();
        }

        /** v16 stored-ECG record: one row for the second. Lead-off block read as in section 21.4 of the decode log (n at 1534, I at 1535, Q at 1557). */
        static String v16(byte[] f) {
            long ts = u32(f, 15);
            int n = Math.min(u8(f, 1534), 11);
            double si = 0, sq = 0;
            for (int k = 0; k < n; k++) { si += u16(f, 1535 + 2 * k); sq += i16(f, 1557 + 2 * k); }
            int cnt = Math.min(u16(f, 32), 500);
            int contact = 0, fast = 0, hn = 0;
            double hs = 0;
            long p1 = 0, p2 = 0;
            boolean f1 = false, f2 = false;
            for (int k = 0; k < cnt; k++) {
                int o = 34 + 3 * k;
                int b0 = u8(f, o);
                boolean fr = (b0 & 0x40) != 0;
                if ((b0 & 0x80) != 0) contact++;
                if (fr) fast++;
                long raw = ((long) (b0 & 3) << 16) | ((long) u8(f, o + 1) << 8) | u8(f, o + 2);
                if ((raw & 0x20000L) != 0) raw -= 0x40000L;
                if (k >= 2 && !fr && !f1 && !f2) {
                    double d2 = raw - 2.0 * p1 + p2;
                    hs += d2 * d2;
                    hn++;
                }
                p2 = p1; f2 = f1;
                p1 = raw; f1 = fr;
            }
            StringBuilder b = new StringBuilder(140);
            b.append(ts).append(',').append(num(u16(f, 19) / 32768.0, 3)).append(',').append(u32(f, 11)).append(',').append(u8(f, 10))
                    .append(',').append(u8(f, 21)).append(',').append(u8(f, 22)).append(',').append(u8(f, 23)).append(',').append(u8(f, 24))
                    .append(',').append(u8(f, 25)).append(',').append(u8(f, 26)).append(',').append(u8(f, 27)).append(',').append(u8(f, 28))
                    .append(',').append(u16(f, 29)).append(',').append(n)
                    .append(',').append(n > 0 ? num(si / n, 1) : "").append(',').append(n > 0 ? num(sq / n, 1) : "")
                    .append(',').append(cnt > 0 ? num(contact / (double) cnt, 3) : "").append(',').append(cnt > 0 ? num(fast / (double) cnt, 3) : "")
                    .append(',').append(hn > 20 ? num(Math.sqrt(hs / hn / 6.0) * 0.0621, 1) : "");
            return b.toString();
        }

        private static String tok(String text, String key) {
            String t = " " + text + " ";
            int i = t.indexOf(" " + key + "=");
            if (i < 0) return "";
            int s = i + key.length() + 2;
            int e = t.indexOf(' ', s);
            return e < 0 ? t.substring(s) : t.substring(s, e);
        }

        /** One CSV row for a log line of the form "yyyy-MM-dd HH:mm:ss.SSS TAB ms TAB text", or null when the line is not wanted. */
        static String eventRow(String line, long sinceMs) {
            if (line == null) return null;
            int a = line.indexOf('\t');
            if (a < 0) return null;
            int b = line.indexOf('\t', a + 1);
            if (b < 0) return null;
            String text = line.substring(b + 1);
            boolean ev = text.startsWith("EVENT id=");
            boolean t1d = !ev && text.startsWith("RECORDING_COMPLETE raw=");
            if (!ev && !t1d) return null;
            long ms;
            try { ms = Long.parseLong(line.substring(a + 1, b)); } catch (Exception e) { return null; }
            if (ms < sinceMs) return null;
            StringBuilder o = new StringBuilder(140);
            if (t1d) {
                String raw = tok(text, "raw");
                o.append(ms).append(",0x1D,TIMER_0x1D,,,,,,").append(raw.length() / 2).append(',').append(raw);
                return o.toString();
            }
            int id;
            try { id = Integer.parseInt(tok(text, "id")); } catch (Exception e) { return null; }
            if (skipEvent(id)) return null;
            String payload = tok(text, "payload");
            o.append(ms).append(',').append(id).append(',').append(q(tok(text, "name"))).append(',').append(tok(text, "seq")).append(',')
                    .append(tok(text, "src")).append(',').append(tok(text, "ts")).append(',').append(tok(text, "sub")).append(',')
                    .append(tok(text, "phoneMinusStrapS")).append(',').append(payload.length() / 2).append(',').append(payload);
            return o.toString();
        }

        /** Runs of the record counter (bytes 11-14) against the strap clock: a new run starts when the offset idx - ts steps by more than 2 or the clock jumps by more than 10 minutes. */
        static final class IdxRuns {
            final StringBuilder out = new StringBuilder(IDX_HEADER).append('\n');
            int rows = 0;
            private boolean open;
            private long rStart, rEnd, rIdxFirst, rIdxLast, rOffFirst, rOffMin, rOffMax, rGap, rStep, lastOff, prevRunOffFirst;
            private int rN;
            private String rReason = "start";

            void add(long ts, long idx) {
                long off = idx - ts;
                if (!open) { begin(ts, idx, off, "start", 0, 0); return; }
                long gap = ts - rEnd;
                boolean g = gap > 600, s = Math.abs(off - lastOff) > 2;
                if (g || s) {
                    flush();
                    begin(ts, idx, off, g && s ? "gap+step" : g ? "gap" : "step", gap, off - lastOff);
                    return;
                }
                rEnd = ts; rN++; rIdxLast = idx; lastOff = off;
                if (off < rOffMin) rOffMin = off;
                if (off > rOffMax) rOffMax = off;
            }

            private void begin(long ts, long idx, long off, String reason, long gap, long step) {
                open = true;
                rStart = rEnd = ts; rN = 1; rIdxFirst = rIdxLast = idx; rOffFirst = rOffMin = rOffMax = lastOff = off;
                rReason = reason; rGap = gap; rStep = step;
            }

            private void flush() {
                if (!open) return;
                out.append(rStart).append(',').append(rEnd).append(',').append(rN).append(',').append(rEnd - rStart + 1 - rN).append(',')
                        .append(rIdxFirst).append(',').append(rIdxLast).append(',').append(rOffFirst).append(',').append(rOffMin).append(',')
                        .append(rOffMax).append(',').append(rReason).append(',').append(rGap).append(',').append(rStep).append('\n');
                rows++;
            }

            String finish() { flush(); open = false; return out.toString(); }
        }
    }

    // ------------------------------------------------------------------ the export
    /** days = 0 means everything on the phone; otherwise the history files and logs of the last [days] days. Runs on the calling thread (call from a worker). */
    // ------------------------------------------------------------------ real R16 frames for upstream (0.13.1)
    /**
     * Cuts a handful of REAL stored-ECG frames out of the history files for a bug report or a decoder test, byte for byte: real strap clock, real
     * counter, original CRC32, nothing rebased. Picks the newest reading that reached a normal verdict: the zero-sample record that opens it, the first
     * record with samples, three consecutive quality-3 seconds, the second before the verdict and the verdict record, plus one record of each lead-off
     * layout (10 and 11 slots). An R16 frame carries no serial, MAC or account data; the samples are the wearer's own ECG. Pure Java, tested off the phone.
     */
    static final class Fixture {
        private Fixture() {}
        static int u8(byte[] f, int o) { return f[o] & 0xff; }
        static int u16(byte[] f, int o) { return (f[o] & 0xff) | ((f[o + 1] & 0xff) << 8); }
        static long u32(byte[] f, int o) { return (f[o] & 0xffL) | ((f[o + 1] & 0xffL) << 8) | ((f[o + 2] & 0xffL) << 16) | ((f[o + 3] & 0xffL) << 24); }
        static boolean has(byte[] f) { return u16(f, 32) > 0; }
        static String hex(byte[] f, int from, int to) { StringBuilder s = new StringBuilder((to - from) * 2); for (int i = from; i < to; i++) s.append(String.format("%02x", f[i] & 0xff)); return s.toString(); }
        static String utc(long t) { SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US); d.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); return d.format(new Date(t * 1000L)) + " UTC"; }
        static int stretch(List<Long> run, TreeMap<Long, byte[]> fr) {
            for (int i = 0; i + 2 < run.size(); i++) {
                boolean ok = true;
                for (int j = i; j < i + 3 && ok; j++) { byte[] f = fr.get(run.get(j)); ok = u8(f, 21) == 3 && u16(f, 32) == 500; }
                if (ok) return i;
            }
            return -1;
        }
        static boolean verdict(List<Long> run, TreeMap<Long, byte[]> fr) {
            for (long k : run) { byte[] f = fr.get(k); if (u8(f, 24) == 2 && u8(f, 23) == 1) return true; }
            return false;
        }
        /** {json, hex text, one-line summary}; json and hex are null when no suitable reading exists. */
        static String[] build(TreeMap<Long, byte[]> fr) {
            if (fr.isEmpty()) return new String[] {null, null, "no R16 records found"};
            List<List<Long>> runs = new ArrayList<>();
            List<Long> cur = new ArrayList<>();
            for (long k : fr.keySet()) {
                if (!cur.isEmpty() && k - cur.get(cur.size() - 1) > 2) { runs.add(cur); cur = new ArrayList<>(); }
                cur.add(k);
            }
            runs.add(cur);
            List<Long> pickRun = null, anyRun = null;
            for (List<Long> r : runs) { if (stretch(r, fr) < 0) continue; anyRun = r; if (verdict(r, fr)) pickRun = r; }
            if (pickRun == null) pickRun = anyRun;
            if (pickRun == null) return new String[] {null, null, "no reading with three consecutive quality-3 seconds"};
            LinkedHashMap<Long, String> pick = new LinkedHashMap<>();
            Long open = null, first = null;
            for (long k : pickRun) { if (!has(fr.get(k))) open = k; else if (first == null) first = k; }
            if (open != null && (first == null || open < first)) pick.put(open, "zero-sample record that opens the reading (declared count 0)");
            if (first != null && !pick.containsKey(first)) pick.put(first, "first record with samples");
            int g = stretch(pickRun, fr);
            for (int j = g; j < g + 3; j++) if (!pick.containsKey(pickRun.get(j))) pick.put(pickRun.get(j), "quality 3, 500 samples");
            int vi = -1;
            for (int i = 0; i < pickRun.size(); i++) if (u8(fr.get(pickRun.get(i)), 24) == 2) { vi = i; break; }
            if (vi > 0 && !pick.containsKey(pickRun.get(vi - 1))) pick.put(pickRun.get(vi - 1), "second before the verdict (state 1)");
            if (vi >= 0 && !pick.containsKey(pickRun.get(vi))) pick.put(pickRun.get(vi), "first state-2 record: the verdict (result byte 23, reason mask byte 26)");
            for (int ln = 10; ln <= 11; ln++) {
                Long k = null;
                for (long x : pickRun) { byte[] f = fr.get(x); if (u8(f, 1534) == ln && has(f)) { k = x; break; } }
                if (k == null) for (Long x : fr.descendingKeySet()) { byte[] f = fr.get(x); if (u8(f, 1534) == ln && has(f)) { k = x; break; } }
                if (k != null && !pick.containsKey(k)) pick.put(k, "lead-off count " + ln + " (I words 1535-1556, Q words 1557-1578)");
            }
            TreeMap<Long, String> sorted = new TreeMap<>(pick);
            StringBuilder j = new StringBuilder(80000), h = new StringBuilder(30000);
            j.append("{\n \"description\": \"Real, unedited R16 (layout 16, 1584 B) frames from one WHOOP MG, hardware WS50_r00, firmware 50.40.1.0. Strap clock and counter as recorded, original CRC32. ")
             .append("Sample = signed 18-bit, big-endian in the 3-byte slot from frame byte 34 ((b0&3)<<16|b1<<8|b2); b0 bit 7 = contact, bit 6 = fast recovery. Lead-off block bytes 1534-1579.\",\n \"frames\": [\n");
            boolean firstFrame = true;
            for (Map.Entry<Long, String> e : sorted.entrySet()) {
                byte[] f = fr.get(e.getKey());
                int n = Math.min(u16(f, 32), 500), contact = 0, fast = 0;
                int[] s = new int[n];
                for (int k = 0; k < n; k++) {
                    int b0 = u8(f, 34 + 3 * k), v = ((b0 & 3) << 16) | (u8(f, 35 + 3 * k) << 8) | u8(f, 36 + 3 * k);
                    if (v >= 131072) v -= 262144;
                    s[k] = v; contact += (b0 >> 7) & 1; fast += (b0 >> 6) & 1;
                }
                if (!firstFrame) j.append(",\n");
                firstFrame = false;
                j.append("  {\"label\": \"").append(e.getValue()).append("\", \"strap_time_utc\": \"").append(utc(u32(f, 15))).append("\", \"frame_len\": ").append(f.length)
                 .append(", \"crc32_ok\": true,\n   \"inner_hex_frame_bytes_8_to_1579\": \"").append(hex(f, 8, f.length - 4)).append("\",\n   \"frame_hex\": \"").append(hex(f, 0, f.length)).append("\",\n")
                 .append("   \"decoded\": {\"counter\": ").append(u32(f, 11)).append(", \"strap_unix\": ").append(u32(f, 15)).append(", \"sub_second_q15\": ").append(u16(f, 19))
                 .append(", \"byte10\": ").append(u8(f, 10)).append(", \"quality\": ").append(u8(f, 21)).append(", \"flags22\": ").append(u8(f, 22)).append(", \"result\": ").append(u8(f, 23))
                 .append(", \"state\": ").append(u8(f, 24)).append(", \"progress\": ").append(u8(f, 25)).append(", \"reason_mask\": ").append(u8(f, 26)).append(", \"avg_hr\": ").append(u8(f, 27))
                 .append(", \"live_hr\": ").append(u8(f, 28)).append(", \"hrv_u16\": ").append(u16(f, 29)).append(", \"byte31\": ").append(u8(f, 31)).append(", \"declared_count\": ").append(u16(f, 32))
                 .append(", \"leadoff_count\": ").append(u8(f, 1534)).append(", \"leadoff_I\": [");
                for (int k = 0; k < 11; k++) j.append(k > 0 ? ", " : "").append(u16(f, 1535 + 2 * k));
                j.append("], \"leadoff_Q\": [");
                for (int k = 0; k < 11; k++) j.append(k > 0 ? ", " : "").append((short) u16(f, 1557 + 2 * k));
                j.append("], \"contact_flag_samples\": ").append(contact).append(", \"fast_recovery_flag_samples\": ").append(fast).append(", \"samples\": [");
                for (int k = 0; k < n; k++) j.append(k > 0 ? "," : "").append(s[k]);
                j.append("]}}");
                h.append("# ").append(e.getValue()).append(" | strap ").append(utc(u32(f, 15))).append(" | counter ").append(u32(f, 11)).append(" | quality ").append(u8(f, 21)).append(" | state ").append(u8(f, 24))
                 .append(" | result ").append(u8(f, 23)).append(" | count ").append(u16(f, 32)).append(" | lead-off n ").append(u8(f, 1534)).append('\n').append(hex(f, 0, f.length)).append('\n');
            }
            j.append("\n ]\n}\n");
            return new String[] {j.toString(), h.toString(), sorted.size() + " frames from the reading starting " + utc(pickRun.get(0)) + " (" + pickRun.size() + " records)"};
        }
    }

    static Result run(Context c, int days) { return run(c, days, false); }

    static Result run(Context c, int days, boolean rawHistory) {
        Result res = new Result();
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis() / 1000L;
        long since = days <= 0 ? 0L : now - days * 86400L;
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder info = new StringBuilder("key,value\n");
        SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        info.append("exported_at,").append(Rows.q(full.format(new Date()))).append('\n');
        info.append("exported_unix,").append(now).append('\n');
        info.append("window_days,").append(days <= 0 ? "all" : String.valueOf(days)).append('\n');
        try {
            info.append("app_version,").append(app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName).append('\n');
        } catch (Throwable ignored) { }
        info.append("phone,").append(Rows.q(Build.MANUFACTURER + " " + Build.MODEL + " android " + Build.VERSION.RELEASE)).append('\n');
        SQLiteDatabase db = null;
        try { db = DayLog.db(app); } catch (Throwable t) { info.append("error_db,").append(Rows.q(String.valueOf(t))).append('\n'); }

        // ---- ECG readings saved by the app (same columns as mg_ecg_readings.csv)
        try {
            List<HealthModel.Reading> rs = HealthModel.readings(app);
            Collections.sort(rs, new Comparator<HealthModel.Reading>() {
                @Override public int compare(HealthModel.Reading a, HealthModel.Reading b) { return Long.compare(a.timeMs, b.timeMs); }
            });
            SimpleDateFormat mm = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            StringBuilder b = new StringBuilder(Rows.READINGS_HEADER).append('\n');
            for (HealthModel.Reading r : rs) {
                b.append(mm.format(new Date(r.timeMs))).append(',').append(r.timeMs / 1000L).append(',').append(Rows.q(r.hr)).append(',')
                        .append(Rows.q(r.hrv)).append(',').append(Rows.q(r.rhythm)).append(',').append(Rows.q(r.grade)).append(",app\n");
            }
            files.put("mg_ecg_readings.csv", ExportCatalog.filterSince(b.toString(), 1, 1, since));
            info.append("readings,").append(rs.size()).append('\n');
        } catch (Throwable t) { info.append("error_readings,").append(Rows.q(String.valueOf(t))).append('\n'); }

        if (db != null) {
            // ---- nights (same columns as mg_nights.csv; logged_h = hours of per-second records in the analysed window)
            try {
                files.put("mg_nights.csv", ExportCatalog.filterSince(nights(db, info), 4, 1, since));
            } catch (Throwable t) { info.append("error_nights,").append(Rows.q(String.valueOf(t))).append('\n'); }
            // ---- record counter against the strap clock, from the day-logger database
            try {
                Rows.IdxRuns ir = new Rows.IdxRuns();
                Cursor q = db.rawQuery("SELECT ts, substr(rec,12,4) FROM r18 ORDER BY ts", null);
                long n = 0;
                try {
                    while (q.moveToNext()) {
                        byte[] b = q.getBlob(1);
                        if (b == null || b.length < 4) continue;
                        long idx = (b[0] & 0xffL) | ((b[1] & 0xffL) << 8) | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
                        ir.add(q.getLong(0), idx);
                        n++;
                    }
                } finally { q.close(); }
                files.put("mg_idx_runs.csv", ExportCatalog.filterSince(ir.finish(), 1, 1, since));
                files.put("mg_r18_minutes.csv", r18Minutes(db, since));
                info.append("r18_records,").append(n).append('\n').append("idx_runs,").append(ir.rows).append('\n');
                q = db.rawQuery("SELECT MIN(ts), MAX(ts) FROM r18", null);
                try { if (q.moveToFirst() && !q.isNull(0)) info.append("r18_first_ts,").append(q.getLong(0)).append('\n').append("r18_last_ts,").append(q.getLong(1)).append('\n'); } finally { q.close(); }
                q = db.rawQuery("SELECT ver, COUNT(*) FROM other_rec GROUP BY ver ORDER BY ver", null);
                try { while (q.moveToNext()) info.append("other_rec_v").append(q.getInt(0)).append(',').append(q.getLong(1)).append('\n'); } finally { q.close(); }
            } catch (Throwable t) { info.append("error_idx,").append(Rows.q(String.valueOf(t))).append('\n'); }
        }

        // ---- v16 (stored ECG) and v26 (optical pulse) records from the raw history files
        try {
            scanBins(app, db, since, now, files, info);
        } catch (Throwable t) { info.append("error_bins,").append(Rows.q(String.valueOf(t))).append('\n'); }

        // ---- the link-related lines of the app log (0.15.0), so a connection question can be answered from the export
        try { files.put("mg_app_log_key_lines.txt", logKeyLines(app, since)); } catch (Throwable t) { info.append("error_log_lines,").append(Rows.q(String.valueOf(t))).append('\n'); }

        // ---- strap events and 0x1D timer messages from the app log
        try {
            events(app, since, files, info);
        } catch (Throwable t) { info.append("error_events,").append(Rows.q(String.valueOf(t))).append('\n'); }

        files.put("mg_export_info.csv", info.toString());
        String ver = "?";
        try { ver = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Throwable ignored) { }
        long newest = 0L;
        try {
            if (db != null) { Cursor nq = db.rawQuery("SELECT MAX(ts) FROM r18", null); try { if (nq.moveToFirst()) newest = nq.getLong(0); } finally { nq.close(); } }
        } catch (Throwable ignored) { }
        return ExportCatalog.commit(app, files, System.currentTimeMillis(), days, since, ver, Build.MANUFACTURER + " " + Build.MODEL + " android " + Build.VERSION.RELEASE, newest,
                rawHistory ? rawBins(app, since) : null);
    }

    static final String[] LOG_TOKENS = {"HEADLESS", "LINK_WATCHDOG", "LINK_HOST", "MAIN_DESTROY", "DAYLOG_", "BT_WATCHDOG", "BT_STUCK", "EASY_CONNECT", "RECONNECT", "ERROR", "Exception", "REALTIME_HR", "PULL_"};

    /** Pure: is this log line about the strap link? */
    static boolean isLinkLine(String line) {
        for (String t : LOG_TOKENS) if (line.indexOf(t) >= 0) return true;
        return false;
    }

    /** The newest 3,000 link-related lines of the app's text logs in the period, oldest first. */
    static String logKeyLines(Context app, long since) throws Exception {
        File dir = app.getExternalFilesDir(null);
        File[] fs = dir == null ? null : dir.listFiles();
        List<File> logs = new ArrayList<>();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("labrador_log_") || !n.endsWith(".txt")) continue;
            if (since > 0 && f.lastModified() < since * 1000L) continue;
            logs.add(f);
        }
        Collections.sort(logs, new Comparator<File>() { @Override public int compare(File a, File b) { return b.getName().compareTo(a.getName()); } });   // newest first
        ArrayList<String> keep = new ArrayList<>();
        for (File f : logs) {
            ArrayList<String> mine = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16);
            try { String line; while ((line = r.readLine()) != null) if (isLinkLine(line)) mine.add(line.length() > 400 ? line.substring(0, 400) : line); } finally { r.close(); }
            for (int i = mine.size() - 1; i >= 0 && keep.size() < 3000; i--) keep.add(mine.get(i));
            if (keep.size() >= 3000) break;
        }
        StringBuilder out = new StringBuilder("# link-related lines of the app log, oldest first (" + keep.size() + " lines)\n");
        for (int i = keep.size() - 1; i >= 0; i--) out.append(keep.get(i)).append('\n');
        return out.toString();
    }

    /** The raw history files (historical_bin_*.bin) in the app folder whose stamp falls in the period. */
    static List<File> rawBins(Context app, long since) {
        List<File> out = new ArrayList<>();
        File dir = app.getExternalFilesDir(null);
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("historical_bin_") || !n.endsWith(".bin")) continue;
            long stamp = 0;
            try { stamp = Long.parseLong(n.substring("historical_bin_".length(), n.length() - 4)); } catch (Exception ignored) { }
            if (since > 0 && stamp > 0 && stamp < since) continue;
            out.add(f);
        }
        Collections.sort(out, new Comparator<File>() { @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); } });
        return out;
    }

    // ------------------------------------------------------------------ per-minute summary of the per-second records (0.14.0)
    static final String MIN_HEADER = "minute_unix,minute_local,seconds,hr_mean,hr_min,hr_max,beat_intervals,skin_c_mean,cell_c_mean,steps_end,activity_max,sleep_state_mode,spo2_percent_max,b82_status_max,pulse_lock_lost_pct,optical_quality_mean,accel_step_max_g";

    /** One minute of per-second records. Pure apart from the formatter; tested on the logged R18 fixture. */
    static final class Minutes {
        private final SimpleDateFormat lf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        long minute = -1;
        int n, hrN, hrMin, hrMax, rr, act, spo2, stat, lost, opt, skinN, steps;
        double hrSum, skinSum, cellSum, acc;
        int[] st = new int[4];

        void reset(long m) { minute = m; n = 0; hrN = 0; hrMin = 999; hrMax = 0; rr = 0; act = 0; spo2 = 0; stat = 0; lost = 0; opt = 0; skinN = 0; steps = 0; hrSum = 0; skinSum = 0; cellSum = 0; acc = 0; st = new int[4]; }

        void add(byte[] f) {
            int hr = R18Codec.hr(f);
            if (hr > 0) { hrSum += hr; hrN++; hrMin = Math.min(hrMin, hr); hrMax = Math.max(hrMax, hr); }
            rr += Math.min(R18Codec.u8(f, 23), 4);
            if ((byte) R18Codec.u8(f, 108) == -128) lost++;
            opt += R18Codec.u8(f, 40);
            if (R18Codec.skinOk(f)) { skinSum += R18Codec.skinC(f); skinN++; }
            cellSum += R18Codec.i16(f, 69) / 10.0;
            steps = R18Codec.steps(f);
            act = Math.max(act, R18Codec.u8(f, 63));
            st[R18Codec.state(f)]++;
            int b82 = R18Codec.u8(f, 82);
            if (b82 >= 80 && b82 <= 100) spo2 = Math.max(spo2, b82); else if (b82 > 0) stat = Math.max(stat, b82);   // 80-100 = a percentage; anything else is a status code
            acc = Math.max(acc, R18Codec.f32(f, 41));
            n++;
        }

        String row() {
            if (n == 0) return "";
            int mode = 0;
            for (int i = 1; i < 4; i++) if (st[i] > st[mode]) mode = i;
            Locale L = Locale.US;
            return minute * 60L + "," + lf.format(new Date(minute * 60000L)) + "," + n + ","
                    + (hrN > 0 ? String.format(L, "%.1f", hrSum / hrN) : "") + "," + (hrN > 0 ? hrMin : "") + "," + (hrN > 0 ? hrMax : "") + "," + rr + ","
                    + (skinN > 0 ? String.format(L, "%.2f", skinSum / skinN) : "") + "," + String.format(L, "%.1f", cellSum / n) + "," + steps + "," + act + "," + mode + "," + (spo2 > 0 ? spo2 : "") + "," + (stat > 0 ? stat : "") + ","
                    + String.format(L, "%.0f", 100.0 * lost / n) + "," + String.format(L, "%.0f", opt / (double) n) + "," + String.format(L, "%.4f", acc) + "\n";
        }
    }

    static String r18Minutes(SQLiteDatabase db, long since) {
        StringBuilder out = new StringBuilder(MIN_HEADER).append('\n');
        Minutes m = new Minutes();
        Cursor q = db.rawQuery("SELECT rec FROM r18 WHERE ts >= ? ORDER BY ts", new String[] {String.valueOf(Math.max(0L, since))});
        try {
            while (q.moveToNext()) {
                byte[] f = q.getBlob(0);
                if (f == null || !R18Codec.isR18(f)) continue;
                long min = R18Codec.ts(f) / 60L;
                if (min != m.minute) { out.append(m.row()); m.reset(min); }
                m.add(f);
            }
        } finally { q.close(); }
        out.append(m.row());
        return out.toString();
    }


    private static String nights(SQLiteDatabase db, StringBuilder info) {
        Cursor q = db.rawQuery("SELECT ts FROM r18 WHERE state = 2 ORDER BY ts", null);
        List<Long> tl = new ArrayList<>();
        try { while (q.moveToNext()) tl.add(q.getLong(0)); } finally { q.close(); }
        long[] st = new long[tl.size()];
        for (int i = 0; i < st.length; i++) st[i] = tl.get(i);
        List<long[]> cl = SleepNight.clusters(st);
        StringBuilder b = new StringBuilder(Rows.NIGHTS_HEADER).append('\n');
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US), mm = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        int rows = 0;
        for (long[] cc : cl) {
            List<byte[]> recs = recsBetween(db, cc[0] - 7200L, cc[1] + 600L);
            SleepNight.Night n = SleepNight.analyse(recs);
            if (n == null) continue;
            List<byte[]> nr = recsBetween(db, n.bedTs - 600L, n.endTs + 600L);
            Spo2.Summary sp = Spo2.summarize(Spo2.windows(nr));
            HealthModel.Data d = new HealthModel.Data();
            HealthModel.stagesFor(nr, n.bedTs, n.endTs, d);
            int solid = 0;
            for (boolean s : d.binSure) if (s) solid++;
            b.append(day.format(new Date(n.endTs * 1000L))).append(',').append(mm.format(new Date(n.bedTs * 1000L))).append(',')
                    .append(mm.format(new Date(n.endTs * 1000L))).append(',').append(n.bedTs).append(',').append(n.endTs).append(',')
                    .append(Rows.num(n.tstH, 2)).append(',').append(Rows.num(n.tibH, 2)).append(',').append(Rows.num(n.effPct, 0)).append(',')
                    .append(Rows.num(n.hrLow5, 1)).append(',').append(Rows.num(n.hrMean, 1)).append(',').append(Rows.num(n.rmssd, 1)).append(',')
                    .append(Rows.num(n.skinMedian, 2)).append(',').append(Rows.num(n.brMedian, 1)).append(',').append(n.restless.size()).append(',')
                    .append(sp == null ? "" : String.valueOf(sp.windows)).append(',').append(sp == null || sp.median < 0 ? "" : String.valueOf(sp.median)).append(',')
                    .append(sp == null || sp.min < 0 ? "" : String.valueOf(sp.min)).append(',').append(sp == null || sp.max < 0 ? "" : String.valueOf(sp.max)).append(',')
                    .append(Math.round(d.stageSecs[HealthModel.WAKE] / 60.0)).append(',').append(Math.round(d.stageSecs[HealthModel.LIGHT] / 60.0)).append(',')
                    .append(Math.round(d.stageSecs[HealthModel.DEEP] / 60.0)).append(',').append(Math.round(d.stageSecs[HealthModel.REM] / 60.0)).append(',')
                    .append(solid).append(',').append(d.binSure.length).append(',').append(Rows.num(recs.size() / 3600.0, 2)).append(",app\n");
            rows++;
        }
        info.append("nights,").append(rows).append('\n');
        return b.toString();
    }

    private static List<byte[]> recsBetween(SQLiteDatabase db, long from, long to) {
        List<byte[]> recs = new ArrayList<>();
        Cursor r = db.rawQuery("SELECT rec FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts", new String[] {String.valueOf(from), String.valueOf(to)});
        try { while (r.moveToNext()) recs.add(r.getBlob(0)); } finally { r.close(); }
        return recs;
    }

    private static void scanBins(Context app, SQLiteDatabase db, final long since, final long now, Map<String, String> files, StringBuilder info) throws Exception {
        File dir = app.getExternalFilesDir(null);
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        List<File> bins = new ArrayList<>();
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("historical_bin_") || !n.endsWith(".bin")) continue;
            long stamp = 0;
            try { stamp = Long.parseLong(n.substring("historical_bin_".length(), n.length() - 4)); } catch (Exception ignored) { }
            if (since > 0 && stamp > 0 && stamp < since) continue;
            bins.add(f);
        }
        Collections.sort(bins, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        final TreeMap<Long, String> v16 = new TreeMap<>();
        final TreeMap<Long, String> v26 = new TreeMap<>();
        final TreeMap<Long, byte[]> v16raw = new TreeMap<>();   // newest 4,000 stored-ECG frames, for the real-frame fixture
        for (File f : bins) {
            byte[] data = Files.readAllBytes(f.toPath());
            R18Codec.scan(data, new R18Codec.Visitor() {
                @Override public void frame(byte[] fr) {
                    if (R18Codec.u8(fr, 8) != 47) return;
                    long ts = R18Codec.ts(fr);
                    if (ts < 1700000000L || ts > now + 86400L || (since > 0 && ts < since)) return;
                    if (Rows.isV16(fr)) {
                        if (!v16.containsKey(ts)) v16.put(ts, Rows.v16(fr));
                        if (!v16raw.containsKey(ts)) { v16raw.put(ts, fr.clone()); if (v16raw.size() > 4000) v16raw.remove(v16raw.firstKey()); }
                    } else if (Rows.isV26(fr)) {
                        long key = ts * 65536L + Rows.u16(fr, 21);
                        if (!v26.containsKey(key)) v26.put(key, Rows.v26(fr));
                    }
                }
            });
        }
        info.append("history_files_read,").append(bins.size()).append('\n');
        StringBuilder b16 = new StringBuilder(Rows.V16_HEADER).append('\n');
        for (String s : v16.values()) b16.append(s).append('\n');
        files.put("mg_ecg_status.csv", b16.toString());
        info.append("v16_rows,").append(v16.size()).append('\n');
        try {
            String[] fxo = Fixture.build(v16raw);
            if (fxo[0] != null) { files.put("mg_r16_fixture.json", fxo[0]); files.put("mg_r16_fixture_hex.txt", fxo[1]); }
            info.append("r16_fixture,").append(Rows.q(fxo[2])).append('\n');
        } catch (Throwable t) { info.append("error_r16_fixture,").append(Rows.q(String.valueOf(t))).append('\n'); }

        Map<Long, long[]> r18 = new HashMap<>();
        if (db != null && !v26.isEmpty()) {
            long lo = v26.firstKey() / 65536L, hi = v26.lastKey() / 65536L;
            Cursor q = db.rawQuery("SELECT ts, hr, substr(rec,107,2), substr(rec,109,2) FROM r18 WHERE ts BETWEEN ? AND ?",
                    new String[] {String.valueOf(lo), String.valueOf(hi)});
            try {
                while (q.moveToNext()) {
                    byte[] d1 = q.getBlob(2), d2 = q.getBlob(3);
                    long det = d1 != null && d1.length >= 2 ? ((d1[0] & 0xff) | ((d1[1] & 0xff) << 8)) : -1;
                    long p8 = d2 != null && d2.length >= 2 ? d2[0] : -999, p9 = d2 != null && d2.length >= 2 ? d2[1] : -999;
                    r18.put(q.getLong(0), new long[] {q.getLong(1), det, p8, p9});
                }
            } finally { q.close(); }
        }
        StringBuilder b26 = new StringBuilder(Rows.V26_HEADER).append('\n');
        for (Map.Entry<Long, String> e : v26.entrySet()) {
            long[] r = r18.get(e.getKey() / 65536L);
            b26.append(e.getValue());
            if (r == null) b26.append(",,,,\n");
            else b26.append(',').append(r[0]).append(',').append(r[1]).append(',').append(r[2]).append(',').append(r[3]).append('\n');
        }
        files.put("mg_v26.csv", b26.toString());
        info.append("v26_rows,").append(v26.size()).append('\n');
    }

    private static void events(Context app, long since, Map<String, String> files, StringBuilder info) throws Exception {
        File dir = app.getExternalFilesDir(null);
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        List<File> logs = new ArrayList<>();
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("labrador_log_") || !n.endsWith(".txt")) continue;
            if (since > 0 && f.lastModified() < since * 1000L) continue;
            logs.add(f);
        }
        Collections.sort(logs, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        StringBuilder b = new StringBuilder(Rows.EVENT_HEADER).append('\n');
        int rows = 0;
        for (File f : logs) {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 1 << 16);
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.indexOf("EVENT id=") < 0 && line.indexOf("RECORDING_COMPLETE raw=") < 0) continue;
                    String row = Rows.eventRow(line, since * 1000L);
                    if (row == null) continue;
                    b.append(row).append('\n');
                    rows++;
                }
            } finally { r.close(); }
        }
        files.put("mg_strap_events.csv", b.toString());
        info.append("log_files_read,").append(logs.size()).append('\n').append("event_rows,").append(rows).append('\n');
    }

    // ------------------------------------------------------------------ writing into Download/MGsummary
    private static Uri writeDownload(Context c, String name, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            String rel = "Download/" + FOLDER + "/";
            try {
                cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[] {rel, name});
            } catch (Throwable ignore) { }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("could not create " + name);
            OutputStream os = cr.openOutputStream(u);
            if (os == null) throw new Exception("could not open " + name);
            try { os.write(bytes); } finally { os.close(); }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, done, null, null);
            return u;
        }
        File dir = c.getExternalFilesDir(null);
        if (dir == null) throw new Exception("no storage folder");
        java.io.FileOutputStream os = new java.io.FileOutputStream(new File(dir, name));
        try { os.write(bytes); } finally { os.close(); }
        return null;
    }
}

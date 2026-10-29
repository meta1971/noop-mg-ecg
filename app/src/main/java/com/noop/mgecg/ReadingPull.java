package com.noop.mgecg;

// FILE VERSION 0.25.0 (29 Oct 2026): "Full pull" of ONE reading: a small zip with everything the app holds for that reading (live raw frames, the stored 500 Hz, 100 Hz, per-second, 50 Hz optical, 100 Hz motion and pulse records, events, log lines, H10 files, the report), with readable CSV copies. Research only.

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ReadingPull {
    private ReadingPull() {}
    static final String FOLDER = "MGpull";
    private static volatile boolean busy;
    private static final double UV = 0.0621;

    private static int s24(byte[] f, int o) { int r = (f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8) | ((f[o + 2] & 0xFF) << 16); return (r & 0x800000) != 0 ? r - 0x1000000 : r; }
    private static float f32(byte[] f, int o) { return Float.intBitsToFloat((int) ProAnalysis.u32(f, o)); }
    private static short s16(byte[] f, int o) { return (short) ((f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8)); }
    private static double ft(byte[] f) { return ProAnalysis.u32(f, 15) + ProAnalysis.u16(f, 19) / 32768.0; }
    private static String hex(byte[] f) { StringBuilder sb = new StringBuilder(f.length * 2); for (byte b : f) sb.append(String.format("%02X", b & 0xFF)); return sb.toString(); }

    /** Builds every file of the pull (name to bytes). Pure Java: also used by the off-phone test. */
    static LinkedHashMap<String, byte[]> build(File dir, File html, String build) throws Exception {
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
        long unix = ProReportMaker.unixOf(html); File bin = ProReportMaker.findBin(dir, unix);
        List<byte[]> live16 = new ArrayList<>(), live17 = new ArrayList<>();
        if (bin != null && bin.length() < 80L * 1024 * 1024) { byte[] b = Files.readAllBytes(bin.toPath()); for (byte[] f : ProAnalysis.frames(b)) { if (!ProAnalysis.crcOk(f)) continue; if (f.length == 1584) live16.add(f); else if (f.length == 240) live17.add(f); } out.put("live_raw_" + bin.getName().substring(17), b); }
        double t0 = live16.isEmpty() ? unix : ProAnalysis.u32(live16.get(0), 15), t1 = live16.isEmpty() ? unix + 120 : ProAnalysis.u32(live16.get(live16.size() - 1), 15);
        ReadingFrames.Sets S = ReadingFrames.collect(dir, t0, t1);
        StringBuilder man = new StringBuilder();
        man.append("Full pull of one ECG reading (WHOOP MG, NOOP-MG-ECG app ").append(build).append(")\nreading start (strap time, unix): ").append((long) t0).append("\nreading end: ").append((long) t1).append("\nlive frames: R16 ").append(live16.size()).append(", R17 ").append(live17.size()).append("\nstored in the history files (").append(S.files).append(" files scanned): R16 ").append(S.r16.size()).append(", R17 ").append(S.r17.size()).append(", R18 ").append(S.r18.size()).append(", R20 ").append(S.r20.size()).append(", R21 ").append(S.r21.size()).append(", R26 ").append(S.r26.size()).append("\nThe strap serial is masked in the text files. unknown_frames_*.hex holds RAW device-info frames that can contain the strap serial and token: never post that file publicly. Research use only.\n");
        out.put("manifest.txt", man.toString().getBytes("UTF-8"));
        out.put("README.txt", README.getBytes("UTF-8"));
        // ECG 500 Hz live
        StringBuilder sb = new StringBuilder("unix_time_s,sample,microvolts,lead_on,fast_recovery,rail,quality\n");
        for (byte[] f : live16) { double t = ft(f); int n = ProAnalysis.u16(f, 32); int q = f[21] & 0xFF; for (int k = 0; k < n && k < 500; k++) { int b0 = f[34 + 3 * k] & 0xFF; int r = ((b0 & 3) << 16) | ((f[35 + 3 * k] & 0xFF) << 8) | (f[36 + 3 * k] & 0xFF); if ((r & 0x20000) != 0) r -= 0x40000; sb.append(String.format(Locale.UK, "%.4f,%d,%.2f,%d,%d,%d,%d\n", t + k / 500.0, k, r * UV, (b0 & 0x80) != 0 ? 1 : 0, (b0 & 0x40) != 0 ? 1 : 0, Math.abs(r) >= 126900 ? 1 : 0, q)); } }
        out.put("ecg_500hz_live.csv", sb.toString().getBytes("UTF-8"));
        // R17 100 Hz filtered live
        sb = new StringBuilder("unix_time_s,sample,microvolts_filtered\n");
        for (byte[] f : live17) { double t = ft(f); int n = ProAnalysis.u16(f, 32); for (int k = 0; k < n && k < 100; k++) sb.append(String.format(Locale.UK, "%.4f,%d,%d\n", t + k / 100.0, k, s16(f, 34 + 2 * k))); }
        out.put("ecg_100hz_filtered_live.csv", sb.toString().getBytes("UTF-8"));
        // per-second records
        sb = new StringBuilder("unix_time_s,heart_rate,interval_count,interval1_ms,interval2_ms,interval3_ms,interval4_ms,accel_step_g,gravity_x,gravity_y,gravity_z,steps_counter,optical_quality,skin_c,activity_class,sleep_state,byte33,byte36\n");
        for (byte[] f : S.r18) { int n = f[23] & 0xFF; sb.append(String.format(Locale.UK, "%d,%d,%d,%d,%d,%d,%d,%.5f,%.4f,%.4f,%.4f,%d,%d,%.2f,%d,%d,%d,%d\n", ProAnalysis.u32(f, 15), f[22] & 0xFF, n, n > 0 ? ProAnalysis.u16(f, 24) : 0, n > 1 ? ProAnalysis.u16(f, 26) : 0, n > 2 ? ProAnalysis.u16(f, 28) : 0, n > 3 ? ProAnalysis.u16(f, 30) : 0, f32(f, 41), f32(f, 45), f32(f, 49), f32(f, 53), ProAnalysis.u16(f, 57), f[40] & 0xFF, ProAnalysis.u16(f, 73) / 100.0, f[63] & 0xFF, (f[81] >> 4) & 3, f[33] & 0xFF, f[36] & 0xFF)); }
        out.put("per_second_r18.csv", sb.toString().getBytes("UTF-8"));
        // optical 50 Hz: five blocks, two slots
        sb = new StringBuilder("unix_time_s,block,slot,sample,value,led_driver_byte,led_current_mA\n");
        for (byte[] f : S.r20) { double t = ft(f); for (int blk = 0; blk < 5; blk++) { int o = 26 + 422 * blk; int n = f[o] & 0xFF; if (n < 1 || n > 50) continue; int cur = ProAnalysis.u16(f, o + 2); for (int slot = 0; slot < 2; slot++) for (int k = 0; k < n; k++) sb.append(String.format(Locale.UK, "%.4f,%d,%s,%d,%d,%d,%.2f\n", t + k / 50.0, blk, slot == 0 ? "A" : "B", k, s24(f, o + 21 + 200 * slot + 4 * k), f[o + 1] & 0xFF, cur * 0.01)); } }
        out.put("optical_50hz_r20.csv", sb.toString().getBytes("UTF-8"));
        // motion 100 Hz
        sb = new StringBuilder("unix_time_s,sample,accel_x_g,accel_y_g,accel_z_g,gyro_x_dps,gyro_y_dps,gyro_z_dps\n");
        for (byte[] f : S.r21) { double t = ft(f); for (int k = 0; k < 100; k++) sb.append(String.format(Locale.UK, "%.4f,%d,%.5f,%.5f,%.5f,%.3f,%.3f,%.3f\n", t + k / 100.0, k, s16(f, 28 + 2 * k) / 4096.0, s16(f, 228 + 2 * k) / 4096.0, s16(f, 428 + 2 * k) / 4096.0, s16(f, 640 + 2 * k) / 16.384, s16(f, 840 + 2 * k) / 16.384, s16(f, 1040 + 2 * k) / 16.384)); }
        out.put("motion_100hz_r21.csv", sb.toString().getBytes("UTF-8"));
        // stored 500 Hz copy (gap filler) and pulse packets, raw
        sb = new StringBuilder("unix_time_s,layout,hex\n"); for (byte[] f : S.r26) sb.append(String.format(Locale.UK, "%.4f,26,%s\n", ft(f), hex(f))); out.put("pulse_packets_r26_hex.csv", sb.toString().getBytes("UTF-8"));
        sb = new StringBuilder("unix_time_s,sample,microvolts\n"); for (byte[] f : S.r16) { double t = ft(f); int n = ProAnalysis.u16(f, 32); for (int k = 0; k < n && k < 500; k++) { int r = ((f[34 + 3 * k] & 3) << 16) | ((f[35 + 3 * k] & 0xFF) << 8) | (f[36 + 3 * k] & 0xFF); if ((r & 0x20000) != 0) r -= 0x40000; sb.append(String.format(Locale.UK, "%.4f,%d,%.2f\n", t + k / 500.0, k, r * UV)); } }
        out.put("ecg_500hz_stored_r16.csv", sb.toString().getBytes("UTF-8"));
        // log lines and events from the app logs
        StringBuilder ev = new StringBuilder(), lg = new StringBuilder();
        File[] fs = dir.listFiles(); if (fs != null) { Arrays.sort(fs); for (File f : fs) { String n = f.getName(); if (!n.startsWith("labrador_log_") || !n.endsWith(".txt")) continue; long mt = f.lastModified() / 1000; if (mt < t0 - 60 || mt - 86400 > t1) continue;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) { String ln; while ((ln = br.readLine()) != null) { String[] c = ln.split("\t", 4); if (c.length < 3) continue; long ms; try { ms = Long.parseLong(c[1]); } catch (NumberFormatException e) { continue; } double s = ms / 1000.0; if (s < t0 - 15 || s > t1 + 90) continue; ln = ln.replaceAll("5AM[0-9]{6}", "5AM******"); if (c[2].startsWith("EVENT ")) ev.append(ln).append('\n'); if (c[2].startsWith("RX_GENERIC") || c[2].startsWith("RSSI")) continue; lg.append(ln).append('\n'); if (lg.length() > 1500000) break; } } catch (Exception ignored) { } } }
        out.put("events.txt", ev.toString().getBytes("UTF-8")); out.put("app_log_excerpt.txt", lg.toString().getBytes("UTF-8"));
        // the report, strip, H10 and unknown-frame captures
        if (html != null && html.exists() && html.length() < 5000000) out.put(html.getName(), Files.readAllBytes(html.toPath()));
        String stamp = html == null ? "" : html.getName().replaceAll("[^0-9]", ""); File png = new File(dir, "ecg_strip_" + stamp + ".png"); if (png.exists() && png.length() < 5000000) out.put(png.getName(), Files.readAllBytes(png.toPath()));
        File hf = ProReportMaker.findH10(dir, t0, (int) (t1 - t0) + 1); if (hf != null && hf.length() < 40000000) out.put(hf.getName(), Files.readAllBytes(hf.toPath()));
        StringBuilder con = new StringBuilder(); int lastC = -1;
        if (fs != null) for (File f : fs) { String n = f.getName(); if (n.startsWith("unknown_frames_") && n.endsWith(".hex") && f.length() < 6000000) {
            out.put(n, Files.readAllBytes(f.toPath()));
            try (BufferedReader br = new BufferedReader(new FileReader(f))) { String ln; while ((ln = br.readLine()) != null) { String[] c = ln.split(" "); if (c.length < 4 || c[3].length() != 152) continue; byte[] b = new byte[76]; for (int i = 0; i < 76; i++) b[i] = (byte) Integer.parseInt(c[3].substring(2 * i, 2 * i + 2), 16); if ((b[8] & 0xFF) != 50) continue;
                int cc = b[9] & 0xFF; if (lastC >= 0 && ((cc - lastC) & 0xFF) != 1) con.append("\n[frames missing]\n"); lastC = cc; int e = 21; while (e < 71 && b[e] != 0) e++; con.append(new String(b, 21, e - 21, "ISO-8859-1")); } } catch (Exception ignored) { } } }
        if (con.length() > 0) out.put("firmware_console.txt", con.toString().replaceAll("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}", "XX:XX:XX:XX:XX:XX").replaceAll("5AM[0-9]{6}", "5AM******").getBytes("UTF-8"));
        return out;
    }

    static final String README = "Columns and units (all times are strap time, unix seconds).\n"
        + "ecg_500hz_live.csv: the live 500 Hz ECG (layout 16), microvolts at the design scale 0.0621 uV per count; lead_on 1 = electrode contact, fast_recovery 1 = the strap's recovery flag, rail 1 = amplifier limit; quality = the strap's 0 to 3 per second.\n"
        + "ecg_100hz_filtered_live.csv: the strap's own filtered 100 Hz stream (layout 17), 1 uV per count.\n"
        + "ecg_500hz_stored_r16.csv: the stored copy of layout 16 delivered by the history pull (fills gaps in the live stream).\n"
        + "per_second_r18.csv: one row a second: heart rate, up to four beat intervals in ms (optical), accel_step_g, gravity vector, step counter, optical quality, skin temperature, activity class, sleep state, bytes 33 and 36 (flags, not decoded).\n"
        + "optical_50hz_r20.csv: the raw optical record, 50 samples a second. block 0 = green (the pulse), 1 and 2 configured but empty, 3 = infrared, 4 = ambient; slot A and B; value = signed 24-bit counts; led_current_mA from the block header.\n"
        + "motion_100hz_r21.csv: accelerometer in g (4096 counts per g) and gyroscope in degrees per second (16.384 counts per dps), 100 samples a second.\n"
        + "pulse_packets_r26_hex.csv: raw pulse information packets (layout 26) as hex.\n"
        + "events.txt and app_log_excerpt.txt: the strap events and the app log lines around the reading (bulk packet lines left out).\n"
        + "firmware_console.txt: the strap firmware's own log (type 50 frames joined; each frame carries 50 characters), addresses masked: command names, ECG start sequence, state changes, battery, history pulls.\n"
        + "unknown_frames_*.hex: raw copies of frames of kinds that are not decoded yet (console log type 50, the device-info channel); one frame per line: time ms, channel, length, hex.\n";

    static void make(final Activity a, final File html) {
        if (html == null) { Toast.makeText(a, "No reading selected.", Toast.LENGTH_SHORT).show(); return; }
        if (busy) { Toast.makeText(a, "A full pull is already running.", Toast.LENGTH_SHORT).show(); return; }
        busy = true; Toast.makeText(a, "Collecting everything for this reading (about 20 seconds)...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() { @Override public void run() {
            String msg;
            try {
                String ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
                LinkedHashMap<String, byte[]> files = build(html.getParentFile(), html, ver);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (ZipOutputStream z = new ZipOutputStream(bo)) { z.setLevel(6); for (Map.Entry<String, byte[]> e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); } }
                String stamp = html.getName().replaceAll("[^0-9]", ""); String name = "MG_full_pull_" + (stamp.length() > 10 ? stamp.substring(0, 10) : stamp) + ".zip";
                ContentResolver cr = a.getContentResolver(); ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name); v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip"); v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + FOLDER + "/"); v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) msg = "Could not save the full pull.";
                else { try (OutputStream os = cr.openOutputStream(uri)) { os.write(bo.toByteArray()); } ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, done, null, null); msg = "Saved Download/" + FOLDER + "/" + name + " (" + files.size() + " files, " + String.format(Locale.UK, "%.1f", bo.size() / 1048576.0) + " MB)"; }
            } catch (Throwable t) { msg = "The full pull failed: " + t; }
            busy = false; final String m = msg; a.runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); } });
        } }, "full-pull").start();
    }
}

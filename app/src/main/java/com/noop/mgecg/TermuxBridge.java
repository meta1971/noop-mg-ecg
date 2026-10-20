package com.noop.mgecg;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 0.5.15: hand-off to Termux, where the ALADIN second-opinion script runs (research use only, not a diagnosis).
 *   1. export(): copies the pulled historical_bin files into Download/ecg/pull_YYYYMMDD_HHMMSS (the app's own folder is unreadable to Termux on Android 11)
 *   2. run():    asks Termux to run ~/bin/run_aladin.sh on that folder in the background (Termux RUN_COMMAND; needs allow-external-apps=true in Termux)
 *   3. AladinResultReceiver gets the report text back from Termux, saves it, and notifies.
 * Pure helpers, no UI. Termux must be the F-Droid build (the old Play Store build has no RUN_COMMAND).
 */
public final class TermuxBridge {
    private TermuxBridge() {}

    public static final String PKG = "com.termux";
    public static final String PERM = "com.termux.permission.RUN_COMMAND";
    public static final String HOME = "/data/data/com.termux/files/home";
    public static final String RUN_SCRIPT = HOME + "/bin/run_aladin.sh";
    public static final String PREFS = "aladin";
    public static final String SETUP_NAME = "setup_termux.sh";

    /** Written once into Download/ecg; the user runs it in Termux with: bash ~/storage/downloads/ecg/setup_termux.sh */
    static final String SETUP_SCRIPT =
        "#!/data/data/com.termux/files/usr/bin/bash\n"
      + "mkdir -p ~/bin ~/.termux\n"
      + "cat > ~/bin/run_aladin.sh <<'EOF'\n"
      + "#!/data/data/com.termux/files/usr/bin/bash\n"
      + "termux-wake-lock 2>/dev/null\n"
      + "cd ~/storage/downloads/aladin || { echo \"ALADIN folder missing: ~/storage/downloads/aladin\"; exit 2; }\n"
      + "D=~/storage/downloads/ecg/\"$1\"\n"
      + "ls \"$D\"/historical_bin_*.bin >/dev/null 2>&1 || { echo \"No historical_bin files in $D\"; exit 3; }\n"
      + "python aladin_phone.py \"$D\"\n"
      + "EOF\n"
      + "chmod +x ~/bin/run_aladin.sh\n"
      + "grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n"
      + "termux-reload-settings 2>/dev/null\n"
      + "echo \"Termux is ready for the app. Swipe Termux away once, then open it again.\"\n";

    // ---- 0.6.1: watcher mode. A small script, started once in Termux, waits for req_*.txt files written by this app, runs ALADIN
    // on that folder and sends the report back with am broadcast (token-checked). No pasting per run.
    public static final String WATCH_NAME = "start_watch.sh";
    public static final String WATCH_CMD = "bash ~/storage/downloads/ecg/start_watch.sh";
    static final String START_WATCH_TEMPLATE =
        "#!/data/data/com.termux/files/usr/bin/bash\n"
      + "mkdir -p ~/bin\n"
      + "cat > ~/bin/aladin_watch.sh <<'WATCHEOF'\n"
      + "#!/data/data/com.termux/files/usr/bin/bash\n"
      + "# ALADIN watcher: waits for req_*.txt files written by the app, runs ALADIN on that folder, sends the report back to the app.\n"
      + "TOKEN=\"__TOKEN__\"\n"
      + "ECG=\"$HOME/storage/downloads/ecg\"\n"
      + "ALA=\"$HOME/storage/downloads/aladin\"\n"
      + "APP=\"com.noop.mgecg/.AladinResultReceiver\"\n"
      + "say() {\n"
      + "  am broadcast -n \"$APP\" --es token \"$TOKEN\" --es status \"$1\" --es folder \"${2:- }\" --es text \"${3:- }\" >/dev/null 2>&1\n"
      + "}\n"
      + "say watching \" \" \"watcher running\"\n"
      + "while true; do\n"
      + "  for R in \"$ECG\"/req_*.txt; do\n"
      + "    [ -e \"$R\" ] || continue\n"
      + "    N=$(basename \"$R\" .txt)\n"
      + "    [ -e \"$ECG/.done_$N\" ] && continue\n"
      + "    : > \"$ECG/.done_$N\"\n"
      + "    F=$(tr -d '\\r\\n ' < \"$R\")\n"
      + "    D=\"$ECG/$F\"\n"
      + "    say started \"$F\" \"ALADIN started on $F\"\n"
      + "    if [ -z \"$F\" ] || [ ! -d \"$D\" ]; then say error \"$F\" \"Folder not found: $D\"; continue; fi\n"
      + "    if [ ! -f \"$ALA/aladin_phone.py\" ]; then say error \"$F\" \"Missing $ALA/aladin_phone.py\"; continue; fi\n"
      + "    if [ ! -f \"$ALA/aladin_fp16.npz\" ]; then say error \"$F\" \"Missing $ALA/aladin_fp16.npz\"; continue; fi\n"
      + "    termux-wake-lock 2>/dev/null\n"
      + "    ( cd \"$ALA\" && timeout 1200 python aladin_phone.py \"$D\" ) > \"$D/aladin_report.txt\" 2>&1\n"
      + "    RC=$?\n"
      + "    termux-wake-unlock 2>/dev/null\n"
      + "    T=$(tail -c 50000 \"$D/aladin_report.txt\" | tr '\\r' '\\n')\n"
      + "    [ -n \"$T\" ] || T=\"(no output, exit code $RC)\"\n"
      + "    if [ \"$RC\" -eq 0 ]; then say done \"$F\" \"$T\"; else say error \"$F\" \"ALADIN exited with code $RC\"$'\\n'\"$T\"; fi\n"
      + "  done\n"
      + "  sleep 3\n"
      + "done\n"
      + "WATCHEOF\n"
      + "chmod +x ~/bin/aladin_watch.sh\n"
      + "grep -q 'aladin_watch' ~/.bashrc 2>/dev/null || echo 'pgrep -f aladin_watch.sh >/dev/null 2>&1 || (nohup ~/bin/aladin_watch.sh </dev/null >/dev/null 2>&1 &)' >> ~/.bashrc\n"
      + "pkill -f 'bin/aladin_watch.sh' 2>/dev/null\n"
      + "sleep 1\n"
      + "nohup ~/bin/aladin_watch.sh </dev/null >/dev/null 2>&1 &\n"
      + "echo \"ALADIN watcher started. Leave Termux open in the background (do not swipe it away), then tap 2 in the app.\"\n";

    public static final String RUN_LATEST_NAME = "run_latest.sh";
    /** Typed or pasted in Termux when its build has no run-command permission (0.5.16). */
    public static final String RUN_LATEST_CMD = "bash ~/storage/downloads/ecg/run_latest.sh";

    /** Runs ALADIN on the newest pull_ folder and sends the report text back to the app with a broadcast (Termux's am command). */
    static String runLatestScript(String token) {
        return "#!/data/data/com.termux/files/usr/bin/bash\n"
      + "D=$(ls -1d ~/storage/downloads/ecg/pull_* 2>/dev/null | sort | tail -1)\n"
      + "[ -n \"$D\" ] || { echo \"No pull_ folder in ~/storage/downloads/ecg. Tap 2 in the app first.\"; exit 3; }\n"
      + "ls \"$D\"/historical_bin_*.bin >/dev/null 2>&1 || { echo \"No historical_bin files in $D\"; exit 3; }\n"
      + "cd ~/storage/downloads/aladin || { echo \"ALADIN folder missing: ~/storage/downloads/aladin\"; exit 2; }\n"
      + "termux-wake-lock 2>/dev/null\n"
      + "python aladin_phone.py \"$D\" 2>&1 | tee \"$D/aladin_report.txt\"\n"
      + "T=$(tail -c 60000 \"$D/aladin_report.txt\" | tr '\\r' '\\n')\n"
      + "am broadcast -n com.noop.mgecg/.AladinResultReceiver --es folder \"$(basename \"$D\")\" --es token \"" + token + "\" --es text \"$T\" >/dev/null 2>&1 && echo \"Report sent to the app.\" || echo \"Could not send the report to the app; it is saved in $D/aladin_report.txt\"\n";
    }

    /** A random string only our own script knows, so other apps cannot post fake results. */
    public static String token(Context c) {
        SharedPreferences p = prefs(c);
        String t = p.getString("token", null);
        if (t == null) {
            java.security.SecureRandom r = new java.security.SecureRandom();
            t = Long.toHexString(r.nextLong()) + Long.toHexString(r.nextLong());
            p.edit().putString("token", t).apply();
        }
        return t;
    }

    /** False when this Termux build does not define the run-command permission (the Google Play build). */
    public static boolean runCommandAvailable(Context c) {
        try { c.getPackageManager().getPermissionInfo(PERM, 0); return true; } catch (Throwable t) { return false; }
    }

    public static void writeRunLatest(Context c) throws Exception {
        copyToDownloads(c, "ecg", RUN_LATEST_NAME, "application/x-sh", new java.io.ByteArrayInputStream(runLatestScript(token(c)).getBytes(StandardCharsets.UTF_8)));
    }

    public static void writeStartWatch(Context c) throws Exception {
        String sh = START_WATCH_TEMPLATE.replace("__TOKEN__", token(c));
        copyToDownloads(c, "ecg", WATCH_NAME, "application/x-sh", new java.io.ByteArrayInputStream(sh.getBytes(StandardCharsets.UTF_8)));
    }

    /** Asks the Termux watcher to run ALADIN on Download/ecg/<folder>. The watcher marks each request done itself, so a request is never run twice. */
    public static void requestRun(Context c, String folder) throws Exception {
        prefs(c).edit().putLong("run_started_ms", System.currentTimeMillis()).putLong("run_ack_ms", 0L).putString("run_folder", folder).apply();
        copyToDownloads(c, "ecg", "req_" + folder + ".txt", "text/plain", new java.io.ByteArrayInputStream(folder.getBytes(StandardCharsets.UTF_8)));
    }

    public static long ackTime(Context c) { return prefs(c).getLong("run_ack_ms", 0L); }
    public static long watchSeen(Context c) { return prefs(c).getLong("watch_seen_ms", 0L); }

    static void markSeen(Context c, boolean started) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = prefs(c).edit().putLong("watch_seen_ms", now);
        if (started) e.putLong("run_ack_ms", now);
        e.apply();
    }

    public static boolean termuxInstalled(Context c) {
        try { c.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (Throwable t) { return false; }
    }

    public static boolean permissionGranted(Context c) {
        return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** historical_bin files saved since the last export; if none are new, the newest two (one reading can be split across two files). */
    public static List<File> pending(Context c) {
        List<File> out = new ArrayList<File>();
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return out;
        File[] all = dir.listFiles();
        if (all == null) return out;
        List<File> bins = new ArrayList<File>();
        for (File f : all) if (f.isFile() && f.getName().startsWith("historical_bin_") && f.getName().endsWith(".bin") && f.length() > 0) bins.add(f);
        Collections_sortByModified(bins);
        long last = prefs(c).getLong("last_export_ms", 0L);
        for (File f : bins) if (f.lastModified() > last) out.add(f);
        if (out.isEmpty()) for (int i = Math.max(0, bins.size() - 2); i < bins.size(); i++) out.add(bins.get(i));
        return out;
    }

    private static void Collections_sortByModified(List<File> l) {
        java.util.Collections.sort(l, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
    }

    /** Copies the files into Download/ecg/pull_YYYYMMDD_HHMMSS and returns that folder name. */
    public static String export(Context c, List<File> files) throws Exception {
        if (files.isEmpty()) throw new Exception("No historical_bin files found. Pull the strap's history first.");
        String folder = "pull_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        long newest = 0;
        for (File f : files) {
            copyToDownloads(c, "ecg/" + folder, f.getName(), "application/octet-stream", new FileInputStream(f));
            newest = Math.max(newest, f.lastModified());
        }
        prefs(c).edit().putLong("last_export_ms", newest).putString("last_folder", folder).apply();
        return folder;
    }

    /** Puts setup_termux.sh into Download/ecg (replacing an older copy). */
    public static void writeSetupScript(Context c) throws Exception {
        copyToDownloads(c, "ecg", SETUP_NAME, "application/x-sh", new java.io.ByteArrayInputStream(SETUP_SCRIPT.getBytes(StandardCharsets.UTF_8)));
    }

    private static void copyToDownloads(Context c, String sub, String name, String mime, InputStream in) throws Exception {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                String rel = "Download/" + sub + "/";
                try { cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{rel, name}); } catch (Throwable ignore) {}
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new Exception("Could not create " + name + " in Download/" + sub);
                OutputStream os = cr.openOutputStream(u);
                if (os == null) throw new Exception("Could not open " + name + " for writing");
                try { pump(in, os); } finally { os.close(); }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(u, done, null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub);
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create " + dir);
                OutputStream os = new FileOutputStream(new File(dir, name));
                try { pump(in, os); } finally { os.close(); }
            }
        } finally { try { in.close(); } catch (Throwable ignore) {} }
    }

    private static void pump(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Asks Termux to run run_aladin.sh on the exported folder, in the background. The report text comes back to AladinResultReceiver. */
    public static void run(Context c, String folder) {
        Intent i = new Intent();
        i.setClassName(PKG, "com.termux.app.RunCommandService");
        i.setAction("com.termux.RUN_COMMAND");
        i.putExtra("com.termux.RUN_COMMAND_PATH", RUN_SCRIPT);
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{folder});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        Intent res = new Intent(c, AladinResultReceiver.class);
        res.putExtra("folder", folder);
        // Termux fills the result into this intent, so the PendingIntent must be mutable (FLAG_MUTABLE = 1 << 25, API 31+)
        PendingIntent pi = PendingIntent.getBroadcast(c, (int) (System.currentTimeMillis() & 0x7fff), res, PendingIntent.FLAG_ONE_SHOT | 0x02000000);
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);
        prefs(c).edit().putLong("run_started_ms", System.currentTimeMillis()).putString("run_folder", folder).apply();
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static String lastText(Context c) { return prefs(c).getString("last_text", ""); }
    public static long lastTime(Context c) { return prefs(c).getLong("last_time", 0L); }
    public static long runStarted(Context c) { return prefs(c).getLong("run_started_ms", 0L); }
    public static String lastFolder(Context c) { return prefs(c).getString("last_folder", ""); }

    static void saveResult(Context c, String text) {
        prefs(c).edit().putString("last_text", text).putLong("last_time", System.currentTimeMillis()).putLong("run_started_ms", 0L).apply();
        try {
            File d = new File(c.getExternalFilesDir(null), "aladin");
            d.mkdirs();
            FileOutputStream o = new FileOutputStream(new File(d, "aladin_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt"));
            try { o.write(text.getBytes(StandardCharsets.UTF_8)); } finally { o.close(); }
        } catch (Throwable ignore) {}
    }

    static String[] splitLines(String s) { return s.split("\n"); }
    static String join(List<String> l) { StringBuilder b = new StringBuilder(); for (String s : l) b.append(s).append('\n'); return b.toString(); }
    static List<String> list(String... a) { return new ArrayList<String>(Arrays.asList(a)); }
}

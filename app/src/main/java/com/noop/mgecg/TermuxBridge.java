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
      + "python aladin_phone.py \"$D\"/historical_bin_*.bin\n"
      + "EOF\n"
      + "chmod +x ~/bin/run_aladin.sh\n"
      + "grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n"
      + "termux-reload-settings 2>/dev/null\n"
      + "echo \"Termux is ready for the app. Swipe Termux away once, then open it again.\"\n";

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

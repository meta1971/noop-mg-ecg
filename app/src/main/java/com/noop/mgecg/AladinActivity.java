package com.noop.mgecg;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 0.5.15: one screen for the Termux hand-off: prepare Termux once, export the pull, run ALADIN, read the last result. Research use only. */
public class AladinActivity extends Activity {
    private static final int BG = Color.parseColor("#0B1220");
    private static final int REQ_PERM = 77;
    private TextView status, out;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long shownLast = 0L;
    private final Runnable tick = new Runnable() { @Override public void run() {
        refresh();
        if (TermuxBridge.lastTime(AladinActivity.this) > shownLast) showLast();       // a new report arrived while this screen is open
        h.postDelayed(this, 2000L);
    } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        TextView title = text("ALADIN second opinion (runs in Termux)", 16, true);
        col.addView(title);
        col.addView(text("Research use only. Not a medical device, not a diagnosis.", 12, false));
        status = text("", 13, false);
        col.addView(status);

        col.addView(btn("1. SET UP TERMUX WATCHER (once)", new View.OnClickListener() { @Override public void onClick(View v) { prepare(); } }));
        col.addView(btn("2. EXPORT PULL AND RUN ALADIN", new View.OnClickListener() { @Override public void onClick(View v) { exportAndRun(true); } }));
        col.addView(btn("PULL STRAP HISTORY NOW", new View.OnClickListener() { @Override public void onClick(View v) { pullNow(); } }));
        col.addView(btn("EXPORT ONLY (copy to Download/ecg)", new View.OnClickListener() { @Override public void onClick(View v) { exportAndRun(false); } }));
        col.addView(btn("OPEN TERMUX", new View.OnClickListener() { @Override public void onClick(View v) { openTermux(); } }));
        col.addView(btn("SHOW LAST RESULT", new View.OnClickListener() { @Override public void onClick(View v) { showLast(); } }));
        col.addView(btn("PASTE-COMMAND MODE (fallback)", new View.OnClickListener() { @Override public void onClick(View v) { manualRun(); } }));
        col.addView(btn("\u2190 BACK", new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }));

        out = text("", 12, false);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        col.addView(out);
        setContentView(sv);
        showLast();
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    private void refresh() {
        StringBuilder s = new StringBuilder();
        s.append("Termux installed: ").append(TermuxBridge.termuxInstalled(this) ? "yes" : "NO (install the F-Droid build)").append('\n');
        if (!TermuxBridge.runCommandAvailable(this)) {
            s.append("Mode: Termux watcher (this Termux build has no run-command permission). Start it once with button 1.\n");
            long seen = TermuxBridge.watchSeen(this);
            s.append("Termux watcher: ").append(seen > 0 ? "last heard " + ago(seen) : "not heard yet").append('\n');
        }
        else s.append("Permission to run commands in Termux: ").append(TermuxBridge.permissionGranted(this) ? "granted" : "not yet (asked when you tap 2)").append('\n');
        List<File> p = TermuxBridge.pending(this);
        s.append("Files ready to export: ").append(p.size());
        if (!p.isEmpty()) s.append(" (newest ").append(p.get(p.size() - 1).getName()).append(')');
        s.append('\n');
        long started = TermuxBridge.runStarted(this);
        if (started > 0) s.append("ALADIN running since ").append(clock(started)).append(" (about 1-5 min per 3 min of ECG; a notification appears when done)\n");
        long last = TermuxBridge.lastTime(this);
        if (last > 0) s.append("Last result: ").append(clock(last)).append('\n');
        status.setText(s.toString());
    }

    private void prepare() {
        if (!TermuxBridge.runCommandAvailable(this)) { setupWatch(); return; }
        try {
            TermuxBridge.writeSetupScript(this);
            out.setText("Setup file saved to Download/ecg/" + TermuxBridge.SETUP_NAME + "\n\n"
                + "Now in Termux:\n"
                + "1. Type: bash ~/storage/downloads/ecg/setup_termux.sh\n"
                + "   (the ~ key is on the black strip). Press Enter (CTRL, then j).\n"
                + "2. It should print: Termux is ready for the app.\n"
                + "3. Swipe Termux away once (recent apps), then open it again.\n"
                + "4. Come back here and tap 2.\n\n"
                + "Needs ~/storage/downloads/aladin/aladin_phone.py and aladin_fp16.npz, as already set up.\n"
                + "If Termux says the file is not found, look for a name ending .sh.txt in Download/ecg and rename it.");
        } catch (Throwable t) { out.setText("Could not save the setup file: " + t); }
    }

    private void exportAndRun(boolean run) {
        if (run) {
            if (!TermuxBridge.termuxInstalled(this)) { out.setText("Termux is not installed (or is not visible to this app)."); return; }
            if (!TermuxBridge.runCommandAvailable(this)) { watchRun(); return; }
            if (!TermuxBridge.permissionGranted(this)) {
                out.setText("Android will now ask to let this app run commands in Termux. Tap Allow, then tap 2 again.");
                requestPermissions(new String[]{TermuxBridge.PERM}, REQ_PERM);
                return;
            }
        }
        try {
            List<File> files = TermuxBridge.pending(this);
            if (files.isEmpty()) { out.setText(noFilesText()); return; }
            String folder = TermuxBridge.export(this, files);
            StringBuilder s = new StringBuilder("Copied " + files.size() + " file(s) to Download/ecg/" + folder + ":\n");
            for (File f : files) s.append("  ").append(f.getName()).append('\n');
            if (run) {
                TermuxBridge.run(this, folder);
                s.append("\nSent to Termux. It runs in the background; a notification appears when the report is ready.\n")
                 .append("If nothing happens after 2 minutes: in Termux the setup line allow-external-apps=true must be in place and Termux restarted (tap 1 for the steps).");
            }
            out.setText(s.toString());
        } catch (Throwable t) { out.setText("Failed: " + t); }
        refresh();
    }

    /** This Termux build has no run-command permission: export, put the one command on the clipboard, the user pastes it in Termux. */
    private void manualRun() {
        try {
            List<File> files = TermuxBridge.pending(this);
            if (files.isEmpty()) { out.setText(noFilesText()); return; }
            String folder = TermuxBridge.export(this, files);
            TermuxBridge.writeRunLatest(this);
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ALADIN command", TermuxBridge.RUN_LATEST_CMD));
            StringBuilder s = new StringBuilder("Copied " + files.size() + " file(s) to Download/ecg/" + folder + ":\n");
            for (File f : files) s.append("  ").append(f.getName()).append('\n');
            s.append("\nThe command is on your clipboard:\n  ").append(TermuxBridge.RUN_LATEST_CMD).append("\n\n")
             .append("1. Tap OPEN TERMUX below.\n")
             .append("2. Press and hold on the Termux screen, tap PASTE, then press Enter.\n")
             .append("3. Wait (about 1-5 min per 3 min of ECG). The report prints in Termux and a notification appears here when it is sent back.\n")
             .append("Next time: in Termux press the up arrow, then Enter.");
            out.setText(s.toString());
        } catch (Throwable t) { out.setText("Failed: " + t); }
        refresh();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_PERM) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) exportAndRun(true); else out.setText("Permission not granted. In Android Settings > Apps > this app > Permissions, allow \"Run commands in Termux environment\".");
        }
    }

    private String noFilesText() {
        return "There is no pulled strap history in this app's folder yet.\n\n"
            + "The main app pulls the history from the strap (the day logger does it every 30 minutes while it is running). "
            + "Tap PULL STRAP HISTORY NOW (the main app must be open and connected), wait about a minute, then tap 2 again.";
    }

    private void pullNow() {
        try {
            MainActivity.requestPull();
            out.setText("Asked the main app to pull the strap's history. It must be open and connected. Wait about a minute, then tap 2.");
        } catch (Throwable t) { out.setText("Could not ask for a pull: " + t); }
    }

    private void setupWatch() {
        try {
            TermuxBridge.writeStartWatch(this);
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ALADIN watcher", TermuxBridge.WATCH_CMD));
            out.setText("Watcher script saved to Download/ecg/" + TermuxBridge.WATCH_NAME + "\n\n"
                + "The command is on your clipboard:\n  " + TermuxBridge.WATCH_CMD + "\n\n"
                + "1. Tap OPEN TERMUX below.\n"
                + "2. Press and hold on the Termux screen, tap PASTE, press Enter.\n"
                + "3. It prints: ALADIN watcher started.\n"
                + "4. Leave Termux in the background (do not swipe it away). It starts itself again whenever you open Termux.\n"
                + "5. Back here, tap 2 whenever you want a second opinion. No pasting after this.\n\n"
                + "Needs ~/storage/downloads/aladin/aladin_phone.py and aladin_fp16.npz, as already set up.\n"
                + "If Termux says the file is not found, look for a name ending .sh.txt in Download/ecg and rename it.");
        } catch (Throwable t) { out.setText("Could not save the watcher script: " + t); }
    }

    private void watchRun() {
        try {
            List<File> files = TermuxBridge.pending(this);
            if (files.isEmpty()) { out.setText(noFilesText()); return; }
            String folder = TermuxBridge.export(this, files);
            TermuxBridge.requestRun(this, folder);
            StringBuilder s = new StringBuilder("Copied " + files.size() + " file(s) to Download/ecg/" + folder + ":\n");
            for (File f : files) s.append("  ").append(f.getName()).append('\n');
            s.append("\nAsked the Termux watcher to run ALADIN. Waiting for it to pick the request up (up to 15 seconds)...");
            out.setText(s.toString());
            final long t0 = TermuxBridge.runStarted(this);
            h.postDelayed(new Runnable() { @Override public void run() {
                if (TermuxBridge.runStarted(AladinActivity.this) == t0 && TermuxBridge.ackTime(AladinActivity.this) < t0) watcherSilent();
            } }, 15000L);
        } catch (Throwable t) { out.setText("Failed: " + t); }
        refresh();
    }

    private void watcherSilent() {
        try {
            TermuxBridge.writeStartWatch(this);
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ALADIN watcher", TermuxBridge.WATCH_CMD));
            out.setText("Termux did not answer, so the watcher is not running.\n\n"
                + "The start command is on your clipboard:\n  " + TermuxBridge.WATCH_CMD + "\n\n"
                + "Tap OPEN TERMUX, press and hold, tap PASTE, press Enter. The files are already exported: the run starts by itself as soon as the watcher is up "
                + "(about 1-5 min per 3 min of ECG, then a notification appears here).");
        } catch (Throwable t) { out.setText("Termux did not answer and the start file could not be saved: " + t); }
    }

    private String ago(long ms) {
        long m = Math.max(0, (System.currentTimeMillis() - ms) / 60000L);
        return m < 1 ? "less than a minute ago" : m < 120 ? m + " min ago" : (m / 60) + " h ago";
    }

    private void openTermux() {
        Intent i = getPackageManager().getLaunchIntentForPackage(TermuxBridge.PKG);
        if (i != null) startActivity(i); else out.setText("Termux is not installed.");
    }

    private void showLast() {
        String t = TermuxBridge.lastText(this);
        shownLast = TermuxBridge.lastTime(this);
        out.setText(t.length() == 0 ? "No result yet." : t);
        refresh();
    }

    private String clock(long ms) { return new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK).format(new Date(ms)); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor("#E8EEF7"));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(6)); return t;
    }
    private Button btn(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l); return b; }
}

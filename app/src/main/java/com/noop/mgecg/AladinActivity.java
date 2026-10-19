package com.noop.mgecg;

import android.app.Activity;
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
    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); h.postDelayed(this, 2000L); } };

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

        col.addView(btn("1. PREPARE TERMUX (once)", new View.OnClickListener() { @Override public void onClick(View v) { prepare(); } }));
        col.addView(btn("2. EXPORT PULL AND RUN ALADIN", new View.OnClickListener() { @Override public void onClick(View v) { exportAndRun(true); } }));
        col.addView(btn("EXPORT ONLY (copy to Download/ecg)", new View.OnClickListener() { @Override public void onClick(View v) { exportAndRun(false); } }));
        col.addView(btn("OPEN TERMUX", new View.OnClickListener() { @Override public void onClick(View v) { openTermux(); } }));
        col.addView(btn("SHOW LAST RESULT", new View.OnClickListener() { @Override public void onClick(View v) { showLast(); } }));
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
        s.append("Permission to run commands in Termux: ").append(TermuxBridge.permissionGranted(this) ? "granted" : "not yet (asked when you tap 2)").append('\n');
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
            if (!TermuxBridge.permissionGranted(this)) {
                out.setText("Android will now ask to let this app run commands in Termux. Tap Allow, then tap 2 again.");
                requestPermissions(new String[]{TermuxBridge.PERM}, REQ_PERM);
                return;
            }
        }
        try {
            List<File> files = TermuxBridge.pending(this);
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

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_PERM) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) exportAndRun(true); else out.setText("Permission not granted. In Android Settings > Apps > this app > Permissions, allow \"Run commands in Termux environment\".");
        }
    }

    private void openTermux() {
        Intent i = getPackageManager().getLaunchIntentForPackage(TermuxBridge.PKG);
        if (i != null) startActivity(i); else out.setText("Termux is not installed.");
    }

    private void showLast() {
        String t = TermuxBridge.lastText(this);
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

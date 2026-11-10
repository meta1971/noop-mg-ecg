package com.noop.mgecg;

// FILE VERSION 0.51.0 (10 Nov 2026): Lab "S: Scale and corner" (build brief 0.51.0 item 4): the sheets, the audio ladder and the run control. The analysis is SimLadder.

import android.app.Dialog;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One run per tap. Mode A plays the ladder v3 through this phone's wired / USB-C output (or the old phone plays the exported WAV), mode B shows the clip
 * clock plan, mode C records 180 s with no source. Each mode starts a FREE reading with Auto-stop off for the run (restored afterwards), stops it at the
 * planned end, asks for the meter numbers, analyses the live raw file and writes sim_* / floor_* notes. Nothing is applied: the scale stays 0.0621.
 * Read-only toward the strap: the only strap commands are the ones of an ordinary reading.
 */
final class SimLabSheet {
    static final int SR = 48000;
    static final String WAV_NAME = "sim_ladder_v3.wav";

    private final EcgPage p; private final NewLookActivity a;
    char mode; boolean active, internalAudio;
    private long planMs; private volatile AudioTrack track; private volatile boolean stopAudio; private int volBefore = -1;
    private double playStrapS = Double.NaN, playRelS = Double.NaN; private long startMs;

    SimLabSheet(EcgPage page, NewLookActivity act) { p = page; a = act; }

    // ------------------------------------------------------------------ chooser
    void openChooser() {
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        TextView in = p.t("Known voltages through the divider give the scale (uV per count), the high-pass corner, linearity and the floor. Wiring: MG_simulator_test_v3.pdf (Part 2A, 2B, 3). One run per tap; the results are measurements of this wiring and are not applied.", 12, HealthViews.MUT, 400);
        in.setLineSpacing(0, 1.2f); box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        final Dialog[] d = new Dialog[1];
        row(box, "A   Audio ladder", "DC steps, 10 Hz levels, tones 0.5-40 Hz. 3 min. Needs a phone output that passes DC (Part 2A).", () -> { d[0].dismiss(); startA(); });
        row(box, "B   Cell steps", "Clip ON at 1:00, 3:00, 5:00, OFF at 2:00, 4:00, 6:00, STOP 7:00. For an AC-coupled phone (Part 2B).", () -> { d[0].dismiss(); start('B', 425_000L); });
        row(box, "C   Floor only", "180 s with no source (Part 3).", () -> { d[0].dismiss(); start('C', 180_000L); });
        TextView ex = p.chip("Export the ladder WAV to Download/sim (for the old phone)"); ex.setOnClickListener(v -> exportWav());
        box.addView(ex, p.lp(-1, -2, 14));
        d[0] = p.sheet("Scale lab", box); d[0].show();
    }

    private void row(LinearLayout box, String title, String plain, final Runnable go) {
        LinearLayout r = p.hrow(); r.setBackground(HealthViews.glass(a, 16)); r.setPadding(p.dp(16), p.dp(12), p.dp(12), p.dp(12));
        LinearLayout mid = new LinearLayout(a); mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(p.t(title, 15, HealthViews.INK, 400)); TextView pl = p.t(plain, 12, HealthViews.MUT, 400); pl.setLineSpacing(0, 1.2f); mid.addView(pl, p.lp(-2, -2, 3));
        r.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView b = p.pill("Start", HealthViews.BLUE, 0xFF05131E); b.setPadding(p.dp(18), 0, p.dp(18), 0); b.setOnClickListener(v -> go.run());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, p.dp(42)); gp.leftMargin = p.dp(10); r.addView(b, gp);
        box.addView(r, p.lp(-1, -2, 8));
    }

    /** True when this phone has a wired or USB audio output. */
    private boolean hasWiredOut() {
        try {
            AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
            for (AudioDeviceInfo i : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) { int t = i.getType(); if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_ACCESSORY || t == AudioDeviceInfo.TYPE_LINE_ANALOG) return true; }
        } catch (Throwable ignored) { }
        return false;
    }

    private void startA() {
        if (hasWiredOut()) { internalAudio = true; start('A', 183_000L); return; }
        new android.app.AlertDialog.Builder(a).setTitle("No wired or USB-C audio output")
            .setMessage("This phone lists no wired or USB audio output. Play the ladder on the old phone instead: export the WAV once, start this run, then press play on the old phone at about 10 s (the cue says when).")
            .setPositiveButton("Run with the old phone", (x, y) -> { internalAudio = false; start('A', 183_000L); })
            .setNeutralButton("Export WAV", (x, y) -> exportWav())
            .setNegativeButton("Cancel", null).show();
    }

    private void start(char m, long ms) {
        if (p.isRunning()) { a.toast("A reading is running."); return; }
        mode = m; planMs = ms; active = true; playStrapS = Double.NaN; playRelS = Double.NaN; stopAudio = false;
        p.startSimReading();
    }

    // ------------------------------------------------------------------ during the reading (once a second)
    /** Returns true when the planned end is reached (the page then stops the reading). */
    boolean tick(long elMs, long readingStartMs) {
        if (!active) return false;
        startMs = readingStartMs;
        int s = (int) (elMs / 1000);
        if (mode == 'A') {
            if (internalAudio && track == null && elMs >= 10_000L) playLadder();
            double ps = elMs / 1000.0 - 10;
            p.simCue(ps < 0 ? (internalAudio ? "Ladder starts in " + (int) Math.ceil(-ps) + " s. Keep still, no touching the wiring." : "Press PLAY on the old phone in " + (int) Math.ceil(-ps) + " s")
                : ps < 168 ? String.format(Locale.UK, "Ladder %d:%02d of 2:48  ·  meter at 0:06, 0:15, 0:22, 0:40", (int) ps / 60, (int) ps % 60) : "Ladder done. Stopping by itself.");
        } else if (mode == 'B') {
            int[] at = { 60, 120, 180, 240, 300, 360, 420 }; String[] what = { "clip ON", "clip OFF", "clip ON", "clip OFF", "clip ON", "clip OFF", "STOP" };
            String next = "STOP"; int nt = 420; for (int i = 0; i < at.length; i++) if (s < at[i]) { next = what[i]; nt = at[i]; break; }
            p.simCue(String.format(Locale.UK, "%d:%02d  ·  next: %s at %d:%02d  ·  read the R2 meter in each minute", s / 60, s % 60, next, nt / 60, nt % 60));
        } else p.simCue(String.format(Locale.UK, "Floor: no source  ·  %d:%02d of 3:00", s / 60, s % 60));
        return elMs >= planMs;
    }

    private void playLadder() {
        try {
            AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
            volBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC); am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            a.toast("Media volume set to maximum for the ladder");
            final int buf = Math.max(AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), SR / 5 * 2);
            final AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build();
            track = t;
            new Thread(new Runnable() { @Override public void run() {
                try {
                    int n = (int) Math.round(SimLadder.LADDER_S * SR), chunk = SR / 10; short[] s = new short[chunk];
                    for (int i = 0; i < n && !stopAudio; i += chunk) {
                        int m = Math.min(chunk, n - i); for (int k = 0; k < m; k++) s[k] = (short) Math.round(Math.max(-1, Math.min(1, SimLadder.level((i + k) / (double) SR))) * 32767);
                        if (i == 0) { t.play(); long now = System.currentTimeMillis(); playRelS = (now - startMs) / 1000.0; double[] lf = FrameMonitor.lastLive16(); if (lf != null) playStrapS = lf[1] + (now - lf[0]) / 1000.0; }
                        t.write(s, 0, m);
                    }
                } catch (Throwable ignored) { }
                try { t.stop(); t.release(); } catch (Throwable ignored) { }
            } }, "sim-ladder").start();
        } catch (Throwable e) { a.toast("Audio could not start: " + e.getClass().getSimpleName()); }
    }

    private void stopAudio() {
        stopAudio = true;
        try { if (volBefore >= 0) { AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE); am.setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0); } } catch (Throwable ignored) { }
        volBefore = -1; track = null;
    }

    // ------------------------------------------------------------------ after the reading
    void finished(final long startSec, final File dir) {
        if (!active) return; active = false; stopAudio();
        final char m = mode;
        if (m == 'C') { analyse(startSec, dir, m, null); return; }
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        final SharedPreferences sp = a.getSharedPreferences("labrador_ecg_control", android.content.Context.MODE_PRIVATE);
        final EditText[] f;
        if (m == 'A') {
            box.addView(p.t("Meter readings in mV (DC, 2000m range). The 0:40 reading is negative.", 12, HealthViews.MUT, 400));
            f = new EditText[] { num(box, "At 0:06", ""), num(box, "At 0:15", ""), num(box, "At 0:22", ""), num(box, "At 0:40", ""), num(box, "R1 (ohm)", sp.getString("sim_r1", "98600")), num(box, "R2 (ohm)", sp.getString("sim_r2", "250")) };
        } else {
            box.addView(p.t("Cell voltage on the 2000m range, then the R2 meter (200m range) in each minute, in mV.", 12, HealthViews.MUT, 400));
            f = new EditText[8]; f[0] = num(box, "Cell (mV)", "");
            String[] seg = { "0-1 min (OFF)", "1-2 (ON)", "2-3 (OFF)", "3-4 (ON)", "4-5 (OFF)", "5-6 (ON)", "6-7 (OFF)" };
            for (int i = 0; i < 7; i++) f[i + 1] = num(box, seg[i], "");
        }
        final Dialog[] d = new Dialog[1];
        TextView go = p.pill("Analyse", HealthViews.BLUE, 0xFF05131E); go.setPadding(p.dp(18), p.dp(12), p.dp(18), p.dp(12));
        go.setOnClickListener(v -> {
            double[] vals = new double[f.length]; for (int i = 0; i < f.length; i++) vals[i] = parse(f[i].getText().toString());
            if (m == 'A') sp.edit().putString("sim_r1", f[4].getText().toString().trim()).putString("sim_r2", f[5].getText().toString().trim()).apply();
            d[0].dismiss(); analyse(startSec, dir, m, vals);
        });
        box.addView(go, p.lp(-1, -2, 14));
        d[0] = p.sheet(m == 'A' ? "Ladder: meter numbers" : "Cell steps: meter numbers", box); d[0].show();
    }

    private EditText num(LinearLayout box, String label, String def) {
        LinearLayout r = p.hrow(); r.addView(p.t(label, 14, HealthViews.INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        EditText e = new EditText(a); e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED); e.setText(def); e.setTextColor(HealthViews.INK); e.setMinWidth(p.dp(110));
        r.addView(e); box.addView(r, p.lp(-1, -2, 6)); return e;
    }
    private static double parse(String s) { try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (Throwable e) { return Double.NaN; } }

    private void analyse(final long startSec, final File dir, final char m, final double[] v) {
        a.toast("Analysing the run (a few seconds)...");
        final double pr = playRelS, ps = playStrapS;
        new Thread(new Runnable() { @Override public void run() {
            SimLadder.Result R = null; String err = null;
            try {
                File bin = null; for (int i = 0; i < 10 && bin == null; i++) { bin = ProReportMaker.findBin(dir, startSec); if (bin == null) Thread.sleep(500); }
                if (bin == null) err = "No live raw file was found for this run.";
                else {
                    List<byte[]> fr = SpectrumBlock.r16(java.nio.file.Files.readAllBytes(bin.toPath()));
                    if (m == 'A') R = SimLadder.analyseA(fr, new double[] { v[0], v[1], v[2], v[3] }, v[4], v[5], ps);
                    else if (m == 'B') { double[] r2 = new double[7]; System.arraycopy(v, 1, r2, 0, 7); R = SimLadder.analyseB(fr, v[0], r2); }
                    else R = SimLadder.analyseC(fr);
                    if (m == 'A' && !Double.isNaN(pr)) R.notes.put("sim_play_rel_s", String.format(Locale.US, "%.2f", pr));
                    for (Map.Entry<String, String> e : R.notes.entrySet()) ReadingNotes.put(dir, startSec, e.getKey(), e.getValue());
                }
            } catch (Throwable e) { err = "Analysis failed: " + e.getClass().getSimpleName(); }
            final SimLadder.Result fr = R; final String fe = err;
            a.runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    StringBuilder s = new StringBuilder(); if (fe != null) s.append(fe); else for (String l : fr.card) s.append(l).append("\n\n");
                    new android.app.AlertDialog.Builder(a).setTitle(m == 'A' ? "Scale lab: ladder" : m == 'B' ? "Scale lab: cell steps" : "Scale lab: floor").setMessage(s.toString().trim() + (fe == null ? "\n\nThe numbers are in this reading's notes (Send includes them)." : "")).setPositiveButton("OK", null).show();
                } catch (Throwable ignored) { }
            } });
        } }, "sim-analyse").start();
    }

    // ------------------------------------------------------------------ WAV for the old phone
    void exportWav() {
        new Thread(new Runnable() { @Override public void run() {
            String msg;
            try {
                byte[] w = SimLadder.wav(SR);
                ContentValues cv = new ContentValues(); cv.put(MediaStore.MediaColumns.DISPLAY_NAME, WAV_NAME); cv.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav"); cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/sim/"); cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                try (OutputStream o = a.getContentResolver().openOutputStream(u)) { o.write(w); }
                ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); a.getContentResolver().update(u, done, null, null);
                msg = "Saved Download/sim/" + WAV_NAME + " (2:48, 48 kHz). Copy it to the old phone; play it at full volume.";
            } catch (Throwable e) { msg = "The WAV could not be saved: " + e.getClass().getSimpleName(); }
            final String fm = msg; a.runOnUiThread(() -> a.toast(fm));
        } }, "sim-wav").start();
    }
}

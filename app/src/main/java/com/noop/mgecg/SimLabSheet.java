package com.noop.mgecg;

// FILE VERSION 0.52.1 (11 Nov 2026): direct runs M, D, E, F, G added (SimDirect: this phone's audio straight to the pods, no divider; rows in the chooser, a branch in tick() and finished(), and the block "direct runs" near the end). FILE VERSION 0.51.0 (10 Nov 2026): Lab "S: Scale and corner" (build brief 0.51.0 item 4): the sheets, the audio ladder and the run control. The analysis is SimLadder.

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
        // 0.52.1: DIRECT runs (SimDirect): this phone's audio straight to the pods, no divider, no DC; nothing over -48 dBFS reaches the strap
        TextView dh = p.t("DIRECT RUNS, NO DIVIDER", 11, 0xFF6EC3FF, 600); dh.setLetterSpacing(0.12f); box.addView(dh, p.lp(-1, -2, 18));
        TextView di = p.t("This phone's audio straight to the two pods: small signals made digitally (1 mV = -60 dBFS), no resistors, no DC. Calibrate once with the meter (M), then run D to G. " + calText(), 12, HealthViews.MUT, 400);
        di.setLineSpacing(0, 1.2f); box.addView(di, p.lp(-1, -2, 4));
        row(box, "M   Meter calibration", "Cable on the METER (AC mV), NOT on the strap. 1 kHz and 100 Hz at -20 dBFS, 1 kHz at -30 dBFS. 50 s.", () -> { d[0].dismiss(); startCal(); });
        row(box, "D   Direct tones", "Scale (uV per count), linearity and frequency response from 10 Hz levels and 0.5-40 Hz tones. 2 min.", () -> { d[0].dismiss(); startDirect('D'); });
        row(box, "E   ECG scenarios A", "Known beats: regular rhythm, muscle and electrode noise at set levels, set jitter. 6.5 min.", () -> { d[0].dismiss(); startDirect('E'); });
        row(box, "F   ECG scenarios B", "Amplitude ladder, rate ramp and step, premature beats, pauses, hum, wander. 7.5 min.", () -> { d[0].dismiss(); startDirect('F'); });
        row(box, "G   AF replay", "A real atrial-fibrillation record, 2 minutes: the strap's and the app's verdict against a known rhythm. 2.5 min.", () -> { d[0].dismiss(); startDirect('G'); });
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
        if (SimDirect.isDirect(mode)) {      // 0.52.1
            if (track == null && !stopAudio && dtrack != null && elMs >= (long) (SimDirect.AUDIO_AT_S * 1000)) playDirect(dtrack, true);
            double ps = elMs / 1000.0 - SimDirect.AUDIO_AT_S; String seg = "";
            if (dtrack != null && ps >= 0) for (SimDirect.Seg sg : dtrack.segs) if (ps >= sg.start && ps < sg.end) { seg = sg.name; break; }
            int left = (int) Math.max(0, (planMs - elMs) / 1000);
            p.simCue(ps < 0 ? "Signal starts in " + (int) Math.ceil(-ps) + " s. Keep still, no touching the wiring." : SimDirect.title(mode) + (seg.isEmpty() ? "" : "  \u00b7  " + seg) + String.format(Locale.UK, "  \u00b7  %d:%02d left", left / 60, left % 60));
            return elMs >= planMs;
        }
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
        stopAudio = true; playGen++;
        try { if (volBefore >= 0) { AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE); am.setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0); } } catch (Throwable ignored) { }
        volBefore = -1; track = null;
    }

    // ------------------------------------------------------------------ after the reading
    void finished(final long startSec, final File dir) {
        if (!active) return; active = false; stopAudio();
        final char m = mode;
        if (SimDirect.isDirect(m)) { analyseDirect(startSec, dir, m); return; }      // 0.52.1
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

    // ------------------------------------------------------------------ 0.52.1: direct runs (SimDirect)
    private SimDirect.Track dtrack; private volatile int playGen;

    private SharedPreferences ctl() { return a.getSharedPreferences("labrador_ecg_control", android.content.Context.MODE_PRIVATE); }

    /** The phone's full-scale peak voltage in mV from the stored meter readings (100 Hz reading first, else 1 kHz); NaN when none. rms at -20 dBFS = 0.1 x peak / sqrt 2. */
    private double vfsPkMv() {
        double v100 = parse(ctl().getString("sim_cal_v100", "")), v1k = parse(ctl().getString("sim_cal_v1k", "")); double v = !Double.isNaN(v100) && v100 > 0 ? v100 : v1k;
        return Double.isNaN(v) || v <= 0 ? Double.NaN : v * Math.sqrt(2) / 0.1;
    }
    private String calText() {
        double v = vfsPkMv(); if (Double.isNaN(v)) return "No calibration stored yet.";
        long ts = ctl().getLong("sim_cal_ts", 0L); double v1k = parse(ctl().getString("sim_cal_v1k", "")), v30 = parse(ctl().getString("sim_cal_v1k30", ""));
        String chk = !Double.isNaN(v1k) && !Double.isNaN(v30) && v1k > 0 ? String.format(Locale.UK, "; -30 over -20 dBFS reads %.3f (0.316 expected)", v30 / v1k) : "";
        return String.format(Locale.UK, "Stored calibration: full scale %.0f mV peak, %s%s.", v, new java.text.SimpleDateFormat("d MMM HH:mm", Locale.UK).format(new java.util.Date(ts)), chk);
    }

    private void startDirect(final char m) {
        if (p.isRunning()) { a.toast("A reading is running."); return; }
        if (!hasWiredOut()) { a.toast("Plug the audio cable in first: the direct runs play through this phone's wired or USB-C output."); return; }
        playGen++; if (mode == 'M') stopAudio();      // ends the calibration tones at once if they are still playing
        new android.app.AlertDialog.Builder(a).setTitle(SimDirect.title(m) + ": direct connection")
            .setMessage("Cable from this phone straight to the two pods, NO divider. Sound effects off (DSEE, equaliser, normaliser, Dolby). The media volume goes to maximum for the run; the signal itself stays under -48 dBFS, a few millivolts at the strap. Keep still and do not touch the wiring. Play nothing else while the cable is on the strap." + (Double.isNaN(vfsPkMv()) ? "\n\nNo meter calibration is stored: the run still records everything, but the scale can only be given after run M." : ""))
            .setPositiveButton("Start", (x, y) -> { try { internalAudio = true; dtrack = SimDirect.track(m); start(m, (long) ((SimDirect.AUDIO_AT_S + dtrack.seconds) * 1000) + 1000L); } catch (Throwable e) { a.toast("The run could not start: " + e.getClass().getSimpleName()); } })
            .setNegativeButton("Cancel", null).show();
    }

    private void startCal() {
        if (p.isRunning()) { a.toast("A reading is running."); return; }
        if (!hasWiredOut()) { a.toast("Plug the audio cable in first."); return; }
        new android.app.AlertDialog.Builder(a).setTitle("Meter calibration")
            .setMessage("The cable must be on the METER (AC millivolts, 200 mV range), NOT on the strap: these tones are 100 times larger than the direct runs. Sound effects off; the media volume goes to maximum and must stay there for the runs that follow.\n\nRead the meter at 0:05-0:17 (1 kHz), 0:20-0:32 (100 Hz) and 0:35-0:47 (1 kHz, smaller).")
            .setPositiveButton("The strap is NOT connected: play", (x, y) -> calForm())
            .setNegativeButton("Cancel", null).show();
    }

    private void calForm() {
        stopAudio = false; mode = 'M'; final SimDirect.Track tr = SimDirect.track('M'); playDirect(tr, false);
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(p.t("Type the meter readings in mV (AC, rms) as the tones play. Leave a field empty if you missed it; play M again to repeat.", 12, HealthViews.MUT, 400));
        final EditText f1 = num(box, "1 kHz at -20 dBFS (0:05-0:17)", ""), f2 = num(box, "100 Hz at -20 dBFS (0:20-0:32)", ""), f3 = num(box, "1 kHz at -30 dBFS (0:35-0:47)", "");
        final Dialog[] d = new Dialog[1];
        TextView go = p.pill("Save", HealthViews.BLUE, 0xFF05131E); go.setPadding(p.dp(18), p.dp(12), p.dp(18), p.dp(12));
        go.setOnClickListener(v -> {
            double a1 = parse(f1.getText().toString()), a2 = parse(f2.getText().toString());
            if ((Double.isNaN(a1) || a1 <= 0) && (Double.isNaN(a2) || a2 <= 0)) { a.toast("Enter at least one of the -20 dBFS readings."); return; }
            ctl().edit().putString("sim_cal_v1k", f1.getText().toString().trim()).putString("sim_cal_v100", f2.getText().toString().trim()).putString("sim_cal_v1k30", f3.getText().toString().trim()).putLong("sim_cal_ts", System.currentTimeMillis()).apply();
            d[0].dismiss(); a.toast(calText());
        });
        box.addView(go, p.lp(-1, -2, 14));
        d[0] = p.sheet("Meter calibration: readings", box); d[0].setOnDismissListener(x -> { if (mode == 'M') stopAudio(); }); d[0].show();
    }

    /** Plays a SimDirect track as 32-bit float (so that -60 dBFS keeps its resolution). reading = true: the play-start time is noted against the reading and the strap clock. */
    private void playDirect(final SimDirect.Track tr, final boolean reading) {
        try {
            AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
            volBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC); am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            a.toast("Media volume set to maximum for the run");
            final int buf = Math.max(AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), SR / 5 * 4);
            final AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build();
            track = t; final int gen = ++playGen;
            new Thread(new Runnable() { @Override public void run() {
                try {
                    long n = (long) (tr.seconds * SR); int chunk = SR / 10; float[] s = new float[chunk]; java.util.Random rng = new java.util.Random(7);
                    for (long i = 0; i < n && !stopAudio && gen == playGen; i += chunk) {
                        int m = (int) Math.min(chunk, n - i); SimDirect.fill(tr, s, m, i, rng);
                        if (i == 0) { t.play(); if (reading) { long now = System.currentTimeMillis(); playRelS = (now - startMs) / 1000.0; double[] lf = FrameMonitor.lastLive16(); if (lf != null) playStrapS = lf[1] + (now - lf[0]) / 1000.0; } }
                        t.write(s, 0, m, AudioTrack.WRITE_BLOCKING);
                    }
                } catch (Throwable ignored) { }
                try { t.stop(); t.release(); } catch (Throwable ignored) { }
                if (!reading) a.runOnUiThread(new Runnable() { @Override public void run() { if (mode == 'M' && gen == playGen) stopAudio(); } });      // calibration finished by itself: give the volume back
            } }, "sim-direct").start();
        } catch (Throwable e) { a.toast("Audio could not start: " + e.getClass().getSimpleName()); }
    }

    private void analyseDirect(final long startSec, final File dir, final char m) {
        a.toast("Analysing the run (a few seconds)...");
        final double pr = playRelS, vfs = vfsPkMv(); final SimDirect.Track tr = dtrack != null && dtrack.mode == m ? dtrack : null;
        new Thread(new Runnable() { @Override public void run() {
            SimDirect.Result R = null; String err = null;
            try {
                File bin = null; for (int i = 0; i < 10 && bin == null; i++) { bin = ProReportMaker.findBin(dir, startSec); if (bin == null) Thread.sleep(500); }
                if (bin == null) err = "No live raw file was found for this run.";
                else {
                    List<byte[]> fr = SpectrumBlock.r16(java.nio.file.Files.readAllBytes(bin.toPath()));
                    R = m == 'D' ? SimDirect.analyseTones(fr, vfs) : SimDirect.analyseEcg(tr != null ? tr : SimDirect.track(m), fr, vfs, pr);
                    if (!Double.isNaN(pr)) R.notes.put("sim_play_rel_s", String.format(Locale.US, "%.2f", pr));
                    R.notes.put("simd_cal_ts", String.valueOf(ctl().getLong("sim_cal_ts", 0L) / 1000L));
                    for (Map.Entry<String, String> e : R.notes.entrySet()) ReadingNotes.put(dir, startSec, e.getKey(), e.getValue());
                }
            } catch (Throwable e) { err = "Analysis failed: " + e.getClass().getSimpleName(); }
            final SimDirect.Result fr2 = R; final String fe = err;
            a.runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    StringBuilder s = new StringBuilder(); if (fe != null) s.append(fe); else for (String l : fr2.card) s.append(l).append("\n\n");
                    new android.app.AlertDialog.Builder(a).setTitle("Direct run: " + SimDirect.title(m)).setMessage(s.toString().trim() + (fe == null ? "\n\nThe numbers are in the reading's notes; send the Full pull as usual." : "")).setPositiveButton("OK", null).show();
                } catch (Throwable ignored) { }
            } });
        } }, "sim-direct-analyse").start();
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

package com.noop.mgecg;

// FILE VERSION 0.43.0 (6 Nov 2026): ONE "Send" per reading (decode log 50.6 item 1a).
// After Stop the app prepares the complete pull by itself: a strap pull at 12 s (and at 70 s), then every 2 s it checks that the strap's end events (118 and 119) of THIS reading are
// stored, rewrites the report with them (EcgReport.refreshLastWhy, every try noted as refresh_try<n>) and marks the reading ready. The button reads "Preparing... N s", then "Send";
// a tap before it is ready waits and sends when ready. Send saves MG_full_pull_<start>.zip exactly as the old Full pull did (ReadingPull). Works for readings WITHOUT a strap verdict.
// State lives in send_state.csv (startSec,status,epochMs,flag; status prep / ready / sent) so every screen, also the report screen in its own process, shows the same button.

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SendFlow {
    private SendFlow() {}

    static final String STATE = "send_state.csv";
    static final long PREP_CAP_S = 300L, STALE_MS = 420000L;
    private static final Handler H = new Handler(Looper.getMainLooper());

    static final class St { int kind; long stopMs; boolean noEvents; }      // kind 0 none, 1 preparing, 2 ready, 3 sent

    // ------------------------------------------------------------------ state file
    static synchronized void put(File dir, long startSec, String status, String flag) {
        if (dir == null) return;
        try (FileWriter w = new FileWriter(new File(dir, STATE), true)) { w.write(startSec + "," + status + "," + System.currentTimeMillis() + "," + (flag == null ? "" : flag) + "\n"); } catch (Exception ignored) { }
    }

    private static List<String[]> lines(File dir) {
        List<String[]> out = new ArrayList<>();
        if (dir == null) return out;
        File f = new File(dir, STATE); if (!f.exists() || f.length() > 3000000) return out;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) { String ln; while ((ln = br.readLine()) != null) { String[] c = ln.split(",", -1); if (c.length >= 3) out.add(c); } } catch (Exception ignored) { }
        return out;
    }

    /** The newest state of the reading that started within 30 s of startSec (kind 0 when the app never prepared it: then Send works at once). */
    static St status(File dir, long startSec) {
        St s = new St();
        for (String[] c : lines(dir)) {
            long k; try { k = Long.parseLong(c[0]); } catch (NumberFormatException e) { continue; }
            if (Math.abs(k - startSec) > 30) continue;
            long ms = 0; try { ms = Long.parseLong(c[2]); } catch (NumberFormatException ignored) { }
            String st = c[1];
            if (st.equals("prep")) { s.kind = 1; s.stopMs = ms; s.noEvents = false; }
            else if (st.equals("ready")) { s.kind = 2; s.noEvents = c.length > 3 && c[3].trim().equals("noev"); }
            else if (st.equals("sent")) s.kind = 3;
        }
        if (s.kind == 1 && System.currentTimeMillis() - s.stopMs > STALE_MS) s.kind = 2;      // a preparation that never finished (app killed) must not block Send
        return s;
    }

    /** Readings this app prepared and nobody has sent yet, newest first, at most 8 (phone start seconds). */
    static List<Long> unsent(File dir) {
        List<String[]> ls = lines(dir);
        List<Long> preps = new ArrayList<>(); List<Long> sents = new ArrayList<>();
        for (String[] c : ls) { long k; try { k = Long.parseLong(c[0]); } catch (NumberFormatException e) { continue; } if (c[1].equals("prep") && !preps.contains(k)) preps.add(k); else if (c[1].equals("sent")) sents.add(k); }
        List<Long> out = new ArrayList<>();
        for (long p : preps) { boolean sent = false; for (long s : sents) if (Math.abs(s - p) <= 30) { sent = true; break; } if (!sent) out.add(p); }
        Collections.sort(out, Collections.reverseOrder());
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    // ------------------------------------------------------------------ files of a reading
    /** The report file of the reading that started within 30 s of startSec (phone and strap start differ by about 2 s), or null. */
    static File findReport(File dir, long startSec) {
        if (dir == null) return null;
        File best = null; long bd = Long.MAX_VALUE;
        for (File f : NewLookActivity.listReportFiles(dir)) { long u = ProReportMaker.unixOf(f); long d = Math.abs(u - startSec); if (d <= 30 && d < bd) { bd = d; best = f; } }
        return best;
    }

    /** No report yet (or none will come): a report-named file that does not exist, built from the live raw file, so the pull still holds everything else. Null when nothing was recorded. */
    static File pseudoReport(File dir, long startSec) {
        try {
            File bin = ProReportMaker.findBin(dir, startSec); if (bin == null) return null;
            String n = bin.getName(); long u = Long.parseLong(n.substring(17, n.length() - 4));
            return new File(dir, "ecg_report_" + u + ".html");
        } catch (Throwable t) { return null; }
    }

    static boolean eventsStored(android.content.Context app, long startSec, long durS) {
        try {
            long from = startSec - 20, to = startSec + durS + 150;
            return !StrapEvents.raw(app, 118, from, to, 1).isEmpty() && !StrapEvents.raw(app, 119, from, to, 1).isEmpty();
        } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------ the preparation after Stop
    /** Called once from EcgPage.finishReading, first thing. pull = ask the strap link for a pull (run on the main thread); onRefreshed = the toast. */
    static void begin(final android.content.Context app, final File dir, final long startSec, final long stopMs, final long durS, final Runnable pull, final Runnable onRefreshed) {
        put(dir, startSec, "prep", "");
        ReadingNotes.put(dir, startSec, "prepare", "start");
        new Thread(new Runnable() { @Override public void run() {
            try { prepare(app, dir, startSec, stopMs, durS, pull, onRefreshed); }
            catch (Throwable t) { ReadingNotes.put(dir, startSec, "prepare", "error_" + t.getClass().getSimpleName()); put(dir, startSec, "ready", "noev"); }
        } }, "send-prep").start();
    }

    private static void prepare(android.content.Context app, File dir, long startSec, long stopMs, long durS, Runnable pull, Runnable onRefreshed) throws Exception {
        boolean pulled12 = false, pulled70 = false, noted90 = false, readyMarked = false; int tryNo = 0; long nextTry = 0; String lastWhy = ""; boolean ev = false;
        while (true) {
            long el = (System.currentTimeMillis() - stopMs) / 1000L;
            if (!pulled12 && el >= 12) { pulled12 = true; if (pull != null) H.post(pull); }
            if (!pulled70 && el >= 70) { pulled70 = true; if (pull != null) H.post(pull); }
            boolean evNow = eventsStored(app, startSec, durS);
            if (evNow && !ev) { ev = true; ReadingNotes.put(dir, startSec, "events_118_119_stored_s", String.valueOf(el)); if (readyMarked) put(dir, startSec, "ready", ""); }
            if (ev && el >= nextTry) {
                String why = EcgReport.refreshLastWhy(startSec);
                if (!why.equals(lastWhy)) { ReadingNotes.put(dir, startSec, "refresh_try" + (++tryNo), why); lastWhy = why; }
                if ("ok".equals(why)) {
                    ReadingNotes.put(dir, startSec, "prepare", "ready_" + el + "s"); put(dir, startSec, "ready", "");
                    if (onRefreshed != null) H.post(onRefreshed);
                    return;
                }
                nextTry = el + (why.startsWith("no_report") || why.startsWith("other_reading") ? 2 : 10);
            }
            if (el >= 90 && !noted90) {
                noted90 = true;
                if (!ev) ReadingNotes.put(dir, startSec, "prepare", "no_end_events_90s");
                if (!ev && findReport(dir, startSec) != null) { put(dir, startSec, "ready", "noev"); readyMarked = true; }      // a reading the strap sent no end events for (under 30 s, or none arrived): Send is allowed, the card says so
            }
            if (el >= PREP_CAP_S) {
                ReadingNotes.put(dir, startSec, "prepare", "timeout_events_" + (ev ? "yes" : "no") + "_" + lastWhy);
                put(dir, startSec, "ready", ev ? "" : "noev");
                return;
            }
            Thread.sleep(2000L);
        }
    }

    // ------------------------------------------------------------------ the Send button (any screen)
    private static final class Binding {
        Activity a; TextView b; long startSec; File html; boolean looping, waiting; long tapMs;
        final Runnable loop = new Runnable() { @Override public void run() {
            looping = false;
            try { if (b == null || !b.isAttachedToWindow() || a == null || a.isFinishing()) return; refresh(); } catch (Throwable ignored) { }
        } };
        void refresh() {
            File dir = a.getExternalFilesDir(null);
            St s = status(dir, startSec);
            if (waiting) {
                if (s.kind != 1) { waiting = false; go(); }
                else b.setText("Sending when ready\u2026");
            } else if (s.kind == 1) b.setText("Preparing\u2026 " + Math.max(0, (System.currentTimeMillis() - s.stopMs) / 1000L) + " s");
            else b.setText(s.kind == 3 ? "Send again" : "Send");
            if (waiting || s.kind == 1) { if (!looping) { looping = true; b.postDelayed(loop, 1000L); } }
        }
        void go() { doSend(a, startSec, html); refresh(); }
    }

    /** Makes b the Send button of the reading that started at startSec (html = its report file when known). Safe to call again to point it at another reading. */
    static void attach(final Activity a, final TextView b, long startSec, File html) {
        Binding bd = b.getTag() instanceof Binding ? (Binding) b.getTag() : null;
        if (bd == null) {
            final Binding nb = new Binding(); nb.a = a; nb.b = b; b.setTag(nb); bd = nb;
            b.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) {
                St s = status(a.getExternalFilesDir(null), nb.startSec);
                if (s.kind == 1) { nb.waiting = true; Toast.makeText(a, "It will send as soon as it is ready.", Toast.LENGTH_SHORT).show(); nb.refresh(); } else nb.go();
            } });
            b.setOnLongClickListener(new android.view.View.OnLongClickListener() { @Override public boolean onLongClick(android.view.View v) { share(a, nb.startSec, nb.html); return true; } });
        }
        bd.a = a; bd.startSec = startSec; bd.html = html; bd.waiting = false;
        try { bd.refresh(); } catch (Throwable ignored) { }
    }

    static void doSend(final Activity a, final long startSec, File html0) {
        final File dir = a.getExternalFilesDir(null);
        File h = html0 != null && html0.exists() ? html0 : findReport(dir, startSec);
        if (h == null) h = pseudoReport(dir, startSec);
        if (h == null) { Toast.makeText(a, "Nothing is stored for this reading.", Toast.LENGTH_LONG).show(); return; }
        ReadingPull.make(a, h, new Runnable() { @Override public void run() { put(dir, startSec, "sent", ""); } });
    }

    /** Long press on Send: the Android share sheet for the newest saved zip of this reading (no new permission: the file is in this app's own Downloads entry). */
    static void share(Activity a, long startSec, File html0) {
        try {
            Uri u = ReadingPull.lastUri;
            if (u == null) { Toast.makeText(a, "Tap Send first, then long-press to share the saved file.", Toast.LENGTH_LONG).show(); return; }
            Intent i = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "Send the zip"));
        } catch (Throwable t) { Toast.makeText(a, "Could not open the share sheet.", Toast.LENGTH_SHORT).show(); }
    }

    /** A finished guided test sends itself: waits (without a button, also if the screen is left) until the preparation is over, then saves the zip. */
    static void autoSend(final Activity a, final long startSec) {
        final long t0 = System.currentTimeMillis();
        new Runnable() { @Override public void run() {
            try {
                St s = status(a.getExternalFilesDir(null), startSec);
                if (s.kind == 1 && System.currentTimeMillis() - t0 < 420000L) { H.postDelayed(this, 1500L); return; }
                doSend(a, startSec, null);
            } catch (Throwable ignored) { }
        } }.run();
    }

    // ------------------------------------------------------------------ Home: Send all new
    static int newCount(File dir) { return unsent(dir).size(); }

    /** ONE zip with a folder per reading not yet sent (at most 8). Waits while one of them is still preparing. */
    static void sendAllNew(final Activity a, final TextView label) {
        final File dir = a.getExternalFilesDir(null);
        final List<Long> starts = unsent(dir);
        if (starts.isEmpty()) { Toast.makeText(a, "Nothing new to send.", Toast.LENGTH_SHORT).show(); return; }
        final long t0 = System.currentTimeMillis();
        new Runnable() { @Override public void run() {
            try {
                boolean prep = false; for (long s : starts) if (status(dir, s).kind == 1) { prep = true; break; }
                if (prep && System.currentTimeMillis() - t0 < 420000L) { if (label != null) label.setText("Waiting for the last reading\u2026"); H.postDelayed(this, 1500L); return; }
                final List<File> files = new ArrayList<>(); final List<Long> used = new ArrayList<>();
                for (long s : starts) { File h = findReport(dir, s); if (h == null) h = pseudoReport(dir, s); if (h != null) { files.add(h); used.add(s); } }
                if (files.isEmpty()) { Toast.makeText(a, "Nothing is stored for these readings.", Toast.LENGTH_LONG).show(); return; }
                ReadingPull.makeBatch(a, files, new Runnable() { @Override public void run() { for (long s : used) put(dir, s, "sent", ""); } });
            } catch (Throwable ignored) { }
        } }.run();
    }
}

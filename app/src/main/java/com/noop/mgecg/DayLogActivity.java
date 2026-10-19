package com.noop.mgecg;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Control and context screen for the all-day logger: on/off, pull interval, markers, cuff readings, export. */
public class DayLogActivity extends Activity {
    private static final int BG = Color.parseColor("#0B1220");
    private static final int FG = Color.parseColor("#E6EEF8");
    private static final int ACCENT = Color.parseColor("#22E6D6");
    private TextView status, out;
    private long lastSummaryMs = 0L;
    private boolean showSleep = false;
    private SleepChartView chart;
    private LinearLayout chartNav;
    private int chartBack = 0;
    private Button toggle;
    private TextView h10Status;
    private Button h10Toggle;
    private Button h10Preview;
    private H10TraceView h10Trace;
    private boolean pendingPreview = false;
    private EditText sys, dia, pulse, note;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 1000L); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        status = text("", 13, false);
        col.addView(status);

        toggle = btn("", new View.OnClickListener() { @Override public void onClick(View v) { toggleLogger(); } });
        col.addView(toggle);

        col.addView(label("POLAR H10 CHEST STRAP (raw ECG, same phone clock as the MG)"));
        h10Preview = btn("", new View.OnClickListener() { @Override public void onClick(View v) { togglePreview(); } });
        col.addView(h10Preview);
        h10Trace = new H10TraceView(this);
        col.addView(h10Trace, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
        h10Toggle = btn("", new View.OnClickListener() { @Override public void onClick(View v) { toggleH10(); } });
        col.addView(h10Toggle);
        col.addView(btn("OVERNIGHT: BATTERY SETTINGS", new View.OnClickListener() { @Override public void onClick(View v) {
            toast("Tap Battery, then choose Unrestricted");
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Throwable t) { toast("Open Settings > Apps > this app > Battery"); }
        } }));
        h10Status = text("", 12, false);
        col.addView(h10Status);
        String[][] h10m = {{"REST START", "rest_start"}, {"REST END", "rest_end"}, {"SQUATS START", "squats_start"}, {"SQUATS DONE", "squats_done"},
                {"WALK START", "walk_start"}, {"WALK END", "walk_end"}, {"STAIRS START", "stairs_start"}, {"STAIRS DONE", "stairs_done"},
                {"FINGER ON MG", "finger_on_mg"}, {"FINGER OFF MG", "finger_off_mg"}};
        for (int hi = 0; hi < h10m.length; hi += 2) {
            LinearLayout hr = row();
            for (int hj = hi; hj < hi + 2 && hj < h10m.length; hj++) {
                final String hname = h10m[hj][0], hkind = h10m[hj][1];
                hr.addView(weight(btn(hname, new View.OnClickListener() {
                    @Override public void onClick(View v) { toast(PolarH10.wanted && PolarH10.recordToFile ? PolarH10.mark(hkind) : "Start the H10 recording first"); }
                })));
            }
            col.addView(hr);
        }

        col.addView(label("PULL THE STRAP'S HISTORY EVERY"));
        LinearLayout iv = row();
        for (final int m : new int[] {15, 30, 60}) {
            iv.addView(weight(btn(m + " min", new View.OnClickListener() {
                @Override public void onClick(View v) { DayLog.setIntervalMin(DayLogActivity.this, m); refresh(); }
            })));
        }
        col.addView(iv);

        LinearLayout r2 = row();
        r2.addView(weight(btn("PULL NOW", new View.OnClickListener() {
            @Override public void onClick(View v) { MainActivity.requestPull(); toast("Asked the main app to pull; it must be open and connected"); }
        })));
        r2.addView(weight(btn("EXPORT CSV", new View.OnClickListener() {
            @Override public void onClick(View v) { toast(DayLog.exportCsv(DayLogActivity.this)); }
        })));
        col.addView(r2);
        col.addView(btn("BATTERY SETTINGS (choose this app, then Don't optimise)", new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        }));

        col.addView(label("MARKERS (time-stamped, for correlating later)"));
        String[][] marks = {{"CAFFEINE", "caffeine"}, {"MEAL", "meal"}, {"WORKOUT START", "workout_start"}, {"WORKOUT END", "workout_end"},
                {"STRESS", "stress"}, {"SYMPTOM", "symptom"}, {"GOING TO BED", "bed"}, {"WOKE UP", "woke"}};
        for (int i = 0; i < marks.length; i += 2) {
            LinearLayout r = row();
            for (int j = i; j < i + 2 && j < marks.length; j++) {
                final String name = marks[j][0], kind = marks[j][1];
                r.addView(weight(btn(name, new View.OnClickListener() {
                    @Override public void onClick(View v) { DayLog.addEvent(DayLogActivity.this, "marker", kind); toast(name + " saved"); DayLogService.refresh(DayLogActivity.this); }
                })));
            }
            col.addView(r);
        }

        col.addView(btn("SECOND OPINION: ALADIN IN TERMUX", new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(DayLogActivity.this, AladinActivity.class)); } }));

        col.addView(label("CUFF READING"));
        LinearLayout bp = row();
        sys = num("sys"); dia = num("dia"); pulse = num("pulse");
        bp.addView(weight(sys)); bp.addView(weight(dia)); bp.addView(weight(pulse));
        col.addView(bp);
        col.addView(btn("SAVE CUFF READING", new View.OnClickListener() { @Override public void onClick(View v) { saveBp(); } }));

        col.addView(label("NOTE"));
        note = new EditText(this);
        note.setTextColor(FG); note.setHintTextColor(Color.GRAY); note.setHint("free text");
        col.addView(note);
        col.addView(btn("SAVE NOTE", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String t = note.getText().toString().trim();
                if (t.length() == 0) return;
                DayLog.addEvent(DayLogActivity.this, "note", t); note.setText(""); toast("note saved");
            }
        }));

        col.addView(label("SUMMARY"));
        LinearLayout r3 = row();
        r3.addView(weight(btn("SLEEP REPORT", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSleep = true;
                hideChart();
                out.setText("working...");
                new Thread(new Runnable() {
                    @Override public void run() {
                        final String t = DayLog.sleepReport(DayLogActivity.this, 7);
                        runOnUiThread(new Runnable() { @Override public void run() { out.setText(t); } });
                    }
                }).start();
            }
        })));
        r3.addView(weight(btn("24 H SUMMARY", new View.OnClickListener() {
            @Override public void onClick(View v) { showSleep = false; lastSummaryMs = 0L; hideChart(); refresh(); }
        })));
        col.addView(r3);
        col.addView(btn("SLEEP CHART", new View.OnClickListener() {
            @Override public void onClick(View v) { showSleep = true; chartBack = 0; showChart(); }
        }));
        out = text("", 12, false);
        col.addView(out);
        chartNav = row();
        chartNav.addView(weight(btn("\u25C0 OLDER NIGHT", new View.OnClickListener() {
            @Override public void onClick(View v) { chartBack++; showChart(); }
        })));
        chartNav.addView(weight(btn("NEWER NIGHT \u25B6", new View.OnClickListener() {
            @Override public void onClick(View v) { if (chartBack > 0) chartBack--; showChart(); }
        })));
        chartNav.setVisibility(View.GONE);
        col.addView(chartNav);
        chart = new SleepChartView(this);
        chart.setVisibility(View.GONE);
        col.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 0.5.8: a bottom way home too, since the page is long
        Button homeBottom = btn("\u2190 HOME", new View.OnClickListener() { @Override public void onClick(View v) { goHome(); } });
        LinearLayout.LayoutParams hbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hbp.topMargin = dp(16);
        col.addView(homeBottom, hbp);

        // 0.5.8: a bar that stays on screen while the page scrolls, so the way home is always one tap away
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        LinearLayout bar = row();
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setBackgroundColor(Color.parseColor("#131A26"));
        Button homeTop = btn("\u2190 HOME", new View.OnClickListener() { @Override public void onClick(View v) { goHome(); } });
        bar.addView(homeTop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView barTitle = text("MG DAY LOGGER", 16, true);
        barTitle.setPadding(dp(14), 0, 0, 0);
        bar.addView(barTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    /** Back to the app's Home screen. From the main app this just closes this page; from the notification or the
     *  second launcher icon the main app is started (or brought forward) first. */
    private void goHome() {
        if (isTaskRoot() || MainActivity.instance == null) {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        }
        finish();
    }

    @Override public void onBackPressed() {
        if (isTaskRoot() || MainActivity.instance == null) goHome(); else super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    private void toggleLogger() {
        boolean on = !DayLog.enabled(this);
        DayLog.setEnabled(this, on);
        if (on) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 31);
            DayLogService.start(this);
            if (MainActivity.instance == null) {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
                toast("Logger on. The main app opens so it can connect to the strap.");
            } else {
                toast("Logger on. The main app is already running and will connect to the strap.");
            }
        } else {
            DayLogService.stop(this);
            toast("Logger off");
        }
        refresh();
    }

    private void toggleH10() {
        if (PolarH10.wanted && !PolarH10.recordToFile) { PolarH10.beginRecording(); toast("Recording started"); refresh(); return; }
        if (PolarH10.wanted) { PolarH10.stop(); toast("H10 recording stopped. The files are saved."); refresh(); return; }
        String[] miss = PolarH10.missingPermissions(this);
        if (miss.length > 0) { pendingPreview = false; requestPermissions(miss, 51); return; }
        PolarH10.start(this);
        refresh();
    }

    private void togglePreview() {
        if (PolarH10.wanted && PolarH10.recordToFile) { toast("Already recording. Stop it first."); return; }
        if (PolarH10.wanted) { PolarH10.stop(); toast("Preview stopped. Nothing was saved."); refresh(); return; }
        String[] miss = PolarH10.missingPermissions(this);
        if (miss.length > 0) { pendingPreview = true; requestPermissions(miss, 51); return; }
        PolarH10.startPreview(this);
        refresh();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 51) return;
        boolean essential = true;
        for (int i = 0; i < perms.length; i++) if (res[i] != android.content.pm.PackageManager.PERMISSION_GRANTED && !perms[i].equals("android.permission.POST_NOTIFICATIONS")) essential = false;
        if (essential) { if (pendingPreview) PolarH10.startPreview(this); else PolarH10.start(this); refresh(); }
        else toast("Bluetooth permission is needed to find the H10");
    }

    private void saveBp() {
        try {
            int s = Integer.parseInt(sys.getText().toString().trim());
            int d = Integer.parseInt(dia.getText().toString().trim());
            int p = Integer.parseInt(pulse.getText().toString().trim());
            if (s < 60 || s > 260 || d < 30 || d > 160 || p < 30 || p > 220 || d >= s) { toast("Those numbers do not look right"); return; }
            DayLog.addEvent(this, "bp", s + "/" + d + " pulse " + p);
            sys.setText(""); dia.setText(""); pulse.setText("");
            toast("cuff reading saved");
        } catch (NumberFormatException e) { toast("Enter all three numbers"); }
    }

    private void refresh() {
        toggle.setText(DayLog.enabled(this) ? "LOGGER IS ON (tap to turn off)" : "LOGGER IS OFF (tap to turn on)");
        status.setText(DayLog.statusLine(this) + "\npull interval " + DayLog.intervalMin(this) + " min");
        boolean prev = PolarH10.wanted && !PolarH10.recordToFile;
        h10Toggle.setText(PolarH10.wanted ? (prev ? "START RECORDING (preview is on)" : "H10 IS RECORDING (tap to stop)") : "START H10 RECORDING");
        h10Preview.setText(prev ? "STOP PREVIEW" : (PolarH10.wanted ? "(recording)" : "H10 LIVE PREVIEW (nothing is saved)"));
        h10Preview.setEnabled(!(PolarH10.wanted && PolarH10.recordToFile));
        String hs = PolarH10.status();
        h10Status.setText(hs + (PolarH10.wanted || hs.startsWith("STOPPED") ? "\n" + PolarH10.logTail(6) : ""));
        long now = System.currentTimeMillis();
        if (!showSleep && now - lastSummaryMs > 60000L) { lastSummaryMs = now; out.setText(DayLog.summary(this)); }   // the summary reads a day of records, so not every 5 s
    }

    private void hideChart() { chart.setVisibility(View.GONE); chartNav.setVisibility(View.GONE); }

    /** Loads the chosen night off the main thread and shows its chart, with that night's report text above it. */
    private void showChart() {
        if (chart.getVisibility() != View.VISIBLE) out.setText("working...");
        final int back = chartBack;
        new Thread(new Runnable() {
            @Override public void run() {
                final SleepNight.Series s = DayLog.sleepSeries(DayLogActivity.this, back);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (s == null) {
                            if (back > 0) { chartBack = back - 1; toast("No older night in the log"); }
                            else {
                                chart.setVisibility(View.GONE);
                                chartNav.setVisibility(View.GONE);
                                out.setText("No complete night in the log yet (it needs about 3 hours of SLEEP state). Pull the strap's history in the morning.");
                            }
                            return;
                        }
                        chartNav.setVisibility(View.VISIBLE);
                        out.setText("Night " + (s.index + 1) + " of " + s.count + " (touch the chart to read a minute)\n" + s.report);
                        chart.setData(s);
                        chart.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ small view helpers
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(bold ? ACCENT : FG);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }
    private TextView label(String s) { TextView t = text(s, 12, true); t.setPadding(0, dp(12), 0, dp(2)); return t; }
    private Button btn(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l); return b; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private View weight(View v) { v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); return v; }
    private EditText num(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(Color.GRAY); e.setTextColor(FG); e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}

/**
 * Polar H10 data frames, decoded with no Android classes so they can be tested on a desktop.
 * ECG comes over Polar's "PMD" service: control point FB005C81, data FB005C82 (both under service FB005C80).
 * Heart rate and beat-to-beat (RR) intervals come over the standard heart-rate service 0x180D / 0x2A37.
 * Research use only.
 */
final class PolarPmd {
    private PolarPmd() { }

    /** Request measurement start: ECG (type 0), sample rate 130 Hz (setting 0), resolution 14 bit (setting 1). */
    public static final byte[] START_ECG = {0x02, 0x00, 0x00, 0x01, (byte) 0x82, 0x00, 0x01, 0x01, 0x0E, 0x00};
    /** Request measurement stop: ECG. */
    public static final byte[] STOP_ECG = {0x03, 0x00};
    /** The sensor's clock counts nanoseconds since 2000-01-01 00:00 UTC. */
    public static final long POLAR_EPOCH_UNIX_S = 946684800L;
    public static final double FS = 130.0;

    public static final class Ecg { public long tsNs; public int[] uv; }
    public static final class Hr { public int bpm; public double[] rrMs; }

    /** One ECG notification: type byte 0, 8-byte time of the LAST sample, frame type 0, then 3-byte signed samples in microvolts. */
    public static Ecg parseEcg(byte[] v) {
        if (v == null || v.length < 13) return null;
        if ((v[0] & 0xFF) != 0x00) return null;
        int frameType = v[9] & 0xFF;
        if (frameType != 0x00) return null;          // compressed (bit 7) or other frame types are not handled
        int n = (v.length - 10) / 3;
        Ecg e = new Ecg();
        e.tsNs = le64(v, 1);
        e.uv = new int[n];
        for (int i = 0; i < n; i++) {
            int o = 10 + 3 * i;
            int s = (v[o] & 0xFF) | ((v[o + 1] & 0xFF) << 8) | ((v[o + 2] & 0xFF) << 16);
            if ((s & 0x800000) != 0) s |= 0xFF000000;
            e.uv[i] = s;
        }
        return e;
    }

    /** Time of sample i (0 = oldest) in a frame of n samples whose last sample is at tsNs. */
    public static long sampleNs(long tsLastNs, int n, int i) {
        return tsLastNs - Math.round((n - 1 - i) * 1e9 / FS);
    }

    /** Control-point reply: {op code, measurement type, status, more}. Status 0 = success. */
    public static int[] parseCp(byte[] v) {
        if (v == null || v.length < 4 || (v[0] & 0xFF) != 0xF0) return null;
        return new int[] {v[1] & 0xFF, v[2] & 0xFF, v[3] & 0xFF, v.length > 4 ? v[4] & 0xFF : 0};
    }

    /** Standard Heart Rate Measurement: flags, heart rate (8 or 16 bit), optional energy, RR values in 1/1024 s. */
    public static Hr parseHr(byte[] v) {
        if (v == null || v.length < 2) return null;
        int flags = v[0] & 0xFF, i, bpm;
        if ((flags & 0x01) != 0) {
            if (v.length < 3) return null;
            bpm = (v[1] & 0xFF) | ((v[2] & 0xFF) << 8); i = 3;
        } else { bpm = v[1] & 0xFF; i = 2; }
        if ((flags & 0x08) != 0) i += 2;
        List<Double> rr = new ArrayList<Double>();
        if ((flags & 0x10) != 0) {
            while (i + 1 < v.length) {
                rr.add(((v[i] & 0xFF) | ((v[i + 1] & 0xFF) << 8)) * 1000.0 / 1024.0);
                i += 2;
            }
        }
        Hr h = new Hr(); h.bpm = bpm; h.rrMs = new double[rr.size()];
        for (int k = 0; k < h.rrMs.length; k++) h.rrMs[k] = rr.get(k);
        return h;
    }

    public static long le64(byte[] v, int o) {
        long r = 0;
        for (int k = 7; k >= 0; k--) r = (r << 8) | (v[o + k] & 0xFF);
        return r;
    }

    public static String hex(byte[] v, int max) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length && i < max; i++) sb.append(String.format("%02X ", v[i] & 0xFF));
        if (v.length > max) sb.append("...(").append(v.length).append(" bytes)");
        return sb.toString().trim();
    }

    /** Plain-words reason for a control-point status code (Polar's numbering, as I remember it; the number is always shown too). */
    public static String statusText(int s) {
        switch (s) {
            case 0: return "ok";
            case 1: return "invalid op code";
            case 2: return "invalid measurement type";
            case 3: return "not supported (H10 firmware older than 3.0.35?)";
            case 4: return "invalid length";
            case 5: return "invalid parameter";
            case 6: return "invalid state (is another app streaming ECG?)";
            case 7: return "invalid resolution";
            case 8: return "invalid sample rate";
            default: return "see code";
        }
    }
}

/**
 * Records a Polar H10 chest strap's raw ECG (130 Hz, microvolts), heart rate and beat-to-beat intervals to CSV files
 * in the app's files folder, on the same phone clock as the MG logs. Research use only.
 *
 * Files (all in the same folder as the MG logs):
 *   h10_ecg_<unix s>.csv     phone_ms,sample_time_ns,uV     one per unbroken stretch (a new file starts after a reconnect)
 *   h10_rr_<session>.csv     phone_ms,bpm,rr_ms             the strap's own beat-to-beat intervals
 *   h10_events_<session>.csv phone_ms,label                 the MARK buttons
 *   h10_log_<session>.txt    what the Bluetooth link did, for fault-finding
 */
@SuppressLint("MissingPermission")
final class PolarH10 {
    private PolarH10() { }

    static final UUID PMD_SERVICE = UUID.fromString("FB005C80-02E7-F387-1CAD-8ACD2D8DF0C8");
    static final UUID PMD_CP = UUID.fromString("FB005C81-02E7-F387-1CAD-8ACD2D8DF0C8");
    static final UUID PMD_DATA = UUID.fromString("FB005C82-02E7-F387-1CAD-8ACD2D8DF0C8");
    static final UUID HR_SERVICE = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB");
    static final UUID HR_MEAS = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    private static final String PREFS = "h10";

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Runnable> steps = new ArrayDeque<Runnable>();
    private static final ArrayDeque<String> tail = new ArrayDeque<String>();

    static volatile boolean wanted = false;          // START pressed and STOP not yet pressed
    static volatile boolean recordToFile = true;     // false = live preview only: the trace is shown, nothing is written
    private static final int RING = 130 * 12;        // the last 12 s of ECG, for the live trace
    private static final int[] ring = new int[RING];
    private static long ringCount = 0L;
    private static String state = "idle";
    private static Context app;
    private static BluetoothGatt gatt;
    private static BluetoothGattCharacteristic cpChar;
    private static ScanCallback scanCb;
    private static boolean scanning = false, recording = false, everConnected = false;
    private static int attempts = 0;
    private static boolean linkUp = false;      // 0.5.14: true from the moment the phone's Bluetooth reports the H10 link up until the link is closed
    private static String addr;
    private static long sessionStamp = 0L, samples = 0L, frames = 0L, segStartMs = 0L, lastFrameMs = 0L;
    private static int lastHr = 0, lastP2p = 0, unknownLogged = 0;
    private static long cpOkMs = 0L, lastKickMs = 0L;   // watchdog: when the ECG stream last started, and when it was last restarted
    private static boolean kickPending = false;          // a watchdog restart is in flight: ignore its reply instead of treating a refusal as fatal
    private static String ecgPath = "";
    private static BufferedWriter ecgW, rrW, evW, logW;

    // ------------------------------------------------------------------ public controls (call from the UI thread)

    /** Runtime permissions still missing for Bluetooth scanning/connecting on this Android version. */
    public static String[] missingPermissions(Context c) {
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) need.add("android.permission.POST_NOTIFICATIONS");
        return need.toArray(new String[0]);
    }

    public static synchronized void start(Context c) { start(c, true); }

    /** Streams the ECG and shows the live trace, but saves nothing: to check the strap's contact before a recording. */
    public static synchronized void startPreview(Context c) { start(c, false); }

    /** Turns a running preview into a recording: files open at the next data frame. */
    public static synchronized void beginRecording() {
        recordToFile = true; log("preview -> recording");
        if (recording) set("RECORDING ECG");
    }

    /** The newest samples (microvolts), oldest first; returns how many were copied. */
    public static synchronized int recent(int[] out) {
        int n = (int) Math.min(Math.min(ringCount, (long) RING), (long) out.length);
        long first = ringCount - n;
        for (int i = 0; i < n; i++) out[i] = ring[(int) ((first + i) % RING)];
        return n;
    }

    public static int hrNow() { return lastHr; }

    public static synchronized void start(Context c, boolean record) {
        if (wanted) return;
        app = c.getApplicationContext();
        recordToFile = record; ringCount = 0L;
        wanted = true; everConnected = false; attempts = 0; samples = 0L; frames = 0L; lastHr = 0; lastP2p = 0; unknownLogged = 0; recording = false;
        sessionStamp = System.currentTimeMillis() / 1000L; cpOkMs = 0L; lastKickMs = 0L; kickPending = false;
        tail.clear();
        addr = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("addr", null);
        openLog();
        log("START pressed (" + (recordToFile ? "recording" : "preview only") + "), Android " + Build.VERSION.SDK_INT + ", saved address " + addr);
        DayLogService.start(app);
        H.postDelayed(notifyTick, 15000L);
        H.postDelayed(watchdog, 2000L);
        set("starting: looking for your H10 (strap on skin, electrodes wet)");
        scan();
    }

    public static synchronized void stop() {
        if (!wanted && !scanning && gatt == null) { return; }
        log("STOP pressed");
        wanted = false;
        H.removeCallbacks(notifyTick); H.removeCallbacks(scanTimeout); H.removeCallbacks(reconnect); H.removeCallbacks(watchdog);
        stopScan();
        if (gatt != null && cpChar != null && recording) {
            try { writeCp(PolarPmd.STOP_ECG); log("TX stop ECG"); } catch (Throwable t) { log("stop write failed " + t); }
            set("stopping");
            H.postDelayed(new Runnable() { @Override public void run() { finishStop(); } }, 600L);
        } else {
            finishStop();
        }
    }

    public static synchronized String mark(String label) {
        long now = System.currentTimeMillis();
        String clean = label.replace(',', ' ').replace('\n', ' ');
        try {
            if (evW == null) {
                evW = new BufferedWriter(new FileWriter(new File(app.getExternalFilesDir(null), "h10_events_" + sessionStamp + ".csv"), true));
                evW.write("phone_ms,label\n");
            }
            evW.write(now + "," + clean + "\n"); evW.flush();
        } catch (Throwable t) { log("mark write failed " + t); }
        try { DayLog.addEvent(app, "marker", "h10 " + clean); } catch (Throwable ignored) { }
        log("MARK " + clean);
        return clean + " saved";
    }

    public static synchronized String status() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(state);
        if (recording) {
            if (recordToFile) sb.append(String.format(Locale.UK, "\nrecorded %d s (%d samples) in this file", samples / 130L, samples));
            else sb.append("\npreview only: nothing is being saved");
            sb.append(lastFrameMs > 0 ? String.format(Locale.UK, "\nlast data %.1f s ago", (now - lastFrameMs) / 1000.0) : "\nwaiting for the first data");
            if (lastHr > 0) sb.append("\nstrap heart rate ").append(lastHr).append(" bpm");
            long age = lastDataAgeMs();
            if (age > 4000L) sb.append("\nNO DATA for ").append(age / 1000L).append(" s: the app will restart the stream, then reconnect");
            else {
                int[] qb = new int[130 * 6]; int qn = recent(qb); float[] qq = TraceMath.quality(qb, qn);
                switch ((int) qq[2]) {
                    case TraceMath.GOOD: sb.append("\nsignal GOOD (").append(Math.round(qq[0])).append(" uV)"); break;
                    case TraceMath.OK: sb.append("\nsignal OK (").append(Math.round(qq[0])).append(" uV)"); break;
                    case TraceMath.NOISY: sb.append("\nsignal NOISY: stay still, press the strap on"); break;
                    case TraceMath.WEAK: sb.append("\nsignal WEAK (").append(Math.round(qq[0])).append(" uV): wet the electrodes, tighten the strap"); break;
                    default: sb.append("\nchecking the signal ...");
                }
            }
            if (recordToFile) sb.append("\nfile ").append(ecgPath);
        }
        return sb.toString();
    }

    public static synchronized String logTail(int n) {
        StringBuilder sb = new StringBuilder();
        String[] all = tail.toArray(new String[0]);
        for (int i = Math.max(0, all.length - n); i < all.length; i++) sb.append(all[i]).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ scanning and connecting

    private static final Runnable scanTimeout = new Runnable() {
        @Override public void run() {
            synchronized (PolarH10.class) {
                if (!scanning) return;
                stopScan();
                log("SCAN timeout");
                if (everConnected && wanted) { set("H10 not in range, still looking"); H.postDelayed(reconnect, 4000L); return; }
                failStop("H10 not found. Put the strap on with wet electrodes, close Polar Flow and any other app using it, and switch Location ON in the phone settings (Android needs it for Bluetooth scanning).");
            }
        }
    };

    private static final Runnable reconnect = new Runnable() {
        @Override public void run() {
            synchronized (PolarH10.class) {
                if (!wanted) return;
                attempts++;
                log("RECONNECT attempt " + attempts);
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (addr != null && ad != null && attempts <= 3) {
                    try { connect(ad.getRemoteDevice(addr)); return; } catch (Throwable t) { log("direct connect failed " + t); }
                }
                scan();
            }
        }
    };

    private static final Runnable notifyTick = new Runnable() {
        @Override public void run() {
            if (!wanted) return;
            DayLogService.refresh(app);
            H.postDelayed(this, 15000L);
        }
    };

    /** Every 2 s: if the stream has gone quiet, first restart it, then drop and reconnect. Never ends the recording by itself. */
    private static final Runnable watchdog = new Runnable() {
        @Override public void run() {
            synchronized (PolarH10.class) {
                if (!wanted) return;
                try {
                    if (recording && gatt != null && cpChar != null) {
                        long now = System.currentTimeMillis();
                        long ref = Math.max(lastFrameMs, cpOkMs);
                        long silence = ref > 0 ? now - ref : 0L;
                        int act = H10Watchdog.decide(silence, lastKickMs == 0L ? -1L : now - lastKickMs);
                        if (act == H10Watchdog.KICK) {
                            lastKickMs = now; kickPending = true;
                            log("WATCHDOG no data for " + (silence / 1000) + " s: restarting the ECG stream");
                            set("no data for " + (silence / 1000) + " s, restarting the stream");
                            writeCp(PolarPmd.START_ECG);
                        } else if (act == H10Watchdog.RECONNECT) {
                            lastKickMs = 0L; kickPending = false;
                            log("WATCHDOG still no data after " + (silence / 1000) + " s: reconnecting");
                            onLost(-1);
                        }
                    }
                } catch (Throwable t) { log("watchdog error " + t); }
                H.postDelayed(this, 2000L);
            }
        }
    };

    /** Seconds since the last ECG data (or -1 when nothing is flowing). */
    public static synchronized long lastDataAgeMs() { return (recording && lastFrameMs > 0) ? System.currentTimeMillis() - lastFrameMs : -1L; }

    /** One short line for the notification. */
    public static synchronized String shortStatus() {
        if (!wanted) return "off";
        if (!recording) return state;
        long age = lastDataAgeMs();
        String t = recordToFile ? ("recording " + (samples / 130L) + " s") : "preview";
        if (age > 5000L) t = "NO DATA " + (age / 1000L) + " s";
        if (lastHr > 0) t += ", HR " + lastHr;
        return t;
    }

    private static void scan() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { failStop("Bluetooth is not available on this phone"); return; }
        if (!ad.isEnabled()) {
            // 0.5.14: Android's Bluetooth stack can switch itself off after a dropped H10 link (seen 4 times overnight on 18-19 Oct 2026). Do not end the
            // recording: wait and look again every 5 s, and carry on in a new file when Bluetooth is back. A START pressed with Bluetooth already off still stops with the message.
            if (wanted && everConnected) { set("Bluetooth is switched off, waiting for it to come back"); log("BT_OFF waiting"); H.removeCallbacks(reconnect); H.postDelayed(reconnect, 5000L); return; }
            failStop("Bluetooth is switched off"); return;
        }
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null) { failStop("Bluetooth scanner not available"); return; }
        stopScan();
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                String n = null;
                try { if (r.getScanRecord() != null) n = r.getScanRecord().getDeviceName(); } catch (Throwable ignored) { }
                try { if (n == null) n = r.getDevice().getName(); } catch (Throwable ignored) { }
                if (n != null && n.startsWith("Polar H10")) {
                    synchronized (PolarH10.class) {
                        if (!scanning) return;
                        stopScan();
                        log("SCAN found " + n + " " + r.getDevice().getAddress() + " rssi " + r.getRssi());
                        connect(r.getDevice());
                    }
                }
            }
            @Override public void onScanFailed(int code) {
                synchronized (PolarH10.class) { log("SCAN failed " + code); failStop("Scan failed (code " + code + "). Is Location switched ON?"); }
            }
        };
        try {
            sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
            scanning = true; log("SCAN started");
            H.postDelayed(scanTimeout, 25000L);
        } catch (Throwable t) { failStop("Could not start scanning: " + t); }
    }

    private static void stopScan() {
        H.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null && ad.getBluetoothLeScanner() != null && scanCb != null) ad.getBluetoothLeScanner().stopScan(scanCb);
        } catch (Throwable ignored) { }
    }

    private static void connect(BluetoothDevice d) {
        addr = d.getAddress();
        closeGatt();
        set("found H10, connecting");
        log("CONNECT " + addr);
        try { gatt = d.connectGatt(app, false, CB, BluetoothDevice.TRANSPORT_LE); }
        catch (Throwable t) { failStop("connect failed: " + t); }
        H.removeCallbacks(connectTimeout);
        H.postDelayed(connectTimeout, 30000L);
    }

    /** 0.5.14: a connect that never gets any answer (seen 00:24-00:44 UTC on 19 Oct 2026, 20 minutes stuck on "found H10, connecting") is dropped after 30 s and retried. */
    private static final Runnable connectTimeout = new Runnable() {
        @Override public void run() {
            synchronized (PolarH10.class) {
                if (!wanted || gatt == null || linkUp) return;
                log("CONNECT timeout: no answer from the phone's Bluetooth after 30 s");
                closeGatt();
                H.removeCallbacks(reconnect);
                H.postDelayed(reconnect, 5000L);
            }
        }
    };

    private static void closeGatt() {
        steps.clear(); cpChar = null; linkUp = false;
        if (gatt != null) { try { gatt.disconnect(); } catch (Throwable ignored) { } try { gatt.close(); } catch (Throwable ignored) { } gatt = null; }
    }

    // ------------------------------------------------------------------ the Bluetooth callbacks

    private static final BluetoothGattCallback CB = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            synchronized (PolarH10.class) {
                log("STATE status=" + status + " newState=" + newState);
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    linkUp = true;
                    set("connected, setting up");
                    H.postDelayed(new Runnable() { @Override public void run() {
                        synchronized (PolarH10.class) {
                            if (gatt == null) return;
                            boolean ok = false;
                            try { ok = gatt.requestMtu(232); } catch (Throwable ignored) { }
                            log("MTU request " + ok);
                            if (!ok) gatt.discoverServices();
                        }
                    } }, 400L);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    onLost(status);
                }
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            synchronized (PolarH10.class) {
                log("MTU " + mtu + " status " + status);
                if (g == gatt) g.discoverServices();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            synchronized (PolarH10.class) {
                if (g != gatt) return;
                log("SERVICES status " + status);
                BluetoothGattService pmd = g.getService(PMD_SERVICE);
                if (pmd == null) { failStop("This strap has no ECG service (not an H10, or firmware older than 3.0.35). Update it in the Polar Flow app."); return; }
                final BluetoothGattCharacteristic cp = pmd.getCharacteristic(PMD_CP), data = pmd.getCharacteristic(PMD_DATA);
                BluetoothGattService hrs = g.getService(HR_SERVICE);
                final BluetoothGattCharacteristic hr = hrs == null ? null : hrs.getCharacteristic(HR_MEAS);
                if (cp == null || data == null) { failStop("ECG characteristics missing"); return; }
                cpChar = cp;
                steps.clear();
                steps.add(new Runnable() { @Override public void run() { enable(cp, true); } });
                steps.add(new Runnable() { @Override public void run() { enable(data, false); } });
                if (hr != null) steps.add(new Runnable() { @Override public void run() { enable(hr, false); } });
                steps.add(new Runnable() { @Override public void run() {
                    set("starting ECG");
                    log("TX start ECG " + PolarPmd.hex(PolarPmd.START_ECG, 20));
                    if (!writeCp(PolarPmd.START_ECG)) failStop("could not send the ECG start command");
                } });
                nextStep();
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            synchronized (PolarH10.class) {
                log("CCCD " + d.getCharacteristic().getUuid().toString().substring(4, 8) + " status " + status);
                if (g != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { failStop("could not switch on notifications (status " + status + ")"); return; }
                nextStep();
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (PolarH10.class) {
                log("WRITE " + c.getUuid().toString().substring(4, 8) + " status " + status);
                if (status != BluetoothGatt.GATT_SUCCESS && wanted && g == gatt && !recording) failStop("ECG start was refused by the strap (write status " + status + ")");
            }
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) route(c.getUuid(), c.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            route(c.getUuid(), value);
        }
    };

    private static void route(UUID u, byte[] v) {
        synchronized (PolarH10.class) {
            if (!wanted) return;
            if (PMD_DATA.equals(u)) onData(v);
            else if (PMD_CP.equals(u)) onCp(v);
            else if (HR_MEAS.equals(u)) onHr(v);
        }
    }

    private static void nextStep() {
        Runnable r = steps.poll();
        if (r != null) r.run();
    }

    private static void enable(BluetoothGattCharacteristic c, boolean indicate) {
        try {
            gatt.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            d.setValue(indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(d)) failStop("could not write the notification switch");
        } catch (Throwable t) { failStop("notification setup failed: " + t); }
    }

    private static boolean writeCp(byte[] cmd) {
        if (gatt == null || cpChar == null) return false;
        try {
            cpChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cpChar.setValue(cmd);
            return gatt.writeCharacteristic(cpChar);
        } catch (Throwable t) { log("writeCp failed " + t); return false; }
    }

    // ------------------------------------------------------------------ incoming data

    private static void onCp(byte[] v) {
        log("CP " + PolarPmd.hex(v, 24));
        int[] r = PolarPmd.parseCp(v);
        if (r == null) return;
        if (r[0] == 2 && r[1] == 0) {
            if (kickPending) {                                   // reply to the watchdog's restart: log it, never stop the recording over it
                kickPending = false; log("watchdog restart reply: code " + r[2] + (r[2] == 0 ? " (stream restarted)" : " (" + PolarPmd.statusText(r[2]) + ")"));
                if (r[2] == 0) cpOkMs = System.currentTimeMillis();
                return;
            }
            if (r[2] == 0) { recording = true; attempts = 0; everConnected = true; cpOkMs = System.currentTimeMillis(); set(recordToFile ? "RECORDING ECG" : "PREVIEW (live trace only, nothing saved)"); }
            else failStop("ECG start refused, code " + r[2] + ": " + PolarPmd.statusText(r[2]));
        }
    }

    private static void onHr(byte[] v) {
        PolarPmd.Hr h = PolarPmd.parseHr(v);
        if (h == null) return;
        lastHr = h.bpm;
        if (h.rrMs.length == 0) return;
        try {
            if (rrW == null) {
                rrW = new BufferedWriter(new FileWriter(new File(app.getExternalFilesDir(null), "h10_rr_" + sessionStamp + ".csv"), true));
                rrW.write("phone_ms,bpm,rr_ms\n");
            }
            long now = System.currentTimeMillis();
            for (double rr : h.rrMs) rrW.write(now + "," + h.bpm + "," + String.format(Locale.US, "%.1f", rr) + "\n");
            rrW.flush();
        } catch (Throwable t) { log("rr write failed " + t); }
    }

    private static void onData(byte[] v) {
        PolarPmd.Ecg e = PolarPmd.parseEcg(v);
        if (e == null) {
            if (unknownLogged++ < 3) log("DATA not an ECG frame: " + PolarPmd.hex(v, 24));
            return;
        }
        long now = System.currentTimeMillis();
        if (!recording) { recording = true; attempts = 0; everConnected = true; set(recordToFile ? "RECORDING ECG" : "PREVIEW (live trace only, nothing saved)"); }
        int n = e.uv.length, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            ring[(int) (ringCount % RING)] = e.uv[i]; ringCount++;
            if (e.uv[i] < lo) lo = e.uv[i];
            if (e.uv[i] > hi) hi = e.uv[i];
        }
        frames++; lastFrameMs = now; lastP2p = hi - lo; lastKickMs = 0L; kickPending = false;
        if (frames == 1) log("first ECG frame: " + n + " samples, sensor time " + e.tsNs + " ns, first values " + e.uv[0] + " " + (n > 1 ? e.uv[1] : 0) + " " + (n > 2 ? e.uv[2] : 0));
        if (!recordToFile) return;
        try {
            if (ecgW != null && now - segStartMs > 30L * 60L * 1000L) { log("rolling to a new ECG file (30 min)"); closeEcgFile(); }
            if (ecgW == null) {
                File f = new File(app.getExternalFilesDir(null), "h10_ecg_" + (now / 1000L) + ".csv");
                ecgPath = f.getName();
                ecgW = new BufferedWriter(new FileWriter(f, true));
                ecgW.write("phone_ms,sample_time_ns,uV\n");
                samples = 0L; segStartMs = now;
                log("ECG file " + f.getAbsolutePath());
            }
            StringBuilder sb = new StringBuilder(n * 28);
            for (int i = 0; i < n; i++) sb.append(now).append(',').append(PolarPmd.sampleNs(e.tsNs, n, i)).append(',').append(e.uv[i]).append('\n');
            ecgW.write(sb.toString()); ecgW.flush();
            samples += n;
        } catch (Throwable t) { failStop("could not write the ECG file: " + t); }
    }

    // ------------------------------------------------------------------ losing and ending

    private static void onLost(int status) {
        closeGatt();
        closeEcgFile();
        recording = false;
        if (!wanted) return;
        if (!everConnected && attempts >= 2) { failStop("could not connect to the H10 (status " + status + "). If Android said it could not pair (incorrect PIN), forget the H10: Settings > Connected devices > Bluetooth > Polar H10 > Forget, then try again. Otherwise close Polar Flow / other apps using it."); return; }
        set("connection lost (status " + status + "), reconnecting");
        log("LOST status " + status);
        H.removeCallbacks(reconnect);
        // 0.5.14: after status 133 (generic GATT error) wait a little longer each time, up to 30 s, instead of hammering the Bluetooth stack every 3 s
        H.postDelayed(reconnect, status == 133 ? Math.min(30000L, 3000L + 4000L * attempts) : 3000L);
    }

    private static void failStop(String msg) {
        log("FAIL " + msg);
        wanted = false;
        H.removeCallbacks(notifyTick); H.removeCallbacks(scanTimeout); H.removeCallbacks(reconnect); H.removeCallbacks(watchdog);
        stopScan();
        finishStop();
        set("STOPPED: " + msg);
    }

    private static void finishStop() {
        synchronized (PolarH10.class) {
            wanted = false; recording = false;
            closeGatt();
            closeEcgFile();
            try { if (rrW != null) { rrW.close(); } } catch (Throwable ignored) { } rrW = null;
            try { if (evW != null) { evW.close(); } } catch (Throwable ignored) { } evW = null;
            if (app != null) { try { if (!DayLog.enabled(app)) DayLogService.stop(app); } catch (Throwable ignored) { } }
            if (!state.startsWith("STOPPED")) state = "stopped";
            log("STOPPED");
            try { if (logW != null) { logW.flush(); } } catch (Throwable ignored) { }
        }
    }

    private static void closeEcgFile() {
        try { if (ecgW != null) { ecgW.close(); log("ECG file closed, " + samples + " samples"); } } catch (Throwable ignored) { }
        ecgW = null;
    }

    // ------------------------------------------------------------------ small helpers

    private static void set(String s) { state = s; log("STATUS " + s); }

    private static void openLog() {
        try {
            if (logW != null) { try { logW.close(); } catch (Throwable ignored) { } }
            logW = new BufferedWriter(new FileWriter(new File(app.getExternalFilesDir(null), "h10_log_" + sessionStamp + ".txt"), true));
        } catch (Throwable ignored) { logW = null; }
    }

    private static void log(String s) {
        String line = String.format(Locale.UK, "%tT.%<tL ", System.currentTimeMillis()) + s;
        tail.addLast(line); while (tail.size() > 60) tail.removeFirst();
        try { if (logW != null) { logW.write(System.currentTimeMillis() + "\t" + s + "\n"); logW.flush(); } } catch (Throwable ignored) { }
    }
}


/** Signal-quality maths for the live H10 trace. Pure Java so it can be tested off the phone. */
final class TraceMath {
    private TraceMath() { }
    static final int WAIT = 0, WEAK = 1, NOISY = 2, OK = 3, GOOD = 4;

    /** x minus a centred 1-second moving average (removes baseline wander for display and measurement). */
    static float[] detrend(int[] x, int n) {
        float[] out = new float[n];
        if (n == 0) return out;
        long[] ps = new long[n + 1];
        for (int i = 0; i < n; i++) ps[i + 1] = ps[i] + x[i];
        int h = 65;
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - h), b = Math.min(n, i + h + 1);
            out[i] = x[i] - (float) (ps[b] - ps[a]) / (b - a);
        }
        return out;
    }

    private static float pct(float[] sorted, double p) { return sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.round(p * (sorted.length - 1))))]; }

    /** Returns {amplitude uV (99th minus 1st percentile of the detrended signal), noise ratio (median successive step / amplitude), quality code}. */
    static float[] quality(int[] x, int n) {
        if (n < 260) return new float[] {0f, 0f, WAIT};
        float[] d = detrend(x, n);
        float[] s = d.clone(); java.util.Arrays.sort(s);
        float amp = pct(s, 0.99) - pct(s, 0.01);
        float[] st = new float[n - 1];
        for (int i = 1; i < n; i++) st[i - 1] = Math.abs(d[i] - d[i - 1]);
        java.util.Arrays.sort(st);
        float noise = st[st.length / 2] / Math.max(amp, 1f);
        float q;
        if (amp < 300f) q = WEAK;
        else if (noise >= 0.12f) q = NOISY;           // provisional cut-offs: to be tuned on real H10 recordings
        else if (amp >= 600f && noise < 0.075f) q = GOOD;
        else q = OK;
        return new float[] {amp, noise, q};
    }
}

/** A live, scrolling ECG strip for the Polar H10 (last 6 s) with a plain-words signal-quality line. */
class H10TraceView extends View {
    private static final int SHOW = 130 * 6;
    private final Paint bg = new Paint(), minor = new Paint(), major = new Paint(), trace = new Paint(), txt = new Paint();
    private final Path path = new Path();
    private final int[] buf = new int[SHOW];
    private final Runnable tick = new Runnable() {
        @Override public void run() { invalidate(); if (isAttachedToWindow()) postDelayed(this, 200L); }
    };

    H10TraceView(Context c) {
        super(c);
        bg.setColor(Color.parseColor("#06101C"));
        minor.setColor(Color.parseColor("#15283C")); minor.setStrokeWidth(1f);
        major.setColor(Color.parseColor("#22405C")); major.setStrokeWidth(1.5f);
        trace.setColor(Color.parseColor("#22E6D6")); trace.setStyle(Paint.Style.STROKE); trace.setStrokeWidth(3f); trace.setAntiAlias(true);
        txt.setAntiAlias(true); txt.setTextSize(34f);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

    private void msg(Canvas c, String s) { txt.setColor(Color.parseColor("#9FB3C8")); c.drawText(s, 24f, getHeight() / 2f, txt); }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bg);
        if (!PolarH10.wanted) { msg(c, "Live H10 trace: tap PREVIEW"); return; }
        int n = PolarH10.recent(buf);
        if (n < 130) { msg(c, "waiting for ECG data ..."); return; }
        long age = PolarH10.lastDataAgeMs();
        float[] d = TraceMath.detrend(buf, n);
        float[] q = TraceMath.quality(buf, n);
        float span = Math.max(1600f, q[0] * 1.7f);            // microvolts shown top to bottom
        float top = 8f, bottom = h - 46f, pxPerUv = (bottom - top) / span, mid = (top + bottom) / 2f;
        float dx = w / (float) SHOW;
        for (int i = 0; i <= 6 * 5; i++) { float x = i * (130f / 5f) * dx; c.drawLine(x, top, x, bottom, i % 5 == 0 ? major : minor); }
        for (float uv = -span / 2f; uv <= span / 2f; uv += 250f) { float y = mid - uv * pxPerUv; c.drawLine(0, y, w, y, Math.round(uv) % 500 == 0 ? major : minor); }
        path.reset();
        float x0 = (SHOW - n) * dx;
        for (int i = 0; i < n; i++) {
            float x = x0 + i * dx, y = Math.max(top, Math.min(bottom, mid - d[i] * pxPerUv));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, trace);
        String lab; int col;
        switch ((int) q[2]) {
            case TraceMath.GOOD: lab = "GOOD signal"; col = Color.parseColor("#30D158"); break;
            case TraceMath.OK: lab = "OK signal"; col = Color.parseColor("#C8E64A"); break;
            case TraceMath.NOISY: lab = "NOISY: stay still, press the strap on"; col = Color.parseColor("#FFB020"); break;
            case TraceMath.WEAK: lab = "WEAK: wet the electrodes, tighten the strap"; col = Color.parseColor("#FF5A5F"); break;
            default: lab = "..."; col = Color.WHITE;
        }
        txt.setColor(col);
        int hr = PolarH10.hrNow();
        c.drawText(lab + "  " + Math.round(q[0]) + " uV" + (hr > 0 ? "  HR " + hr : ""), 16f, h - 12f, txt);
        if (age > 3000L) { txt.setColor(Color.parseColor("#FF5A5F")); txt.setTextSize(46f); c.drawText("NO DATA " + (age / 1000L) + " s", 24f, 70f, txt); txt.setTextSize(34f); }
    }
}


/** The watchdog's decision, kept pure so it can be tested off the phone. */
final class H10Watchdog {
    private H10Watchdog() { }
    static final int NONE = 0, KICK = 1, RECONNECT = 2;
    /** silenceMs: time since the last ECG data (or since the stream started). sinceKickMs: time since the last restart attempt, or -1 if none. */
    static int decide(long silenceMs, long sinceKickMs) {
        if (silenceMs < 8000L) return NONE;
        if (sinceKickMs < 0L || sinceKickMs > 30000L) return KICK;                 // first stall: try restarting the stream
        if (sinceKickMs >= 9000L && silenceMs >= 17000L) return RECONNECT;         // the restart did not help: drop the link and reconnect
        return NONE;
    }
}

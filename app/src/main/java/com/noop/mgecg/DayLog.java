package com.noop.mgecg;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All-day logger store. The strap already records one summary record per second (R18) in its own flash; the logger
 * pulls that history on a schedule, keeps every raw capture file, decodes the per-second records into a small SQLite
 * database, and adds what the strap cannot know: markers (caffeine, meals, workouts, symptoms), cuff readings, the
 * strap battery, and the phone's own light and air pressure, so everything can be lined up by time later.
 * RESEARCH ONLY, not a medical record.
 */
final class DayLog {
    private DayLog() {}

    static final String PREFS = "daylog";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static Helper helper;
    private static volatile String lastIngestNote = "";

    // ------------------------------------------------------------------ settings
    private static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return p(c).getBoolean("enabled", false); }
    static void setEnabled(Context c, boolean on) { p(c).edit().putBoolean("enabled", on).apply(); }
    static int intervalMin(Context c) { return p(c).getInt("interval", 30); }
    static void setIntervalMin(Context c, int m) { p(c).edit().putInt("interval", m).apply(); }
    static String lastAddr(Context c) { return p(c).getString("addr", null); }
    static void setLastAddr(Context c, String a) { if (a != null) p(c).edit().putString("addr", a).apply(); }
    static long lastPullMs(Context c) { return p(c).getLong("lastPull", 0L); }
    static void setLastPullMs(Context c, long ms) { p(c).edit().putLong("lastPull", ms).apply(); }

    // ------------------------------------------------------------------ database
    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context c) { super(c.getApplicationContext(), "daylog.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE r18 (ts INTEGER PRIMARY KEY, hr INTEGER, state INTEGER, skin REAL, b82 INTEGER, steps INTEGER, act INTEGER, rec BLOB)");
            db.execSQL("CREATE TABLE other_rec (ts INTEGER, ver INTEGER, len INTEGER, PRIMARY KEY (ts, ver))");
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, ms INTEGER, kind TEXT, text TEXT)");
            db.execSQL("CREATE INDEX events_ms ON events (ms)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { }
    }

    static synchronized SQLiteDatabase db(Context c) {
        if (helper == null) helper = new Helper(c);
        return helper.getWritableDatabase();
    }

    // ------------------------------------------------------------------ events
    static void addEvent(Context c, String kind, String text) {
        try {
            ContentValues v = new ContentValues();
            v.put("ms", System.currentTimeMillis());
            v.put("kind", kind);
            v.put("text", text == null ? "" : text);
            db(c).insert("events", null, v);
        } catch (Throwable ignored) { }
    }

    private static long lastBatteryMs = 0L;
    private static int lastBatteryPct = -2;
    static void addBattery(Context c, int pct) {
        long now = System.currentTimeMillis();
        if (pct == lastBatteryPct && now - lastBatteryMs < 600000L) return;
        lastBatteryPct = pct;
        lastBatteryMs = now;
        addEvent(c, "battery", String.valueOf(pct));
    }

    // ------------------------------------------------------------------ phone sensors (light, pressure)
    private static boolean sampling = false;
    static void sampleSensors(final Context c) {
        if (sampling) return;
        final Context app = c.getApplicationContext();
        final SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        final Sensor light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        final Sensor press = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (light == null && press == null) return;
        sampling = true;
        final float[] lux = {Float.NaN}, hpa = {Float.NaN};
        final SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                if (e.sensor.getType() == Sensor.TYPE_LIGHT) lux[0] = e.values[0];
                else if (e.sensor.getType() == Sensor.TYPE_PRESSURE) hpa[0] = e.values[0];
            }
            @Override public void onAccuracyChanged(Sensor s, int a) { }
        };
        Handler h = new Handler(Looper.getMainLooper());
        if (light != null) sm.registerListener(l, light, SensorManager.SENSOR_DELAY_NORMAL, h);
        if (press != null) sm.registerListener(l, press, SensorManager.SENSOR_DELAY_NORMAL, h);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try { sm.unregisterListener(l); } catch (Throwable ignored) { }
                sampling = false;
                if (!Float.isNaN(lux[0]) || !Float.isNaN(hpa[0])) {
                    addEvent(app, "phone", String.format(Locale.US, "lux=%.1f;hpa=%.2f", Float.isNaN(lux[0]) ? -1f : lux[0], Float.isNaN(hpa[0]) ? -1f : hpa[0]));
                }
            }
        }, 4000L);
    }

    // ------------------------------------------------------------------ history ingestion
    /** Decodes any history capture that has stopped growing and was not ingested at its current size. */
    static void scanNewFiles(final Context c) {
        final Context app = c.getApplicationContext();
        EXEC.execute(new Runnable() {
            @Override public void run() {
                try {
                    File dir = app.getExternalFilesDir(null);
                    if (dir == null) return;
                    File[] fs = dir.listFiles();
                    if (fs == null) return;
                    SharedPreferences pr = p(app);
                    long now = System.currentTimeMillis();
                    for (File f : fs) {
                        String n = f.getName();
                        if (!n.startsWith("historical_bin_") || !n.endsWith(".bin")) continue;
                        if (now - f.lastModified() < 90000L) continue;       // still being written
                        String key = "ing_" + n;
                        if (pr.getLong(key, -1L) == f.length()) continue;
                        int[] r = ingest(app, f);
                        pr.edit().putLong(key, f.length()).apply();
                        lastIngestNote = n + ": " + r[0] + " new per-second records, " + r[1] + " other records";
                        addEvent(app, "ingest", lastIngestNote);
                    }
                } catch (Throwable t) { lastIngestNote = "ingest error: " + t; }
            }
        });
    }

    static int[] ingest(Context c, File f) throws Exception {
        final SQLiteDatabase db = db(c);
        byte[] data = Files.readAllBytes(f.toPath());
        final int[] cnt = {0, 0};
        db.beginTransaction();
        try {
            R18Codec.scan(data, new R18Codec.Visitor() {
                @Override public void frame(byte[] fr) {
                    if (R18Codec.u8(fr, 8) != 47) return;
                    long ts = R18Codec.ts(fr);
                    if (R18Codec.isR18(fr)) {
                        ContentValues v = new ContentValues();
                        v.put("ts", ts);
                        v.put("hr", R18Codec.hr(fr));
                        v.put("state", R18Codec.state(fr));
                        v.put("skin", R18Codec.skinC(fr));
                        v.put("b82", R18Codec.u8(fr, 82));
                        v.put("steps", R18Codec.steps(fr));
                        v.put("act", R18Codec.u8(fr, 63));
                        v.put("rec", fr);
                        if (db.insertWithOnConflict("r18", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) cnt[0]++;
                    } else {
                        ContentValues v = new ContentValues();
                        v.put("ts", ts);
                        v.put("ver", R18Codec.u8(fr, 9));
                        v.put("len", fr.length);
                        if (db.insertWithOnConflict("other_rec", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) cnt[1]++;
                    }
                }
            });
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return cnt;
    }

    // ------------------------------------------------------------------ status and summary
    private static String ago(long ms) {
        if (ms <= 0) return "never";
        long s = (System.currentTimeMillis() - ms) / 1000L;
        if (s < 90) return s + " s ago";
        if (s < 5400) return (s / 60) + " min ago";
        return String.format(Locale.US, "%.1f h ago", s / 3600.0);
    }

    static String statusLine(Context c) {
        try {
            SQLiteDatabase d = db(c);
            Cursor q = d.rawQuery("SELECT COUNT(*), MAX(ts) FROM r18", null);
            long n = 0, mx = 0;
            if (q.moveToFirst()) { n = q.getLong(0); mx = q.getLong(1); }
            q.close();
            String bat = "?";
            Cursor b = d.rawQuery("SELECT text FROM events WHERE kind='battery' ORDER BY id DESC LIMIT 1", null);
            if (b.moveToFirst()) bat = b.getString(0) + "%";
            b.close();
            return "pulled " + ago(lastPullMs(c)) + " | " + n + " s logged" + (mx > 0 ? ", newest " + ago(mx * 1000L) : "") + " | strap " + bat;
        } catch (Throwable t) { return "starting"; }
    }

    static String summary(Context c) {
        StringBuilder s = new StringBuilder();
        try {
            SQLiteDatabase d = db(c);
            long since = System.currentTimeMillis() / 1000L - 86400L;
            Cursor q = d.rawQuery("SELECT rec FROM r18 WHERE ts >= ? ORDER BY ts", new String[] {String.valueOf(since)});
            List<byte[]> recs = new ArrayList<>();
            while (q.moveToNext()) recs.add(q.getBlob(0));
            q.close();
            s.append("LAST 24 HOURS\n");
            if (recs.isEmpty()) { s.append("no per-second records yet. Connect the strap in the main app, then tap PULL NOW.\n"); }
            else {
                int n = recs.size(), sleep = 0, still = 0, walk = 0, run = 0, raw = 0, win = 0;
                List<Integer> hr = new ArrayList<>();
                List<Double> skin = new ArrayList<>();
                List<Integer> inband = new ArrayList<>();
                int steps0 = -1, stepsSum = 0, prevSteps = -1;
                int prevWin = 0, bursts = 0;
                for (byte[] f : recs) {
                    int st = R18Codec.state(f);
                    if (st == 2) sleep++;
                    int act = R18Codec.u8(f, 63);
                    if (act == 0) still++; else if (act == 1) walk++; else if (act == 2) run++;
                    if (R18Codec.rawActive(f)) raw++;
                    int h = R18Codec.hr(f); if (h > 0) hr.add(h);
                    skin.add(R18Codec.skinC(f));
                    boolean w = R18Codec.spo2Window(f);
                    if (w) { win++; int b = R18Codec.u8(f, 82); if (b >= 70 && b <= 100) inband.add(b); }
                    if (w && prevWin == 0) bursts++;
                    prevWin = w ? 1 : 0;
                    int sc = R18Codec.steps(f);
                    if (prevSteps >= 0) { int dlt = sc - prevSteps; if (dlt < 0) dlt += 65536; if (dlt >= 0 && dlt <= 2000) stepsSum += dlt; }
                    prevSteps = sc;
                }
                s.append(String.format(Locale.US, "logged %.1f h of the last 24 h (%d records)\n", n / 3600.0, n));
                if (!hr.isEmpty()) {
                    Integer[] a = hr.toArray(new Integer[0]); Arrays.sort(a);
                    int resting = Integer.MAX_VALUE;
                    for (int i = 0; i + 300 <= hr.size(); i += 60) {
                        Integer[] w5 = hr.subList(i, i + 300).toArray(new Integer[0]); Arrays.sort(w5); resting = Math.min(resting, w5[150]);
                    }
                    s.append(String.format(Locale.US, "heart rate median %d, range %d-%d, lowest 5-min median %s bpm\n", a[a.length / 2], a[0], a[a.length - 1], resting == Integer.MAX_VALUE ? "n/a" : String.valueOf(resting)));
                }
                if (!skin.isEmpty()) { Double[] a = skin.toArray(new Double[0]); Arrays.sort(a); s.append(String.format(Locale.US, "skin temperature median %.2f C (%.1f-%.1f)\n", a[a.length / 2], a[0], a[a.length - 1])); }
                s.append(String.format(Locale.US, "band state SLEEP %.1f h | activity: still %.1f h, walk %d min, run %d min\n", sleep / 3600.0, still / 3600.0, walk / 60, run / 60));
                s.append("step counter rose by ").append(stepsSum).append(" (partial coverage counts only what was pulled)\n");
                s.append("sleep-only byte-82 measurement windows: ").append(bursts);
                if (!inband.isEmpty()) { Integer[] a = inband.toArray(new Integer[0]); Arrays.sort(a); s.append(", median value ").append(a[a.length / 2]); }
                s.append('\n');
                s.append(String.format(Locale.US, "raw ECG/optical collection was active for %d s\n", raw));
            }
            Cursor e = d.rawQuery("SELECT kind, COUNT(*) FROM events WHERE ms >= ? GROUP BY kind", new String[] {String.valueOf(since * 1000L)});
            s.append("\nEVENTS IN THE LAST 24 H: ");
            boolean any = false;
            while (e.moveToNext()) { s.append(e.getString(0)).append(' ').append(e.getInt(1)).append("  "); any = true; }
            e.close();
            if (!any) s.append("none");
            s.append("\n\nlast ingest: ").append(lastIngestNote.isEmpty() ? "none yet" : lastIngestNote).append('\n');
            s.append("last history pull: ").append(ago(lastPullMs(c))).append(" | pull every ").append(intervalMin(c)).append(" min | logger ").append(enabled(c) ? "ON" : "OFF").append('\n');
        } catch (Throwable t) { s.append("summary error: ").append(t); }
        return s.toString();
    }

    // ------------------------------------------------------------------ sleep report
    private static String f0(double v) { return Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%.0f", v); }
    private static String f1(double v) { return Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%.1f", v); }

    /** One block per night found in the log, newest first. Heart rate, RMSSD, skin temperature and the byte-82 windows are
     *  as measured; the sleep window comes from the band's own state. Restless spells are arm movement while lying down. */
    static String sleepReport(Context c, int maxNights) {
        StringBuilder s = new StringBuilder();
        try {
            SQLiteDatabase d = db(c);
            Cursor q = d.rawQuery("SELECT ts FROM r18 WHERE state = 2 ORDER BY ts", null);
            List<Long> tl = new ArrayList<>();
            while (q.moveToNext()) tl.add(q.getLong(0));
            q.close();
            long[] st = new long[tl.size()];
            for (int i = 0; i < st.length; i++) st[i] = tl.get(i);
            List<long[]> cl = SleepNight.clusters(st);
            if (cl.isEmpty()) return "No complete night in the log yet (it needs about 3 hours of SLEEP state). Pull the strap's history in the morning.\n";
            SimpleDateFormat day = new SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault());
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
            int from = Math.max(0, cl.size() - maxNights);
            for (int k = cl.size() - 1; k >= from; k--) {
                long[] cc = cl.get(k);
                Cursor r = d.rawQuery("SELECT rec FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts", new String[] {String.valueOf(cc[0] - 7200L), String.valueOf(cc[1] + 600L)});
                List<byte[]> recs = new ArrayList<>();
                while (r.moveToNext()) recs.add(r.getBlob(0));
                r.close();
                SleepNight.Night n = SleepNight.analyse(recs);
                if (n == null) continue;
                s.append(day.format(new Date(n.bedTs * 1000L))).append(" to ").append(hm.format(new Date(n.endTs * 1000L))).append('\n');
                s.append(String.format(Locale.US, "  in bed %.1f h, asleep %.1f h (%.0f%%), settled in %.0f min\n", n.tibH, n.tstH, n.effPct, n.settleMin));
                s.append("  restless: ");
                if (n.restless.isEmpty()) s.append("none");
                for (long[] x : n.restless) s.append(hm.format(new Date(x[0] * 1000L))).append(' ').append(x[1] / 60).append(" min  ");
                s.append('\n');
                s.append("  heart rate: mean ").append(f0(n.hrMean)).append(", lowest 5 min ").append(f0(n.hrLow5));
                if (n.hrLow5Ts > 0) s.append(" at ").append(hm.format(new Date(n.hrLow5Ts * 1000L)));
                s.append(", first 30 min ").append(f0(n.hrFirst30)).append(", last 30 min ").append(f0(n.hrLast30)).append('\n');
                s.append("  RMSSD ").append(f1(n.rmssd)).append(" ms; by hour");
                for (double v : n.rmssdByHour) s.append(' ').append(f0(v));
                s.append('\n');
                s.append("  skin ").append(String.format(Locale.US, "%.2f", n.skinMedian)).append(" C after warm-up (").append(f1(n.skinMin)).append("-").append(f1(n.skinMax)).append(")\n");
                s.append("  byte 82: ").append(n.windows).append(" windows, ").append(n.validN).append(" valid values, median ").append(n.b82Median).append(", lowest ").append(n.b82Min).append("\n\n");
            }
            s.append("Sleep window and restless spells come from the band's own state. Restless means arm movement while lying down, not getting up. No sleep stages are shown: none has been validated. Research only.\n");
        } catch (Throwable t) { s.append("sleep report error: ").append(t); }
        return s.toString();
    }

    // ------------------------------------------------------------------ export
    static String exportCsv(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return "external files folder unavailable";
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
            File f1 = new File(dir, "daylog_r18_" + stamp + ".csv");
            File f2 = new File(dir, "daylog_events_" + stamp + ".csv");
            SQLiteDatabase d = db(c);
            int rows = 0;
            Writer w = new OutputStreamWriter(new FileOutputStream(f1), "UTF-8");
            try {
                w.write(R18Codec.HEADER + "\n");
                Cursor q = d.rawQuery("SELECT rec FROM r18 ORDER BY ts", null);
                while (q.moveToNext()) { w.write(R18Codec.csv(q.getBlob(0)) + "\n"); rows++; }
                q.close();
            } finally { w.close(); }
            int ev = 0;
            Writer w2 = new OutputStreamWriter(new FileOutputStream(f2), "UTF-8");
            try {
                w2.write("unix_ms,iso_local,kind,text\n");
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                Cursor q = d.rawQuery("SELECT ms, kind, text FROM events ORDER BY ms", null);
                while (q.moveToNext()) {
                    String t = q.getString(2).replace("\"", "'");
                    w2.write(q.getLong(0) + "," + iso.format(new Date(q.getLong(0))) + "," + q.getString(1) + ",\"" + t + "\"\n");
                    ev++;
                }
                q.close();
            } finally { w2.close(); }
            return "wrote " + f1.getName() + " (" + rows + " rows) and " + f2.getName() + " (" + ev + " events) in the app's files folder";
        } catch (Throwable t) { return "export failed: " + t; }
    }
}

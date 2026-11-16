package com.noop.mgecg;

import android.os.Process;
import android.os.SystemClock;

/**
 * 0.67.0: where the start-up time goes. The first three marks are seconds since the process started (Process.getStartElapsedRealtime),
 * set once per process; the heart screen's own marks are per screen instance (the newest one), as seconds after that screen started.
 * Shown as one muted line at the bottom of the You page.
 */
final class StartupClock {
    static final int MAIN_CREATE = 0, MAIN_UI = 1, HEALTH_CREATE = 2;
    private static final String[] NAME = {"app opens", "old layers built", "heart screen starts"};
    private static final long[] AT = new long[NAME.length];
    private static long hStart, hPaint, hLoaded;

    /** First call wins, for the process-wide marks. */
    static void mark(int i) {
        if (i < 0 || i >= AT.length) return;
        synchronized (AT) { if (AT[i] == 0) AT[i] = SystemClock.elapsedRealtime(); }
    }

    /** A HealthActivity is starting (a new instance resets its own paint and load marks). */
    static void healthStart() {
        long n = SystemClock.elapsedRealtime();
        synchronized (AT) { if (AT[HEALTH_CREATE] == 0) AT[HEALTH_CREATE] = n; hStart = n; hPaint = 0; hLoaded = 0; }
    }

    static void healthPaint() { long n = SystemClock.elapsedRealtime(); synchronized (AT) { if (hStart != 0 && hPaint == 0) hPaint = n; } }

    static void healthLoaded() { long n = SystemClock.elapsedRealtime(); synchronized (AT) { if (hStart != 0 && hLoaded == 0) hLoaded = n; } }

    static String line() {
        long p = Process.getStartElapsedRealtime();
        synchronized (AT) { return render(p, AT, hStart, hPaint, hLoaded); }
    }

    /** Pure: the line from the marks (0 = not marked). */
    static String render(long procStart, long[] at, long hStart, long hPaint, long hLoaded) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < at.length && i < NAME.length; i++) {
            if (at[i] == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(NAME[i]).append(' ').append(fmt(at[i] - procStart));
        }
        if (sb.length() == 0 && hStart == 0) return "";
        StringBuilder out = new StringBuilder("Start-up, seconds from process start: ").append(sb.length() == 0 ? "-" : sb.toString());
        if (hStart != 0) {
            out.append(". Latest heart screen, after it started: first paint ").append(hPaint == 0 ? "-" : fmt(hPaint - hStart));
            out.append(", records loaded ").append(hLoaded == 0 ? "-" : fmt(hLoaded - hStart));
        }
        return out.toString();
    }

    /** Pure: milliseconds as seconds with one decimal ("1.9 s"). */
    static String fmt(long ms) {
        if (ms < 0) ms = 0;
        return (ms / 1000) + "." + ((ms % 1000) / 100) + " s";
    }
}

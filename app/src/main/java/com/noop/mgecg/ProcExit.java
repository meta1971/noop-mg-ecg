package com.noop.mgecg;

// FILE VERSION 0.19.0 (27 Oct 2026): why did Android end the app process? Reads the system's own exit history (API 30 and up) and keeps the newest lines.

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * On the night of 26 to 27 Oct the app process ended silently at 00:06:48 (the strap logged BLE_CONNECTION_DOWN two seconds after the last log line, and
 * the log is empty until the screen was opened at 05:56). An app cannot log its own death, but Android keeps the reason: this class reads it at the next
 * start and from the logger alarm, and keeps the newest lines in preferences for the Strap data screen and the log.
 */
final class ProcExit {
    private ProcExit() {}

    static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_UNKNOWN: return "UNKNOWN";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF (the app ended itself)";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED (killed by a signal, usually 9)";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH (uncaught exception)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR (not responding)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED (swiped away or force stopped in settings)";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED (the user or a profile was stopped)";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER (a system or vendor kill)";
            case ApplicationExitInfo.REASON_FREEZER: return "FREEZER";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "PACKAGE_STATE_CHANGE";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "PACKAGE_UPDATED (an update was installed)";
            default: return "reason " + r;
        }
    }

    static String importanceName(int i) {
        if (i <= 100) return "foreground";
        if (i <= 125) return "foreground service";
        if (i <= 200) return "visible";
        if (i <= 300) return "service";
        if (i <= 400) return "cached";
        return "gone (" + i + ")";
    }

    /** Lines for exits not seen before (newest first), already stored for the Strap data screen. Empty below Android 11 or when nothing is new. */
    static List<String> check(Context c) {
        List<String> fresh = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 30) return fresh;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return fresh;
            List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 12);
            SharedPreferences p = c.getSharedPreferences("procexit", Context.MODE_PRIVATE);
            long seen = p.getLong("seen_ts", 0L), maxTs = seen;
            SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.UK);
            for (ApplicationExitInfo e : l) {
                long ts = e.getTimestamp();
                if (ts <= seen) continue;
                if (ts > maxTs) maxTs = ts;
                String d = e.getDescription();
                if (d != null && d.length() > 110) d = d.substring(0, 110);
                fresh.add(f.format(new Date(ts)) + "  " + reasonName(e.getReason()) + ", was " + importanceName(e.getImportance()) + ", process " + e.getProcessName()
                        + (e.getStatus() != 0 ? ", status " + e.getStatus() : "") + (d != null && !d.isEmpty() ? ", " + d : ""));
            }
            if (!fresh.isEmpty()) {
                String old = p.getString("lines", "");
                StringBuilder s = new StringBuilder();
                for (String x : fresh) s.append(x).append('\n');
                String[] o = old.isEmpty() ? new String[0] : old.split("\n");
                for (int i = 0; i < o.length && i < 8 - fresh.size(); i++) s.append(o[i]).append('\n');
                p.edit().putLong("seen_ts", maxTs).putString("lines", s.toString().trim()).apply();
            }
        } catch (Throwable ignored) { }
        return fresh;
    }

    /** The stored lines, newest first. */
    static String stored(Context c) {
        try { return c.getSharedPreferences("procexit", Context.MODE_PRIVATE).getString("lines", ""); } catch (Throwable t) { return ""; }
    }
}

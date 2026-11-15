package com.noop.mgecg;

// FILE VERSION 0.57.0 (15 Nov 2026): optional Do Not Disturb (Alarms only) for the length of an ECG reading, so a call or message cannot ring or buzz the phone while the strap is on the same arm (reading 1794765254: two missed calls at 17:56,
// 11 ring bursts in the strap accelerometer, extra baseline steps in the ECG). OFF by default. It needs the one-time "Do Not Disturb access" grant (Settings > Apps > Special app access), which the app can only open, not give itself.
// Safety: the filter that was active before is stored; at the end of the reading it is put back, but only if the filter is still the one we set (if you changed it yourself meanwhile, it is left alone). If the app is killed in the middle,
// the next start of the app restores it (recover). While it is on, calls and messages are silenced, including repeat callers and starred contacts; alarms still ring.
// Not run on a device by the author of this build.

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

final class QuietPhone {
    private QuietPhone() {}

    static final String PREFS = "quiet_phone_state", K_SET = "set_by_us", K_PREV = "prev_filter";
    private static volatile String lastResult = "";

    /** The reading imu stream is wanted by the test switch or by the buzz guard (default on). */
    static boolean imuWanted(SharedPreferences p) {
        try { return p.getBoolean("imu_live_test", false) || p.getBoolean("ring_guard", true); } catch (RuntimeException e) { return false; }
    }

    static boolean wanted(SharedPreferences control) { try { return control.getBoolean("quiet_phone", false); } catch (RuntimeException e) { return false; } }

    static boolean granted(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 23) return false;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationPolicyAccessGranted();
        } catch (RuntimeException e) { return false; }
    }

    static Intent grantIntent() { return new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }

    /** Called when a reading starts. Does nothing unless switched on and granted; returns the text for reading_notes. */
    static String begin(Context c, SharedPreferences control) {
        lastResult = "";
        try {
            if (!wanted(control)) return lastResult = "off";
            if (!granted(c)) return lastResult = "no_access";
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            SharedPreferences st = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (st.getBoolean(K_SET, false)) return lastResult = "already_set";      // a crashed earlier reading left it: recover() handles that at start, so this is a second start; keep the stored previous filter
            int prev = nm.getCurrentInterruptionFilter();
            if (prev == NotificationManager.INTERRUPTION_FILTER_ALARMS || prev == NotificationManager.INTERRUPTION_FILTER_NONE) return lastResult = "dnd_already_on";
            if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return lastResult = "filter_unknown";
            st.edit().putInt(K_PREV, prev).putBoolean(K_SET, true).commit();      // commit: must be on disk before the filter changes
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
            return lastResult = "alarms_only_set_prev" + prev;
        } catch (RuntimeException e) { return lastResult = "error_" + e.getClass().getSimpleName(); }
    }

    /** Called when a reading ends (any path) and at app start. Restores the earlier filter if we set the current one. */
    static String end(Context c) {
        try {
            SharedPreferences st = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!st.getBoolean(K_SET, false)) return "";
            int prev = st.getInt(K_PREV, NotificationManager.INTERRUPTION_FILTER_ALL);
            String r;
            if (granted(c)) {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALARMS) { nm.setInterruptionFilter(prev); r = "restored" + prev; }
                else r = "left_as_changed_by_user";
            } else r = "access_lost_not_restored";
            st.edit().putBoolean(K_SET, false).commit();
            lastResult = lastResult + ">" + r;
            return r;
        } catch (RuntimeException e) { return "error_" + e.getClass().getSimpleName(); }
    }

    static String lastResult() { return lastResult; }
}

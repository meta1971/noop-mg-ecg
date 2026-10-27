package com.noop.mgecg;

// FILE VERSION 0.19.0 (27 Oct 2026): keeps the last uncaught exception, so a crash is no longer a silent end.

import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class CrashLog {
    private CrashLog() {}

    private static final class H implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Thread.UncaughtExceptionHandler prev;
        H(Context app, Thread.UncaughtExceptionHandler prev) { this.app = app; this.prev = prev; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String[] lines = sw.toString().split("\n");
                StringBuilder b = new StringBuilder();
                b.append(new SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.UK).format(new Date())).append("  thread ").append(t.getName()).append('\n');
                for (int i = 0; i < lines.length && i < 22; i++) b.append(lines[i]).append('\n');
                app.getSharedPreferences("crashlog", Context.MODE_PRIVATE).edit().putString("last", b.toString().trim()).putLong("at_ms", System.currentTimeMillis()).commit();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    static void install(Context c) {
        try {
            Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
            if (cur instanceof H) return;
            Thread.setDefaultUncaughtExceptionHandler(new H(c.getApplicationContext(), cur));
        } catch (Throwable ignored) { }
    }

    /** The last crash text, or "" if none was recorded. */
    static String last(Context c) {
        try { SharedPreferences p = c.getSharedPreferences("crashlog", Context.MODE_PRIVATE); return p.getString("last", ""); } catch (Throwable t) { return ""; }
    }
}

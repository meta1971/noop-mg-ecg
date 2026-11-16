package com.noop.mgecg;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.67.0: the last finished load of the Health screens, kept in memory so a new HealthActivity (rotation, return from an old
 * screen, relaunch while the strap service keeps the process alive) paints at once from it and refreshes behind it.
 * It is not saved to disk: after the process dies the first paint is a fresh load, as before.
 */
final class HealthSnap {
    static final long MAX_AGE_MS = 6L * 3600L * 1000L;

    HealthModel.Data data;
    List<ActivityDetector.Bout> actBouts = new ArrayList<ActivityDetector.Bout>();
    String actLine = "";
    int actMin = -1, actGoal = 240;
    ActivityPlus.DayStats actToday; long actStatsAt;
    List<TrustData.Day> trustDays = new ArrayList<TrustData.Day>(); List<String> trustIngests = new ArrayList<String>(); long trustAt;
    String syncRange = "", syncBattery = "", syncSub = "";
    long at;

    private static volatile HealthSnap last;

    static void put(HealthSnap s) { s.at = System.currentTimeMillis(); last = s; }

    /** The snapshot when it is younger than [maxAgeMs] and holds data, else null. Pure apart from the clock argument. */
    static HealthSnap get(long nowMs, long maxAgeMs) {
        HealthSnap s = last;
        if (s == null || s.data == null) return null;
        long age = nowMs - s.at;
        return age >= 0 && age <= maxAgeMs ? s : null;
    }

    static void clear() { last = null; }
}

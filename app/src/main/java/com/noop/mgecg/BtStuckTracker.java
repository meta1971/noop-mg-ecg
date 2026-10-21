package com.noop.mgecg;

// FILE VERSION 0.7.3 (21 Oct): decides when a stuck Bluetooth stack should be switched on

import java.util.HashMap;

/**
 * Night of 20-21 Oct 2026: the phone's Bluetooth stack failed at about 23:10 with the Bluetooth switch off (BLE-only mode). The H10 recorder asked
 * for a scanner 3,997 times in 6.5 hours and always got "not ready"; the strap link went dead without any callback. Nothing switched the stack back on.
 * This class keeps one clock per client ("who"): while a client keeps reporting that the stack is unusable, and the Bluetooth switch is off, the
 * answer becomes "switch it on" after STUCK_MS of continuous failure, then at most once per COOLDOWN_MS. A success (or a gap in the failures longer than
 * GAP_MS) restarts the clock, so one old failure never counts as a long one.
 */
final class BtStuckTracker {
    static final long STUCK_MS = 30000L;
    static final long COOLDOWN_MS = 120000L;          // before the second try; doubles on every further try up to MAX_COOLDOWN_MS
    static final long MAX_COOLDOWN_MS = 1800000L;
    static final long GAP_MS = 90000L;

    private final HashMap<String, long[]> bad = new HashMap<>();   // who -> {first failure, last failure}
    private long lastEnableMs = 0L;
    private int tries = 0;

    synchronized void ok(String who) {
        bad.remove(who);
        tries = 0;                                                 // the stack works again: the next failure starts from the short cooldown
    }

    /** One more failure from this client. Returns how many seconds it has been failing when Bluetooth should be switched on now, else -1. */
    synchronized long bad(String who, long nowMs, boolean switchOn) {
        long[] t = bad.get(who);
        if (t == null || nowMs - t[1] > GAP_MS) {
            t = new long[]{nowMs, nowMs};
            bad.put(who, t);
        }
        t[1] = nowMs;
        if (switchOn) return -1;                                   // the switch is already on: nothing to switch on
        long stuck = nowMs - t[0];
        if (stuck < STUCK_MS) return -1;
        if (tries > 0) {
            long cooldown = Math.min(COOLDOWN_MS << Math.min(tries - 1, 5), MAX_COOLDOWN_MS);   // 2, 4, 8, 16, 30 minutes
            if (nowMs - lastEnableMs < cooldown) return -1;
        }
        tries++;
        lastEnableMs = nowMs;
        return stuck / 1000L;
    }
}

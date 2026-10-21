package com.noop.mgecg;

// FILE VERSION 0.7.3 (21 Oct): Bluetooth watchdog, switches Bluetooth on when the stack is stuck

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

/** Shared by the strap link (MainActivity) and the H10 recorder (DayLogActivity). The owner asked for Bluetooth to be switched on when the stack is stuck. */
final class BtRecovery {
    private BtRecovery() {}

    interface Sink { void log(String s); }

    private static final BtStuckTracker TRACKER = new BtStuckTracker();

    /** The client worked (scanner available, link connected): restart its clock. */
    static void ok(String who) {
        TRACKER.ok(who);
    }

    /**
     * The client found the stack unusable (no scanner, connect returned nothing). After 30 s of continuous failure with the Bluetooth switch off,
     * Bluetooth is switched on (BluetoothAdapter.enable(); allowed on Android 11 with BLUETOOTH_ADMIN). Returns true when the request was accepted.
     * The state receiver in MainActivity then reconnects the strap, and the H10 retry loop finds the scanner at its next try. Bluetooth is left on.
     */
    @SuppressWarnings("deprecation")
    static boolean bad(Context ctx, String who, Sink sink) {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        boolean on = false;
        try { on = ad != null && ad.isEnabled(); } catch (Throwable ignored) { }
        long secs = TRACKER.bad(who, System.currentTimeMillis(), on);
        if (secs < 0 || ad == null) return false;
        boolean accepted = false;
        String err = "";
        try { accepted = ad.enable(); } catch (Throwable t) { err = " (" + t + ")"; }
        String msg = "BT_WATCHDOG " + who + ": Bluetooth stack unusable for " + secs + " s with the switch off, enable() "
                + (accepted ? "accepted - Bluetooth is being switched on" : "refused") + err;
        try { if (sink != null) sink.log(msg); } catch (Throwable ignored) { }
        try { DayLog.addEvent(ctx, "bluetooth", msg); } catch (Throwable ignored) { }
        return accepted;
    }
}

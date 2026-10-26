package com.noop.mgecg;

// FILE VERSION 0.16.0 (27 Oct): the body of the strap clock command. Pure Java, tested off the phone.

/**
 * SET_CLOCK (0x0A) body, 8 bytes: [seconds u32 LE][sub-second u32 LE]. The official WHOOP app sends the same two fields for an MG (Maverick) strap
 * (it uses the old clock command 10 for Gen 4, Maverick and Goose, and the newer 146 only for older families), with the sub-second part filled in.
 * The strap reports its own sub-second in units of 1/32768 s (hello values 10,485 to 32,440 in our logs, GET_CLOCK 15,728 = 0.48 s), so that is the unit used here.
 * We used to send 0, which truncates the set time and left the strap clock 0.4 to 1.1 s behind the phone.
 */
final class ClockSet {
    private ClockSet() {}

    static byte[] body(long nowMs, boolean subSecond) {
        long sec = Math.floorDiv(nowMs, 1000L);
        long sub = subSecond ? ((Math.floorMod(nowMs, 1000L) * 32768L) / 1000L) : 0L;
        byte[] b = new byte[8];
        for (int i = 0; i < 4; i++) { b[i] = (byte) ((sec >> (8 * i)) & 0xFF); b[4 + i] = (byte) ((sub >> (8 * i)) & 0xFF); }
        return b;
    }

    /** Strap clock minus phone clock in seconds from a GET_CLOCK (0x0B) response frame (seconds u32 at 13, sub-second u32 at 17); NaN when the frame is too short. */
    static double strapMinusPhone(byte[] f, long phoneMs) {
        if (f == null || f.length < 21) return Double.NaN;
        long sec = (f[13] & 0xFFL) | ((f[14] & 0xFFL) << 8) | ((f[15] & 0xFFL) << 16) | ((f[16] & 0xFFL) << 24);
        long sub = (f[17] & 0xFFL) | ((f[18] & 0xFFL) << 8) | ((f[19] & 0xFFL) << 16) | ((f[20] & 0xFFL) << 24);
        if (sub > 32767L) return Double.NaN;
        return sec + sub / 32768.0 - phoneMs / 1000.0;
    }
}

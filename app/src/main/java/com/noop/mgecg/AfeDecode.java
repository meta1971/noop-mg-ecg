package com.noop.mgecg;

// FILE VERSION 0.54.4 (14 Nov 2026): + fullScaleMv, hpfCornerHz, fastRecoveryCounts, lsbTable, chainSummary (the ECG chain in numbers, decode log 189-191); ecg2 and ecg3 give the full scale and the trigger. 0.53.9 (12 Nov 2026): plain-words decode of the MAX86176 register lines in the firmware console (build brief 0.51.0 v2, item 2). 0.53.9: the ECG registers 0x90-0x92 and the calibration registers
// 0xA0-0xA2 now use the MAX86176 field layout found in a smartwatch firmware's own driver (decode log 185); the sibling chip's layout is no longer applied to them. Pure Java.

import java.util.Locale;

/**
 * Two public sources, neither confirmed on the MG (labels say so in every line):
 *  MAX86176 = Maxim's own MAX86176 driver header as published in WrigNanoSystems-pvt-ltd/C-ECG-trial Drivers/ECG_PPG/max86176.h (part ID 0x39): Status 1-6 at
 *    0x00-0x05, FIFO Config 1 / 2 at 0x0D / 0x0E, Sys Config 1-4 at 0x10-0x13, ECG Config 1-3 at 0x90-0x92, Cal Config 1-3 at 0xA0-0xA2. Bit fields given there:
 *    ECG Config 1: ECG enable bit 7, decimation bits 2:0 (divider 16 x 2^n); Sys Config 1: reset 0, shutdown 1, PPG1 / PPG2 power-down 2 / 3, sync mode 5:4,
 *    force sync 6, timing reset 7; Sys Config 2: measurement 1-8 enable bits 0-7; Sys Config 3: meas1 config select 0, raw data 1, PPG timing data 2, master
 *    timing sync 3, ALC disable 4, VDD out-of-range enable 6, meas9 enable 7; Sys Config 4: prox auto 0, prox data 1, sample sync frequency 6:2; FIFO Config 2:
 *    roll-over 1, almost-full type 2, status clear 3, flush 4, mark 5; Status 1: power ready 0, threshold 1 / 2 1-2, exposure overflow 3, ALC overflow 4,
 *    FIFO data ready 5, frame ready 6, almost full 7; Status 2: invalid config 2, VDD out of range 6, LED9 compliance 7. No gain fields are given for the ECG.
 *  WATCH = a smartwatch firmware's own MAX86176 driver (duxinglang-1/nRF5340_test N2_NCS2.7.0_TEST/src/ecg/max86176.h and .c; the older E2 copies in nRF9160_test): a mask and shift for every ECG register: 0x90 ECG_EN bit 7,
 *    ECG_DEC_RATE 2:0; 0x91 ECG_IPOL 7, ECG_PGA_GAIN 6:4, ECG_INA_GAIN 1:0; 0x92 EN_ECG_FAST_REC 7:6, ECG_FAST_REC_THRESHOLD 5:0; 0xA0 CAL_EN 7, CAL_FREQ 6:4, CAL_DUTY 3, CAL_HIGH_MSB 2:0; 0xA1 CAL_HIGH_LSB; 0xA2 OPEN_P 7,
 *    OPEN_N 6, CAL_MODE 5, CAL_MAG 4, CAL_P_SEL 3:2, CAL_N_SEL 1:0. Its code writes 0x90 = 0x84 (128 sps), 0x91 = polarity | PGA code 3 (x8) | INA code 0 (20) and 0x92 = 0xBF. The meaning of INA codes 1-3 is NOT settled
 *    (sibling chip: 30 / 40 / 60; that driver's comments: 40 / 80 / 160; the data sheet cover's 480 and 960 V/V ranges and the strap's total gain of 120 need 30 and 60).
 *  MAX86178 = the sibling chip's ECG block (C-ECG-trial Drivers/MAX86178/max86178.h, esl-epfl VersaSens): ECG_CONFIG_1 0x80 enable bit 0, DEC_RATE 3:1;
 *    ECG_CONFIG_2 0x81 INA gain 1:0 = 20 / 30 / 40 / 60 V/V, INA_RGE 3:2 (meaning not given), PGA gain 6:4 = x1 / x2 / x4 / x8 / (7) x16, IPOL bit 7;
 *    ECG_CONFIG_3 0x82 MUX_SEL 1:0, AUTO_REC 2, IMP_HI 3; ECG_CONFIG_4 0x83 fast-recovery threshold 4:0, EN_FAST_REC 7:6. Unverified for the MAX86176.
 * The decimation clock: the MAX86178 driver notes DEC 64 = 512 Hz at a 32,768 Hz ECG ADC clock; the MAX86176 frame clock takes 32,768 or 32,000 Hz.
 */
final class AfeDecode {
    private AfeDecode() {}

    static String norm(String name) { return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US); }

    static int parse(String hex) { try { String h = hex.trim(); if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2); return Integer.parseInt(h, 16) & 0xFF; } catch (Throwable e) { return -1; } }

    static String bits(int v, String[] names) { StringBuilder s = new StringBuilder(); for (int i = 0; i < 8; i++) if (names[i] != null && (v & (1 << i)) != 0) s.append(s.length() > 0 ? ", " : "").append(names[i]); return s.length() == 0 ? "none set" : s.toString(); }

    static String asCfg2Sibling(int v) {
        int ina = new int[] { 20, 30, 40, 60 }[v & 3], rge = (v >> 2) & 3, pc = (v >> 4) & 7, ipol = (v >> 7) & 1;
        int pga = pc == 0 ? 1 : pc == 1 ? 2 : pc == 2 ? 4 : pc == 3 ? 8 : pc == 7 ? 16 : -1;
        return "IPOL " + ipol + ", INA " + ina + " V/V, RGE " + rge + ", PGA " + (pga < 0 ? "code " + pc + " (not listed)" : "x" + pga) + (pga > 0 ? ", total " + ina * pga + " V/V" : "");
    }
    static String asCfg1Sibling(int v) { int dr = (v >> 1) & 7; return "EN " + (v & 1) + ", DEC_RATE " + dr + (dr <= 5 ? " (/" + (16 << dr) + ")" : " (not listed)") + ((v & 0xF0) != 0 ? ", reserved bits " + String.format(Locale.US, "0x%02X", v & 0xF0) : ""); }

    static int pgaOf(int code) { return code == 0 ? 1 : code == 1 ? 2 : code == 2 ? 4 : code == 3 ? 8 : code == 7 ? 16 : -1; }

    /** Counts of 18 bits: 1 LSB in nanovolts for a total gain and a reference (the reference 1.0 V is INFERRED, decode log 184). */
    static double lsbNv(int totalGain, double vref) { return vref * 1e9 / (131072.0 * totalGain); }

    /** ECG Config 2 (0x91), MAX86176 layout from a watch firmware's driver; the INA table 20 / 30 / 40 / 60 is INFERRED (decode log 185). */
    static String ecg2(int v) {
        int ipol = (v >> 7) & 1, pc = (v >> 4) & 7, ic = v & 3, mid = (v >> 2) & 3, pga = pgaOf(pc), ina = new int[] { 20, 30, 40, 60 }[ic];
        StringBuilder s = new StringBuilder("MAX86176 layout (watch driver): polarity " + (ipol == 1 ? "inverted" : "normal") + ", PGA code " + pc + (pga < 0 ? " (gain not listed)" : " = x" + pga) + ", INA code " + ic + " = " + (ic == 0 ? "20 V/V" : ina + " V/V if the table is 20 / 30 / 40 / 60 (inferred; a watch driver's comments say " + (20 << ic) + ")"));
        if (mid != 0) s.append(", bits 3:2 = ").append(mid).append(" (not named)");
        if (pga > 0) s.append(String.format(Locale.US, "; total %d V/V = %.2f nV per count, full scale +-%.2f mV, at a 1.0 V reference (inferred)", ina * pga, lsbNv(ina * pga, 1.0), fullScaleMv(ina * pga, 1.0)));
        return s.toString();
    }

    /** ECG Config 3 (0x92): fast-recovery mode 7:6 and threshold 5:0 (n / 64 of full scale; the sibling chip's 0x3D is commented 95 %, 61 / 64). */
    static String ecg3(int v) {
        int m = (v >> 6) & 3, t = v & 0x3F;
        return "MAX86176 layout (watch driver): fast-recovery enable bits 7:6 = " + m + " (that driver writes 2), threshold " + t + " / 64 of full scale = " + (t * 2048) + " counts" + (t == 62 ? " = the strap's raw output limit of +-126,976 counts (the documented trigger is +-2048 x the code)" : "");
    }

    // ------------------------------------------------------------------ 0.54.4: the ECG chain in numbers (decode log 189 to 191). OBSERVED = read in a document or board of ADI's; INFERRED = derived; OPEN = not known.

    /** Total gains of the INA x PGA table (INA 20 / 30 / 40 / 60 inferred, PGA 1 / 2 / 4 / 8 / 16): the distinct totals up to the 960 V/V of the data sheet cover. */
    static final int[] TOTAL_GAINS = { 20, 30, 40, 60, 80, 120, 160, 240, 480, 960 };

    /** Full scale, +- millivolts, of the 18-bit code for a total gain and a reference: VREF / gain (OBSERVED for the sister chips MAX30003 and MAX86178). */
    static double fullScaleMv(int totalGain, double vref) { return vref * 1000.0 / totalGain; }

    /** First-order corner (Hz) of an input coupling capacitor [capUf] with an internal resistor [rKohm]. 1 uF with 318.3 kohm is 0.5 Hz (ADI's own wrist board note). */
    static double hpfCornerHz(double capUf, double rKohm) { return 1.0 / (2.0 * Math.PI * rKohm * 1e3 * capUf * 1e-6); }

    /** Counts at which the documented automatic fast recovery triggers for a 6-bit threshold code: 2048 x code (MAXREFDES106 guide, MAX30003). */
    static int fastRecoveryCounts(int code) { return code * 2048; }

    /** One line per total gain: nanovolts a count and full scale, at the reference vref. */
    static String lsbTable(double vref) {
        StringBuilder s = new StringBuilder();
        for (int g : TOTAL_GAINS) s.append(String.format(Locale.US, "%s%d V/V: %.2f nV, +-%.2f mV", s.length() > 0 ? "\n" : "", g, lsbNv(g, vref), fullScaleMv(g, vref)));
        return s.toString();
    }

    /** The in-app summary of what is known about the strap's ECG chain, labelled OBSERVED / INFERRED / OPEN. */
    static String chainSummary() {
        return "ECG CHAIN, 14 Nov 2026 (decode log 189 to 191)\n\n"
            + "SCALE. 1 count = VREF / (2^17 x total gain). OBSERVED on the sister chips MAX30001, MAX30003 and MAX86178: VREF 1.000 V, full scale = VREF / gain. INFERRED for this chip: VREF 1.0 V (not published; a VREF_ECG ball exists) and total gain 120 (INA 30 x PGA 4, or INA 60 x PGA 2), so 63.58 nV a count and +-8.33 mV. A third party's 76.29 nV matches no gain setting. A known-voltage run decides; until then +-20 %.\n\n"
            + "NANOVOLTS A COUNT AND FULL SCALE AT 1.0 V (INFERRED):\n" + lsbTable(1.0) + "\n\n"
            + "CROSS-CHECK (INFERRED, medium-low). The data sheet cover's 15.6 effective bits at 0.6 uV rms implies a full scale of about 100 mV peak to peak, which is 1.0 V / 20: the same reference. Good to about +-20 % in full scale.\n\n"
            + "OUTPUT LIMIT. +-126,976 counts = 62 x 2048 = +-8.07 mV, exactly the documented fast-recovery trigger (+-2048 x the threshold code in register 0x92) with code 62 (INFERRED; the strap's own 0x92 byte is not printed). The strap flags the 250 samples after it.\n\n"
            + "HIGH-PASS. OBSERVED for this chip: the input is AC-coupled through an EXTERNAL capacitor on balls CAPP and CAPN with an internal resistor; ADI's wrist board fits 1 uF there and its schematic says that sets 0.5 Hz (so the resistor is about 318 kohm). INFERRED for the strap: its raw stream decays after steps with time constants of 2 to 9 s, which needs 10 to 22 uF, a corner of 0.02 to 0.05 Hz. The 0.53 Hz second-order filter of the strap's R17 stream is firmware, not the chip. No digital DC-removal bit is known for this chip.\n\n"
            + "INPUT RANGE (OBSERVED, data sheet cover). DC offset +-1300 mV, AC range above 90 mV p-p. The strap itself is limited to +-8.07 mV by the fast-recovery threshold.\n\n"
            + "NOISE. No 0.5 to 40 Hz figure is published. The cover gives 0.6 uV rms over 0.05 to 40 Hz at a gain it does not state (9.4 counts at 63.58 nV). With a finger on, the strap shows 24 to 26 uV between beats: body, muscle or electrode, about 40 times the chip's figure. The no-finger floor has not been measured.\n\n"
            + "TEST SOURCE. The chip has a built-in calibration source (registers 0xA0 to 0xA2); no command of the strap writes it, so it cannot be used.\n\n"
            + "OPEN: the VREF_ECG voltage, the strap's 0x91 and 0x92 bytes, noise by gain, the register pages (under NDA). The known-voltage run measures the first and, with a slow source, the high-pass corner.";
    }

    /** One line in plain words, or "" when the register is not one of these. */
    static String describe(String name, String hex) {
        int v = parse(hex); if (v < 0) return "";
        String n = norm(name);
        if (n.endsWith("ecg config 1")) {
            int dr = v & 7, div = 16 << dr;
            return String.format(Locale.US, "MAX86176 layout (watch driver): ECG enable %d, decimation code %d (divide by %d: %.0f Hz at a 32.768 kHz clock, %.0f Hz at 32.000 kHz; the strap delivers 500 a second, so the 32.000 kHz clock)%s",
                    (v >> 7) & 1, dr, div, 32768.0 / div, 32000.0 / div, (v & 0x78) != 0 ? String.format(Locale.US, ", other bits 0x%02X", v & 0x78) : "");
        }
        if (n.endsWith("ecg config 2")) return ecg2(v);
        if (n.endsWith("ecg config 3")) return ecg3(v);
        if (n.endsWith("ecg config 4")) { int m = (v >> 6) & 3; return "MAX86178 layout, unverified: fast-recovery threshold " + (v & 0x1F) + ", mode " + (m == 0 ? "normal" : m == 1 ? "manual" : m == 2 ? "auto" : "3"); }
        if (n.endsWith("sys config 1")) return "MAX86176 driver: sync mode " + ((v >> 4) & 3) + "; " + bits(v & ~0x30, new String[] { "reset", "shutdown", "PPG1 power-down", "PPG2 power-down", null, null, "force sync", "timing reset" });
        if (n.endsWith("sys config 2")) return "MAX86176 driver: enabled " + bits(v, new String[] { "meas1", "meas2", "meas3", "meas4", "meas5", "meas6", "meas7", "meas8" });
        if (n.endsWith("sys config 3")) return "MAX86176 driver: " + bits(v, new String[] { "meas1 config select", "raw data", "PPG timing data", "master timing sync", "ALC disabled", null, "VDD range check", "meas9" });
        if (n.endsWith("sys config 4")) return "MAX86176 driver: " + bits(v & 3, new String[] { "prox auto", "prox data", null, null, null, null, null, null }) + ", sample sync frequency " + ((v >> 2) & 0x1F);
        if (n.endsWith("fifo config 1")) return "MAX86176 driver: almost-full setting " + v;
        if (n.endsWith("fifo config 2")) return "MAX86176 driver: " + bits(v, new String[] { null, "roll-over", "almost-full once", "status clear on read", "flush", "mark", null, null });
        if (n.endsWith("status 1")) return "MAX86176 driver: " + bits(v, new String[] { "power ready", "threshold 1", "threshold 2", "exposure overflow", "ALC overflow", "FIFO data ready", "frame ready", "FIFO almost full" });
        if (n.endsWith("status 2")) return "MAX86176 driver: " + bits(v, new String[] { null, null, "invalid config", null, null, null, "VDD out of range", "LED9 compliance" });
        if (n.endsWith("status 3")) return "MAX86176 driver: LED compliance " + bits(v, new String[] { "1", "2", "3", "4", "5", "6", "7", "8" });
        if (n.matches(".*status [4-6]")) return "MAX86176 driver: bits not given";
        if (n.endsWith("cal config 1")) return "MAX86176 layout (watch driver): calibration source " + (((v >> 7) & 1) != 0 ? "on" : "off") + ", CAL_FREQ code " + ((v >> 4) & 7) + ", CAL_DUTY " + ((v >> 3) & 1) + ", CAL_HIGH bits 10:8 = " + (v & 7);
        if (n.endsWith("cal config 2")) return "MAX86176 layout (watch driver): CAL_HIGH bits 7:0 = " + v;
        if (n.endsWith("cal config 3")) return "MAX86176 layout (watch driver): OPEN_P " + ((v >> 7) & 1) + ", OPEN_N " + ((v >> 6) & 1) + ", CAL_MODE " + ((v >> 5) & 1) + ", CAL_MAG " + ((v >> 4) & 1) + ", CAL_P_SEL " + ((v >> 2) & 3) + ", CAL_N_SEL " + (v & 3);
        if (n.contains("cal config")) return "calibration register; value only";
        return "";
    }
}

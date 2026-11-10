package com.noop.mgecg;

// FILE VERSION 0.51.0 (10 Nov 2026): plain-words decode of the MAX86176 register lines in the firmware console (build brief 0.51.0 v2, item 2). Pure Java.

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

    /** One line in plain words, or "" when the register is not one of these. */
    static String describe(String name, String hex) {
        int v = parse(hex); if (v < 0) return "";
        String n = norm(name);
        if (n.endsWith("ecg config 1")) {
            int dr = v & 7, div = 16 << dr;
            return String.format(Locale.US, "MAX86176 driver: ECG enable %d, decimation %d (/%d = %.0f Hz at 32.768 kHz, %.0f Hz at 32.000 kHz)%s",
                    (v >> 7) & 1, dr, div, 32768.0 / div, 32000.0 / div, (v & 0x78) != 0 ? String.format(Locale.US, ", other bits 0x%02X", v & 0x78) : "")
                + " | MAX86178 layout, unverified: as CONFIG_2: " + asCfg2Sibling(v) + "; as CONFIG_1: " + asCfg1Sibling(v);
        }
        if (n.endsWith("ecg config 2")) return "MAX86178 layout, unverified: as CONFIG_2: " + asCfg2Sibling(v);
        if (n.endsWith("ecg config 3")) return "MAX86178 layout, unverified: MUX_SEL " + (v & 3) + ", AUTO_REC " + ((v >> 2) & 1) + ", IMP_HI " + ((v >> 3) & 1);
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
        if (n.contains("cal config")) return "calibration register (sibling: CAL enable bit 0); value only";
        return "";
    }
}

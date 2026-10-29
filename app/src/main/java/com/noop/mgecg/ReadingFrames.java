package com.noop.mgecg;

// FILE VERSION 0.25.0 (29 Oct 2026): collects the STORED records (history layouts 16, 17, 18, 20, 21, 26) that belong to one reading from the app's historical_bin_* files. Pure Java, no Android, so it is tested off the phone. Research only.

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ReadingFrames {
    private ReadingFrames() {}

    static final class Sets {
        double t0, t1; int files;
        final List<byte[]> r16 = new ArrayList<>(), r17 = new ArrayList<>(), r18 = new ArrayList<>(), r20 = new ArrayList<>(), r21 = new ArrayList<>(), r26 = new ArrayList<>();
    }

    /** Frames whose strap time lies from 2 s before t0 to 10 s after t1, from the history files started within 6 h of the reading. */
    static Sets collect(File dir, double t0, double t1) {
        Sets S = new Sets(); S.t0 = t0; S.t1 = t1;
        File[] fs = dir == null ? null : dir.listFiles(); if (fs == null) return S;
        Arrays.sort(fs);
        Set<String> seen = new HashSet<>();
        for (File f : fs) {
            String n = f.getName(); if (!n.startsWith("historical_bin_") || !n.endsWith(".bin")) continue;
            long st; try { st = Long.parseLong(n.substring(15, n.length() - 4)); } catch (NumberFormatException e) { continue; }
            if (st < t0 - 6 * 3600 || st > t1 + 6 * 3600 || f.length() > 40L * 1024 * 1024) continue;
            byte[] b; try { b = Files.readAllBytes(f.toPath()); } catch (Exception e) { continue; }
            S.files++;
            for (byte[] fr : ProAnalysis.frames(b)) {
                if (fr.length < 20 || (fr[8] & 0xFF) != 47) continue;
                int lay = fr[9] & 0xFF, len = fr.length;
                List<byte[]> dst = lay == 16 && len == 1584 ? S.r16 : lay == 17 && len == 240 ? S.r17 : lay == 18 && len == 124 ? S.r18 : lay == 20 && len == 2140 ? S.r20 : lay == 21 && len == 1244 ? S.r21 : lay == 26 && len == 88 ? S.r26 : null;
                if (dst == null) continue;
                long u = ProAnalysis.u32(fr, 15); if (u < t0 - 2 || u > t1 + 10) continue;
                if (!ProAnalysis.crcOk(fr)) continue;
                if (!seen.add(lay + ":" + u + ":" + ProAnalysis.u16(fr, 19) + ":" + ProAnalysis.u32(fr, 11))) continue;
                dst.add(fr);
            }
        }
        Comparator<byte[]> byTime = new Comparator<byte[]>() { @Override public int compare(byte[] a, byte[] b) { int c = Long.compare(ProAnalysis.u32(a, 15), ProAnalysis.u32(b, 15)); return c != 0 ? c : Integer.compare(ProAnalysis.u16(a, 19), ProAnalysis.u16(b, 19)); } };
        Collections.sort(S.r16, byTime); Collections.sort(S.r17, byTime); Collections.sort(S.r18, byTime); Collections.sort(S.r20, byTime); Collections.sort(S.r21, byTime); Collections.sort(S.r26, byTime);
        return S;
    }
}

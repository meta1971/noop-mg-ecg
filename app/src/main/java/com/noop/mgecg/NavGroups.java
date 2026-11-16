package com.noop.mgecg;

import java.util.ArrayList;
import java.util.List;

/** 0.67.0: which drop-down groups on the You page are open, kept as one comma list of keys in a preference. Pure. */
final class NavGroups {
    private NavGroups() { }

    static List<String> keys(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null) return out;
        for (String k : csv.split(",")) { k = k.trim(); if (!k.isEmpty() && !out.contains(k)) out.add(k); }
        return out;
    }

    static boolean isOpen(String csv, String key) { return key != null && keys(csv).contains(key); }

    /** The list with [key] open or closed; other keys and their order are kept, no duplicates. */
    static String set(String csv, String key, boolean open) {
        List<String> k = keys(csv);
        if (key == null || key.isEmpty() || key.indexOf(',') >= 0) return join(k);
        if (open) { if (!k.contains(key)) k.add(key); } else k.remove(key);
        return join(k);
    }

    /** All of [group] open or closed at once. */
    static String setAll(String csv, List<String> group, boolean open) {
        String s = csv;
        for (String g : group) s = set(s, g, open);
        return s == null ? "" : s;
    }

    private static String join(List<String> k) {
        StringBuilder sb = new StringBuilder();
        for (String x : k) { if (sb.length() > 0) sb.append(','); sb.append(x); }
        return sb.toString();
    }
}

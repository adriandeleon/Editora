package com.editora.fstab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses {@code /etc/fstab} text into {@link FstabEntry} rows for the preview. Comments ({@code #}) and
 * blank lines are dropped. Each entry line is 4–6 whitespace-separated columns (device mount type options
 * [dump] [pass]); fewer than 4, more than 6, or a non-numeric dump/pass yields an entry with an
 * {@link FstabEntry#error()}. Pure, java.base-only, unit-tested.
 */
public final class Fstab {

    private Fstab() {}

    public static List<FstabEntry> parse(String text) {
        List<FstabEntry> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            out.add(entry(line, i + 1));
        }
        return out;
    }

    private static FstabEntry entry(String line, int lineNo) {
        String[] f = line.split("\\s+");
        if (f.length < 4) {
            return new FstabEntry(
                    f.length > 0 ? f[0] : "",
                    f.length > 1 ? f[1] : "",
                    f.length > 2 ? f[2] : "",
                    List.of(),
                    0,
                    0,
                    "expected at least 4 columns, found " + f.length,
                    lineNo);
        }
        if (f.length > 6) {
            return new FstabEntry(
                    f[0], f[1], f[2], splitOptions(f[3]), 0, 0, "too many columns (" + f.length + ")", lineNo);
        }
        List<String> options = splitOptions(f[3]);
        Integer dump = f.length > 4 ? parseColumn(f[4]) : Integer.valueOf(0);
        Integer pass = f.length > 5 ? parseColumn(f[5]) : Integer.valueOf(0);
        if (dump == null || pass == null) {
            String bad = dump == null ? f[4] : f[5];
            return new FstabEntry(
                    f[0],
                    f[1],
                    f[2],
                    options,
                    0,
                    0,
                    "non-numeric " + (dump == null ? "dump" : "pass") + " value \"" + bad + "\"",
                    lineNo);
        }
        return new FstabEntry(f[0], f[1], f[2], options, dump, pass, null, lineNo);
    }

    private static List<String> splitOptions(String field) {
        List<String> out = new ArrayList<>();
        for (String o : field.split(",")) {
            String t = o.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.isEmpty() ? List.of("defaults") : out;
    }

    private static Integer parseColumn(String s) {
        try {
            return Integer.valueOf(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Utility for tests: the raw whitespace split of a line. */
    static List<String> columns(String line) {
        return Arrays.asList(line.strip().split("\\s+"));
    }
}

package com.editora.install;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Lenient version arithmetic for the external tools Editora runs: their version lines come in every shape
 * ({@code v1.117.0}, {@code 2026.10.15-11.42.38}, {@code gh version 2.96.0 (2026-07-02)}, {@code 263.7084.0}), so
 * a version is read as its run of numbers and compared number by number, a missing number counting as 0. Pure.
 */
public final class Versions {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** The first dotted number in a line ({@code 2.96.0} in {@code gh version 2.96.0 (…)}), with an optional v. */
    private static final Pattern DOTTED = Pattern.compile("\\d+(?:\\.\\d+)+|\\d+");

    private Versions() {}

    /**
     * Compares two versions number by number: negative when {@code a} is older than {@code b}, 0 when they name the
     * same release, positive when {@code a} is newer. Text between the numbers is ignored ({@code v1.2} = {@code
     * 1.2.0}); a number of any length compares by value.
     */
    public static int compare(String a, String b) {
        List<String> x = numbers(a);
        List<String> y = numbers(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int c = compareNumber(i < x.size() ? x.get(i) : "0", i < y.size() ? y.get(i) : "0");
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * The first version number in {@code line} ({@code 2.96.0} from {@code gh version 2.96.0 (2026-07-02)},
     * {@code 18.20.4} from {@code v18.20.4}), or null when it has none.
     */
    public static @Nullable String firstNumber(@Nullable String line) {
        if (line == null) {
            return null;
        }
        Matcher m = DOTTED.matcher(line);
        return m.find() ? m.group() : null;
    }

    private static List<String> numbers(@Nullable String version) {
        List<String> out = new ArrayList<>();
        if (version == null) {
            return out;
        }
        Matcher m = DIGITS.matcher(version);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static int compareNumber(String a, String b) {
        String x = stripZeros(a);
        String y = stripZeros(b);
        return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
    }

    private static String stripZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }
}

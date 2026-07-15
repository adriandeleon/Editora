package com.editora.editorconfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure EditorConfig glob matcher. Compiles a section glob to a regex and tests it against a file path
 * (relative to the {@code .editorconfig}'s directory, {@code /}-separated). Supports {@code *} (not across
 * {@code /}), {@code **} (across {@code /}), {@code ?}, {@code [seq]}/{@code [!seq]}, {@code {a,b,c}}, and
 * {@code {n1..n2}} numeric ranges. A glob with no {@code /} matches the basename in any directory; a leading
 * {@code /} anchors it to the {@code .editorconfig} directory.
 */
public final class EditorConfigGlob {

    /** Cap on a numeric-range alternation; beyond this we fall back to a generic integer pattern. */
    private static final int MAX_RANGE = 8192;

    private static final Pattern NUM_RANGE = Pattern.compile("(-?\\d+)\\.\\.(-?\\d+)");

    private EditorConfigGlob() {}

    public static boolean matches(String glob, String relPath) {
        if (glob == null || relPath == null) {
            return false;
        }
        String g = glob;
        if (g.indexOf('/') < 0) {
            g = "**/" + g; // no separator → match the basename in any directory
        } else if (g.startsWith("/")) {
            g = g.substring(1); // leading slash → anchored to the .editorconfig directory
        }
        try {
            StringBuilder re = new StringBuilder("^");
            appendPattern(re, g); // also inside the try: a malformed glob must never throw out of matches()
            re.append('$');
            return Pattern.compile(re.toString()).matcher(relPath).matches();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A brace-range bound as a long, or null when it doesn't fit (so the caller degrades to "any integer"). */
    private static Long parseBound(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    private static void appendPattern(StringBuilder re, String g) {
        int n = g.length();
        int i = 0;
        while (i < n) {
            char c = g.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < n && g.charAt(i + 1) == '*') {
                        if (i + 2 < n && g.charAt(i + 2) == '/') {
                            re.append("(?:.*/)?"); // `**/` matches any number of directories, including none
                            i += 3;
                        } else {
                            re.append(".*");
                            i += 2;
                        }
                    } else {
                        re.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> {
                    re.append("[^/]");
                    i++;
                }
                case '[' -> {
                    int close = classEnd(g, i);
                    if (close < 0) {
                        re.append("\\[");
                        i++;
                    } else {
                        appendClass(re, g, i, close);
                        i = close + 1;
                    }
                }
                case '{' -> {
                    int close = matchingBrace(g, i);
                    if (close < 0) {
                        re.append("\\{");
                        i++;
                    } else {
                        appendBrace(re, g.substring(i + 1, close));
                        i = close + 1;
                    }
                }
                default -> {
                    if ("\\.^$+|()".indexOf(c) >= 0) {
                        re.append('\\');
                    }
                    re.append(c);
                    i++;
                }
            }
        }
    }

    private static void appendClass(StringBuilder re, String g, int open, int close) {
        re.append('[');
        int j = open + 1;
        if (j < close && (g.charAt(j) == '!' || g.charAt(j) == '^')) {
            re.append('^');
            j++;
        }
        while (j < close) {
            char c = g.charAt(j++);
            if (c == '\\' || c == '[') {
                re.append('\\');
            }
            re.append(c);
        }
        re.append(']');
    }

    private static void appendBrace(StringBuilder re, String inner) {
        Matcher m = NUM_RANGE.matcher(inner);
        if (m.matches()) {
            // NUM_RANGE accepts any digit count, so a bound past Long.MAX (e.g. `{1..99999999999999999999}` in
            // a hostile .editorconfig) overflows Long.parseLong. The MAX_RANGE cap only fires once BOTH bounds
            // parse, so the throw escaped — and matches()' try/catch is after this call. A bound we can't hold
            // in a long can't be a small enumerable range anyway, so fall back to "match any integer".
            Long lo = parseBound(m.group(1));
            Long hi = parseBound(m.group(2));
            re.append(lo == null || hi == null ? "-?\\d+" : numericRange(lo, hi));
            return;
        }
        List<String> parts = splitTopLevelCommas(inner);
        if (parts.size() == 1) {
            // A single alternative with no comma isn't a brace expansion — treat literally (e.g. `{foo}`).
            re.append("\\{");
            appendPattern(re, inner);
            re.append("\\}");
            return;
        }
        re.append("(?:");
        for (int k = 0; k < parts.size(); k++) {
            if (k > 0) {
                re.append('|');
            }
            appendPattern(re, parts.get(k));
        }
        re.append(')');
    }

    private static String numericRange(long a, long b) {
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        if (hi - lo > MAX_RANGE) {
            return "-?\\d+"; // pathological range → match any integer
        }
        StringBuilder sb = new StringBuilder("(?:");
        for (long v = lo; v <= hi; v++) {
            if (v > lo) {
                sb.append('|');
            }
            sb.append(v);
        }
        return sb.append(')').toString();
    }

    /** Index of the closing {@code ]} of a character class started at {@code open}, or -1. */
    private static int classEnd(String g, int open) {
        // A `]` right after `[` or `[!`/`[^` is a literal member, not the close.
        int j = open + 1;
        if (j < g.length() && (g.charAt(j) == '!' || g.charAt(j) == '^')) {
            j++;
        }
        if (j < g.length() && g.charAt(j) == ']') {
            j++;
        }
        for (; j < g.length(); j++) {
            if (g.charAt(j) == ']') {
                return j;
            }
        }
        return -1;
    }

    private static int matchingBrace(String g, int open) {
        int depth = 0;
        for (int j = open; j < g.length(); j++) {
            char c = g.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return j;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTopLevelCommas(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int j = 0; j < inner.length(); j++) {
            char c = inner.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(inner.substring(start, j));
                start = j + 1;
            }
        }
        parts.add(inner.substring(start));
        return parts;
    }
}

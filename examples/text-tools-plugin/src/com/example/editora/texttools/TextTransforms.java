package com.example.editora.texttools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure text transforms (no editor/JavaFX deps), so they're trivial to reason about and unit-test. */
final class TextTransforms {

    private TextTransforms() {
    }

    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Title Case: capitalize the first letter of each whitespace-separated word, lowercase the rest. */
    static String title(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean start = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                start = true;
                sb.append(c);
            } else if (start) {
                sb.append(Character.toUpperCase(c));
                start = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** Splits a string into lowercase words on spaces, underscores, dashes, and camelCase boundaries. */
    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean boundary = c == ' ' || c == '\t' || c == '_' || c == '-';
            boolean camel = i > 0 && Character.isUpperCase(c) && Character.isLowerCase(s.charAt(i - 1));
            if (camel && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (boundary) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(Character.toLowerCase(c));
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    static String camel(String s) {
        List<String> w = words(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.size(); i++) {
            String word = w.get(i);
            if (i == 0) {
                sb.append(word);
            } else if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return sb.toString();
    }

    static String snake(String s) {
        return String.join("_", words(s));
    }

    static String kebab(String s) {
        return String.join("-", words(s));
    }

    /** Per-line transforms operate on the lines of {@code s}, preserving nothing but the line text. */
    private static String[] lines(String s) {
        return s.split("\n", -1);
    }

    static String sortLines(String s) {
        String[] ls = lines(s);
        Arrays.sort(ls, String.CASE_INSENSITIVE_ORDER);
        return String.join("\n", ls);
    }

    static String uniqueLines(String s) {
        Set<String> seen = new LinkedHashSet<>(Arrays.asList(lines(s)));
        return String.join("\n", seen);
    }

    static String reverseLines(String s) {
        String[] ls = lines(s);
        List<String> list = new ArrayList<>(Arrays.asList(ls));
        java.util.Collections.reverse(list);
        return String.join("\n", list);
    }

    /** Strips trailing spaces/tabs from each line. */
    static String trimTrailing(String s) {
        String[] ls = lines(s);
        for (int i = 0; i < ls.length; i++) {
            ls[i] = ls[i].replaceAll("[ \\t]+$", "");
        }
        return String.join("\n", ls);
    }

    /** Collapses runs of blank (empty/whitespace-only) lines into a single empty line. */
    static String squeezeBlank(String s) {
        String[] ls = lines(s);
        List<String> out = new ArrayList<>();
        boolean prevBlank = false;
        for (String l : ls) {
            boolean blank = l.isBlank();
            if (blank && prevBlank) {
                continue;
            }
            out.add(blank ? "" : l);
            prevBlank = blank;
        }
        return String.join("\n", out);
    }
}

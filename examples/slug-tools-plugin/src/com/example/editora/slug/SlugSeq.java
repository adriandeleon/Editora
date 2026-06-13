package com.example.editora.slug;

import java.util.Locale;

/** Pure slug + line-sequence transforms (no editor/JavaFX deps). */
final class SlugSeq {

    private SlugSeq() {
    }

    /** A URL slug: lowercase, runs of non-alphanumerics → single hyphen, trimmed. */
    static String slugify(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean dash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                dash = false;
            } else if (!dash && sb.length() > 0) {
                sb.append('-');
                dash = true;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '-') {
            end--;
        }
        return sb.substring(0, end);
    }

    /** Prefixes each line with its 1-based number, e.g. {@code "1. foo"}. */
    static String numberLines(String s) {
        String[] ls = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i + 1).append(". ").append(ls[i]);
        }
        return sb.toString();
    }

    /** Replaces each line with its 1-based index (a column of N lines becomes {@code 1..N}). */
    static String fillSequence(String s) {
        int n = s.split("\n", -1).length;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            if (i > 1) {
                sb.append('\n');
            }
            sb.append(i);
        }
        return sb.toString();
    }
}

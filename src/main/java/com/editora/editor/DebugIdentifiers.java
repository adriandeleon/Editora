package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure identifier extraction for the debugger's editor surfaces: {@link #wordAt} feeds the hover
 * value tooltip (which identifier is under the mouse) and {@link #matchesIn} feeds the inline-values
 * overlay (which suspended-frame variables appear on a visible line). An identifier is a
 * letter/underscore/dollar start followed by letter/digit/underscore/dollar — the common ground of
 * Java, Python, and JavaScript names.
 */
final class DebugIdentifiers {

    private DebugIdentifiers() {
    }

    static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** The identifier covering 0-based {@code col} of {@code line}, or null (not on an identifier,
     *  or the covering token starts with a digit — a number literal, not a name). */
    static String wordAt(String line, int col) {
        if (line == null || col < 0 || col >= line.length() || !isIdentChar(line.charAt(col))) {
            return null;
        }
        int s = col;
        while (s > 0 && isIdentChar(line.charAt(s - 1))) {
            s--;
        }
        int e = col;
        while (e < line.length() - 1 && isIdentChar(line.charAt(e + 1))) {
            e++;
        }
        return isIdentStart(line.charAt(s)) ? line.substring(s, e + 1) : null;
    }

    /** The members of {@code names} appearing in {@code line} as whole identifiers, in
     *  first-occurrence order, deduplicated. */
    static List<String> matchesIn(String line, Set<String> names) {
        List<String> out = new ArrayList<>();
        if (line == null || names == null || names.isEmpty()) {
            return out;
        }
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (isIdentStart(c)) {
                int s = i;
                while (i < n && isIdentChar(line.charAt(i))) {
                    i++;
                }
                String w = line.substring(s, i);
                if (names.contains(w) && !out.contains(w)) {
                    out.add(w);
                }
            } else if (isIdentChar(c)) {
                // A token starting with a digit (number literal, possibly with trailing letters like
                // 0x1F or 10L): consume the whole run so its letters aren't misread as a name.
                while (i < n && isIdentChar(line.charAt(i))) {
                    i++;
                }
            } else {
                i++;
            }
        }
        return out;
    }
}

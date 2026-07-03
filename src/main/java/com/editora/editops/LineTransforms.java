package com.editora.editops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Pure, unit-tested whole-line transforms (the String-Manipulation-plugin family): sort
 * (natural/numeric-aware ascending/descending, by length), reverse, shuffle, remove duplicate /
 * empty lines, and trim trailing whitespace. Each takes the full-line slice to transform and
 * returns the transformed slice (a preserved single trailing newline rides along); the controller
 * computes the slice bounds via {@link #lineBounds} and applies the result as one undoable
 * {@code replaceText}. No toolkit dependency (mirrors {@link LineOps}).
 */
public final class LineTransforms {

    private LineTransforms() {}

    /**
     * Extends {@code [selStart, selEnd)} to full-line bounds: from the start of the first selected
     * line to the end of the last selected line (its trailing newline excluded). A non-empty
     * selection ending at column 0 does <b>not</b> pull in that line (the usual select-by-dragging
     * convention). Returns {@code {from, to}}.
     */
    public static int[] lineBounds(String text, int selStart, int selEnd) {
        int n = text.length();
        int start = Math.clamp(selStart, 0, n);
        int end = Math.clamp(selEnd, start, n);
        if (end > start && text.charAt(end - 1) == '\n') {
            end--; // selection ends at column 0 — the caret's line isn't part of it
        }
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        while (end < n && text.charAt(end) != '\n') {
            end++;
        }
        return new int[] {start, end};
    }

    /** Sorts lines ascending with the natural (numeric-aware, case-insensitive) order. */
    public static String sortAscending(String text) {
        return applyToLines(text, lines -> lines.sort(NATURAL));
    }

    /** Sorts lines descending with the natural (numeric-aware, case-insensitive) order. */
    public static String sortDescending(String text) {
        return applyToLines(text, lines -> lines.sort(NATURAL.reversed()));
    }

    /** Sorts lines by length, shortest first (natural order breaks length ties). */
    public static String sortByLength(String text) {
        return applyToLines(
                text,
                lines -> lines.sort(Comparator.comparingInt(String::length).thenComparing(NATURAL)));
    }

    /** Reverses the line order. */
    public static String reverse(String text) {
        return applyToLines(text, java.util.Collections::reverse);
    }

    /** Shuffles the line order with the supplied randomness source (injected for testability). */
    public static String shuffle(String text, RandomGenerator rnd) {
        return applyToLines(text, lines -> {
            for (int i = lines.size() - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                String tmp = lines.get(i);
                lines.set(i, lines.get(j));
                lines.set(j, tmp);
            }
        });
    }

    /** Removes duplicate lines, keeping each line's first occurrence in place (exact match). */
    public static String removeDuplicates(String text) {
        return replaceLines(text, lines -> new ArrayList<>(new LinkedHashSet<>(lines)));
    }

    /** Removes blank (empty or whitespace-only) lines. */
    public static String removeEmpty(String text) {
        return replaceLines(
                text, lines -> lines.stream().filter(l -> !l.isBlank()).toList());
    }

    /** Strips trailing whitespace from every line. */
    public static String trimTrailing(String text) {
        return replaceLines(
                text, lines -> lines.stream().map(LineTransforms::stripTrailing).toList());
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    /** In-place list mutation wrapper (sorts/reorderings — the line count never changes). */
    private static String applyToLines(String text, java.util.function.Consumer<List<String>> mutate) {
        return replaceLines(text, lines -> {
            List<String> copy = new ArrayList<>(lines);
            mutate.accept(copy);
            return copy;
        });
    }

    /**
     * Splits into lines, transforms, and re-joins — preserving a single trailing newline (so a
     * whole-buffer slice ending in {@code \n} never grows a phantom empty line that a sort would
     * float to the top).
     */
    private static String replaceLines(String text, java.util.function.Function<List<String>, List<String>> f) {
        boolean trailingNewline = text.endsWith("\n");
        String body = trailingNewline ? text.substring(0, text.length() - 1) : text;
        List<String> lines = Arrays.asList(body.split("\n", -1));
        String joined = String.join("\n", f.apply(lines));
        return trailingNewline ? joined + "\n" : joined;
    }

    /**
     * Natural line order: digit runs compare numerically ({@code file2} before {@code file10}),
     * everything else case-insensitively; a full case-sensitive compare breaks remaining ties so
     * the order is deterministic.
     */
    public static final Comparator<String> NATURAL = (a, b) -> {
        int cmp = naturalCompare(a, b);
        return cmp != 0 ? cmp : a.compareTo(b);
    };

    private static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        int an = a.length();
        int bn = b.length();
        while (i < an && j < bn) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ie = i;
                while (ie < an && Character.isDigit(a.charAt(ie))) {
                    ie++;
                }
                int je = j;
                while (je < bn && Character.isDigit(b.charAt(je))) {
                    je++;
                }
                int is = i;
                while (is < ie - 1 && a.charAt(is) == '0') {
                    is++; // skip leading zeros (keep one digit)
                }
                int js = j;
                while (js < je - 1 && b.charAt(js) == '0') {
                    js++;
                }
                int lenCmp = Integer.compare(ie - is, je - js);
                if (lenCmp != 0) {
                    return lenCmp; // more significant digits ⇒ bigger number
                }
                int digitCmp = CharSequence.compare(a.subSequence(is, ie), b.subSequence(js, je));
                if (digitCmp != 0) {
                    return digitCmp;
                }
                i = ie;
                j = je;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) {
                    return c;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(an - i, bn - j);
    }
}

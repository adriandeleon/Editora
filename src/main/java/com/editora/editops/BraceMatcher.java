package com.editora.editops;

/**
 * Finds the bracket matching the one adjacent to the caret — pure (no toolkit), so it is unit-tested.
 * {@link EditorBuffer} uses it to highlight the matching {@code () [] {}} pair.
 *
 * <p>Matching is by nesting depth over the raw text (string/comment contents are not excluded — a
 * best-effort that is correct for the overwhelmingly common case). The bracket just <em>left</em> of
 * the caret is preferred (you've just typed/passed it), then the one to the right.
 */
public final class BraceMatcher {

    /** Default cap on how far to scan for a match (keeps caret moves cheap on huge files). */
    public static final int DEFAULT_MAX_SCAN = 50_000;

    private BraceMatcher() {}

    /** The two matching bracket offsets (ascending) to highlight, or {@code null} if none is adjacent. */
    public static int[] match(String text, int caret, int maxScan) {
        int[] left = matchFrom(text, caret - 1, maxScan);
        return left != null ? left : matchFrom(text, caret, maxScan);
    }

    private static int[] matchFrom(String text, int pos, int maxScan) {
        if (pos < 0 || pos >= text.length()) {
            return null;
        }
        char c = text.charAt(pos);
        char mate = mate(c);
        if (mate == 0) {
            return null;
        }
        int dir = isOpen(c) ? 1 : -1;
        int depth = 0;
        for (int i = pos, steps = 0; i >= 0 && i < text.length() && steps < maxScan; i += dir, steps++) {
            char ch = text.charAt(i);
            if (ch == c) {
                depth++;
            } else if (ch == mate && --depth == 0) {
                return i < pos ? new int[] {i, pos} : new int[] {pos, i};
            }
        }
        return null;
    }

    private static char mate(char c) {
        return switch (c) {
            case '(' -> ')';
            case ')' -> '(';
            case '[' -> ']';
            case ']' -> '[';
            case '{' -> '}';
            case '}' -> '{';
            default -> 0;
        };
    }

    private static boolean isOpen(char c) {
        return c == '(' || c == '[' || c == '{';
    }
}

package com.editora.dap;

import java.util.regex.Pattern;

/**
 * How an error an adapter or jdtls reports is said to the user. java-debug answers a refused request with
 * the text of the Java exception behind it, class names and all — {@code
 * org.eclipse.debug.core.DebugException: com.sun.jdi.InvalidTypeException: generated value is not a
 * String} — and those names say nothing to the user. Pure (unit-tested).
 */
public final class DapErrors {

    private DapErrors() {}

    /**
     * One leading {@code a.b.SomeException: } (or {@code …Error: }) — a fully qualified Java class name that
     * names a throwable, then a colon. A bare word ("Error: …") or a path with a colon is left alone.
     */
    private static final Pattern CLASS_PREFIX =
            Pattern.compile("^\\s*(?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable)\\s*:\\s*");

    /** {@code text} without the Java exception class names it starts with; {@code text} itself when it is only that. */
    public static String userText(String text) {
        if (text == null) {
            return "";
        }
        String out = text;
        for (int i = 0; i < 8; i++) { // nested causes repeat the prefix; a few is all any adapter sends
            String next = CLASS_PREFIX.matcher(out).replaceFirst("");
            if (next.equals(out)) {
                break;
            }
            if (next.isBlank()) {
                return out.strip(); // nothing after the class name: it is the only thing there is to say
            }
            out = next;
        }
        return out;
    }

    /** The first non-blank line of {@code text}, stripped ("" for none). */
    public static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    /** Whether {@code text} has more than one non-blank line — too much for the one-line status bar. */
    public static boolean multiLine(String text) {
        if (text == null) {
            return false;
        }
        int lines = 0;
        for (String line : text.split("\\R")) {
            if (!line.isBlank() && ++lines > 1) {
                return true;
            }
        }
        return false;
    }
}

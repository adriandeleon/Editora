package com.editora.dap;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

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
    public static String userText(@Nullable String text) {
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

    /** What lsp4j puts in a refused request's message when the adapter's answer has none. */
    static final String LSP4J_UNSET = "Unset error message.";

    private static final Pattern REPEATED_ERROR_NAME = Pattern.compile("^(?:Uncaught )?([A-Z]\\w*) \\1: ");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /**
     * The text of an adapter's refusal: its {@code message}, or — when it sent none, as vscode-js-debug does for a
     * failed evaluation (lsp4j then says "Unset error message.") — the protocol's {@code body.error.format} with
     * its {@code {name}} variables filled in, first line only, and js-debug's
     * {@code Uncaught ReferenceError ReferenceError: x} said once ({@code ReferenceError: x}). {@code body} is the
     * response body as lsp4j hands it over (a gson {@code JsonObject} or a {@code Map}). Null when neither says
     * anything.
     */
    public static @Nullable String responseText(String message, Object body) {
        if (message != null && !message.isBlank() && !LSP4J_UNSET.equals(message.strip())) {
            return message;
        }
        Object error = member(body, "error");
        Object format = member(error, "format");
        String text = format == null ? null : string(format);
        if (text == null || text.isBlank()) {
            return null;
        }
        Object variables = member(error, "variables");
        java.util.regex.Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder filled = new StringBuilder();
        while (m.find()) {
            Object v = member(variables, m.group(1));
            m.appendReplacement(filled, java.util.regex.Matcher.quoteReplacement(v == null ? m.group() : string(v)));
        }
        m.appendTail(filled);
        String first = firstLine(filled.toString());
        return REPEATED_ERROR_NAME.matcher(first).replaceFirst("$1: ");
    }

    private static @Nullable Object member(@Nullable Object container, String name) {
        if (container instanceof com.google.gson.JsonObject o) {
            return o.has(name) ? o.get(name) : null;
        }
        if (container instanceof java.util.Map<?, ?> map) {
            return map.get(name);
        }
        return null;
    }

    private static String string(Object value) {
        if (value instanceof com.google.gson.JsonPrimitive p) {
            return p.getAsString();
        }
        if (value instanceof com.google.gson.JsonNull) {
            return "";
        }
        return String.valueOf(value);
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

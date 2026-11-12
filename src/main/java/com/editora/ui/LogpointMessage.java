package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * A logpoint's message, split into its text and its {@code {expression}} parts, as DAP's {@code logMessage}
 * interpolates them. Used when the adapter does not log logpoints itself (netcoredbg): Editora evaluates each
 * expression in the stopped frame and logs the filled-in message. Braces nest, so {@code {new { a = 1 }}} is one
 * expression; a brace that is never closed, or an empty pair, is kept as text.
 */
final class LogpointMessage {

    /** One piece of a message: literal {@code text}, or an {@code expression} to evaluate. */
    record Part(String text, boolean expression) {}

    private LogpointMessage() {}

    static List<Part> parse(String message) {
        List<Part> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < message.length()) {
            char c = message.charAt(i);
            int end = c == '{' ? closing(message, i) : -1;
            String expr = end > 0 ? message.substring(i + 1, end).strip() : "";
            if (expr.isEmpty()) {
                text.append(c);
                i++;
                continue;
            }
            if (!text.isEmpty()) {
                parts.add(new Part(text.toString(), false));
                text.setLength(0);
            }
            parts.add(new Part(expr, true));
            i = end + 1;
        }
        if (!text.isEmpty()) {
            parts.add(new Part(text.toString(), false));
        }
        return parts;
    }

    /** The index of the brace that closes the one at {@code open}, or -1. */
    private static int closing(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The message with each expression part replaced by its value, {@code values} in the parts' order. */
    static String fill(List<Part> parts, List<String> values) {
        StringBuilder out = new StringBuilder();
        int v = 0;
        for (Part p : parts) {
            out.append(p.expression() ? (v < values.size() ? values.get(v++) : "") : p.text());
        }
        return out.toString();
    }
}

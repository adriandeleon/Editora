package com.editora.ui;

import java.util.regex.Pattern;

/**
 * Classifies a debugger value string for IntelliJ-style type coloring in the Debug variables tree
 * (strings green, numbers blue, booleans orange, null-likes muted). Adapters render values
 * language-specifically (Java {@code "text"}, Python {@code 'text'}/{@code None}, JS
 * {@code undefined}), so the classification is a cross-language heuristic. Pure — unit-tested.
 */
final class DebugValues {

    /** Value categories, each mapped to a CSS class via {@link #cssClass}. */
    enum ValueKind {
        STRING,
        NUMBER,
        BOOLEAN,
        NULL,
        OTHER
    }

    /** Integers, decimals, scientific notation, and hex — with an optional sign. */
    private static final Pattern NUMBER =
            Pattern.compile("[+-]?(\\d[\\d_]*(\\.\\d+)?([eE][+-]?\\d+)?|0[xX][0-9a-fA-F]+)[LlFfDd]?");

    /**
     * How many children of a variable the tree shows at a time. A {@code byte[65536]} or a 60,000-element list
     * must never become that many tree rows in one go: the rest sits behind a "show more" row.
     */
    static final int PAGE_SIZE = 100;

    /** Terminal control sequences: CSI ({@code ESC [ … letter}, colours and cursor moves) and OSC (titles). */
    private static final Pattern ANSI =
            Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?");

    private DebugValues() {}

    /** {@code text} without terminal control sequences — the Debug console shows plain text. */
    static String stripAnsi(String text) {
        return text == null || text.indexOf('\u001B') < 0
                ? text
                : ANSI.matcher(text).replaceAll("");
    }

    /**
     * Whether a container's indexed children are <em>requested</em> a page at a time (DAP {@code start} /
     * {@code count}). Only possible when the adapter reported how many there are; an adapter that did not is
     * asked for everything, and the tree then pages what came back.
     */
    static boolean fetchedByPage(int indexedVariables) {
        return indexedVariables > PAGE_SIZE;
    }

    /** How many more children the next "show more" reveals, with {@code shown} of {@code total} on screen. */
    static int nextPage(int shown, int total) {
        return Math.max(0, Math.min(PAGE_SIZE, total - shown));
    }

    /**
     * The short name shown for a frame's source. Adapters also put things that are not files there — {@code
     * jdt://contents/java.base/java.lang/String.class?=project/…}, {@code <frozen importlib>} — whose last
     * path segment is a piece of a query string, so the query is dropped before the name is taken.
     */
    static String sourceName(java.nio.file.Path file) {
        if (file == null) {
            return "";
        }
        String text = file.toString();
        int query = text.indexOf('?');
        if (query > 0) {
            text = text.substring(0, query);
        }
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        return slash >= 0 && slash < text.length() - 1 ? text.substring(slash + 1) : text;
    }

    static ValueKind kind(String value) {
        if (value == null || value.isEmpty()) {
            return ValueKind.OTHER;
        }
        char c = value.charAt(0);
        if (c == '"' || c == '\'') {
            return ValueKind.STRING;
        }
        if (value.equals("true") || value.equals("false") || value.equals("True") || value.equals("False")) {
            return ValueKind.BOOLEAN;
        }
        if (value.equals("null") || value.equals("None") || value.equals("undefined") || value.equals("nil")) {
            return ValueKind.NULL;
        }
        if (NUMBER.matcher(value).matches()) {
            return ValueKind.NUMBER;
        }
        return ValueKind.OTHER;
    }

    /** The Text style class for a kind ({@code .debug-val-*} rules in app.css). */
    static String cssClass(ValueKind kind) {
        return switch (kind) {
            case STRING -> "debug-val-string";
            case NUMBER -> "debug-val-number";
            case BOOLEAN -> "debug-val-bool";
            case NULL -> "debug-val-null";
            case OTHER -> "debug-val-other";
        };
    }
}

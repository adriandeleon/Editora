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

    private DebugValues() {}

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

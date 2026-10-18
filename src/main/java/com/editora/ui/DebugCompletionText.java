package com.editora.ui;

import com.editora.dap.DapModels;

/**
 * Pure text decisions of {@link DebugCompletion}: what an adapter's completion looks like in the list and what
 * accepting it does to the field. java-debug labels a method {@code toArray(T[] a) : T[]} with the text
 * {@code toArray()}, a type {@code Name - javax.naming} with the text {@code javax.naming.Name}, and says nothing of
 * the range to replace — which is then the name before the caret (seen live against java-debug 0.53.2).
 */
final class DebugCompletionText {

    private DebugCompletionText() {}

    /** A completion as the list shows it: the name (a method with its parameters) and the muted detail. */
    record Shown(String label, String detail) {}

    /** How a field reads after an accept, and where its caret goes. */
    record Edit(String text, int caret) {}

    static Shown shown(String label) {
        if (label == null) {
            return new Shown("", "");
        }
        int type = label.lastIndexOf(" : ");
        if (type > 0 && label.indexOf('(') >= 0 && label.indexOf('(') < type) {
            return new Shown(label.substring(0, type), label.substring(type + 3)); // toArray() : Object[]
        }
        int pkg = label.indexOf(" - ");
        if (pkg > 0) {
            return new Shown(label.substring(0, pkg), label.substring(pkg + 3)); // Name - javax.naming
        }
        return new Shown(label, "");
    }

    /** Whether a method label declares parameters ({@code get(Object key) : Integer}); the caret goes inside. */
    static boolean hasParameters(String label) {
        if (label == null) {
            return false;
        }
        int open = label.indexOf('(');
        return open >= 0 && open + 1 < label.length() && label.charAt(open + 1) != ')';
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** The name being typed: the identifier characters right before {@code caret}. */
    static String prefix(String text, int caret) {
        if (text == null) {
            return "";
        }
        int end = Math.max(0, Math.min(caret, text.length()));
        int start = end;
        while (start > 0 && isIdent(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, end);
    }

    /**
     * {@code text} with completion {@code t} accepted at {@code caret}: it replaces the range the adapter gave, or
     * else the name before the caret (and the rest of that name after it). A method with parameters leaves the
     * caret between its parentheses, one without after them.
     */
    static Edit apply(String text, int caret, DapModels.CompletionTarget t, boolean parameters) {
        String s = text == null ? "" : text;
        int at = Math.max(0, Math.min(caret, s.length()));
        int start;
        int end;
        if (t.start() >= 0) {
            start = Math.min(t.start(), s.length());
            end = Math.min(s.length(), start + Math.max(0, t.length()));
        } else {
            start = at - prefix(s, at).length();
            end = at;
            while (end < s.length() && isIdent(s.charAt(end))) {
                end++;
            }
        }
        String insert = t.insert();
        String out = s.substring(0, start) + insert + s.substring(end);
        int newCaret = start + insert.length();
        if (parameters && insert.endsWith("()")) {
            newCaret--; // between the parentheses: the arguments come next
        }
        return new Edit(out, newCaret);
    }

    /**
     * Where the {@code {expression}} around {@code caret} of a log message starts (after its brace), or -1 when the
     * caret is not inside one — a log message is text with expressions in braces ({@code n={n}}).
     */
    static int braceStart(String text, int caret) {
        if (text == null) {
            return -1;
        }
        int at = Math.max(0, Math.min(caret, text.length()));
        int open = text.lastIndexOf('{', at - 1);
        if (open < 0) {
            return -1;
        }
        int close = text.lastIndexOf('}', at - 1);
        return close > open ? -1 : open + 1;
    }
}

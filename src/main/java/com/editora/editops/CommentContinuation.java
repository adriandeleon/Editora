package com.editora.editops;

import java.util.regex.Pattern;

/**
 * Enter inside a {@code /* … *}{@code /} block comment in a brace language (JSDoc, Javadoc, Doxygen): the new
 * line continues the comment with {@code " * "}, as VS Code's JavaScript/TypeScript rules and IntelliJ do. In a
 * plain-JS Node project JSDoc is the type system ({@code // @ts-check}), so this is typed on every other line.
 *
 * <ul>
 *   <li>Enter after {@code /**} that nothing closes yet adds {@code " * "} and the closing {@code " *}{@code /"}
 *       below, caret on the middle line.
 *   <li>Enter between {@code /**} and {@code *}{@code /} splits them the same way.
 *   <li>Enter after {@code /**} of a comment closed further down, or on a {@code " * text"} line, continues
 *       with {@code " * "}.
 *   <li>Enter after a {@code " *}{@code /"} line goes back to the comment's own indent (one space less).
 * </ul>
 *
 * Pure: {@code text} holds the caret's line and, below it, as much as the caller read (enough to see whether the
 * comment is already closed).
 */
public final class CommentContinuation {

    private CommentContinuation() {}

    /** A {@code /**} opening whose comment is not closed before the caret (VS Code's JSDoc rule). */
    private static final Pattern DOC_OPEN = Pattern.compile("^\\s*/\\*\\*(?!/)([^*]|\\*(?!/))*$");

    /** A comment body line: indent ending in a space, then {@code *}, then a space or nothing (VS Code's rule). */
    private static final Pattern BODY = Pattern.compile("^[\\t ]* \\*( ([^*]|\\*(?!/))*)?$");

    /** The {@code *}{@code /} line that ends such a comment. */
    private static final Pattern BODY_END = Pattern.compile("^[\\t ]* \\*/\\s*$");

    /**
     * The edit for Enter at {@code caret}, or {@code null} when the caret is not in a block comment this handles.
     *
     * @param before the caret's line up to the caret
     * @param after the caret's line from the caret
     */
    public static Indenter.EnterEdit enter(String text, int caret, String before, String after) {
        String indent = Indenter.leadingWhitespace(before);
        if (DOC_OPEN.matcher(before).matches()) {
            String body = "\n" + indent + " * ";
            if (after.strip().startsWith("*/")) {
                return new Indenter.EnterEdit(body + "\n" + indent + " ", body.length()); // `/**|*/`
            }
            if (after.isBlank() && !closedBelow(text, caret)) {
                return new Indenter.EnterEdit(body + "\n" + indent + " */", body.length());
            }
            return new Indenter.EnterEdit(body, body.length());
        }
        if (BODY.matcher(before).matches() && !after.strip().startsWith("*/")) {
            String insert = "\n" + indent + "* ";
            return new Indenter.EnterEdit(insert, insert.length());
        }
        if (BODY_END.matcher(before).matches() && indent.endsWith(" ")) {
            String insert = "\n" + indent.substring(0, indent.length() - 1);
            return new Indenter.EnterEdit(insert, insert.length());
        }
        return null;
    }

    /** Whether a {@code *}{@code /} follows the caret before any other comment opens. */
    private static boolean closedBelow(String text, int caret) {
        int close = text.indexOf("*/", caret);
        if (close < 0) {
            return false;
        }
        int open = text.indexOf("/*", caret);
        return open < 0 || close < open;
    }
}

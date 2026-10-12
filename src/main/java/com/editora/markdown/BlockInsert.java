package com.editora.markdown;

/**
 * Where the newlines go when a block (a table, a table of contents) is inserted at an offset: the block
 * gets lines of its own with a blank line before it, because a table row or a list written directly under
 * a paragraph line is read as part of that paragraph. Pure.
 */
public final class BlockInsert {

    /** The text to insert at the offset, and where {@code block} starts inside it. */
    public record Framed(String text, int blockStart) {}

    private BlockInsert() {}

    public static Framed at(String text, int offset, String block) {
        int at = Math.max(0, Math.min(offset, text.length()));
        int lineStart = text.lastIndexOf('\n', at - 1) + 1;
        String lead;
        if (at > lineStart) {
            lead = "\n\n"; // mid-line: end the line, then a blank one
        } else {
            int prevStart = lineStart == 0 ? 0 : text.lastIndexOf('\n', lineStart - 2) + 1;
            boolean prevBlank =
                    lineStart == 0 || text.substring(prevStart, lineStart - 1).isBlank();
            lead = prevBlank ? "" : "\n";
        }
        int lineEnd = text.indexOf('\n', at);
        String rest = text.substring(at, lineEnd < 0 ? text.length() : lineEnd);
        return new Framed(lead + block + "\n" + (rest.isBlank() ? "" : "\n"), lead.length());
    }
}

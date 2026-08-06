package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a completion's resolved {@code additionalTextEdits} across the accept's own insertion.
 *
 * <p>A server computes {@code additionalTextEdits} (a TypeScript/Pyright {@code import} line, say) as absolute
 * {@code (line, character)} positions against the document as it stood when the <em>completion</em> was computed.
 * Editora applies the main completion text first and only then resolves and applies those extra edits, so by the
 * time they land the document has legitimately moved — the accept itself moved it. When the accepted text spans
 * lines (a multi-line snippet, or an {@code insertText} carrying newlines), every position at or below the caret
 * has shifted, and applying the server's positions verbatim writes the import into the wrong line — silently
 * (#410). The pre-accept frame is line-accurate for the server's positions because any characters typed while the
 * popup filtered are confined to the caret's own line (typing a newline dismisses the popup).
 *
 * <p>Pure and toolkit-free so the arithmetic is unit-testable; {@link EditorBuffer} supplies the {@link Change}
 * measured around its own edit.
 */
public final class LspEditShift {

    private LspEditShift() {}

    /**
     * The single contiguous change a completion accept made, in 0-based LSP coordinates: the range it replaced
     * in the <em>pre</em>-accept document ({@code start} → {@code preEnd}) and where that range's end sits in the
     * <em>post</em>-accept document ({@code postEnd}).
     */
    public record Change(int startLine, int startCol, int preEndLine, int preEndCol, int postEndLine, int postEndCol) {

        /** True when the accept moved nothing after it — no position needs translating. */
        public boolean identity() {
            return preEndLine == postEndLine && preEndCol == postEndCol;
        }
    }

    /**
     * Returns {@code edits} with every position mapped from the pre-accept document into the post-accept one.
     * An edit that falls strictly inside the replaced range is dropped: the text it addressed is gone, so there
     * is nowhere honest to put it — better a missing import than one spliced into the middle of the accepted
     * text. Null/empty input, or a change that moved nothing, is returned untouched.
     */
    public static List<LspTextEdit> shift(List<LspTextEdit> edits, Change change) {
        if (edits == null || edits.isEmpty() || change == null || change.identity()) {
            return edits;
        }
        List<LspTextEdit> out = new ArrayList<>(edits.size());
        for (LspTextEdit e : edits) {
            int[] start = shiftPosition(e.startLine(), e.startCol(), change);
            int[] end = shiftPosition(e.endLine(), e.endCol(), change);
            if (start == null || end == null) {
                continue;
            }
            out.add(new LspTextEdit(start[0], start[1], end[0], end[1], e.newText()));
        }
        return out;
    }

    /**
     * Maps one pre-accept position into the post-accept document, or null when it sits strictly inside the
     * replaced range. Positions at or before the replaced range are untouched (the common case — an import line
     * above the caret); positions after it move by the change's line delta, and those sharing the replaced
     * range's last line also move by its column delta.
     */
    static int[] shiftPosition(int line, int col, Change c) {
        if (atOrBefore(line, col, c.startLine(), c.startCol())) {
            return new int[] {line, col};
        }
        if (strictlyBefore(line, col, c.preEndLine(), c.preEndCol())) {
            return null;
        }
        int newLine = line + (c.postEndLine() - c.preEndLine());
        int newCol = line == c.preEndLine() ? col + (c.postEndCol() - c.preEndCol()) : col;
        return new int[] {Math.max(0, newLine), Math.max(0, newCol)};
    }

    private static boolean atOrBefore(int line, int col, int atLine, int atCol) {
        return line < atLine || (line == atLine && col <= atCol);
    }

    private static boolean strictlyBefore(int line, int col, int atLine, int atCol) {
        return line < atLine || (line == atLine && col < atCol);
    }

    /**
     * Where a caret at absolute offset {@code caret} ends up once a set of edits is applied — used to put the
     * caret back where the user left it after an auto-import has been spliced in above it.
     *
     * <p>An {@code additionalTextEdits} import lands <em>before</em> the caret, and applying it leaves the caret
     * at the end of the inserted line, which is nowhere the user was typing. Only edits ending at or before the
     * caret move it, by their net length change; edits entirely after it leave it alone.
     *
     * <p>{@code ranges} are {@code {from, to}} absolute offsets against the document as it stood <b>before</b>
     * these edits, ascending and non-overlapping — exactly what {@link EditorBuffer#applyLspEdits} resolves them
     * to — with {@code texts} the parallel replacements.
     */
    public static int caretAfterEdits(int caret, List<int[]> ranges, List<String> texts) {
        int shift = 0;
        for (int i = 0; i < ranges.size(); i++) {
            int from = ranges.get(i)[0];
            int to = ranges.get(i)[1];
            int newLen = texts.get(i) == null ? 0 : texts.get(i).length();
            if (to <= caret) {
                shift += newLen - (to - from);
            } else if (from < caret) {
                // This edit replaces the text the caret sits inside, so the offset it named is gone. Land at the
                // end of the replacement — and because the ranges are ascending and non-overlapping, everything
                // still to come starts after the caret and cannot move it.
                return from + shift + newLen;
            } else {
                break; // ascending: this edit and every later one start after the caret
            }
        }
        return caret + shift;
    }
}

package com.editora.editor;

import org.fxmisc.richtext.CodeArea;

/** Resolves LSP text edits to document ranges and applies them; the pure-ish half of {@link EditorBuffer}'s LSP edits. */
final class LspEditPlacement {
    private LspEditPlacement() {}

    /** Edits resolved to absolute, ascending, non-overlapping {@code {start, end}} ranges and their texts. */
    record Placement(java.util.List<int[]> ranges, java.util.List<String> texts) {}

    /**
     * Resolves each edit to an absolute range against the current document. Lenient ({@code strict} false):
     * an edit that cannot be placed is left out. Strict: one such edit makes the whole set unplaceable and
     * the result is {@code null}.
     */
    static Placement place(CodeArea a, java.util.List<LspTextEdit> edits, boolean strict) {
        int len = a.getLength();
        int lineCount = a.getParagraphs().size();
        java.util.List<int[]> ranges = new java.util.ArrayList<>(); // {start, end}
        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.List<LspTextEdit> asc = new java.util.ArrayList<>(edits);
        if (strict) {
            for (LspTextEdit e : asc) {
                // A negative coordinate would be clamped onto line/column 0 — somewhere the server never
                // meant. The lenient path keeps that clamp; a strict set is refused instead.
                if (e == null || e.startLine() < 0 || e.startCol() < 0 || e.endLine() < 0 || e.endCol() < 0) {
                    return null;
                }
            }
        }
        asc.sort((x, y) -> Integer.compare(editOffset(a, x), editOffset(a, y)));
        int last = 0;
        for (LspTextEdit e : asc) {
            try {
                if (e.startLine() > lineCount || (e.startLine() == lineCount && e.startCol() > 0)) {
                    if (strict) {
                        return null;
                    }
                    continue; // start beyond the document — a stale edit; clamping would misplace it (#667)
                }
                int s = offset(a, e.startLine(), e.startCol());
                int en = offset(a, e.endLine(), e.endCol());
                int from = Math.min(s, en);
                int to = Math.max(s, en);
                if (from < last || to > len) {
                    if (strict) {
                        return null;
                    }
                    continue; // overlaps a previous edit or out of range — skip (rare; positions shifted)
                }
                ranges.add(new int[] {from, to});
                texts.add(e.newText() == null ? "" : e.newText());
                last = to;
            } catch (RuntimeException ignored) {
                // Position no longer valid (document changed under us) — skip this edit.
                if (strict) {
                    return null;
                }
            }
        }
        return new Placement(ranges, texts);
    }

    /** Places and applies {@code edits} as one undo unit; returns whether they were placed. */
    static boolean apply(CodeArea a, java.util.List<LspTextEdit> edits, boolean preserveCaret, boolean strict) {
        int caretBefore = a.getCaretPosition();
        int anchorBefore = a.getAnchor();
        LspEditView.Before view = preserveCaret ? null : LspEditView.capture(a);
        // Resolve each edit to an absolute [start,end] against the current document, keep valid + non-overlapping,
        // sorted ascending. Applying them as ONE MultiChangeBuilder commit makes the whole set a single undo
        // unit — a multi-line Format Document (or an auto-import's additional edits) was previously one
        // replaceText per edit, so it took many Ctrl-Z to revert (#415, the Format-Document sub-item).
        Placement placement = place(a, edits, strict);
        if (placement == null) {
            return false;
        }
        java.util.List<int[]> ranges = placement.ranges();
        java.util.List<String> texts = placement.texts();
        if (ranges.isEmpty()) {
            return true;
        }
        if (ranges.size() == 1) {
            a.replaceText(ranges.get(0)[0], ranges.get(0)[1], texts.get(0));
            restoreCaretAfterEdits(a, view, caretBefore, anchorBefore, ranges, texts);
            return true;
        }
        // Apply BOTTOM-TO-TOP. The fork's MultiChangeBuilder applies its replacements *sequentially against
        // the progressively-edited document* (documented in CLAUDE.md), so absolute offsets computed against
        // the ORIGINAL text are only valid while nothing before them has changed length. Feeding ascending
        // order silently corrupted every edit after the first length-changing one — which is exactly what
        // Format Document does (re-indent = grow/shrink), so it mangled the file. Descending order keeps
        // every offset valid because each edit lies before the region already rewritten. (The LSP spec gives
        // the same rule for applying a TextEdit[].) Same-length edits hid this in the original test.
        org.fxmisc.richtext.MultiChangeBuilder<?, ?, ?> builder = a.createMultiChange(ranges.size());
        for (int i = ranges.size() - 1; i >= 0; i--) {
            builder.replaceTextAbsolutely(ranges.get(i)[0], ranges.get(i)[1], texts.get(i));
        }
        builder.commit(); // one undo unit for the whole edit set
        restoreCaretAfterEdits(a, view, caretBefore, anchorBefore, ranges, texts);
        return true;
    }

    /** Puts the caret back where it was, translated across the edits just applied; see {@code LspEditShift}. */
    private static void restoreCaretAfterEdits(
            CodeArea a,
            LspEditView.Before view,
            int caretBefore,
            int anchorBefore,
            java.util.List<int[]> ranges,
            java.util.List<String> texts) {
        if (view != null) {
            LspEditView.restore(a, view, ranges, texts); // format / quick fix / rename: see LspEditView
            return;
        }
        int target = LspEditShift.caretAfterEdits(caretBefore, ranges, texts);
        int anchor = LspEditShift.caretAfterEdits(anchorBefore, ranges, texts);
        a.selectRange(Math.max(0, Math.min(anchor, a.getLength())), Math.max(0, Math.min(target, a.getLength())));
        a.requestFollowCaret();
    }

    private static int editOffset(CodeArea a, LspTextEdit e) {
        try {
            return offset(a, e.startLine(), e.startCol());
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /**
     * Absolute offset for a 0-based LSP line/character, clamped to the document/paragraph bounds. A line past
     * the last one is the document <em>end</em> (as in {@code LspPositions.offset}), not the start of the
     * last line: {@code (lineCount, 0)} is how a server addresses "after everything".
     */
    static int offset(CodeArea a, int line, int col) {
        if (line >= a.getParagraphs().size()) {
            return a.getLength();
        }
        int par = Math.max(0, line);
        var paragraph = a.getParagraph(par);
        int column = Math.max(0, Math.min(col, paragraph.length()));
        // A column between the two halves of a surrogate pair addresses no character: editing there
        // leaves two lone surrogates, which are then saved as '?'. Snap back to the pair's start.
        if (column > 0
                && column < paragraph.length()
                && Character.isLowSurrogate(paragraph.charAt(column))
                && Character.isHighSurrogate(paragraph.charAt(column - 1))) {
            column--;
        }
        return a.getAbsolutePosition(par, column);
    }

    /**
     * The 0-based LSP {@code {line, character}} of an absolute offset (the inverse of {@link #offset}),
     * clamped to the document. {@code Backward} bias so an offset at a line's end reads as that line's last
     * column rather than the next line's column 0 — the two ends of the accept's change must agree.
     */
    static int[] position(CodeArea a, int offset) {
        int clamped = Math.max(0, Math.min(offset, a.getLength()));
        var pos = a.offsetToPosition(clamped, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
        return new int[] {pos.getMajor(), pos.getMinor()};
    }
}

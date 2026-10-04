package com.editora.editor;

import java.util.List;

import org.fxmisc.richtext.CodeArea;

/**
 * Keeps the caret, the selection and the viewport where the user left them across a set of server edits
 * (Format Document, a quick fix, organize imports, a rename).
 *
 * <p>Those edits used to be applied with no restoration at all: a single-edit result left the caret at the end
 * of that edit — an "add import" quick fix threw it from the line being worked on into the import block, with
 * the selection dropped, so the next keystroke typed there — and a whole-document or every-line result reset
 * the viewport to line 1.
 */
final class LspEditView {

    private LspEditView() {}

    /** Where the caret, the anchor and the viewport were before the edits. */
    record Before(int caret, int anchor, int caretLine, int caretCol, int anchorLine, int anchorCol, Double scrollY) {}

    static Before capture(CodeArea a) {
        int caret = a.getCaretPosition();
        int anchor = a.getAnchor();
        var caretAt = a.offsetToPosition(caret, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
        var anchorAt = a.offsetToPosition(anchor, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
        return new Before(
                caret,
                anchor,
                caretAt.getMajor(),
                caretAt.getMinor(),
                anchorAt.getMajor(),
                anchorAt.getMinor(),
                a.estimatedScrollYProperty().getValue());
    }

    /** Puts the selection back, translated across the applied {@code ranges}, and undoes a viewport reset. */
    static void restore(CodeArea a, Before before, List<int[]> ranges, List<String> texts) {
        int caret = target(a, before.caret(), before.caretLine(), before.caretCol(), ranges, texts);
        int anchor = before.anchor() == before.caret()
                ? caret
                : target(a, before.anchor(), before.anchorLine(), before.anchorCol(), ranges, texts);
        a.selectRange(anchor, caret);
        // As for restyles (setStyleSpansPreservingScroll): a commit that replaces every paragraph drops the
        // virtual flow's scroll to the top. Restoring only an actual collapse keeps ordinary edits untouched.
        Double after = a.estimatedScrollYProperty().getValue();
        if (before.scrollY() != null && before.scrollY() > 1 && (after == null || after <= 1)) {
            a.estimatedScrollYProperty().setValue(before.scrollY());
        }
    }

    private static int target(CodeArea a, int offset, int line, int col, List<int[]> ranges, List<String> texts) {
        if (insideReplaced(offset, ranges)) {
            // The text around the caret was rewritten (a whole-document format): no offset arithmetic can
            // say where it "went", but the same line and column is where the user was looking.
            int par = Math.max(0, Math.min(line, a.getParagraphs().size() - 1));
            return a.getAbsolutePosition(par, Math.max(0, Math.min(col, a.getParagraphLength(par))));
        }
        return Math.max(0, Math.min(LspEditShift.caretAfterEdits(offset, ranges, texts), a.getLength()));
    }

    /** Pure: whether {@code offset} lies strictly inside one of the replaced {@code {from, to}} ranges. */
    static boolean insideReplaced(int offset, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (range[0] < offset && offset < range[1]) {
                return true;
            }
        }
        return false;
    }
}

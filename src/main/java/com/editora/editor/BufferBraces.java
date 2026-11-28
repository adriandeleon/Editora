package com.editora.editor;

import javafx.application.Platform;

import com.editora.editops.BraceMatcher;
import com.editora.perf.OffFx;
import org.fxmisc.richtext.CodeArea;

/**
 * Bracket matching at the caret: one re-match per pulse while the caret moves, a near window scanned on the FX
 * thread and the rest off it, and the style classes that mark the pair.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferBraces {

    private final EditorBuffer b;

    BufferBraces(EditorBuffer b) {
        this.b = b;
    }

    /** Coalesces brace-match recomputes to one per pulse (caret moves rapidly while typing). */
    private boolean braceMatchPending;
    /** Coalesces a matching-bracket recompute to the next pulse (caret moves rapidly while typing). */
    void scheduleBraceMatch() {
        if (braceMatchPending) {
            return;
        }
        braceMatchPending = true;
        Platform.runLater(this::updateBraceMatch);
    }
    /** The latest brace-match ask: a far scan answering an older one is dropped. */
    private long braceAsk;
    /**
     * Clears any previous match highlight and highlights the pair adjacent to the focused caret. The partner is
     * looked for in place only near the caret ({@link EditorBuffer#NEAR_BRACE_SCAN} chars each way); when it is not there, the whole {@link BraceMatcher#DEFAULT_MAX_SCAN} window is scanned off the FX
     * thread and the pair shown when that lands, if the text, the caret and the focused view are unchanged.
     */
    private void updateBraceMatch() {
        braceMatchPending = false;
        long ask = ++braceAsk;
        clearBraceMatch();
        if (b.largeFile) {
            return; // brace matching off in large-file mode (highlighting is disabled there too)
        }
        CodeArea a = b.focusedArea;
        int caret = a.getCaretPosition();
        // BraceMatcher answers from the two characters beside the caret and never scans beyond
        // DEFAULT_MAX_SCAN chars from it: read those two first, and build a window only when one of them is a bracket.
        int max = BraceMatcher.DEFAULT_MAX_SCAN;
        int winStart = Math.max(0, caret - max - 1);
        int winEnd = Math.min(a.getLength(), caret + max + 1);
        String beside = a.getText(Math.max(0, caret - 1), Math.min(a.getLength(), caret + 1));
        if (!BraceMatcher.needsScan(
                caret > 0 ? beside.charAt(0) : 0, beside.isEmpty() ? 0 : beside.charAt(beside.length() - 1))) {
            return;
        }
        int[] near = nearBraceWindow(a, caret);
        int nearStart = Math.max(winStart, near[0]);
        int nearEnd = Math.min(winEnd, near[1]);
        int[] m = BraceMatcher.match(a.getText(nearStart, nearEnd), caret - nearStart, max);
        if (m != null) {
            showBraceMatch(m[0] + nearStart, m[1] + nearStart);
            return;
        }
        if (nearStart == winStart && nearEnd == winEnd) {
            return; // the whole window was near: there is no partner
        }
        String window = a.getText(winStart, winEnd);
        long version = b.docVersion;
        OffFx.then(
                EditorBuffer.BRACE_SCAN_SITE,
                () -> java.util.Optional.ofNullable(BraceMatcher.match(window, caret - winStart, max)),
                found -> {
                    int[] far = found.value() == null ? null : found.value().orElse(null);
                    if (far != null
                            && ask == braceAsk
                            && !b.disposed
                            && b.docVersion == version
                            && b.focusedArea == a
                            && a.getCaretPosition() == caret) {
                        showBraceMatch(far[0] + winStart, far[1] + winStart);
                    }
                });
    }
    /**
     * The near window around {@code caret}: the lines on screen, at most {@link EditorBuffer#NEAR_BRACE_SCAN} chars each way;
     * empty (at the caret) when the caret's line is not on screen or the view is not laid out yet, so the whole
     * search goes off the FX thread.
     */
    private static int[] nearBraceWindow(CodeArea a, int caret) {
        int first;
        int last;
        try {
            first = a.firstVisibleParToAllParIndex();
            last = a.lastVisibleParToAllParIndex();
        } catch (RuntimeException notLaidOut) {
            return new int[] {caret, caret};
        }
        int line = a.getCurrentParagraph();
        if (first < 0 || last < first || line < first || line > last) {
            return new int[] {caret, caret};
        }
        int start = a.getAbsolutePosition(first, 0);
        int end = a.getAbsolutePosition(last, a.getParagraphLength(last));
        return new int[] {
            Math.max(Math.max(0, caret - EditorBuffer.NEAR_BRACE_SCAN), start),
            Math.min(Math.min(a.getLength(), caret + EditorBuffer.NEAR_BRACE_SCAN), end)
        };
    }

    private void showBraceMatch(int open, int close) {
        addBraceClass(open);
        addBraceClass(close);
        b.completionActions.setBraceMatch(new int[] {open, close});
    }

    void clearBraceMatch() {
        if (b.completionActions.braceMatch() != null) {
            removeBraceClass(b.completionActions.braceMatch()[0]);
            removeBraceClass(b.completionActions.braceMatch()[1]);
            b.completionActions.setBraceMatch(null);
        }
    }
    // The match style combines with the char's syntax classes, so the brace keeps its token color; the
    // syntax highlighter overwrites it on re-highlight, after which scheduleBraceMatch() re-applies.
    private void addBraceClass(int pos) {
        if (pos < 0 || pos >= b.area.getLength()) {
            return;
        }
        java.util.List<String> style = new java.util.ArrayList<>(b.area.getStyleOfChar(pos));
        if (!style.contains("brace-match")) {
            style.add("brace-match");
            b.area.setStyle(pos, pos + 1, style);
        }
    }

    private void removeBraceClass(int pos) {
        if (pos < 0 || pos >= b.area.getLength()) {
            return;
        }
        java.util.List<String> style = new java.util.ArrayList<>(b.area.getStyleOfChar(pos));
        if (style.remove("brace-match")) {
            b.area.setStyle(pos, pos + 1, style);
        }
    }
}

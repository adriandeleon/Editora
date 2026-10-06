package com.editora.editor;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntFunction;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * Carries line-pinned marks ({@link BookmarkManager}, {@link BreakpointManager}) through the text changes of
 * a {@link CodeArea}: resolves where each change happened, hands it to {@link LineMarks#shift}, and keeps the
 * marks' line-text snapshots current.
 *
 * <p>Changes arrive as a <em>batch</em> ({@code multiPlainChanges}). A formatter's edit set, an undo of a
 * multi-caret edit and a workspace edit are one batch of several replacements, and the area reports all of
 * them only after the whole batch has been applied — each with the position it had <em>when it ran</em>.
 * Resolving such a position against the final document gives the wrong line whenever another replacement of
 * the batch changed the text above it, so each one is first translated (see {@link #settle}).
 */
final class LineMarkTracker {

    private LineMarkTracker() {}

    /** The marks after a batch, and what kind of difference there is to the marks before it. */
    record Result<T>(NavigableMap<Integer, T> marks, boolean moved, boolean retexted) {}

    /**
     * Where the {@code i}-th replacement of a batch sits in the final document.
     *
     * @param offset its start offset in the final document
     * @param linesAddedAbove the net number of lines the later replacements added above it, i.e. how much
     *     lower its line is in the final document than it was when the replacement ran
     * @param linesAddedLater the net number of lines all later replacements added anywhere
     */
    record Settled(int offset, int linesAddedAbove, int linesAddedLater) {}

    /**
     * Pure: translates the start of replacement {@code i} into final-document terms. Every later replacement
     * of the batch that starts at or before it moved it by its own net length and line count.
     *
     * @param positions each replacement's start offset, in the document as it was when that one ran
     * @param lengthDelta inserted minus removed characters, per replacement
     * @param lineDelta inserted minus removed newlines, per replacement
     */
    static Settled settle(int i, int[] positions, int[] lengthDelta, int[] lineDelta) {
        int offset = positions[i];
        int above = 0;
        int later = 0;
        for (int j = i + 1; j < positions.length; j++) {
            later += lineDelta[j];
            if (positions[j] <= offset) {
                offset += lengthDelta[j];
                above += lineDelta[j];
            }
        }
        return new Settled(Math.max(0, offset), above, later);
    }

    static <T> Result<T> apply(
            NavigableMap<Integer, T> marks, LineMarks.Kind<T> kind, List<PlainTextChange> changes, CodeArea area) {
        if (marks.isEmpty()) {
            // Nothing to carry — the usual case — so a large batch (a formatter's edit set, a multi-caret
            // edit) is not settled replacement by replacement, which is quadratic in the batch.
            return new Result<>(marks, false, false);
        }
        int n = changes.size();
        int[] positions = new int[n];
        int[] lengthDelta = new int[n];
        int[] lineDelta = new int[n];
        if (n > 1) {
            for (int i = 0; i < n; i++) {
                PlainTextChange c = changes.get(i);
                positions[i] = c.getPosition();
                lengthDelta[i] = c.getInserted().length() - c.getRemoved().length();
                lineDelta[i] = LineMarks.countNewlines(c.getInserted()) - LineMarks.countNewlines(c.getRemoved());
            }
        }
        int paragraphs = area.getParagraphs().size();
        IntFunction<String> finalLine =
                line -> line >= 0 && line < paragraphs ? area.getParagraph(line).getText() : "";
        NavigableMap<Integer, T> current = marks;
        boolean moved = false;
        int[] touchedFrom = new int[n];
        int[] touchedTo = new int[n];
        for (int i = 0; i < n; i++) {
            PlainTextChange c = changes.get(i);
            Settled at = n == 1 ? new Settled(c.getPosition(), 0, 0) : settle(i, positions, lengthDelta, lineDelta);
            var pos = area.offsetToPosition(Math.min(at.offset(), area.getLength()), Bias.Forward);
            int insertedNL = LineMarks.countNewlines(c.getInserted());
            touchedFrom[i] = pos.getMajor();
            touchedTo[i] = pos.getMajor() + insertedNL;
            if (c.getRemoved().equals(c.getInserted())
                    || (insertedNL == 0 && c.getRemoved().indexOf('\n') < 0)) {
                continue; // nothing moved between lines
            }
            int above = at.linesAddedAbove();
            // Lines as they were numbered right after this replacement ran, read from the final document.
            IntFunction<String> lineThen = line -> finalLine.apply(line + above);
            LineMarks.Edit edit = LineMarks.Edit.of(
                    c.getRemoved(), c.getInserted(), pos.getMajor() - above, pos.getMinor(), lineThen);
            NavigableMap<Integer, T> shifted =
                    LineMarks.shift(current, kind, edit, paragraphs - at.linesAddedLater(), lineThen);
            if (!shifted.equals(current)) {
                moved = true;
                current = shifted;
            }
        }
        // A mark's stored line text is what re-anchors it after a reopen and what lets it follow a moved
        // line; keep it the text the line has now, not the text it had when the mark was set.
        boolean retexted = false;
        for (int i = 0; i < n; i++) {
            List<T> stale = null;
            for (T mark :
                    current.subMap(touchedFrom[i], true, touchedTo[i], true).values()) {
                if (!LineMarks.snapshotText(finalLine.apply(kind.line(mark))).equals(kind.lineText(mark))) {
                    if (stale == null) {
                        stale = new java.util.ArrayList<>();
                    }
                    stale.add(mark);
                }
            }
            if (stale == null) {
                continue;
            }
            if (current == marks) {
                current = new TreeMap<>(marks);
            }
            for (T mark : stale) {
                int line = kind.line(mark);
                current.put(line, kind.withLineText(mark, LineMarks.snapshotText(finalLine.apply(line))));
            }
            retexted = true;
        }
        return new Result<>(current, moved, retexted);
    }
}

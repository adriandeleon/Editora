package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.NarrowLines;
import org.fxmisc.richtext.CodeArea;

/**
 * Where the user was in a buffer, captured before its text is replaced by the copy on disk and put back
 * afterwards: the caret as line and column, the first visible line, and which folds were collapsed. A
 * reload replaces the whole document, which resets the viewport to the top and clears the fold state — so
 * restoring only the caret offset left the caret off-screen, the user's place lost and every fold open.
 *
 * <p>Everything is held in <b>document</b> lines. A narrowed buffer is widened by the reload, and its text
 * area's own line numbers (region-relative) would then point at unrelated text.
 */
final class ReloadViewState {

    /** One pane's caret and scroll position. {@code top} is -1 when the pane had not been laid out. */
    private record Pane(CodeArea area, int line, int column, int top) {}

    private final EditorBuffer buffer;
    private final List<Pane> panes = new ArrayList<>(2);
    private final List<Integer> collapsed = new ArrayList<>();

    private ReloadViewState(EditorBuffer buffer) {
        this.buffer = buffer;
    }

    /** Captures {@code buffer}'s view. FX thread, before the content is replaced. */
    static ReloadViewState capture(EditorBuffer buffer) {
        ReloadViewState state = new ReloadViewState(buffer);
        int base = buffer.isNarrowed() ? NarrowLines.firstLine(buffer.getContent(), buffer.narrowStart()) : 0;
        state.add(buffer.getArea(), base);
        if (buffer.getFocusedArea() != buffer.getArea()) {
            state.add(buffer.getFocusedArea(), base); // the split's second pane, when it is the focused one
        }
        for (int line : buffer.getFoldManager().collapsedStartLines()) {
            state.collapsed.add(line + base);
        }
        return state;
    }

    private void add(CodeArea area, int base) {
        int top = -1;
        try {
            top = area.firstVisibleParToAllParIndex() + base;
        } catch (RuntimeException notLaidOut) {
            // A tab that has never been shown has no viewport to restore.
        }
        panes.add(new Pane(area, area.getCurrentParagraph() + base, area.getCaretColumn(), top));
    }

    /** Re-applies the captured view to the reloaded text, clamped to what the file now holds. */
    void restore() {
        buffer.getFoldManager().applyCollapsedStartLines(collapsed);
        for (Pane pane : panes) {
            CodeArea area = pane.area();
            int last = area.getParagraphs().size() - 1;
            int line = clamp(pane.line(), last);
            area.moveTo(line, clamp(pane.column(), area.getParagraphLength(line)));
            if (pane.top() < 0) {
                area.requestFollowCaret();
                continue;
            }
            // A pulse later, like every other "show this line" in the app: asked for in the same turn as the
            // replace, the scroll target is resolved against the old layout and lands somewhere in between.
            int top = clamp(pane.top(), last);
            javafx.application.Platform.runLater(() -> {
                try {
                    area.showParagraphAtTop(Math.min(top, area.getParagraphs().size() - 1));
                } catch (RuntimeException notLaidOut) {
                    area.requestFollowCaret();
                }
            });
        }
    }

    /** {@code value} limited to {@code 0..max}; pure. */
    static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}

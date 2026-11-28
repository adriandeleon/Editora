package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * Editing commands that act on the active buffer through the window: the find bar's next, previous and replace
 * steps, multiple carets at every occurrence or match, AceJump, Undo History checkpoints, going to a line, a TODO
 * window's one-line edit, and C-g's cancel. It lived inside {@code MainController}; it is a file of its own only
 * because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowEditCommands {

    private final MainController mc;

    WindowEditCommands(MainController mc) {
        this.mc = mc;
    }

    /** Moves the active editor's caret to {@code line} and anchors it below any sticky-scroll rows. */
    void navigateToLine(int line) {
        EditorBuffer buffer = mc.activeBuffer();
        CodeArea area = mc.activeArea();
        if (buffer == null
                || area == null
                || line < 0
                || line >= area.getParagraphs().size()) {
            return;
        }
        NavigationHistory.Location origin = mc.navigation.navigating ? null : mc.navigation.captureCurrent();
        // Reveal the target if it's hidden inside a collapsed fold, so we don't scroll to a hidden line.
        buffer.getFoldManager().unfoldContaining(line);
        area.moveTo(line, 0);
        if (!mc.navigation.navigating && buffer.getPath() != null) {
            mc.navigation.recordJumpToLine(origin, buffer, line);
        }
        buffer.jumps().reveal(area, line, false);
        area.requestFocus();
    }

    /** Starts AceJump on the active buffer: type a character, then a label, to jump the caret. */
    void startAceJump() {
        EditorBuffer b = mc.activeBuffer();
        if (b == null) {
            return;
        }
        mc.setStatus(tr("acejump.prompt"));
        b.startAceJump();
    }

    void startAceJumpLine() {
        EditorBuffer b = mc.activeBuffer();
        if (b == null) {
            return;
        }
        mc.setStatus(tr("acejump.promptLine"));
        b.startAceJumpLine();
    }

    /**
     * Rewrites a single source line for a TODO tool-window edit (set priority / mark done / edit description):
     * opens the file so the change is visible, verifies the target line still reads {@code expectedLine}, then
     * replaces that paragraph with {@code newLine} as one undoable edit (marks the buffer dirty). A no-op with a
     * status when the line moved/changed since the scan (the panel re-scans via {@code afterApply}).
     */
    void applyTodoLineEdit(
            java.nio.file.Path file, int line, String expectedLine, String newLine, Runnable afterApply) {
        mc.fileWorkflows.openThen(
                file,
                () -> { // after the load: the shell is empty and read-only until then
                    EditorBuffer b = mc.activeBuffer();
                    if (b == null
                            || b.getPath() == null
                            || !MainController.pathKey(b.getPath()).equals(MainController.pathKey(file))) {
                        return;
                    }
                    // Say so rather than doing nothing: a TODO in a read-only buffer is common (a .log opens in View
                    // mode, as does anything not writable on disk — i.e. exactly the vendored/generated code a scan
                    // turns up), and a menu click that produced no status, no error and no change looked like a bug.
                    if (!mc.editing.activeEditable()) {
                        mc.setStatus(tr("status.todo.readOnly"));
                        return;
                    }
                    org.fxmisc.richtext.CodeArea area = b.getArea();
                    int idx = mc.navigation.areaLine(b, line - 1); // a narrowed area numbers its lines from the region
                    int paragraphs = area.getParagraphs().size();
                    if (idx < 0 || idx >= paragraphs) {
                        mc.setStatus(tr("status.todo.lineChanged")); // the file shrank under the scan snapshot
                        if (afterApply != null) {
                            afterApply.run();
                        }
                        return;
                    }
                    String current = area.getParagraph(idx).getText();
                    if (!current.equals(expectedLine)) {
                        mc.setStatus(tr("status.todo.lineChanged"));
                        if (afterApply != null) {
                            afterApply.run();
                        }
                        return;
                    }
                    int start = area.getAbsolutePosition(idx, 0);
                    area.replaceText(start, start + current.length(), newLine); // undoable; marks the buffer dirty
                    mc.setStatus(tr("status.todo.edited"));
                    if (afterApply != null) {
                        afterApply.run();
                    }
                });
    }

    void findNextMatch() {
        if (mc.findBar.isShown()) {
            mc.findBar.findNext();
        } else {
            mc.findBar.show(false);
        }
    }

    void findPreviousMatch() {
        if (mc.findBar.isShown()) {
            mc.findBar.findPrevious();
        } else {
            mc.findBar.show(true);
        }
    }

    void findReplaceCurrentMatch() {
        if (mc.findBar.isShown()) {
            mc.findBar.replaceCurrentMatch();
        } else {
            mc.findBar.show(false);
            mc.findBar.focusReplace();
        }
    }

    void findReplaceAllMatches() {
        if (mc.findBar.isShown()) {
            mc.findBar.replaceAllMatches();
        } else {
            mc.findBar.show(false);
            mc.findBar.focusReplace();
        }
    }

    // --- Undo History popup (the QuickOpen mirror of the Undo History tool window) ---

    /** Checkpoints of the active buffer, newest-first, seeding a baseline when empty (like the panel). */
    java.util.List<com.editora.editor.undo.UndoHistory.Checkpoint> undoHistoryCheckpoints() {
        EditorBuffer b = mc.activeBuffer();
        if (b == null) {
            return java.util.List.of();
        }
        if (b.getUndoHistory().isEmpty()) {
            b.captureUndoCheckpoint(); // baseline = the current state, so there's always something to pick
        }
        return b.getUndoHistory().entriesNewestFirst();
    }

    void restoreUndoCheckpoint(com.editora.editor.undo.UndoHistory.Checkpoint c) {
        EditorBuffer b = mc.activeBuffer();
        if (b != null) {
            b.restoreUndoCheckpoint(c);
        }
    }

    /** C-s: show the find bar, or — if already showing — cycle to the next match. */
    void findShowOrNext() {
        if (mc.findBar.isShown()) {
            mc.findBar.findNext();
        } else {
            mc.findBar.show(false);
        }
    }

    /** C-r: show the find bar (reverse), or — if already showing — cycle to the previous match. */
    void findShowOrPrevious() {
        if (mc.findBar.isShown()) {
            mc.findBar.findPrevious();
        } else {
            mc.findBar.show(true);
        }
    }

    /** Replace: ensure the find/replace bar is showing and focus the replace field. (C-g closes it.) */
    void showReplace() {
        if (!mc.findBar.isShown()) {
            mc.findBar.show(false);
        }
        mc.findBar.focusReplace();
    }

    // --- Multiple cursors / column selection commands (delegate to the active buffer's fork add-on) ---

    /** Runs {@code action} on the active buffer when multi-caret is enabled; else reports it. */
    void withMultiCaret(java.util.function.Consumer<EditorBuffer> action) {
        if (!mc.editorSettings.multiCaretEnabled()) {
            mc.setStatus(tr("status.multiCaret.disabled"));
            return;
        }
        EditorBuffer b = mc.activeBuffer();
        if (b != null) {
            action.accept(b);
        }
    }

    /** VS Code {@code selectHighlights} (Ctrl+Shift+L): a caret at every occurrence of the selection/word. */
    void selectAllOccurrences() {
        withMultiCaret(b -> {
            b.selectAllOccurrences( // searched off the FX thread (PERF-01, Pauta 6.2.0)
                    n -> mc.setStatus(n == 0 ? tr("status.occurrences.none") : tr("status.occurrences.selected", n)));
        });
    }

    /** VS Code {@code selectAllMatches} (Alt+Enter in the find bar): a caret at every find-bar match. */
    void selectAllFindMatches() {
        if (!mc.findBar.isShown()) {
            mc.setStatus(tr("status.find.notOpen"));
            return;
        }
        withMultiCaret(b -> {
            java.util.List<int[]> m = new java.util.ArrayList<>(mc.findBar.currentMatches());
            if (m.isEmpty()) {
                mc.setStatus(tr("status.occurrences.none"));
                return;
            }
            int anchor = mc.activeArea() != null ? mc.activeArea().getCaretPosition() : 0;
            mc.findBar.hideBar(); // returns focus to the editor before the carets are placed
            int n = b.placeOccurrenceCarets(m, anchor);
            mc.setStatus(tr("status.occurrences.selected", n));
        });
    }

    void cancel() {
        EditorBuffer completing = mc.activeBuffer();
        if (completing != null && completing.completionShowing()) {
            completing.cancelCompletion(); // C-g cancels the inline ghost (the popup is caught in-buffer)
            mc.setStatus("");
            return;
        }
        if (mc.palette.isShown()) {
            mc.palette.hide();
        } else if (mc.findBar.isShown()) {
            mc.findBar.hideBar();
        } else if (mc.macroCoordinator.cancelRecording()) {
            return; // nothing else to dismiss: C-g abandons the macro being recorded (it says so itself)
        } else {
            mc.editing.deactivateMark();
            mc.editing.collapseCarets(); // C-g leaves one caret, as Escape does
            CodeArea area = mc.activeArea();
            if (area != null) {
                area.deselect();
            }
        }
        mc.setStatus("");
    }
}

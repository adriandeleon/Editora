package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

/** Folds what the actions on save did (clean-up, organize imports, format) into one undo step. FX thread. */
final class SaveActionUndo {
    private SaveActionUndo() {}

    /** Clean-up, Organize Imports and Format, each of which may be an undo step of its own. */
    private static final int SAVE_ACTION_STEPS = 3;

    /**
     * Makes what the actions on save did one undo step: undoing it took one Undo per action, and the states in
     * between were never on disk (re-review AN7). Undoes the actions' steps back to {@code saved}, then puts
     * their result back as a single edit. The history is left as it was when those steps cannot be told apart
     * from the user's own (they do not lead back to {@code saved}) or the buffer shows only part of its text.
     */
    static void foldIntoOneUndo(EditorBuffer buffer, String saved) {
        CodeArea area = buffer.getArea();
        String result = area.getText();
        if (!result.equals(buffer.getContent())) {
            return; // narrowed or filtered: the area is not the whole document
        }
        var undo = area.getUndoManager();
        int caret = area.getCaretPosition();
        int undone = 0;
        while (!area.getText().equals(saved) && undo.isUndoAvailable() && undone < SAVE_ACTION_STEPS) {
            undo.undo();
            undone++;
        }
        if (!area.getText().equals(saved)) {
            for (int i = 0; i < undone && undo.isRedoAvailable(); i++) {
                undo.redo();
            }
            return;
        }
        if (undone < 2) {
            if (undone == 1) {
                undo.redo(); // already one step
            }
            return;
        }
        int prefix = 0;
        int max = Math.min(saved.length(), result.length());
        while (prefix < max && saved.charAt(prefix) == result.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix
                && saved.charAt(saved.length() - 1 - suffix) == result.charAt(result.length() - 1 - suffix)) {
            suffix++;
        }
        undo.preventMerge();
        area.replaceText(prefix, saved.length() - suffix, result.substring(prefix, result.length() - suffix));
        undo.preventMerge();
        area.moveTo(Math.min(caret, area.getLength()));
    }
}

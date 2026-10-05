package com.editora.editor;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import org.fxmisc.richtext.CodeArea;

/**
 * Copy and Cut with nothing selected: they take the caret's whole line (VS Code's
 * {@code editor.emptySelectionClipboard}). {@code first..last} is the run of paragraphs to take — the
 * caret's line, plus the hidden body of a collapsed fold whose header it is.
 */
final class LineClipboard {

    private LineClipboard() {}

    /** Puts the lines, with a trailing newline, on the clipboard. Leaves the document untouched. */
    static void copy(CodeArea a, int first, int last) {
        put(a.getText(first, 0, last, a.getParagraphLength(last)) + "\n");
    }

    /** Copies the lines, then deletes them as one undoable edit. */
    static void cut(CodeArea a, int first, int last) {
        copy(a, first, last);
        int total = a.getParagraphs().size();
        int start;
        int end;
        if (last < total - 1) { // not the last line: take this line plus its trailing newline
            start = a.getAbsolutePosition(first, 0);
            end = a.getAbsolutePosition(last + 1, 0);
        } else if (first > 0) { // last line: take the preceding newline plus this line
            start = a.getAbsolutePosition(first - 1, a.getParagraph(first - 1).length());
            end = a.getAbsolutePosition(last, a.getParagraph(last).length());
            // The clipboard holds exactly what is removed — newline first — so pasting it back where the
            // cut leaves the caret restores the line. With the newline last, as for every other line, the
            // paste joined it onto the line above: "one\ntwo" came back as "onetwo\n".
            put(a.getText(start, end));
        } else { // only line in the buffer: clear it
            start = 0;
            end = a.getLength();
        }
        a.deleteText(start, end);
    }

    private static void put(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}

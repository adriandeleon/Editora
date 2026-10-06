package com.editora.editor;

import com.editora.editops.IndentWindow;
import com.editora.editops.Indenter;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * What a buffer's Enter / Tab / closer keystrokes need from its document without copying it: a
 * {@link IndentWindow.Doc} view of an area, and the document's detected indent unit, kept until the text it
 * was detected from changes.
 *
 * <p>{@code area.getText()} builds the whole document as one String; RichTextFX drops that String on every
 * edit, so a keystroke that asks for it pays an O(document) copy each time. The indentation algorithms read
 * a few kilobytes around the caret, and the unit comes from the first {@link IndentWindow#HEAD_CHARS}
 * characters — typing anywhere below them cannot change it.
 */
final class IndentKeys {

    private final CodeArea area;
    private String detected;
    private int detectedTabSize;
    private int detections;

    /** {@code area} is the buffer's primary area; a split's second view shares its document. */
    IndentKeys(CodeArea area) {
        this.area = area;
        area.plainTextChanges().subscribe(change -> {
            if (change.getPosition() < IndentWindow.HEAD_CHARS) {
                detected = null;
            }
        });
    }

    /** The indent unit: the override when there is one, else the document's own (see {@link Indenter#unitFor}). */
    String unit(int tabSize, Boolean insertSpaces, Integer indentSize) {
        if (insertSpaces != null) {
            return Indenter.unitFor("", tabSize, insertSpaces, indentSize);
        }
        if (detected == null || detectedTabSize != tabSize) {
            detected = IndentWindow.unit(doc(area), tabSize, null, null);
            detectedTabSize = tabSize;
            detections++;
        }
        return detected;
    }

    /** How many times the unit was inferred from the document's head (tests). */
    int detections() {
        return detections;
    }

    /** {@code a}'s document, read a line or a range at a time. */
    static IndentWindow.Doc doc(CodeArea a) {
        return new IndentWindow.Doc() {
            @Override
            public int length() {
                return a.getLength();
            }

            @Override
            public int lineOf(int offset) {
                return a.offsetToPosition(offset, Bias.Forward).getMajor();
            }

            @Override
            public int lineStart(int line) {
                return a.getAbsolutePosition(line, 0);
            }

            @Override
            public int lineLength(int line) {
                return a.getParagraphLength(line);
            }

            @Override
            public String text(int from, int to) {
                return a.getText(from, to);
            }
        };
    }
}

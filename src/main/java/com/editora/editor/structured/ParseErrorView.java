package com.editora.editor.structured;

import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A preview's "this does not parse" message with where it stopped: the parser's own message, then a
 * "Line 2, column 5" link that puts the caret there (XML review C15 — the SVG preview used to say only "Could
 * not render this SVG", and the XML tree preview gave the message with no position). Shared by the XML tree
 * preview and the SVG preview. FX thread.
 */
public final class ParseErrorView {

    /** Moves to a 1-based line and column of the previewed document. */
    @FunctionalInterface
    public interface Jump {
        void to(int line, int column);
    }

    private ParseErrorView() {}

    /**
     * The error node: {@code message} in a label of {@code styleClass}, and when {@code line > 0} a link to
     * {@code line}:{@code column} (column 0 = unknown, the line's start) — plain text when there is no {@code
     * jump}.
     */
    public static Node of(String message, int line, int column, @Nullable Jump jump, String styleClass) {
        Label text = new Label(message);
        text.getStyleClass().add(styleClass);
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        if (line <= 0) {
            return text;
        }
        String where = position(line, column);
        Node at;
        if (jump == null) {
            at = new Label(where);
        } else {
            Hyperlink link = new Hyperlink(where);
            link.setOnAction(e -> jump.to(line, column));
            at = link;
        }
        at.getStyleClass().add("preview-error-position");
        VBox box = new VBox(4, at, text);
        box.getStyleClass().add("preview-error");
        return box;
    }

    /** "Line 2, column 5", or "Line 2" when the column is unknown. Pure. */
    public static String position(int line, int column) {
        return column > 0 ? tr("preview.errorAt", line, column) : tr("preview.errorAtLine", line);
    }

    /**
     * Puts {@code buffer}'s caret on the 1-based {@code line}:{@code column} (clamped), showing the editor beside
     * the preview when only the preview was on screen, and focuses it.
     */
    public static void jump(EditorBuffer buffer, int line, int column) {
        if (buffer.getMarkdownViewMode() == MarkdownViewMode.PREVIEW) {
            buffer.setMarkdownViewMode(MarkdownViewMode.SPLIT);
        }
        CodeArea area = buffer.getArea();
        int paragraphs = area.getParagraphs().size();
        int l = Math.max(0, Math.min(paragraphs - 1, line - 1));
        int c = Math.max(0, Math.min(area.getParagraphLength(l), column - 1));
        area.moveTo(l, c);
        area.requestFollowCaret();
        area.requestFocus();
    }
}

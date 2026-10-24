package com.editora.editor;

import com.editora.editor.markdown.MarkdownPreview;

/** What tests in other packages may ask of this package's test helpers. */
public final class EditorFxBridge {

    private EditorFxBridge() {}

    /** Every text run of {@code buffer}'s rendered preview, joined without separators inside a block. */
    public static String previewText(EditorBuffer buffer) {
        MarkdownPreview preview = EditorFx.field(buffer, "markdownPreview");
        StringBuilder sb = new StringBuilder();
        collect(preview.column(), sb);
        return sb.toString();
    }

    private static void collect(javafx.scene.Node node, StringBuilder sb) {
        if (node instanceof javafx.scene.text.Text t) {
            sb.append(t.getText());
        } else if (node instanceof javafx.scene.control.Labeled l && l.getText() != null) {
            sb.append(l.getText());
        }
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
                collect(c, sb);
            }
            if (node instanceof javafx.scene.text.TextFlow) {
                sb.append('\n');
            }
        }
    }
}

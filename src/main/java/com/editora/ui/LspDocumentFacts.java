package com.editora.ui;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.FormattingIndent;
import org.fxmisc.richtext.CodeArea;

/**
 * What Editora tells a language server about one buffer's layout ({@link FormattingIndent}): its indentation, its
 * EditorConfig {@code max_line_length}, and whether its text ends with a line break — the HTML server wraps and keeps
 * the final newline only as its settings say (HTML review A3, D6). Recorded on every sync and edit, so it stays cheap:
 * no text is copied.
 */
final class LspDocumentFacts {

    private LspDocumentFacts() {}

    /** {@code buffer}'s indentation, with its wrap column and last line break. */
    static FormattingIndent indent(EditorBuffer buffer) {
        CodeArea area = buffer.getArea();
        int last = area.getParagraphs().size() - 1;
        return FormattingIndent.of(buffer.indentUnit(), buffer.getTabSize())
                .withDocument(
                        buffer.getEditorConfigProps().maxLineLength(), last > 0 && area.getParagraphLength(last) == 0);
    }
}

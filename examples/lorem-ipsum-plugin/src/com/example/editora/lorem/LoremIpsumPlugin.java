package com.example.editora.lorem;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/**
 * A small, real plugin: generates lorem-ipsum placeholder text. It contributes two palette commands —
 * <em>insert a paragraph at the caret</em> and <em>replace the selection</em> (falling back to insert when
 * nothing is selected). Both edit the active buffer through the {@link ActiveEditor} facade.
 */
public class LoremIpsumPlugin implements Plugin {

    private final LoremIpsum lorem = new LoremIpsum();

    @Override
    public void start(PluginContext ctx) {
        // Insert a fresh paragraph at the caret.
        ctx.registerCommand("paragraph", "Lorem Ipsum: Insert Paragraph", () -> {
            ActiveEditor ed = ctx.activeEditor();
            if (ed.filePath() == null && ed.text() == null) {
                ctx.setStatus("Open a file first");
                return;
            }
            ed.insertAtCaret(lorem.paragraph());
        });

        // Replace the current selection with lorem ipsum; if there's no selection, insert at the caret.
        ctx.registerCommand("replaceSelection", "Lorem Ipsum: Replace Selection", () -> {
            ActiveEditor ed = ctx.activeEditor();
            String sel = ed.selectedText();
            if (sel != null && !sel.isEmpty()) {
                ed.replaceSelection(lorem.paragraph());
            } else {
                ed.insertAtCaret(lorem.paragraph());
                ctx.setStatus("Nothing selected — inserted a paragraph at the caret");
            }
        });
    }
}

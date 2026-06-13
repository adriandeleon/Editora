package com.example.editora.mdtoc;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/**
 * Inserts a Markdown table of contents (a nested bullet list of anchor links) at the caret, built from the
 * document's headings. Pure logic lives in {@link Toc}.
 */
public class MarkdownTocPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        ctx.registerCommand("insert", "Markdown: Insert Table of Contents", () -> {
            ActiveEditor ed = ctx.activeEditor();
            String all = ed.text();
            String toc = all == null ? "" : Toc.build(all);
            if (toc.isEmpty()) {
                ctx.setStatus("No Markdown headings found");
                return;
            }
            ed.insertAtCaret(toc);
        });
    }
}

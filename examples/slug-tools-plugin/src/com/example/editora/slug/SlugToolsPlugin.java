package com.example.editora.slug;

import java.util.function.UnaryOperator;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/** Slugify text and number/fill lines. Operates on the selection if present, else the whole document. */
public class SlugToolsPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        cmd(ctx, "slugify", "Slug: Slugify", SlugSeq::slugify);
        cmd(ctx, "numberLines", "Sequence: Number Lines", SlugSeq::numberLines);
        cmd(ctx, "fillSequence", "Sequence: Fill 1..N", SlugSeq::fillSequence);
    }

    private void cmd(PluginContext ctx, String id, String title, UnaryOperator<String> fn) {
        ctx.registerCommand(id, title, () -> {
            ActiveEditor ed = ctx.activeEditor();
            String sel = ed.selectedText();
            if (sel != null && !sel.isEmpty()) {
                ed.replaceSelection(fn.apply(sel));
            } else {
                String all = ed.text();
                if (all != null && !all.isEmpty()) {
                    ed.setText(fn.apply(all));
                }
            }
        });
    }
}

package com.example.editora.texttools;

import java.util.function.UnaryOperator;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/**
 * A bundle of text transforms. Each command applies a {@link UnaryOperator} to the <em>selection</em> when
 * there is one, otherwise to the <em>whole document</em> (via {@code setText}). Pure logic lives in
 * {@link TextTransforms}; this class is just the wiring.
 */
public class TextToolsPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        cmd(ctx, "upper", "Text: UPPERCASE", TextTransforms::upper);
        cmd(ctx, "lower", "Text: lowercase", TextTransforms::lower);
        cmd(ctx, "title", "Text: Title Case", TextTransforms::title);
        cmd(ctx, "camel", "Text: camelCase", TextTransforms::camel);
        cmd(ctx, "snake", "Text: snake_case", TextTransforms::snake);
        cmd(ctx, "kebab", "Text: kebab-case", TextTransforms::kebab);
        cmd(ctx, "sortLines", "Text: Sort Lines", TextTransforms::sortLines);
        cmd(ctx, "uniqueLines", "Text: Unique Lines", TextTransforms::uniqueLines);
        cmd(ctx, "reverseLines", "Text: Reverse Lines", TextTransforms::reverseLines);
        cmd(ctx, "trimTrailing", "Text: Trim Trailing Whitespace", TextTransforms::trimTrailing);
        cmd(ctx, "squeezeBlank", "Text: Squeeze Blank Lines", TextTransforms::squeezeBlank);
    }

    private void cmd(PluginContext ctx, String id, String title, UnaryOperator<String> fn) {
        ctx.registerCommand(id, title, () -> apply(ctx, fn));
    }

    /** Transform the selection if non-empty, else the whole buffer. */
    private void apply(PluginContext ctx, UnaryOperator<String> fn) {
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
    }
}

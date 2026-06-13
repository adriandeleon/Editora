package com.example.editora.jsontools;

import java.util.function.UnaryOperator;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/**
 * Tidy structured text in place: JSON pretty-print / minify (dependency-free) and XML pretty-print (via the
 * JDK's {@code java.xml}). Each command transforms the selection if there is one, else the whole document;
 * malformed input is reported in the status bar and leaves the buffer unchanged.
 */
public class JsonToolsPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        cmd(ctx, "jsonPretty", "JSON: Pretty Print", JsonFormat::pretty);
        cmd(ctx, "jsonMinify", "JSON: Minify", JsonFormat::minify);
        cmd(ctx, "xmlPretty", "XML: Pretty Print", x -> {
            try {
                return XmlFormat.pretty(x);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? "invalid XML" : e.getMessage(), e);
            }
        });
    }

    private void cmd(PluginContext ctx, String id, String title, UnaryOperator<String> fn) {
        ctx.registerCommand(id, title, () -> apply(ctx, fn));
    }

    private void apply(PluginContext ctx, UnaryOperator<String> fn) {
        ActiveEditor ed = ctx.activeEditor();
        String sel = ed.selectedText();
        boolean whole = sel == null || sel.isEmpty();
        String in = whole ? ed.text() : sel;
        if (in == null || in.isBlank()) {
            return;
        }
        String out;
        try {
            out = fn.apply(in);
        } catch (RuntimeException e) {
            ctx.setStatus("Failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return;
        }
        if (whole) {
            ed.setText(out);
        } else {
            ed.replaceSelection(out);
        }
    }
}

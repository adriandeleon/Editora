package com.example.editora.banner;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/** Wraps the selection (or the whole document) in an ASCII box banner. */
public class BoxBannerPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        ctx.registerCommand("box", "Banner: Box", () -> {
            ActiveEditor ed = ctx.activeEditor();
            String sel = ed.selectedText();
            if (sel != null && !sel.isEmpty()) {
                ed.replaceSelection(BannerText.box(sel));
            } else {
                String all = ed.text();
                if (all != null && !all.isEmpty()) {
                    ed.setText(BannerText.box(all));
                }
            }
        });
    }
}

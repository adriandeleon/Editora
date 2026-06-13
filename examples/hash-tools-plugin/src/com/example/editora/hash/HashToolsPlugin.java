package com.example.editora.hash;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/**
 * Hashes text to a lowercase-hex digest. With a selection it <b>replaces</b> the selection with its hash;
 * with no selection it <b>inserts</b> the hash of the whole document at the caret (so the document isn't
 * clobbered).
 */
public class HashToolsPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        cmd(ctx, "md5", "Hash: MD5", "MD5");
        cmd(ctx, "sha1", "Hash: SHA-1", "SHA-1");
        cmd(ctx, "sha256", "Hash: SHA-256", "SHA-256");
    }

    private void cmd(PluginContext ctx, String id, String title, String algorithm) {
        ctx.registerCommand(id, title, () -> {
            ActiveEditor ed = ctx.activeEditor();
            String sel = ed.selectedText();
            if (sel != null && !sel.isEmpty()) {
                ed.replaceSelection(Hashes.hash(algorithm, sel));
            } else {
                String all = ed.text();
                if (all == null || all.isEmpty()) {
                    ctx.setStatus("Nothing to hash");
                    return;
                }
                ed.insertAtCaret(Hashes.hash(algorithm, all));
            }
        });
    }
}

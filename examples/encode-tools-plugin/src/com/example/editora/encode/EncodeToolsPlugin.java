package com.example.editora.encode;

import java.util.function.UnaryOperator;

import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;

/**
 * Encode/decode the selection (or whole document): Base64, URL, HTML entities, ROT13, hex. Decoders that
 * fail on malformed input report the error in the status bar and leave the buffer unchanged.
 */
public class EncodeToolsPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        cmd(ctx, "base64Encode", "Encode: Base64 Encode", Codecs::base64Encode);
        cmd(ctx, "base64Decode", "Encode: Base64 Decode", Codecs::base64Decode);
        cmd(ctx, "urlEncode", "Encode: URL Encode", Codecs::urlEncode);
        cmd(ctx, "urlDecode", "Encode: URL Decode", Codecs::urlDecode);
        cmd(ctx, "htmlEncode", "Encode: HTML Entities Encode", Codecs::htmlEncode);
        cmd(ctx, "htmlDecode", "Encode: HTML Entities Decode", Codecs::htmlDecode);
        cmd(ctx, "rot13", "Encode: ROT13", Codecs::rot13);
        cmd(ctx, "hexEncode", "Encode: Hex Encode", Codecs::hexEncode);
        cmd(ctx, "hexDecode", "Encode: Hex Decode", Codecs::hexDecode);
    }

    private void cmd(PluginContext ctx, String id, String title, UnaryOperator<String> fn) {
        ctx.registerCommand(id, title, () -> apply(ctx, fn));
    }

    /** Transform the selection if non-empty, else the whole buffer; report decode failures. */
    private void apply(PluginContext ctx, UnaryOperator<String> fn) {
        ActiveEditor ed = ctx.activeEditor();
        String sel = ed.selectedText();
        boolean whole = sel == null || sel.isEmpty();
        String in = whole ? ed.text() : sel;
        if (in == null || in.isEmpty()) {
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

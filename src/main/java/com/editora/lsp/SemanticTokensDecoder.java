package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.SemanticToken;

/**
 * Pure decode of an LSP semantic-tokens {@code data} array into absolute, CSS-classed
 * {@link SemanticToken}s.
 *
 * <p>The wire format (LSP 3.16) is a flat {@code int[]} of <b>5 ints per token</b>, each token
 * <b>delta-encoded</b> relative to the previous one:
 * <pre>[deltaLine, deltaStartChar, length, tokenTypeIndex, tokenModifiersBitset]</pre>
 * {@code deltaLine} is relative to the previous token's line; {@code deltaStartChar} is relative to
 * the previous token's start <em>when on the same line</em>, else absolute. Indices reference the
 * server's legend (the ordered type/modifier name lists from {@code initialize}); positions are
 * UTF-16 based, which is exactly a Java {@code String} index — see {@link LspPositions}.
 *
 * <p>Tokens whose type maps to {@code null} (keyword/comment/… — already handled by TextMate) are
 * dropped, but the running line/char cursor still advances through them so later tokens stay aligned.
 * Side-effect-free and toolkit-free for direct unit testing.
 */
public final class SemanticTokensDecoder {

    private SemanticTokensDecoder() {}

    /**
     * Decodes {@code data} against the server's {@code typeLegend}/{@code modLegend}. Returns an empty
     * list for null/too-short data or a missing type legend; never throws on malformed input (a partial
     * trailing group, an out-of-range type index) — such entries are skipped.
     */
    public static List<SemanticToken> decode(List<Integer> data, List<String> typeLegend, List<String> modLegend) {
        List<SemanticToken> out = new ArrayList<>();
        if (data == null || data.size() < 5 || typeLegend == null) {
            return out;
        }
        int line = 0;
        int startChar = 0;
        for (int i = 0; i + 4 < data.size(); i += 5) {
            int deltaLine = val(data.get(i));
            int deltaStart = val(data.get(i + 1));
            int length = val(data.get(i + 2));
            int typeIdx = val(data.get(i + 3));
            int modBits = val(data.get(i + 4));

            line += deltaLine;
            startChar = (deltaLine == 0) ? startChar + deltaStart : deltaStart; // absolute on a new line

            if (length <= 0 || typeIdx < 0 || typeIdx >= typeLegend.size()) {
                continue; // malformed/zero-length — cursor already advanced, just skip emitting
            }
            String css = SemanticTokenMapper.cssClass(typeLegend.get(typeIdx), modBits, modLegend);
            if (css != null) {
                out.add(new SemanticToken(line, startChar, length, css));
            }
        }
        return out;
    }

    private static int val(Integer n) {
        return n == null ? 0 : n;
    }
}

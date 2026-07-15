package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.SemanticToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A semantic-tokens response computed against an older document must be dropped, not re-anchored onto the
 * current (shifted) text. Every other async-highlight path in the codebase uses a generation guard; this one
 * used only a {@code semanticStale} boolean that {@code setSemanticTokens} <b>unconditionally cleared</b> — so
 * a reply that landed after a keystroke (or an older reply arriving after a newer one) painted stale colors
 * onto moved characters until the next response re-anchored them. Now the request's generation is checked.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticTokenStalenessFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final List<SemanticToken> TOKENS = List.of(new SemanticToken(0, 0, 3, "keyword"));

    @SuppressWarnings("unchecked")
    private static int tokenCount(EditorBuffer b) {
        return ((List<SemanticToken>) FxTestSupport.<List<?>>field(b, "semanticTokens")).size();
    }

    private static boolean stale(EditorBuffer b) {
        return FxTestSupport.<Boolean>field(b, "semanticStale");
    }

    @Test
    void aResponseForAnOlderDocumentIsDropped() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("let x = 1\n");
            b.setSemanticActive(true);
            return b;
        });

        // A response for the current document applies and clears the stale flag.
        long gen0 = FxTestSupport.callOnFx(buffer::semanticGen);
        FxTestSupport.runOnFx(() -> buffer.setSemanticTokens(TOKENS, gen0));
        assertEquals(1, FxTestSupport.callOnFx(() -> tokenCount(buffer)), "current-gen tokens applied");
        assertFalse(FxTestSupport.callOnFx(() -> stale(buffer)), "and the overlay is un-suppressed");

        // Now the user types. The edit listener bumps the generation and marks the tokens stale.
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "\n")); // shift everything down a line
        long gen1 = FxTestSupport.callOnFx(buffer::semanticGen);
        assertNotEquals(gen0, gen1, "an edit advances the semantic generation");
        assertTrue(FxTestSupport.callOnFx(() -> stale(buffer)), "and suppresses the overlay");

        // The in-flight request's reply (captured gen0) lands late — it must be DROPPED, staying suppressed,
        // NOT re-anchored onto the now-shifted text.
        FxTestSupport.runOnFx(() -> buffer.setSemanticTokens(List.of(new SemanticToken(0, 0, 9, "string")), gen0));
        assertTrue(FxTestSupport.callOnFx(() -> stale(buffer)), "a stale response leaves the overlay suppressed");

        // A fresh response for the current document re-anchors and un-suppresses.
        FxTestSupport.runOnFx(() -> buffer.setSemanticTokens(TOKENS, gen1));
        assertFalse(FxTestSupport.callOnFx(() -> stale(buffer)), "the current-gen response applies");
        assertEquals(1, FxTestSupport.callOnFx(() -> tokenCount(buffer)));
    }
}

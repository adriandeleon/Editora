package com.editora.lsp;

import java.util.List;
import java.util.Set;

import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LspManager}'s pure capability helpers (trigger chars, semantic-tokens gate). */
class LspManagerTest {

    private static SemanticTokensWithRegistrationOptions stProvider(boolean withLegend, Boolean range, Boolean full) {
        var opts = new SemanticTokensWithRegistrationOptions(); // no-arg: legend stays null (ctor rejects null)
        if (withLegend) {
            opts.setLegend(new SemanticTokensLegend(List.of("keyword"), List.of("static")));
        }
        if (range != null) {
            opts.setRange(range);
        }
        if (full != null) {
            opts.setFull(full);
        }
        return opts;
    }

    @Test
    void extractsTriggerCharactersFromCapabilities() {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setCompletionProvider(new CompletionOptions(false, List.of(".", "<", "/")));
        assertEquals(Set.of('.', '<', '/'), LspManager.triggerCharsOf(caps));
    }

    @Test
    void usesOnlyTheFirstCharOfMultiCharTriggers() {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setCompletionProvider(new CompletionOptions(false, List.of("::", "->")));
        assertEquals(Set.of(':', '-'), LspManager.triggerCharsOf(caps));
    }

    @Test
    void emptyWhenNoCompletionProviderOrNoTriggers() {
        assertTrue(LspManager.triggerCharsOf(null).isEmpty());
        assertTrue(LspManager.triggerCharsOf(new ServerCapabilities()).isEmpty()); // no completionProvider
        ServerCapabilities noTriggers = new ServerCapabilities();
        noTriggers.setCompletionProvider(new CompletionOptions()); // completionProvider, null triggerCharacters
        assertTrue(LspManager.triggerCharsOf(noTriggers).isEmpty());
    }

    @Test
    void semanticTokensProviderSupportedWithRangeOrFull() {
        ServerCapabilities range = new ServerCapabilities();
        range.setSemanticTokensProvider(stProvider(true, true, null));
        assertNotNull(LspManager.semanticTokensProvider(range)); // legend + range → supported

        ServerCapabilities fullOnly = new ServerCapabilities();
        fullOnly.setSemanticTokensProvider(stProvider(true, false, true));
        assertNotNull(LspManager.semanticTokensProvider(
                fullOnly)); // full-only (e.g. some servers) → supported, full fallback
    }

    @Test
    void semanticTokensProviderNullWhenNeitherRangeNorFull() {
        ServerCapabilities neither = new ServerCapabilities();
        neither.setSemanticTokensProvider(stProvider(true, false, false));
        assertNull(LspManager.semanticTokensProvider(neither));

        ServerCapabilities unset = new ServerCapabilities();
        unset.setSemanticTokensProvider(stProvider(true, null, null));
        assertNull(LspManager.semanticTokensProvider(unset)); // range + full both unset
    }

    @Test
    void semanticTokensProviderNullWhenAbsentOrNoLegend() {
        assertNull(LspManager.semanticTokensProvider(null));
        assertNull(LspManager.semanticTokensProvider(new ServerCapabilities())); // no provider at all
        ServerCapabilities noLegend = new ServerCapabilities();
        noLegend.setSemanticTokensProvider(stProvider(false, true, true));
        assertNull(LspManager.semanticTokensProvider(noLegend)); // can't decode without a legend
    }
}

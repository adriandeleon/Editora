package com.editora.lsp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LspManager}'s pure capability helpers (trigger chars, semantic-tokens gate,
 *  jdtls initializationOptions). */
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
    void workspaceSymbolProviderDetectedFromEitherForm() {
        assertFalse(LspManager.workspaceSymbolProvider(null));
        assertFalse(LspManager.workspaceSymbolProvider(new ServerCapabilities())); // unset
        ServerCapabilities bool = new ServerCapabilities();
        bool.setWorkspaceSymbolProvider(true);
        assertTrue(LspManager.workspaceSymbolProvider(bool));
        ServerCapabilities off = new ServerCapabilities();
        off.setWorkspaceSymbolProvider(false);
        assertFalse(LspManager.workspaceSymbolProvider(off));
        ServerCapabilities opts = new ServerCapabilities();
        opts.setWorkspaceSymbolProvider(new org.eclipse.lsp4j.WorkspaceSymbolOptions()); // options form → supported
        assertTrue(LspManager.workspaceSymbolProvider(opts));
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

    @SuppressWarnings("unchecked")
    @Test
    void javaInitOptionsAlwaysDisablesAutobuildNoDebugBundles() {
        Map<String, Object> opts = LspManager.javaInitOptions(List.of());
        assertFalse(opts.containsKey("bundles"));
        Map<String, Object> settings = (Map<String, Object>) opts.get("settings");
        Map<String, Object> java = (Map<String, Object>) settings.get("java");
        Map<String, Object> autobuild = (Map<String, Object>) java.get("autobuild");
        assertEquals(Boolean.FALSE, autobuild.get("enabled"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void javaInitOptionsIncludesBundlesWhenDebugging() {
        List<String> jars = List.of("/path/to/java-debug.jar");
        Map<String, Object> opts = LspManager.javaInitOptions(jars);
        assertEquals(jars, opts.get("bundles"));
        Map<String, Object> settings = (Map<String, Object>) opts.get("settings");
        Map<String, Object> java = (Map<String, Object>) settings.get("java");
        Map<String, Object> autobuild = (Map<String, Object>) java.get("autobuild");
        assertEquals(Boolean.FALSE, autobuild.get("enabled")); // autobuild stays off even while debugging
    }
}

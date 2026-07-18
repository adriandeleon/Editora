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
    void initOptionsFor() {
        // json/css/html: provideFormatter flips the servers' advertised documentFormattingProvider to true,
        // so Format Document becomes available (#468; verified against the real servers).
        assertEquals(Map.of("provideFormatter", true), LspManager.initOptionsFor("json", List.of()));
        assertEquals(Map.of("provideFormatter", true), LspManager.initOptionsFor("css", List.of()));
        assertEquals(Map.of("provideFormatter", true), LspManager.initOptionsFor("html", List.of()));
        // go keeps its semanticTokens flag; java delegates to javaInitOptions; others need none.
        assertEquals(Map.of("semanticTokens", true), LspManager.initOptionsFor("go", List.of()));
        assertEquals(LspManager.javaInitOptions(List.of()), LspManager.initOptionsFor("java", List.of()));
        // maven-pom (lemminx-maven): turn the extension on with the heavy Central index disabled (local ~/.m2).
        assertEquals(
                Map.of("settings", Map.of("xml", Map.of("maven", Map.of("central", Map.of("skip", true))))),
                LspManager.initOptionsFor("maven-pom", List.of()));
        assertNull(LspManager.initOptionsFor("python", List.of()));
        assertNull(LspManager.initOptionsFor("typescript", List.of()));
        assertNull(LspManager.initOptionsFor(null, List.of()));
    }

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

    /**
     * {@code shutdownServer} finds a server's sessions by {@code key.startsWith(serverId + SEP)}. The key was
     * built with a <b>raw NUL byte</b> typed straight into the source while the prefix used a <b>space</b>, so
     * the scan never matched and shutdownServer did nothing at all — silently. Its callers are "disable this
     * server in Settings" (the server kept running and kept publishing diagnostics) and {@code restartServer},
     * which is how toggling Debug is supposed to reload jdtls with the java-debug bundle (it never did).
     *
     * <p>The raw control character is why it survived: it made LspManager.java <b>binary</b> to grep/rg, which
     * skip it without a word — so the two halves could never be compared by searching for them.
     */
    @Test
    void aSessionKeyIsMatchedByTheShutdownPrefixForItsServer() {
        java.nio.file.Path root = java.nio.file.Path.of("/proj");
        String key = LspManager.sessionKey("json", root);
        assertTrue(
                key.startsWith(LspManager.sessionKeyPrefix("json")),
                "shutdownServer(\"json\") must match its own session key: " + key.replace("\u0000", "<NUL>"));
    }

    /** …and must not match a different server whose id merely starts the same. */
    @Test
    void theShutdownPrefixDoesNotMatchASimilarlyNamedServer() {
        java.nio.file.Path root = java.nio.file.Path.of("/proj");
        assertFalse(LspManager.sessionKey("jsonx", root).startsWith(LspManager.sessionKeyPrefix("json")));
        assertFalse(LspManager.sessionKey("java", root).startsWith(LspManager.sessionKeyPrefix("javascript")));
    }

    /** The separator must be a character no server id or URI can contain, or the scan could match wrongly. */
    @Test
    void theSessionKeySeparatorCannotOccurInAnIdOrUri() {
        String key = LspManager.sessionKey("json", java.nio.file.Path.of("/proj"));
        assertEquals(1, key.chars().filter(c -> c == 0).count(), "exactly one separator in the key");
    }
}

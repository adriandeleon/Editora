package com.editora.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LspServerRegistryTest {

    @Test
    void supportedLanguagesMapToTheirServer() {
        assertTrue(LspServerRegistry.isSupported("java"));
        assertTrue(LspServerRegistry.isSupported("javascript"));
        assertTrue(LspServerRegistry.isSupported("typescript"));
        assertTrue(LspServerRegistry.isSupported("typescriptreact"));
        assertFalse(LspServerRegistry.isSupported("python"));
        assertFalse(LspServerRegistry.isSupported("plaintext"));
        assertEquals("java", LspServerRegistry.serverIdFor("java"));
        // One TypeScript server serves all four JS/TS dialects.
        assertEquals("typescript", LspServerRegistry.serverIdFor("javascript"));
        assertEquals("typescript", LspServerRegistry.serverIdFor("javascriptreact"));
        assertEquals("typescript", LspServerRegistry.serverIdFor("typescript"));
        assertEquals("typescript", LspServerRegistry.serverIdFor("typescriptreact"));
        assertNull(LspServerRegistry.serverIdFor("python"));
        assertNull(LspServerRegistry.specFor("python", Map.of()));
    }

    @Test
    void blankCommandFallsBackToDefault() {
        var spec = LspServerRegistry.specFor("java", Map.of());
        assertEquals(List.of("jdtls"), spec.command());
        assertEquals("java", spec.serverId());
        assertTrue(spec.rootMarkers().contains("pom.xml"));

        assertEquals(List.of("jdtls"), LspServerRegistry.specFor("java", null).command());
    }

    @Test
    void typescriptServerDefaultsAndMarkers() {
        var spec = LspServerRegistry.specFor("typescript", Map.of());
        assertEquals("typescript", spec.serverId());
        assertEquals(List.of("typescript-language-server", "--stdio"), spec.command());
        assertTrue(spec.rootMarkers().contains("tsconfig.json"));
        assertTrue(spec.rootMarkers().contains("package.json"));
        // A .js file routes to the same TypeScript server.
        assertEquals("typescript", LspServerRegistry.specFor("javascript", Map.of()).serverId());
    }

    @Test
    void configuredCommandIsTokenizedPerServer() {
        var java = LspServerRegistry.specFor("java",
                Map.of("java", "java -jar /opt/jdtls/launcher.jar -data ws"));
        assertEquals(List.of("java", "-jar", "/opt/jdtls/launcher.jar", "-data", "ws"), java.command());

        var ts = LspServerRegistry.specFor("typescript", Map.of("typescript", "vtsls --stdio"));
        assertEquals(List.of("vtsls", "--stdio"), ts.command());
    }

    @Test
    void tokenizeHonorsQuotesAndCollapsesWhitespace() {
        assertEquals(List.of("/opt/my server/jdtls", "-x"),
                LspServerRegistry.tokenize("\"/opt/my server/jdtls\"   -x"));
        assertEquals(List.of("a", "b"), LspServerRegistry.tokenize("  a    b  "));
        assertEquals(List.of(), LspServerRegistry.tokenize("   "));
        assertEquals(List.of(), LspServerRegistry.tokenize(null));
    }
}

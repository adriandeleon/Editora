package com.editora.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LspServerRegistryTest {

    @Test
    void onlyJavaSupportedInPhase1() {
        assertTrue(LspServerRegistry.isSupported("java"));
        assertFalse(LspServerRegistry.isSupported("python"));
        assertFalse(LspServerRegistry.isSupported("plaintext"));
        assertNull(LspServerRegistry.specFor("python", "pyright"));
    }

    @Test
    void blankCommandFallsBackToDefault() {
        var spec = LspServerRegistry.specFor("java", "");
        assertEquals(List.of("jdtls"), spec.command());
        assertEquals("java", spec.languageId());
        assertTrue(spec.rootMarkers().contains("pom.xml"));

        assertEquals(List.of("jdtls"), LspServerRegistry.specFor("java", null).command());
    }

    @Test
    void configuredCommandIsTokenized() {
        var spec = LspServerRegistry.specFor("java", "java -jar /opt/jdtls/launcher.jar -data ws");
        assertEquals(List.of("java", "-jar", "/opt/jdtls/launcher.jar", "-data", "ws"), spec.command());
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

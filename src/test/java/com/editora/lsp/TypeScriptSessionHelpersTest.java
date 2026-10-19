package com.editora.lsp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session helpers behind the TypeScript fixes: the refusal message (B3), the Node.js PATH prepend, roots (B7/B10). */
class TypeScriptSessionHelpersTest {

    @Test
    void aServersErrorAnswerIsReadAsItsRefusal() {
        var refused = new CompletionException(new ResponseErrorException(
                new ResponseError(-32603, "Could not find a valid TypeScript installation.", null)));
        assertEquals(
                "Could not find a valid TypeScript installation.", LanguageServerSession.responseErrorMessage(refused));
        assertNull(LanguageServerSession.responseErrorMessage(new java.util.concurrent.TimeoutException()));
        assertNull(LanguageServerSession.responseErrorMessage(null));
    }

    @Test
    void theNodeBinFolderGoesFirstOnPath() {
        assertEquals("/n/bin:/usr/bin", LanguageServerSession.prependedPath("/usr/bin", "/n/bin", ":"));
        assertEquals("/n/bin", LanguageServerSession.prependedPath("", "/n/bin", ":"));
        assertEquals("/usr/bin", LanguageServerSession.prependedPath("/usr/bin", null, ":"));
        Map<String, String> windows = new HashMap<>(Map.of("Path", "C:\\x"));
        assertEquals("Path", LanguageServerSession.pathKey(windows));
        assertEquals("PATH", LanguageServerSession.pathKey(new HashMap<>()));
    }

    @Test
    void aFileWithNoRootOfItsOwnIsLoose() {
        java.nio.file.Path home = java.nio.file.Path.of("/home/u");
        List<String> markers = LspServerRegistry.rootMarkersForServer("typescript");
        java.nio.file.Path loose = java.nio.file.Path.of("/nowhere-" + System.nanoTime(), "a", "x.js");
        assertTrue(RootResolver.isLoose(null, loose, markers, home));
        // Held by the project: not loose.
        assertEquals(false, RootResolver.isLoose(loose.getParent(), loose, markers, home));
    }

    @Test
    void aTsconfigWinsOverANearerPackageJson() {
        assertEquals(
                List.of(List.of("tsconfig.json", "jsconfig.json"), List.of("package.json"), List.of(".git")),
                LspServerRegistry.rootMarkerTiersForServer("typescript"));
    }
}

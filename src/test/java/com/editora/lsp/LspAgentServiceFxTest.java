package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class LspAgentServiceFxTest {
    @TempDir
    Path root;

    private LspManager manager;
    private FakeLanguageServer fake;
    private LanguageServerSession session;
    private Path file;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @BeforeEach
    void setup() throws Exception {
        file = Files.writeString(root.resolve("A.java"), "class A {}");
        manager = new LspManager((p, d) -> {}, (a, b) -> {});
        var caps = new ServerCapabilities();
        caps.setDefinitionProvider(true);
        caps.setReferencesProvider(true);
        caps.setRenameProvider(true);
        caps.setHoverProvider(true);
        manager.setSessionStarterForTest(s -> {
            session = s;
            fake = new FakeLanguageServer();
            s.attachForTest(fake, caps);
        });
        manager.configure(true, Map.of("java", "jdtls"));
        fx(() -> {
            manager.openDocument(file, root, "java", "class A {}");
            return null;
        });
    }

    @AfterEach
    void cleanup() {
        manager.shutdownAll();
    }

    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(3, TimeUnit.SECONDS);
    }

    @Test
    void capabilitiesAndNavigationPreserveUnsupportedAndFailureDistinctions() throws Exception {
        var endpoint = fx(() -> manager.agentEndpoint(file));
        assertTrue(endpoint.operations().contains("definition"));
        assertFalse(endpoint.operations().contains("implementation"));
        assertThrows(
                Exception.class,
                () -> fx(() -> endpoint.request("implementation", file.toUri().toString(), json.createObjectNode()))
                        .get(2, TimeUnit.SECONDS));
        fake.definitionResponse =
                List.of(new Location(file.toUri().toString(), new Range(new Position(0, 6), new Position(0, 7))));
        var response = fx(() -> endpoint.request("definition", file.toUri().toString(), json.createObjectNode()))
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                6,
                response.path("items")
                        .get(0)
                        .path("range")
                        .path("start")
                        .path("character")
                        .asInt());
        fake.failEverything = true;
        assertThrows(
                Exception.class,
                () -> fx(() -> endpoint.request("definition", file.toUri().toString(), json.createObjectNode()))
                        .get(2, TimeUnit.SECONDS));
    }

    @Test
    void cancellationPropagatesToRealRequestFutureAndServerRestartInvalidatesResponse() throws Exception {
        fake.hoverFuture = new CompletableFuture<>();
        var endpoint = fx(() -> manager.agentEndpoint(file));
        var waiting = fx(() -> endpoint.request("hover", file.toUri().toString(), json.createObjectNode()));
        waiting.cancel(true);
        assertTrue(fake.hoverFuture.isCancelled());
        fake.hoverFuture = new CompletableFuture<>();
        var stale = fx(() -> endpoint.request("hover", file.toUri().toString(), json.createObjectNode()));
        manager.closeDocument(file);
        fake.hoverFuture.complete(null);
        assertThrows(Exception.class, () -> stale.get(2, TimeUnit.SECONDS));
    }

    @Test
    void diagnosticGenerationsDistinguishCurrentStaleUnknownAndUnavailable() throws Exception {
        assertEquals("PENDING", manager.agentDiagnostics(file).freshness());
        session.publishDiagnostics(new PublishDiagnosticsParams(file.toUri().toString(), List.of(), 1));
        fx(() -> null);
        var current = manager.agentDiagnostics(file);
        assertEquals("CURRENT", current.freshness());
        assertEquals("CURRENT", manager.agentDiagnostics(file, "class A {}").freshness());
        assertEquals(
                "STALE",
                manager.agentDiagnostics(file, "class A { int pending; }").freshness(),
                "an unsent editor change cannot reuse current protocol diagnostics");
        assertTrue(current.generation() > 0);
        fx(() -> {
            manager.changeDocument(file, "class A { int field; }");
            return null;
        });
        assertEquals("STALE", manager.agentDiagnostics(file).freshness());
        session.publishDiagnostics(new PublishDiagnosticsParams(file.toUri().toString(), List.of()));
        fx(() -> null);
        assertEquals("UNKNOWN", manager.agentDiagnostics(file).freshness());
        manager.closeDocument(file);
        assertEquals("UNAVAILABLE", manager.agentDiagnostics(file).freshness());
        fx(() -> {
            manager.openDocument(file, root, "java", "class A {}");
            return null;
        });
        assertEquals("PENDING", manager.agentDiagnostics(file).freshness());
    }

    @Test
    void renameAcceptsAnEmptyUnusedWorkspaceEditFormButRejectsTwoPopulatedForms() throws Exception {
        var text = new TextEdit(new Range(new Position(0, 6), new Position(0, 7)), "B");
        var document = new TextDocumentEdit(
                new VersionedTextDocumentIdentifier(file.toUri().toString(), 1),
                List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(text)));
        var edit = new WorkspaceEdit(List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(document)));
        edit.setChanges(Map.of());
        fake.renameResponse = edit;
        var endpoint = fx(() -> manager.agentEndpoint(file));
        var args = json.createObjectNode().put("new_name", "B");
        var result = fx(() -> endpoint.request("rename", file.toUri().toString(), args))
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                "B",
                result.path("edits").get(0).path("edits").get(0).path("newText").asText());
        edit.setChanges(Map.of(file.toUri().toString(), List.of(text)));
        assertThrows(
                Exception.class,
                () -> fx(() -> endpoint.request("rename", file.toUri().toString(), args))
                        .get(2, TimeUnit.SECONDS));
        edit.setDocumentChanges(List.of());
        assertEquals(
                1,
                fx(() -> endpoint.request("rename", file.toUri().toString(), args))
                        .get(2, TimeUnit.SECONDS)
                        .path("edits")
                        .size());
        assertEquals("class A {}", Files.readString(file), "normalizing a proposal never writes files");
    }

    @Test
    void renameNeverExecutesServerEditsAndRefusesUnseenTargets() throws Exception {
        fake.renameResponse = new WorkspaceEdit(Map.of(
                file.toUri().toString(),
                List.of(new TextEdit(new Range(new Position(0, 6), new Position(0, 7)), "B"))));
        var endpoint = fx(() -> manager.agentEndpoint(file));
        var args = json.createObjectNode().put("new_name", "B");
        var result = fx(() -> endpoint.request("rename", file.toUri().toString(), args))
                .get(2, TimeUnit.SECONDS);
        assertEquals(
                "B",
                result.path("edits").get(0).path("edits").get(0).path("newText").asText());
        assertEquals("class A {}", Files.readString(file));
        fake.renameResponse = new WorkspaceEdit(Map.of(
                root.resolve("Unknown.java").toUri().toString(),
                List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), "B"))));
        assertThrows(
                Exception.class,
                () -> fx(() -> endpoint.request("rename", file.toUri().toString(), args))
                        .get(2, TimeUnit.SECONDS));
        fake.renameResponse = new WorkspaceEdit(List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                new DeleteFile(file.toUri().toString()))));
        assertThrows(
                Exception.class,
                () -> fx(() -> endpoint.request("rename", file.toUri().toString(), args))
                        .get(2, TimeUnit.SECONDS));
        assertTrue(Files.exists(file));
    }
}

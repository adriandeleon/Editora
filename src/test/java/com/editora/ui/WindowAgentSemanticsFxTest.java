package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.agent.runtime.*;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class WindowAgentSemanticsFxTest {
    @TempDir
    Path root;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void semanticRequestStartsBackgroundLspAndRejectsLiveUserEdit() throws Exception {
        Path path = Files.writeString(root.resolve("A.java"), "class A {}");
        var manager = new LspManager((p, d) -> {}, (a, b) -> {});
        var caps = new ServerCapabilities();
        caps.setHoverProvider(true);
        var servers = LspTestHooks.useFakeSessions(manager, caps);
        manager.configure(true, Map.of("java", "jdtls"));
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            var b = new EditorBuffer();
            b.setPath(path);
            b.setContent("class A {}");
            b.setLspChangeListener(text -> manager.changeDocument(path, text));
            return b;
        });
        var started = new CountDownLatch(1);
        var response = new CompletableFuture<org.eclipse.lsp4j.Hover>();
        var host = new WindowAgentDocuments.Host() {
            public LspManager lsp() {
                return manager;
            }

            public void ensureLsp(EditorBuffer b) {
                assertTrue(javafx.application.Platform.isFxApplicationThread());
                if (!manager.isManaged(path)) {
                    manager.openDocument(path, root, "java", b.getContent());
                    servers.getFirst().hoverFuture = response;
                }
                started.countDown();
            }

            public EditorBuffer find(Path p) {
                return buffer;
            }

            public List<EditorBuffer> buffers() {
                return List.of(buffer);
            }

            public void open(Path p, AgentCancellation c, CompletableFuture<EditorBuffer> f) {
                f.complete(buffer);
            }

            public EditorBuffer create(Path p) {
                throw new UnsupportedOperationException();
            }

            public CompletableFuture<Boolean> save(EditorBuffer b, AgentCancellation c) {
                return CompletableFuture.completedFuture(false);
            }

            public AgentDocuments.Diagnostics diagnostics(Path p) {
                return new AgentDocuments.Diagnostics(false, 0, "");
            }

            public byte[] saveBytes(EditorBuffer b) {
                return b.getContent().getBytes();
            }

            public void showDiff(Path p, String a, String b) {}
        };
        try {
            var documents = new WindowAgentDocuments(host, new AgentWorkspace(root));
            var source = documents.read(path, new AgentCancellation());
            var semantic = documents.semantics();
            var result = CompletableFuture.supplyAsync(() -> {
                try {
                    return semantic.request(
                            "hover",
                            new ObjectMapper().createObjectNode(),
                            source,
                            List.of(source),
                            new AgentCancellation());
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("class A { int user; }"));
            response.complete(null);
            var failure =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("Stale"));
            assertTrue(FxTestSupport.callOnFx(buffer::getContent).contains("user"));
        } finally {
            manager.shutdownAll();
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }
}

package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class AiCoordinatorFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void manualCheckRestoresButtonsAfterFailedProbe() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            AtomicBoolean online = new AtomicBoolean();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] response = (online.get() ? "{}" : "{\"error\":{\"message\":\"not ready\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(online.get() ? 200 : 503, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            server.start();
            scope.onClose(() -> server.stop(0));
            Settings settings = enabled();
            settings.setAiProvider("openai");
            settings.setAiEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            Ops ops = new Ops();
            AiCoordinator coordinator = coordinator(settings, ops);
            scope.onClose(() -> FxTestSupport.runOnFx(coordinator::shutdown));
            CountDownLatch failed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> coordinator.checkConnection((ok, message) -> failed.countDown()));
            scope.await(failed, "failed check");
            assertFalse(FxTestSupport.callOnFx(coordinator::isActionsAvailable));
            assertFalse(ops.available);
            online.set(true);
            CountDownLatch recovered = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> coordinator.checkConnection((ok, message) -> recovered.countDown()));
            scope.await(recovered, "successful manual check");
            assertTrue(FxTestSupport.callOnFx(coordinator::isActionsAvailable));
            assertTrue(ops.available);
        }
    }

    @Test
    void codexKeepsMasterGatesAndDoesNotLaunchInlineCompletions() throws Exception {
        Settings settings = enabled();
        settings.setAiProvider("codex");
        settings.setAiInlineCompletion(true);
        AiCoordinator coordinator = coordinator(settings, new Ops());
        try {
            assertTrue(FxTestSupport.callOnFx(coordinator::isEnabled));
            assertFalse(FxTestSupport.callOnFx(coordinator::isInlineCompletionEnabled));
            FxTestSupport.runOnFx(() -> settings.setAiEnabled(false));
            assertFalse(FxTestSupport.callOnFx(coordinator::isEnabled));
            FxTestSupport.runOnFx(() -> {
                settings.setAiEnabled(true);
                settings.setAiSupport(false);
            });
            assertFalse(FxTestSupport.callOnFx(coordinator::isEnabled));
        } finally {
            FxTestSupport.runOnFx(coordinator::shutdown);
        }
    }

    @Test
    void codexRewriteUsesConfiguredAdapterAndRemainsUndoable() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Settings settings = enabled();
            settings.setAiProvider("codex");
            settings.setAiModel("test-model");
            // This deliberately invalid HTTP endpoint must never be used by the Codex provider.
            settings.setAiEndpoint("not-an-http-endpoint");
            settings.setCodexAgentCommand("\"" + Path.of(System.getProperty("java.home"), "bin", "java")
                    + "\" -cp \"" + System.getProperty("java.class.path")
                    + "\" com.editora.ai.CodexAiClientTest$Peer ui");
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setContent("original");
                b.getArea().getUndoManager().forgetHistory();
                b.getArea().selectAll();
                return b;
            });
            scope.onClose(() -> FxTestSupport.runOnFx(buffer::dispose));
            CountDownLatch done = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<String> status =
                    new java.util.concurrent.atomic.AtomicReference<>();
            AiCoordinator coordinator = FxTestSupport.callOnFx(() -> new AiCoordinator(
                    new CoordinatorHostStub() {
                        @Override
                        public Settings settings() {
                            return settings;
                        }

                        @Override
                        public EditorBuffer activeBuffer() {
                            return buffer;
                        }

                        @Override
                        public void promptText(String title, String label, String initial, Consumer<String> action) {
                            action.accept("rewrite");
                        }

                        @Override
                        public void setStatus(String message) {
                            status.set(message);
                            if (!message.equals(com.editora.i18n.Messages.tr("status.ai.rewriting"))) {
                                done.countDown();
                            }
                        }
                    },
                    new Ops()));
            scope.onClose(() -> FxTestSupport.runOnFx(coordinator::shutdown));
            FxTestSupport.runOnFx(coordinator::rewriteSelection);
            scope.await(done, "Codex rewrite");
            assertEquals(com.editora.i18n.Messages.tr("status.ai.rewritten"), status.get());
            assertEquals("answer", FxTestSupport.callOnFx(buffer::getContent));
            FxTestSupport.runOnFx(buffer.getArea()::undo);
            assertEquals("original", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    @Test
    void explanationEndsWithAgentAndReportedModel() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Settings settings = enabled();
            settings.setAiProvider("codex");
            settings.setAiModel("test-model");
            settings.setCodexAgentCommand("\"" + Path.of(System.getProperty("java.home"), "bin", "java")
                    + "\" -cp \"" + System.getProperty("java.class.path")
                    + "\" com.editora.ai.CodexAiClientTest$Peer ui");
            EditorBuffer source = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setContent("selected text");
                b.getArea().selectAll();
                return b;
            });
            scope.onClose(() -> FxTestSupport.runOnFx(source::dispose));
            Ops ops = new Ops();
            CountDownLatch done = new CountDownLatch(1);
            AiCoordinator coordinator = FxTestSupport.callOnFx(() -> new AiCoordinator(
                    new CoordinatorHostStub() {
                        @Override
                        public Settings settings() {
                            return settings;
                        }

                        @Override
                        public EditorBuffer activeBuffer() {
                            return source;
                        }

                        @Override
                        public void setStatus(String message) {
                            if (message.equals(com.editora.i18n.Messages.tr("status.ai.done"))) {
                                done.countDown();
                            }
                        }
                    },
                    ops));
            scope.onClose(() -> FxTestSupport.runOnFx(coordinator::shutdown));
            FxTestSupport.runOnFx(coordinator::explainSelection);
            scope.await(done, "Codex explanation");
            EditorBuffer explanation = ops.openedBuffer;
            assertNotNull(explanation);
            scope.onClose(() -> FxTestSupport.runOnFx(explanation::dispose));
            assertEquals(
                    "answer\n\n---\n\n**Agent:** Codex  \n**Model:** test-model\n",
                    FxTestSupport.callOnFx(explanation::getContent));
        }
    }

    private static Settings enabled() {
        Settings settings = new Settings();
        settings.setAiEnabled(true);
        settings.setAiSupport(true);
        return settings;
    }

    private static AiCoordinator coordinator(Settings settings, Ops ops) throws Exception {
        return FxTestSupport.callOnFx(() -> new AiCoordinator(
                new CoordinatorHostStub() {
                    @Override
                    public Settings settings() {
                        return settings;
                    }
                },
                ops));
    }

    private static class Ops implements AiCoordinator.Ops {
        volatile boolean available;
        volatile EditorBuffer openedBuffer;

        @Override
        public Path repoRoot() {
            return null;
        }

        @Override
        public void stagedDiff(Path root, Consumer<String> result) {}

        @Override
        public void setCommitMessage(String message) {}

        @Override
        public void openCommitWindow() {}

        @Override
        public void openTab(EditorBuffer buffer) {
            openedBuffer = buffer;
        }

        @Override
        public void setCommitAiAvailable(boolean available) {
            this.available = available;
        }
    }
}

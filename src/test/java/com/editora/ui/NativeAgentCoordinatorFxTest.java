package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.layout.VBox;

import com.editora.agent.runtime.AgentCancellation;
import com.editora.agent.runtime.AgentDocuments;
import com.editora.config.AgentSessionHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class NativeAgentCoordinatorFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void repeatedTurnsKeepStreamingIntoTheSamePanel(@TempDir Path root) throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String content = "turn" + bodies.size();
                byte[] data = ("data: {\"choices\":[{\"delta\":{\"content\":\"" + content
                                + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, data.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(data);
                }
            });
            server.start();
            scope.onClose(() -> server.stop(0));
            Settings settings = new Settings();
            settings.setAiProvider("openai");
            settings.setAiEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            AtomicReference<CountDownLatch> done = new AtomicReference<>(new CountDownLatch(1));
            var host = new CoordinatorHostStub() {
                public Settings settings() {
                    return settings;
                }

                public AutoCloseable startBackgroundTask(String label) {
                    CountDownLatch turn = done.get();
                    return turn::countDown;
                }
            };
            var panel = FxTestSupport.callOnFx(
                    () -> new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, p -> {}));
            var coordinator = new NativeAgentCoordinator(host, new Ops(), () -> panel);
            scope.onClose(() -> FxTestSupport.runOnFx(coordinator::shutdown));
            FxTestSupport.runOnFx(() -> coordinator.send("first", root));
            scope.await(done.get(), "first turn");
            done.set(new CountDownLatch(1));
            FxTestSupport.runOnFx(() -> coordinator.send("second", root));
            scope.await(done.get(), "second turn");
            scope.awaitFx();
            assertEquals(2, bodies.size());
            assertTrue(bodies.get(1).contains("first"));
            assertTrue(bodies.get(1).contains("turn1"));
            VBox transcript = FxTestSupport.field(panel, "transcriptBox");
            long markdownEntries = FxTestSupport.callOnFx(() -> transcript.getChildren().stream()
                    .filter(VBox.class::isInstance)
                    .count());
            assertEquals(
                    2, markdownEntries, "second-turn events must not be discarded by the first-turn generation guard");
        }
    }

    @Test
    void resetCancelsActiveTransportAndRejectsItsLateCallbacks(@TempDir Path root) throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            CountDownLatch firstRequest = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                int number = requests.incrementAndGet();
                if (number == 1) {
                    firstRequest.countDown();
                    try {
                        releaseFirst.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                String content = number == 1 ? "stale" : "fresh";
                byte[] data = ("data: {\"choices\":[{\"delta\":{\"content\":\"" + content
                                + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(200, data.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(data);
                    }
                } catch (java.io.IOException cancelledClient) {
                    exchange.close();
                }
            });
            server.start();
            scope.onClose(() -> {
                releaseFirst.countDown();
                server.stop(0);
            });
            Settings settings = new Settings();
            settings.setAiProvider("openai");
            settings.setAiEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            AtomicReference<CountDownLatch> done = new AtomicReference<>(new CountDownLatch(1));
            var host = new CoordinatorHostStub() {
                public Settings settings() {
                    return settings;
                }

                public AutoCloseable startBackgroundTask(String label) {
                    CountDownLatch turn = done.get();
                    return turn::countDown;
                }
            };
            var panel = FxTestSupport.callOnFx(
                    () -> new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, p -> {}));
            var coordinator = new NativeAgentCoordinator(host, new Ops(), () -> panel);
            scope.onClose(() -> FxTestSupport.runOnFx(coordinator::shutdown));
            FxTestSupport.runOnFx(() -> coordinator.send("cancel me", root));
            scope.await(firstRequest, "first model request");
            FxTestSupport.runOnFx(coordinator::reset);
            scope.await(done.get(), "cancelled turn cleanup");
            releaseFirst.countDown();
            done.set(new CountDownLatch(1));
            FxTestSupport.runOnFx(() -> coordinator.send("new turn", root));
            scope.await(done.get(), "replacement turn");
            scope.awaitFx();
            assertEquals(2, requests.get());
            VBox transcript = FxTestSupport.field(panel, "transcriptBox");
            long markdownEntries = FxTestSupport.callOnFx(() -> transcript.getChildren().stream()
                    .filter(VBox.class::isInstance)
                    .count());
            assertEquals(1, markdownEntries, "the reset generation must reject stale streamed text");
        }
    }

    private static final class Ops implements AgentCoordinator.Ops {
        public Path projectRoot() {
            return null;
        }

        public EditorBuffer bufferForPath(String path) {
            return null;
        }

        public void toggleToolWindow() {}

        public void openToolWindow(boolean focus) {}

        public void closeToolWindow() {}

        public void setToolWindowAvailable(boolean available) {}

        public void refreshProjectTree() {}

        public EditorBuffer openBackgroundBuffer(Path path) {
            return null;
        }

        public void openPath(Path path) {}

        public void rememberSession(String id, String cwd, String label, long time, String agent) {}

        public ObservableList<AgentSessionHistory.Entry> sessionHistory() {
            return FXCollections.observableArrayList();
        }

        public WindowAgentDocuments.Host nativeDocuments() {
            return new WindowAgentDocuments.Host() {
                public EditorBuffer find(Path path) {
                    return null;
                }

                public List<EditorBuffer> buffers() {
                    return List.of();
                }

                public void open(Path p, AgentCancellation c, CompletableFuture<EditorBuffer> f) {
                    f.complete(null);
                }

                public EditorBuffer create(Path path) {
                    throw new UnsupportedOperationException();
                }

                public CompletableFuture<Boolean> save(EditorBuffer b, AgentCancellation c) {
                    return CompletableFuture.completedFuture(false);
                }

                public byte[] saveBytes(EditorBuffer b) {
                    return new byte[0];
                }

                public AgentDocuments.Diagnostics diagnostics(Path p) {
                    return new AgentDocuments.Diagnostics(false, 0, "unavailable");
                }

                public void showDiff(Path p, String before, String after) {}
            };
        }
    }
}

package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.editora.ai.AiProvider;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class AiExplanationFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    static Stream<Arguments> models() {
        return Stream.of(
                Arguments.of("configured-alias", "server/model", "server/model"),
                Arguments.of("", "server/model", "server/model"),
                Arguments.of("configured-model", "", "configured-model"),
                Arguments.of("", "", "Unknown"));
    }

    @ParameterizedTest
    @MethodSource("models")
    void explanationRecordsOriginalProviderAndResponseModel(
            String configuredModel, String responseModel, String expectedModel) throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            CountDownLatch requested = new CountDownLatch(1);
            CountDownLatch respond = new CountDownLatch(1);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                requested.countDown();
                try {
                    if (!respond.await(10, TimeUnit.SECONDS)) {
                        throw new java.io.IOException("Timed out waiting for settings change");
                    }
                    // Metadata may arrive after the first text chunk and with no choices at all.
                    byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"Explanation\"}}]}\n\n"
                                    + "data: {\"model\":\"" + responseModel + "\",\"choices\":[]}\n\n"
                                    + "data: [DONE]\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(e);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            scope.onClose(() -> server.stop(0));
            scope.onClose(respond::countDown);
            Settings settings = new Settings();
            settings.setAiEnabled(true);
            settings.setAiSupport(true);
            settings.setAiProvider("lmstudio");
            settings.setAiEndpointFor(
                    AiProvider.LMSTUDIO,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            settings.setAiModelFor(AiProvider.LMSTUDIO, configuredModel);
            EditorBuffer source = FxTestSupport.callOnFx(() -> {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setContent("selected text");
                buffer.getArea().selectAll();
                return buffer;
            });
            scope.onClose(() -> FxTestSupport.runOnFx(source::dispose));
            Ops ops = new Ops();
            scope.onClose(() -> FxTestSupport.runOnFx(() -> {
                if (ops.openedBuffer != null) ops.openedBuffer.dispose();
            }));
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> status = new AtomicReference<>();
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
                            status.set(message);
                            if (!message.equals(tr("status.ai.explaining"))) done.countDown();
                        }
                    },
                    ops));
            scope.onClose(() -> FxTestSupport.runOnFx(coordinator::shutdown));
            FxTestSupport.runOnFx(coordinator::explainSelection);
            scope.await(requested, "explanation request");
            // Attribution belongs to this request even if the user changes settings mid-stream.
            FxTestSupport.runOnFx(() -> {
                settings.setAiProvider("anthropic");
                settings.setAiModelFor(AiProvider.LMSTUDIO, "next-model");
            });
            respond.countDown();
            scope.await(done, "explanation response");
            assertEquals(tr("status.ai.done"), status.get());
            assertEquals(
                    "Explanation\n\n---\n\n**Agent:** LM Studio / Bionic  \n**Model:** " + expectedModel + "\n",
                    FxTestSupport.callOnFx(ops.openedBuffer::getContent));
        }
    }

    private static class Ops implements AiCoordinator.Ops {
        volatile EditorBuffer openedBuffer;

        @Override
        public Path repoRoot() {
            return null;
        }

        @Override
        public void stagedDiff(Path root, Consumer<String> onResult) {}

        @Override
        public void setCommitMessage(String message) {}

        @Override
        public void openCommitWindow() {}

        @Override
        public void openTab(EditorBuffer buffer) {
            openedBuffer = buffer;
        }

        @Override
        public void setCommitAiAvailable(boolean available) {}
    }
}

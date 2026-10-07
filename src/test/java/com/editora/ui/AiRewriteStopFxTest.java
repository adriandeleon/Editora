package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.ai.AiRequests;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * "Rewrite Selection" replaces the user's text with the model's answer, so it may only do that with a
 * whole answer: a reply cut off at the output limit, a stream that closed early, an empty reply, or a
 * selection too large to be sent whole must leave the document exactly as it was and say why. The same
 * rule guards the commit-message box, which may hold the user's own draft.
 */
@Tag("fx")
class AiRewriteStopFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String chunk(String text, String finish) {
        String delta = text == null ? "{}" : "{\"content\":" + quote(text) + "}";
        return "data: {\"choices\":[{\"delta\":" + delta + ",\"finish_reason\":"
                + (finish == null ? "null" : quote(finish)) + "}]}\n\n";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static final String DONE = "data: [DONE]\n\n";

    private record Run(String status, String content, int requests, boolean undoAvailable) {}

    /** Selects all of {@code doc}, runs the rewrite against a server answering {@code sse}, returns the result. */
    private static Run rewrite(String doc, String sse) throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            AtomicInteger requests = new AtomicInteger();
            Settings settings = settings(scope, sse, requests);
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setContent(doc);
                b.getArea().selectAll();
                return b;
            });
            scope.onClose(() -> FxTestSupport.runOnFx(buffer::dispose));
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
                            return buffer;
                        }

                        @Override
                        public void promptText(String title, String label, String initial, Consumer<String> action) {
                            action.accept("rewrite it");
                        }

                        @Override
                        public void setStatus(String message) {
                            status.set(message);
                            if (!message.equals(tr("status.ai.rewriting"))) {
                                done.countDown();
                            }
                        }
                    },
                    new Ops()));
            scope.onClose(() -> FxTestSupport.runOnFx(coordinator::shutdown));
            FxTestSupport.runOnFx(coordinator::rewriteSelection);
            scope.await(done, "the rewrite to finish");
            return new Run(
                    status.get(),
                    FxTestSupport.callOnFx(buffer::getContent),
                    requests.get(),
                    FxTestSupport.callOnFx(() -> buffer.getArea().isUndoAvailable()));
        }
    }

    private static Settings settings(AsyncTestScope scope, String sse, AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(sse.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        scope.onClose(() -> server.stop(0));
        Settings settings = new Settings();
        settings.setAiEnabled(true);
        settings.setAiSupport(true);
        settings.setAiProvider("openai");
        settings.setAiEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        return settings;
    }

    private static final String DOC = "line 1\nline 2\nline 3\nline 4\n";

    @Test
    void aCompleteAnswerReplacesTheSelectionAsOneUndoStep() throws Exception {
        Run run = rewrite(DOC, chunk("LINE 1\nLINE 2\n", null) + chunk(null, "stop") + DONE);
        assertEquals("LINE 1\nLINE 2", run.content());
        assertEquals(ChordHint.tr("status.ai.rewritten", "edit.undo"), run.status());
    }

    @Test
    void anAnswerCutOffAtTheOutputLimitIsNotApplied() throws Exception {
        Run run = rewrite(DOC, chunk("LINE 1\nLINE 2\nLI", null) + chunk(null, "length") + DONE);
        assertEquals(DOC, run.content());
        assertEquals(tr("status.ai.cannotApplyTruncated"), run.status());
    }

    @Test
    void aStreamThatEndsWithoutATerminatorIsNotApplied() throws Exception {
        Run run = rewrite(DOC, chunk("LINE 1\nLI", null));
        assertEquals(DOC, run.content());
        assertEquals(tr("status.ai.cannotApplyIncomplete"), run.status());
    }

    @Test
    void anEmptyAnswerDoesNotDeleteTheSelection() throws Exception {
        Run run = rewrite(DOC, chunk(null, "stop") + DONE);
        assertEquals(DOC, run.content());
        assertEquals(tr("status.ai.cannotApplyEmpty"), run.status());
        Run fenceOnly = rewrite(DOC, chunk("```\n```", "stop") + DONE);
        assertEquals(DOC, fenceOnly.content(), "an empty code fence is an empty answer too");
    }

    @Test
    void aSelectionTooLargeToSendWholeIsRefusedBeforeAnythingIsSent() throws Exception {
        String big = "row of payload text\n".repeat(AiRequests.MAX_INPUT_CHARS / 20 + 10);
        Run run = rewrite(big, chunk("rewritten-head\n", "stop") + DONE);
        assertEquals(big, run.content());
        assertEquals(0, run.requests(), "the truncated head is never sent");
        assertEquals(tr("status.ai.cannotRewriteLarge", big.length(), AiRequests.MAX_INPUT_CHARS), run.status());
    }

    @Test
    void refusalKeysNameWhyNothingWasApplied() {
        assertNull(AiCoordinator.refusalKey("end_turn", "text"));
        assertEquals("status.ai.cannotApplyEmpty", AiCoordinator.refusalKey("end_turn", " \n"));
        assertEquals("status.ai.cannotApplyTruncated", AiCoordinator.refusalKey("max_tokens", "text"));
        assertEquals("status.ai.cannotApplyIncomplete", AiCoordinator.refusalKey("incomplete", "text"));
        assertEquals("status.ai.cannotApplyIncomplete", AiCoordinator.refusalKey(null, "text"));
        assertEquals("status.ai.refused", AiCoordinator.refusalKey("refusal", "text"));
        assertEquals("status.ai.cancelled", AiCoordinator.refusalKey("cancelled", "text"));
        assertEquals("status.ai.cannotApplyStopped", AiCoordinator.refusalKey("tool_use", "text"));
    }

    @Test
    void aTruncatedCommitMessageDoesNotReplaceTheDraft() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Settings settings =
                    settings(scope, chunk("Fix the th", null) + chunk(null, "length") + DONE, new AtomicInteger());
            Ops ops = new Ops();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> status = new AtomicReference<>();
            AiCoordinator coordinator = FxTestSupport.callOnFx(() -> new AiCoordinator(
                    new CoordinatorHostStub() {
                        @Override
                        public Settings settings() {
                            return settings;
                        }

                        @Override
                        public void setStatus(String message) {
                            status.set(message);
                            if (!message.equals(tr("status.ai.generatingCommit"))) {
                                done.countDown();
                            }
                        }
                    },
                    ops));
            scope.onClose(() -> FxTestSupport.runOnFx(coordinator::shutdown));
            FxTestSupport.runOnFx(coordinator::generateCommitMessage);
            scope.await(done, "the commit message generation to finish");
            assertNull(ops.commitMessage, "the message box keeps what the user had");
            assertEquals(tr("status.ai.cannotApplyTruncated"), status.get());
        }
    }

    private static final class Ops implements AiCoordinator.Ops {
        volatile String commitMessage;

        @Override
        public Path repoRoot() {
            return Path.of(".");
        }

        @Override
        public void stagedDiff(Path root, Consumer<String> result) {
            result.accept("diff --git a/x b/x\n+changed\n");
        }

        @Override
        public void setCommitMessage(String message) {
            commitMessage = message;
        }

        @Override
        public void openCommitWindow() {}

        @Override
        public void openTab(EditorBuffer buffer) {}

        @Override
        public void setCommitAiAvailable(boolean available) {}
    }
}

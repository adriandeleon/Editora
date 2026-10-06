package com.editora.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LanguageServerSession.dispose()} against a real child process speaking LSP over its pipes — the one
 * thing an in-process fake cannot show, because the defect was in the order of {@code shutdown}, {@code exit}
 * and the process kill.
 */
@DisabledOnOs(OS.WINDOWS)
class LspGracefulExitProcessTest {

    /** A language server reduced to its lifecycle: it logs what it is told and leaves when told to. */
    public static final class RecordingServer implements LanguageServer {

        private final Path log;
        private final boolean answerShutdown;
        private final FakeLanguageServer services = new FakeLanguageServer();

        RecordingServer(Path log, boolean answerShutdown) {
            this.log = log;
            this.answerShutdown = answerShutdown;
        }

        public static void main(String[] args) throws Exception {
            var server = new RecordingServer(Path.of(args[0]), !"mute".equals(args[1]));
            LSPLauncher.createServerLauncher(server, System.in, System.out)
                    .startListening()
                    .get();
        }

        private void record(String event) {
            try {
                Files.writeString(log, event + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()));
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            record("shutdown");
            return answerShutdown ? CompletableFuture.completedFuture(null) : new CompletableFuture<>();
        }

        @Override
        public void exit() {
            record("exit");
            Runtime.getRuntime().halt(0);
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return services;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return services;
        }
    }

    private static LspServerRegistry.ServerSpec spec(Path log, String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new LspServerRegistry.ServerSpec(
                "test",
                List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        RecordingServer.class.getName(),
                        log.toString(),
                        mode),
                List.of());
    }

    private record Started(LanguageServerSession session, CountDownLatch dead) {}

    private static Started start(Path dir, Path log, String mode) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch dead = new CountDownLatch(1);
        var session = new LanguageServerSession(spec(log, mode), dir, d -> {}, (type, message) -> {
            if ("ServiceReady".equals(type)) {
                ready.countDown();
            }
        });
        session.setOnDead(dead::countDown);
        assertTrue(session.start(), "the test server did not launch");
        assertTrue(ready.await(60, TimeUnit.SECONDS), "the test server never finished initialize");
        return new Started(session, dead);
    }

    /**
     * {@code dispose()} used to send {@code shutdown} and kill the process tree in the same call, so the
     * server was terminated before it could answer and {@code exit} was never delivered.
     */
    @Test
    void disposeLetsTheServerAnswerShutdownAndReceiveExit(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("lifecycle.log");
        Started started = start(dir, log, "answer");

        // The caller is the FX thread during a window close: it must not wait for the server.
        assertTimeoutPreemptively(Duration.ofSeconds(2), started.session()::dispose);

        assertTrue(started.dead().await(30, TimeUnit.SECONDS), "the server process never ended");
        assertEquals(List.of("shutdown", "exit"), Files.readAllLines(log));
    }

    /** The wait is bounded: a server that never answers {@code shutdown} is killed all the same. */
    @Test
    void aServerThatIgnoresShutdownIsStillEnded(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("lifecycle.log");
        Started started = start(dir, log, "mute");

        assertTimeoutPreemptively(Duration.ofSeconds(2), started.session()::dispose);

        assertTrue(started.dead().await(30, TimeUnit.SECONDS), "an unresponsive server must be killed");
        assertEquals(List.of("shutdown"), Files.readAllLines(log), "exit is only sent once shutdown is answered");
    }
}

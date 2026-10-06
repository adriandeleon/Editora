package com.editora.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A superseded request has to reach the server as {@code $/cancelRequest}, and that only shows on a real
 * pipe: against an in-process fake, cancelling a future is all there is to see.
 *
 * <p>The futures a session hands out are stages derived from the JSON-RPC future (a fallback value on
 * failure), and cancelling a derived stage does not cancel what it was derived from — so before this was
 * wired through, abandoning a request told the server nothing and it went on computing.
 */
@DisabledOnOs(OS.WINDOWS)
class LspCancelRequestProcessTest {

    /** A server that never answers {@code documentSymbol} and logs when the client cancels the request. */
    public static final class SlowServer implements LanguageServer, TextDocumentService, WorkspaceService {

        private final Path log;

        SlowServer(Path log) {
            this.log = log;
        }

        public static void main(String[] args) throws Exception {
            LSPLauncher.createServerLauncher(new SlowServer(Path.of(args[0])), System.in, System.out)
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
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
            Runtime.getRuntime().halt(0);
        }

        @Override
        public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {}

        @Override
        public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {}

        @Override
        public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {}

        @Override
        public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {}

        @Override
        public void didChangeConfiguration(org.eclipse.lsp4j.DidChangeConfigurationParams params) {}

        @Override
        public void didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}

        @Override
        public TextDocumentService getTextDocumentService() {
            return this;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return this;
        }

        @Override
        public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
                DocumentSymbolParams params) {
            record("requested");
            var never = new CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>();
            never.whenComplete((result, error) -> {
                if (never.isCancelled()) {
                    record("cancelled"); // LSP4J cancels the method's future when $/cancelRequest arrives
                }
            });
            return never;
        }
    }

    private static boolean logged(Path log, String event) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readAllLines(log).contains(event)) {
                return true;
            }
            // Another process writes the file: there is nothing in this JVM to wait on but the clock.
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return false;
    }

    @Test
    void cancellingASessionRequestSendsCancelRequestToTheServer(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("requests.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var spec = new LspServerRegistry.ServerSpec(
                "test",
                List.of(java, "-cp", System.getProperty("java.class.path"), SlowServer.class.getName(), log.toString()),
                List.of());
        CountDownLatch ready = new CountDownLatch(1);
        var session = new LanguageServerSession(spec, dir, d -> {}, (type, message) -> {
            if ("ServiceReady".equals(type)) {
                ready.countDown();
            }
        });
        try {
            assertTrue(session.start(), "the test server did not launch");
            assertTrue(ready.await(60, TimeUnit.SECONDS), "the test server never finished initialize");
            String uri = dir.resolve("A.java").toUri().toString();
            session.didOpen(uri, "java", "class A {}");

            var request = session.documentSymbol(uri);
            assertTrue(logged(log, "requested"), "the request never reached the server");
            request.cancel(true);

            assertTrue(logged(log, "cancelled"), "the server was never told the request was abandoned");
        } finally {
            session.dispose();
        }
    }
}

package com.editora.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecTargets;
import com.editora.process.MappedTestTarget;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A language server for a folder of this machine whose tools run elsewhere (a container, WSL): it is started
 * on that target, is not given this machine's process id, and knows the folder and its documents only by the
 * target's names for them.
 */
@DisabledOnOs(OS.WINDOWS) // the stand-in target is a symlink
class LspMappedTargetProcessTest {

    /** Writes what it is told: the root, the client's process id, each opened document. */
    public static final class RecordingServer implements LanguageServer, TextDocumentService, WorkspaceService {

        private final Path log;

        RecordingServer(Path log) {
            this.log = log;
        }

        public static void main(String[] args) throws Exception {
            LSPLauncher.createServerLauncher(new RecordingServer(Path.of(args[0])), System.in, System.out)
                    .startListening()
                    .get();
        }

        private void record(String line) {
            try {
                Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            record("root " + params.getRootUri());
            record("pid " + params.getProcessId());
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
        public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
            record("open " + params.getTextDocument().getUri());
        }

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
    }

    private static List<String> lines(Path log, String prefix) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (Files.exists(log)) {
                List<String> found = Files.readAllLines(log).stream()
                        .filter(l -> l.startsWith(prefix))
                        .toList();
                if (!found.isEmpty()) {
                    return found;
                }
            }
            Thread.sleep(50);
        }
        return List.of();
    }

    @Test
    void aServerForAMappedFolderRunsThereAndSeesItsNamesThere(@TempDir Path dir) throws Exception {
        Path base = dir.toRealPath();
        Path folder = Files.createDirectories(base.resolve("app"));
        Path log = base.resolve("server.log");
        Path alias = base.resolve("mounted");
        ExecTargets.setRemoteEnabled(() -> true);
        MappedTestTarget target = MappedTestTarget.register(folder, alias);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var spec = new LspServerRegistry.ServerSpec(
                "test",
                List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        RecordingServer.class.getName(),
                        log.toString()),
                List.of());
        CountDownLatch ready = new CountDownLatch(1);
        var session = new LanguageServerSession(spec, folder, d -> {}, (type, message) -> {
            if ("ServiceReady".equals(type)) {
                ready.countDown();
            }
        });
        try {
            assertTrue(session.start(), "the test server did not launch");
            assertTrue(ready.await(60, TimeUnit.SECONDS), "the test server never finished initialize");
            session.didOpen(DocumentUris.of(folder.resolve("A.java")), "java", "class A {}");

            assertEquals(List.of("root " + DocumentUris.ofDirectory(alias)), lines(log, "root "));
            assertEquals(List.of("pid null"), lines(log, "pid "), "this machine's pid means nothing there");
            assertEquals(List.of("open " + DocumentUris.of(alias.resolve("A.java"))), lines(log, "open "));
            assertFalse(target.started().isEmpty(), "started on the mapped target");
            assertEquals(List.of(), target.leaks());
        } finally {
            session.dispose();
            target.unregister();
            ExecTargets.setRemoteEnabled(null);
        }
    }
}

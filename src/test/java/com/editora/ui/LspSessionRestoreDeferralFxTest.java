package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.ConfigManager;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session restore honours the "start a server only when its tab is shown" deferral.
 *
 * <p>{@code wireBuffer} deferred every restored background tab, but once each tab's text arrived
 * {@code finishSessionBuffer} called {@code syncBuffer} directly, so every restored tab was opened on — and
 * started — its language server at launch after all: one server per distinct (server, root) among files the
 * user had not looked at.
 */
@Tag("fx")
class LspSessionRestoreDeferralFxTest {

    @TempDir
    Path dir;

    @Test
    void aRestoredBackgroundTabDoesNotStartItsServer() throws Exception {
        FxTestSupport.bootToolkit();
        Path cfg = Files.createDirectories(dir.resolve("config"));
        Path work = Files.createDirectories(dir.resolve("work"));
        String[] names = {"a/a.py", "b/b.py", "c/c.py"};
        Path[] files = new Path[names.length];
        StringBuilder open = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            files[i] = work.resolve(names[i]);
            Files.createDirectories(files[i].getParent());
            Files.writeString(files[i], "x = " + i + "\n");
            open.append(i > 0 ? "," : "")
                    .append("{\"path\":\"")
                    .append(files[i].toAbsolutePath().toString().replace("\\", "\\\\"))
                    .append("\"}");
        }
        Files.writeString(
                cfg.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + open + "],\"activeFile\":\""
                        + files[0].toAbsolutePath().toString().replace("\\", "\\\\") + "\"}");
        // Any executable that certainly exists: detection must succeed, and fake sessions mean nothing runs it.
        String anExecutable = ProcessHandle.current().info().command().orElseThrow();

        LspManager[] manager = new LspManager[1];
        FxWindowFixture fx = FxWindowFixture.create(cfg, false, false, false, List.of(), controller -> {
            manager[0] = FxTestSupport.field(controller, "lspManager");
            LspTestHooks.useFakeSessions(manager[0]);
            ConfigManager config = FxTestSupport.field(controller, "config");
            config.getSettings().setLspSupport(true);
            config.getSettings().setPythonLspCommand(anExecutable);
            LspCoordinator coordinator = FxTestSupport.field(controller, "lspCoordinator");
            coordinator.applySupport();
        });
        try {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!FxTestSupport.callOnFx(() -> manager[0].isManaged(files[0]))) {
                assertTrue(System.nanoTime() < deadline, "the visible restored tab never reached its server");
                Thread.sleep(50);
                FxTestSupport.drainFx();
            }
            Thread.sleep(700); // let every background tab's content land and its restore finish
            FxTestSupport.drainFx();

            for (int i = 1; i < files.length; i++) {
                Path background = files[i];
                assertFalse(
                        FxTestSupport.callOnFx(() -> manager[0].isManaged(background)),
                        "a restored tab that was never shown must not start a server: " + names[i]);
            }
        } finally {
            fx.dispose();
        }
    }
}

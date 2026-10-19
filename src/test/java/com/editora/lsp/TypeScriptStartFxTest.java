package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A server that refuses {@code initialize} is reported as refused, with its own words, and is not a crash that
 * would be restarted into the crash-loop cap (Node review B3); and the TypeScript server is not started at all
 * when there is no TypeScript with a tsserver it may run (B1).
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the fake server is launched through a shell script
class TypeScriptStartFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static void drainFx() throws Exception {
        CompletableFuture<Void> drained = new CompletableFuture<>();
        Platform.runLater(() -> drained.complete(null));
        drained.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aRefusedInitializeIsARefusalNotACrash(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("app"));
        Path file = root.resolve("main.py");
        Files.writeString(file, "print('hi')\n");
        Path script = dir.resolve("refusing-server");
        String java = ProcessHandle.current().info().command().orElse("java");
        Files.writeString(
                script,
                "#!/bin/sh\nexec '" + java + "' -cp '" + System.getProperty("java.class.path") + "' "
                        + RefusingLanguageServerMain.class.getName() + "\n");
        script.toFile().setExecutable(true);
        LspManager manager = new LspManager((f, d) -> {}, (type, message) -> {});
        try {
            manager.configure(true, Map.of("python", script.toString()));
            CompletableFuture<StartRefusal> refused = new CompletableFuture<>();
            AtomicInteger crashes = new AtomicInteger();
            manager.setOnStartRefused((serverId, sessionRoot, why) -> refused.complete(why));
            manager.setOnSessionCrashed((serverId, sessionRoot) -> crashes.incrementAndGet());

            Platform.runLater(() -> manager.openDocument(file, root, "python", "print('hi')\n"));

            StartRefusal why = refused.get(30, TimeUnit.SECONDS);
            assertEquals(StartRefusal.Kind.SERVER_REFUSED, why.kind());
            assertEquals(RefusingLanguageServerMain.REASON, why.detail());
            Thread.sleep(500);
            drainFx();
            assertEquals(0, crashes.get(), "a refusal is not a crash, so nothing restarts it");
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void withTypescriptSevenBesideTheServerItIsNotStarted(@TempDir Path dir) throws Exception {
        Path global = dir.resolve("lib/node_modules");
        Path cli = global.resolve("typescript-language-server/lib/cli.mjs");
        Files.createDirectories(cli.getParent());
        Files.writeString(cli, "#!/bin/sh\nexit 3\n");
        cli.toFile().setExecutable(true);
        Files.createDirectories(global.resolve("typescript/lib"));
        Files.writeString(global.resolve("typescript/package.json"), "{\"version\": \"7.0.2\"}");
        Path root = Files.createDirectories(dir.resolve("app"));
        Files.writeString(root.resolve("package.json"), "{}");
        Path file = root.resolve("app.js");
        Files.writeString(file, "console.log(1);\n");
        LspManager manager = new LspManager((f, d) -> {}, (type, message) -> {});
        try {
            manager.configure(true, Map.of("typescript", cli + " --stdio"));
            CompletableFuture<StartRefusal> refused = new CompletableFuture<>();
            manager.setOnStartRefused((serverId, sessionRoot, why) -> refused.complete(why));
            Platform.runLater(() -> manager.openDocument(file, root, "javascript", "console.log(1);\n"));
            StartRefusal why = refused.get(30, TimeUnit.SECONDS);
            assertEquals(StartRefusal.Kind.NO_TYPESCRIPT, why.kind());
            assertEquals("7.0.2", why.detail());
            assertEquals(
                    TypeScriptServer.Outcome.NO_SDK,
                    LspManager.typeScriptChoice(root, null, List.of(cli.toString()), false)
                            .outcome());
        } finally {
            manager.shutdownAll();
        }
    }
}

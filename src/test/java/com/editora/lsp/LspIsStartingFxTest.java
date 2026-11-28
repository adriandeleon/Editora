package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server is "starting" from the moment its session is made until it answers {@code initialize}: what Elixir's
 * debugger waits out, as ElixirLS's debug adapter dies when it boots beside its language server (CI, PR #1859).
 */
@Tag("fx")
class LspIsStartingFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void aServerIsStartingUntilItAnswersInitialize(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("calc"));
        Files.writeString(root.resolve("mix.exs"), "defmodule Calc.MixProject do\nend\n");
        Path file = Files.writeString(
                Files.createDirectories(root.resolve("lib")).resolve("calc.ex"), "defmodule Calc do\nend\n");
        LspManager manager = new LspManager((f, d) -> {}, (t, m) -> {});
        AtomicReference<LanguageServerSession> made = new AtomicReference<>();
        manager.setSessionStarterForTest(made::set); // made, not started: its initialize is not answered
        manager.configure(true, Map.of("elixir", "elixir-ls"));
        try {
            assertFalse(manager.isStarting("elixir"), "no session yet");

            manager.openDocument(file, root, "elixir", "defmodule Calc do\nend\n");
            Await.until(() -> made.get() != null, "the session made", Duration.ofSeconds(10));
            assertTrue(manager.isStarting("elixir"), "made, initialize not answered");
            assertFalse(manager.isStarting("java"), "only the server asked about");

            made.get().attachForTest(new FakeLanguageServer(), LspTestHooks.caps());
            assertFalse(manager.isStarting("elixir"), "initialize answered: started");
        } finally {
            manager.close();
        }
    }
}

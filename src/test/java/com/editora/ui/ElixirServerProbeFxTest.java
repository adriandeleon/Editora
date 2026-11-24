package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.config.SharedConfig;
import com.editora.process.ProcessRunner;
import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in probe of the real ElixirLS as Elixir's language server, in a window: a Mix project's module with a compile error
 * gets its diagnostic in the Problems list — ElixirLS answered Editora's requests (it refused LSP4J's string request ids
 * and crashed on the first, restarting again and again). Run with {@code ./mvnw test -Dtest=ElixirServerProbeFxTest
 * -Dgroups=probe -Dlsp.probe=true}; needs {@code elixir} and {@code mix} and ElixirLS (Editora's install); its first
 * start compiles it.
 */
@Tag("probe")
@Tag("fx")
class ElixirServerProbeFxTest {

    @TempDir
    Path dir;

    @Test
    void aCompileErrorReachesTheProblemsList() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        for (String tool : List.of("elixir", "mix")) {
            Assumptions.assumeTrue(
                    Path.of(ProcessRunner.resolveExecutable(List.of(tool)).getFirst())
                            .isAbsolute(),
                    "needs " + tool);
        }
        Path home = Path.of(System.getProperty("user.home"));
        Assumptions.assumeTrue(
                Files.isRegularFile(home.resolve(".editora/plugins/dap/elixir/language_server.sh")),
                "needs ElixirLS (Editora's install)");
        FxTestSupport.bootToolkit();
        Process mix = new ProcessBuilder(ProcessRunner.resolveExecutable(List.of("mix", "new", "calc")))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(mix.waitFor(120, TimeUnit.SECONDS) && mix.exitValue() == 0, "mix new made the project");
        Path root = dir.resolve("calc").toRealPath();
        Path module = Files.writeString(root.resolve("lib/calc.ex"), """
                defmodule Calc do
                  def add(a, b) do
                    a + undefined_thing
                  end
                end
                """);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setLspSupport(true);
        seed.getSettings().setElixirLspEnabled(true);
        seed.getTrustStore().trust(root); // ElixirLS compiles the project: held back until the folder is trusted
        assertTrue(seed.saveSettings());
        seed.saveTrust();
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(module, 0, 0)), true, c -> {});
        try {
            LspCoordinator lsp = FxTestSupport.field(fx.controller, "lspCoordinator");
            List<LspDiagnostic> found = FxTestSupport.awaitFxValue(
                    () -> {
                        Map<Path, List<LspDiagnostic>> problems = FxTestSupport.field(lsp, "problems");
                        List<LspDiagnostic> all =
                                problems.values().stream().flatMap(List::stream).toList();
                        return all.isEmpty() ? null : all;
                    },
                    "diagnostics from ElixirLS",
                    java.time.Duration.ofSeconds(240));
            assertTrue(
                    found.stream().anyMatch(d -> String.valueOf(d.message()).contains("undefined_thing")),
                    "the compile error: " + found);
        } finally {
            fx.dispose();
        }
    }
}

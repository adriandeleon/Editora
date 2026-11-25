package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.dap.ElixirLaunch;
import com.editora.dap.LaunchRoute;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ElixirRunTest {

    private static final Path ROOT = Path.of("/work/calc").toAbsolutePath();

    private static ElixirRun.Plan plan(Path file, int line, Set<Path> files, String mixExs) {
        return ElixirRun.plan(
                file, "", List.of(), line, ROOT, files::contains, p -> p.endsWith("mix.exs") ? mixExs : null);
    }

    private static Set<Path> project() {
        return Set.of(ROOT.resolve("mix.exs"), ROOT.resolve("test/test_helper.exs"));
    }

    @Test
    void aTestFileRunsUnderMixTestNarrowedToTheCaretsLine() {
        ElixirRun.Plan plan = plan(ROOT.resolve("test/calc_test.exs"), 7, project(), "");
        assertEquals(ElixirRun.Kind.TESTS, plan.kind());
        assertEquals(List.of("mix", "test", "test/calc_test.exs:8"), plan.command());
        assertEquals(ROOT, plan.cwd());
        assertEquals("test", plan.task());
        assertEquals(List.of("test/calc_test.exs:8"), plan.taskArgs());
        assertEquals(List.of("test/test_helper.exs", "test/calc_test.exs"), plan.requireFiles());
        assertEquals(
                List.of("mix", "test", "test/calc_test.exs"),
                plan(ROOT.resolve("test/calc_test.exs"), -1, project(), "").command());
    }

    @Test
    void aScriptOfTheProjectRunsUnderMixRunAndAModuleRunsTheProject() {
        ElixirRun.Plan script = plan(ROOT.resolve("scripts/seed.exs"), -1, project(), "");
        assertEquals(List.of("mix", "run", "scripts/seed.exs"), script.command());
        assertEquals("run", script.task());
        ElixirRun.Plan library = plan(ROOT.resolve("lib/calc.ex"), -1, project(), "def project, do: []");
        assertEquals(List.of("mix", "run"), library.command());
        ElixirRun.Plan app = plan(
                ROOT.resolve("lib/calc/application.ex"),
                -1,
                project(),
                "def application, do: [mod: {Calc.Application, []}]");
        assertEquals(List.of("mix", "run", "--no-halt"), app.command(), "its supervision tree runs until stopped");
        assertEquals(
                List.of("mix", "run"),
                plan(ROOT.resolve("config/config.exs"), -1, project(), "").command());
    }

    @Test
    void outsideAMixProjectAFileRunsUnderElixirAlone() {
        Path script = ROOT.resolve("hello.exs");
        ElixirRun.Plan plan = plan(script, -1, Set.of(), "");
        assertEquals(ElixirRun.Kind.SCRIPT, plan.kind());
        assertEquals(List.of("elixir", script.toString()), plan.command());
        assertNull(plan.project(), "not debugged: ElixirLS runs Mix tasks");
    }

    @Test
    void theRunLines() {
        assertEquals(2, ElixirRun.runLine("calc_test.exs", "# x\n\ndefmodule CalcTest do\nend\n"));
        assertEquals(0, ElixirRun.runLine("seed.exs", "IO.puts 1\n"));
        assertEquals(-1, ElixirRun.runLine("calc.ex", "defmodule Calc do\nend\n"), "a module");
        assertEquals(-1, ElixirRun.runLine("mix.exs", "defmodule Calc.MixProject do\nend\n"));
        assertEquals(-1, ElixirRun.runLine("config.exs", "import Config\n"));
    }

    @Test
    void elixirLsIsSentTheMixTask() {
        ElixirRun.Plan plan = plan(ROOT.resolve("test/calc_test.exs"), 3, project(), "");
        Map<String, Object> launch = ElixirLaunch.launch(plan);
        assertEquals("mix_task", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals("test", launch.get("task"));
        assertEquals(List.of("test/calc_test.exs:4"), launch.get("taskArgs"));
        assertEquals(ROOT.toString(), launch.get("projectDir"));
        assertEquals(true, launch.get("startApps"));
        assertEquals(List.of("test/test_helper.exs", "test/calc_test.exs"), launch.get("requireFiles"));
        Map<String, Object> run = ElixirLaunch.launch(plan(ROOT.resolve("scripts/seed.exs"), -1, project(), ""));
        assertEquals(false, run.get("startApps"));
        assertFalse(run.containsKey("requireFiles"));
        assertEquals(
                LaunchRoute.Kind.ELIXIR_LS, LaunchRoute.of(launch, Map.of()).kind(), "launch.json mix_task too");
    }

    /** A Phoenix 1.8 project's {@code mix.exs}, as {@code mix phx.new} writes it (cut down). */
    private static final String PHOENIX_MIX = """
            def application do
              [mod: {Hello.Application, []}, extra_applications: [:logger, :runtime_tools]]
            end
            defp deps do
              [
                {:phoenix, "~> 1.8.17"},
                {:phoenix_html, "~> 4.1"},
                {:bandit, "~> 1.5"}
              ]
            end
            """;

    @Test
    void aPhoenixProjectsModuleRunsItsWebServer() {
        ElixirRun.Plan server =
                plan(ROOT.resolve("lib/hello_web/controllers/page_controller.ex"), -1, project(), PHOENIX_MIX);
        assertEquals(ElixirRun.Kind.PHOENIX, server.kind());
        assertEquals(List.of("mix", "phx.server"), server.command());
        assertEquals("phx.server", server.task());
        assertEquals(ROOT, server.cwd());
        assertEquals(
                ElixirRun.Kind.TESTS,
                plan(ROOT.resolve("test/hello_web/page_test.exs"), 3, project(), PHOENIX_MIX)
                        .kind(),
                "its tests still run as tests");
        assertEquals(
                List.of("mix", "run", "priv/repo/seeds.exs"),
                plan(ROOT.resolve("priv/repo/seeds.exs"), -1, project(), PHOENIX_MIX)
                        .command(),
                "and its scripts as scripts");
        assertEquals(
                ElixirRun.Kind.MIX_RUN,
                plan(ROOT.resolve("lib/x.ex"), -1, project(), "[{:phoenix_html, \"~> 4.1\"}]")
                        .kind(),
                "phoenix_html alone is not Phoenix");

        Map<String, Object> launch = ElixirLaunch.launch(server);
        assertEquals("phx.server", launch.get("task"));
        assertEquals(List.of(), launch.get("taskArgs"));
        assertEquals(false, launch.get("startApps"));
        assertEquals(false, launch.get("exitAfterTaskReturns"), "phx.server returns once the server is up");
        assertFalse(ElixirLaunch.launch(plan(ROOT.resolve("lib/calc.ex"), -1, project(), ""))
                .containsKey("exitAfterTaskReturns"));
    }
}

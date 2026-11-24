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
}

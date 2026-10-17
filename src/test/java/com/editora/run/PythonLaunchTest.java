package com.editora.run;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a Python program is launched: its interpreter, argv and environment (review C1–C4, C6, C7, C12). */
class PythonLaunchTest {

    private static final Path ROOT = Path.of("/work/shop").toAbsolutePath();

    /** A host whose files are {@code files} and whose PATH has {@code onPath}. */
    private static PythonRunPlan.Host host(Set<Path> files, Map<String, String> onPath) {
        Set<Path> dirs = new HashSet<>();
        for (Path f : files) {
            for (Path p = f.getParent(); p != null; p = p.getParent()) {
                dirs.add(p);
            }
        }
        return new PythonRunPlan.Host(
                true,
                false,
                ":",
                Map.of("PATH", "/usr/bin"),
                files::contains,
                dirs::contains,
                name -> Optional.ofNullable(onPath.get(name)),
                Path::toString);
    }

    private static Set<Path> venv(Path dir) {
        return Set.of(dir.resolve("pyvenv.cfg"), dir.resolve("bin/python"));
    }

    private static PythonRunPlan.Request file(Path script, String firstLine) {
        return PythonRunPlan.Request.file(script, ROOT, script.toString(), List.of("--fast"), firstLine, "", "");
    }

    @Test
    void aProjectsVenvRunsItsScripts() {
        // Every Run was `python3 file.py`: a venv-only import failed with ModuleNotFoundError (C1).
        Path script = ROOT.resolve("main.py");
        Set<Path> files = new HashSet<>(venv(ROOT.resolve(".venv")));
        files.add(script);
        PythonRunPlan plan =
                PythonRunPlan.plan(file(script, "import sys"), host(files, Map.of("python3", "/usr/bin/python3")));
        assertEquals(List.of(ROOT.resolve(".venv/bin/python").toString(), script.toString(), "--fast"), plan.argv());
        assertEquals(PythonInterpreters.Source.PROJECT_VENV, plan.interpreter().source());
    }

    @Test
    void outputIsUnbufferedAndUtf8AndTheProjectIsOnThePythonPath() {
        // C3: a pipe made stdout block-buffered (nothing until exit); C6: Windows wrote the ANSI code page;
        // C7: `from pkg import x` failed from a file deep in the package.
        Path script = ROOT.resolve("pkg/sub/mod.py");
        Set<Path> files = Set.of(script, ROOT.resolve("src/x.py"));
        PythonRunPlan plan = PythonRunPlan.plan(file(script, ""), host(files, Map.of("python3", "/usr/bin/python3")));
        assertEquals("1", plan.env().get("PYTHONUNBUFFERED"));
        assertEquals("utf-8", plan.env().get("PYTHONIOENCODING"));
        assertEquals(ROOT + ":" + ROOT.resolve("src"), plan.env().get("PYTHONPATH"));
    }

    @Test
    void theUsersPythonPathIsKeptAfterTheProjectsAndAConfigurationsVariablesWin() {
        Map<String, String> env = PythonLaunch.environment(
                Map.of(), List.of("/p", "/p/src"), "/lib/extra", ":", Map.of("PYTHONUNBUFFERED", "0", "X", "1"));
        assertEquals("/p:/p/src:/lib/extra", env.get("PYTHONPATH"));
        assertEquals("0", env.get("PYTHONUNBUFFERED"), "a configuration can still turn buffering back on");
        assertEquals("1", env.get("X"));
        Map<String, String> own =
                PythonLaunch.environment(Map.of(), List.of("/p"), "/inherited", ":", Map.of("PYTHONPATH", "/mine"));
        assertEquals("/p:/mine", own.get("PYTHONPATH"), "a configuration's PYTHONPATH replaces the inherited one");
        assertNull(
                PythonLaunch.environment(Map.of(), List.of(), "/x", ":", Map.of())
                        .get("PYTHONPATH"),
                "no project: PYTHONPATH untouched");
    }

    @Test
    void aSpecificShebangIsHonouredAsDotSlashWould() {
        // C2: `#!/usr/bin/env python3.12` ran 3.13 silently; `-O` and `uv run --script` were dropped.
        Path script = ROOT.resolve("tool");
        Set<Path> files = new HashSet<>(venv(ROOT.resolve(".venv")));
        files.add(script);
        var h = host(files, Map.of("python3.12", "/usr/bin/python3.12", "uv", "/home/u/.local/bin/uv"));
        assertEquals(
                List.of("python3.12", script.toString(), "--fast"),
                PythonRunPlan.plan(file(script, "#!/usr/bin/env python3.12"), h).argv());
        assertEquals(
                List.of("uv", "run", "--script", script.toString(), "--fast"),
                PythonRunPlan.plan(file(script, "#!/usr/bin/env -S uv run --script"), h)
                        .argv());
        assertEquals(
                List.of(ROOT.resolve(".venv/bin/python").toString(), "-O", script.toString(), "--fast"),
                PythonRunPlan.plan(file(script, "#!/usr/bin/python3 -O"), h).argv(),
                "a plain python3 shebang names no particular Python: the project's runs it, with the flags");
        assertEquals(
                List.of(ROOT.resolve(".venv/bin/python").toString(), script.toString(), "--fast"),
                PythonRunPlan.plan(file(script, "#!/usr/bin/env python3"), h).argv());
    }

    @Test
    void aShebangInterpreterThatIsNotInstalledFallsBackAndSaysSo() {
        Path script = ROOT.resolve("tool");
        PythonRunPlan plan = PythonRunPlan.plan(
                file(script, "#!/usr/bin/env python3.12"), host(Set.of(script), Map.of("python3", "/usr/bin/python3")));
        assertEquals(List.of("/usr/bin/python3", script.toString(), "--fast"), plan.argv());
        assertEquals("python3.12", plan.missingShebang());
    }

    @Test
    void aConfigurationsInterpreterWinsOverTheShebangAndKeepsItsOptions() {
        Path script = ROOT.resolve("tool.py");
        var request = new PythonRunPlan.Request(
                script,
                ROOT,
                script.toString(),
                false,
                List.of(),
                "#!/usr/bin/env python3.12",
                ".venv-311/bin/python",
                List.of("-X", "dev"),
                "",
                "",
                Map.of());
        PythonRunPlan plan = PythonRunPlan.plan(request, host(Set.of(script), Map.of("python3.12", "/x")));
        assertEquals(
                List.of(ROOT.resolve(".venv-311/bin/python").toString(), "-X", "dev", script.toString()), plan.argv());
    }

    @Test
    void anEnvPrefixBecomesVariablesSoItRunsOnWindowsToo() {
        List<String> cmd = List.of("env", "PYTHONDEVMODE=1", "python3", "-X", "utf8");
        assertEquals(
                List.of("python3", "-X", "utf8", "-m", "app", "a"), PythonLaunch.argv(cmd, "app", true, List.of("a")));
        assertEquals(Map.of("PYTHONDEVMODE", "1"), PythonLaunch.envPrefix(cmd));
    }

    @Test
    void aModuleRunsWithDashM() {
        var request = new PythonRunPlan.Request(
                null, ROOT, "app.main", true, List.of("x"), null, "", List.of(), "", "", Map.of());
        PythonRunPlan plan = PythonRunPlan.plan(request, host(Set.of(), Map.of("python3", "/usr/bin/python3")));
        assertEquals(List.of("/usr/bin/python3", "-m", "app.main", "x"), plan.argv());
    }

    @Test
    void elsewhereTheProjectVenvIsNamedAsTheTargetNamesIt() {
        // C12: a remote, WSL or container run always used that host's python3.
        Path script = ROOT.resolve("main.py");
        Set<Path> files = new HashSet<>(venv(ROOT.resolve(".venv")));
        files.add(script);
        var local = host(files, Map.of());
        var remote = new PythonRunPlan.Host(
                false,
                false,
                ":",
                Map.of(),
                local.isFile(),
                local.isDirectory(),
                n -> Optional.empty(),
                p -> "/mnt" + p.toString().replace('\\', '/'));
        PythonRunPlan plan = PythonRunPlan.plan(
                PythonRunPlan.Request.file(script, ROOT, "/mnt/main.py", List.of(), "", "", ""), remote);
        assertEquals(
                "/mnt" + ROOT.resolve(".venv/bin/python").toString().replace('\\', '/'),
                plan.argv().get(0));
        assertEquals("/mnt" + ROOT.toString().replace('\\', '/'), plan.env().get("PYTHONPATH"));
        assertNull(plan.missingShebang());
    }

    @Test
    void shebangClassification() {
        assertFalse(PythonLaunch.shebang("#!/usr/bin/env python3").specific());
        assertFalse(PythonLaunch.shebang("print(1)").specific());
        assertTrue(PythonLaunch.shebang("#!/opt/pypy3/bin/pypy3").specific());
        assertEquals(
                List.of("-O"), PythonLaunch.shebang("#!/usr/bin/env python -O").flags());
        assertEquals(
                Map.of("A", "1"),
                PythonLaunch.shebang("#!/usr/bin/env A=1 python3").env());
        assertTrue(PythonLaunch.isPythonProgram("/usr/bin/python3.12"));
        assertTrue(PythonLaunch.isPythonProgram("C:\\Python313\\python.exe"));
        assertFalse(PythonLaunch.isPythonProgram("bash"));
    }
}

package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.test.TestCounts;
import com.editora.test.TestRun;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Running Python in a real window, with a real interpreter and a real virtual environment (review C1, C3, C5,
 * C7, C10, C15): the project's venv runs the file, output streams while the program runs, Stop lets
 * {@code finally} run, a package module imports its package, and a test file's ▶ runs its tests into Test
 * Results — through unittest when the venv has no pytest, and through pytest when it has (a stand-in module
 * that writes the report pytest would).
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // POSIX venv layout (bin/python)
class PythonRunWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!FxTestSupport.callOnFx(condition::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what + " never happened");
            FxTestSupport.drainFx();
            Thread.onSpinWait();
        }
    }

    private static String console(MainController c) {
        CodeArea output = FxTestSupport.field(c.runCoordinator.panel(), "output");
        return output.getText();
    }

    private static EditorBuffer open(MainController c, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
        await(
                "the file to open",
                () -> c.activeBuffer() != null && file.equals(c.activeBuffer().getPath()));
        EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
        FxTestSupport.runOnFx(b::settleRunScan);
        return b;
    }

    private static void run(MainController c, String command) throws Exception {
        CommandRegistry registry = FxTestSupport.field(c, "registry");
        FxTestSupport.runOnFx(() -> registry.run(command));
    }

    /** A venv without pip (fast, offline) in {@code root}/.venv, with {@code site} packages added to it. */
    private static Path venv(Path root) throws Exception {
        Path venv = root.resolve(".venv");
        Process p = new ProcessBuilder("python3", "-m", "venv", "--without-pip", venv.toString())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "python3 with venv is installed");
        try (var lib = Files.list(venv.resolve("lib"))) {
            return lib.findFirst().orElseThrow().resolve("site-packages");
        }
    }

    @Test
    void theProjectsVenvRunsTheFileAndTheCommandLineHeadsTheConsole() throws Exception {
        Files.writeString(dir.resolve("pyproject.toml"), "[project]\nname = \"v\"\n");
        Path site = venv(dir);
        Files.writeString(site.resolve("onlyvenv.py"), "VALUE = 'from the venv'\n");
        Path main = dir.resolve("main.py");
        Files.writeString(main, "import onlyvenv\nprint(onlyvenv.VALUE)\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            open(c, main);
            run(c, "file.run");
            // It was `python3 main.py`: ModuleNotFoundError: No module named 'onlyvenv'.
            await("the venv's module to import", () -> console(c).contains("from the venv\n"));
            String first = console(c).lines().findFirst().orElse("");
            assertTrue(first.contains(".venv/bin/python") && first.contains("main.py"), first);
        }
    }

    @Test
    void outputStreamsWhileItRunsAndStopLetsCleanupRun() throws Exception {
        Path mark = dir.resolve("cleaned");
        Path script = dir.resolve("slow.py");
        Files.writeString(
                script,
                "import pathlib, time\nprint('started')\ntry:\n    time.sleep(60)\nfinally:\n" + "    pathlib.Path(r'"
                        + mark + "').write_text('x')\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            open(c, script);
            run(c, "file.run");
            // Block-buffered, "started" only appeared when the program ended (C3).
            await(
                    "the first line while the program still runs",
                    () -> console(c).contains("started\n"));
            assertTrue(FxTestSupport.callOnFx(c.runCoordinator::isRunning));
            run(c, "run.stop");
            // SIGTERM ended Python on the spot: no finally, no atexit (C5).
            await("the finally block to run", () -> Files.exists(mark));
            await("the run to end", () -> !c.runCoordinator.isRunning());
        }
    }

    @Test
    void aModuleInAPackageImportsItsPackage() throws Exception {
        Files.writeString(dir.resolve("pyproject.toml"), "[project]\nname = \"p\"\n");
        Files.createDirectories(dir.resolve("pkg/sub"));
        Files.writeString(dir.resolve("pkg/__init__.py"), "");
        Files.writeString(dir.resolve("pkg/util.py"), "def hello():\n    return 'hello from util'\n");
        Files.writeString(dir.resolve("pkg/sub/__init__.py"), "");
        Path mod = dir.resolve("pkg/sub/mod.py");
        Files.writeString(mod, "from pkg import util\nprint(util.hello())\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(fx.controller, "openProjectRoot", Path.class, dir));
            MainController c = projectWindow(fx, dir);
            open(c, mod);
            run(c, "file.run");
            // sys.path[0] was pkg/sub: ModuleNotFoundError: No module named 'pkg' (C7).
            await("the package import", () -> console(c).contains("hello from util\n"));
        }
    }

    private static MainController projectWindow(FxWindowFixture fx, Path root) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            MainController found = FxTestSupport.callOnFx(() -> {
                List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
                for (Object holder : holders) {
                    MainController c = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                    Path active = (Path) FxTestSupport.call(c, "windowProjectRoot", new Class<?>[] {});
                    if (active != null && Files.isSameFile(active, root)) {
                        return c;
                    }
                }
                return null;
            });
            if (found != null) {
                return found;
            }
            FxTestSupport.drainFx();
            Thread.sleep(20);
        }
        throw new AssertionError("the project's window never opened");
    }

    private static TestRun testRun(MainController c) throws Exception {
        TestRunCoordinator coordinator = FxTestSupport.field(c, "testRunCoordinator");
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "currentRun"));
    }

    @Test
    void aTestFilesRunGlyphRunsItsTestsWithUnittestWhenThereIsNoPytest() throws Exception {
        Files.writeString(dir.resolve("pyproject.toml"), "[project]\nname = \"t\"\n");
        venv(dir); // no pytest in it
        Path test = dir.resolve("test_math.py");
        Files.writeString(
                test,
                "import unittest\n\n\nclass TestMath(unittest.TestCase):\n    def test_ok(self):\n"
                        + "        self.assertEqual(2, 1 + 1)\n\n    def test_bad(self):\n        self.assertEqual(1, 2)\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            EditorBuffer b = open(c, test);
            assertTrue(FxTestSupport.callOnFx(b::isRunnable));
            run(c, "file.run");
            // `python3 test_math.py` ran no test and exited 0 (C10).
            await("the results", () -> {
                try {
                    TestRun r = testRun(c);
                    return r != null && !r.isRunning();
                } catch (Exception e) {
                    return false;
                }
            });
            TestCounts counts = FxTestSupport.callOnFx(() -> testRun(c).counts());
            assertEquals(2, counts.total(), counts.toString());
            assertEquals(1, counts.failedOrErrored(), counts.toString());
            assertFalse(testRun(c).exitCode() == 0);
        }
    }

    @Test
    void withPytestTheReportFillsTestResults() throws Exception {
        Files.writeString(dir.resolve("pyproject.toml"), "[project]\nname = \"t\"\n");
        Path site = venv(dir);
        // A stand-in for pytest (not installed here): writes the xunit1 report for the node ids it was given.
        Files.createDirectories(site.resolve("pytest"));
        Files.writeString(site.resolve("pytest/__init__.py"), "");
        Files.writeString(site.resolve("pytest/__main__.py"), """
                import sys
                ids = [a for a in sys.argv[1:] if not a.startswith('-') and a != 'junit_family=xunit1']
                report = next(a.split('=', 1)[1] for a in sys.argv if a.startswith('--junitxml='))
                cases = ''.join(
                    f'<testcase classname="test_shop" name="{i.split("::")[-1]}" file="test_shop.py" line="3" time="0">'
                    + ('<failure message="boom">test_shop.py:5: AssertionError</failure>' if 'bad' in i else '')
                    + '</testcase>'
                    for i in (ids if any('::' in i for i in ids) else ['test_shop.py::test_ok', 'test_shop.py::test_bad']))
                open(report, 'w').write(f'<testsuites><testsuite name="pytest">{cases}</testsuite></testsuites>')
                print('ran', ' '.join(ids))
                sys.exit(1 if 'bad' in cases else 0)
                """);
        Path test = dir.resolve("test_shop.py");
        Files.writeString(test, "def test_ok():\n    pass\n\n\ndef test_bad():\n    assert 1 == 2\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            open(c, test);
            run(c, "file.run");
            await("the report", () -> {
                try {
                    TestRun r = testRun(c);
                    return r != null && !r.isRunning() && r.counts().total() == 2;
                } catch (Exception e) {
                    return false;
                }
            });
            assertTrue(console(c).contains("-m pytest"), console(c));
            assertEquals(1, FxTestSupport.callOnFx(() -> testRun(c).counts().failed()));

            // Rerun Failed asks pytest for the failed test's node id alone.
            run(c, "test.rerunFailed");
            await("the failed test's rerun", () -> console(c).contains("ran test_shop.py::test_bad"));
        }
    }
}

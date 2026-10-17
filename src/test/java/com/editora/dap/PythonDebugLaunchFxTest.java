package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A local Python debug session against a stand-in {@code debugpy} package (the stdio adapter of
 * {@link RemoteDebugFxTest}, which logs every request it gets): what the program is launched with — which
 * interpreter, its arguments and environment, the folder, {@code PYTHONPATH}, child processes — and which
 * interpreter runs the adapter.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class PythonDebugLaunchFxTest {

    /** The stand-in, first logging which Python runs it and with what PYTHONPATH. */
    static final String ADAPTER = RemoteDebugFxTest.ADAPTER.replace(
            "log = open(LOG, \"a\", buffering=1)\n",
            "log = open(LOG, \"a\", buffering=1)\n"
                    + "log.write(json.dumps({\"command\": \"__adapter__\", \"arguments\": {\"exe\": sys.executable,"
                    + " \"prefix\": sys.prefix}}) + \"\\n\")\n");

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private Path site;
    private Path log;
    private DapManager dap;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<List<DapModels.StackFrameInfo>> stops = new CopyOnWriteArrayList<>();
    private final List<String> commandLines = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "needs python3");
        site = base.resolve("site");
        Path adapterDir = Files.createDirectories(site.resolve("debugpy/adapter"));
        Files.writeString(site.resolve("debugpy/__init__.py"), "");
        Files.writeString(adapterDir.resolve("__init__.py"), "");
        Files.writeString(adapterDir.resolve("__main__.py"), ADAPTER);
        log = adapterDir.resolve("dap.log");
        dap = new DapManager(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dap != null) {
            onFx(dap::shutdown);
        }
    }

    private void configure(String interpreterSetting, boolean programConsole) throws Exception {
        onFx(() -> {
            dap.configure(true, "", true, interpreterSetting, false, "");
            dap.setPythonAdapterForTest(site);
            dap.setProgramConsole(programConsole);
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {}

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                    stops.add(frames);
                }

                @Override
                public void onOutput(String text, String category) {
                    if (DapManager.COMMAND_CATEGORY.equals(category)) {
                        commandLines.add(text);
                    }
                }

                @Override
                public void onError(String message) {
                    errors.add(message);
                }
            });
        });
    }

    private static void onFx(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    private void debug(Path file, Path projectRoot, List<String> args) throws Exception {
        dap.setBreakpointsSupplier(() -> List.of(
                new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(0, null, null, "% 2")))));
        onFx(() -> {
            dap.setPythonProject(projectRoot, "");
            dap.setProgramArgs(args);
            dap.startLaunch(file, "python", null);
        });
        awaitStop();
    }

    private void awaitStop() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (stops.isEmpty()) {
            assertTrue(errors.isEmpty(), errors.toString());
            assertTrue(
                    System.nanoTime() < deadline,
                    "never stopped; adapter was told " + (Files.exists(log) ? Files.readString(log) : "nothing"));
            Thread.sleep(50);
        }
    }

    private JsonObject logged(String command) throws Exception {
        for (String line : Files.readAllLines(log)) {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            if (command.equals(json.get("command").getAsString())) {
                return json.getAsJsonObject("arguments");
            }
        }
        throw new AssertionError("the adapter was never sent " + command + ": " + Files.readString(log));
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        array.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    /** A project with a {@code .venv} (an interpreter linked to the system one, as {@code venv} makes it). */
    private Path projectWithVenv() throws Exception {
        Path project = Files.createDirectories(base.resolve("my project"));
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname = \"x\"\n");
        Files.createDirectories(project.resolve("src"));
        Path bin = Files.createDirectories(project.resolve(".venv/bin"));
        Files.writeString(
                project.resolve(".venv/pyvenv.cfg"), "home = /usr/bin\ninclude-system-site-packages = false\n");
        Files.createSymbolicLink(bin.resolve("python"), Path.of("/usr/bin/python3"));
        return project;
    }

    @Test
    void theProgramAndTheAdapterRunUnderTheProjectsVenvWithTheProjectOnPythonPath() throws Exception {
        Path project = projectWithVenv();
        Path file = Files.createDirectories(project.resolve("tools")).resolve("main.py");
        Files.writeString(file, "a = 1\nprint(a)\n");
        configure("", false);

        debug(file, project, List.of("one", "two words"));

        JsonObject launch = logged("launch");
        String venvPython = project.resolve(".venv/bin/python").toString();
        assertEquals(venvPython, launch.get("python").getAsString(), "the program runs in the project's venv");
        assertEquals(file.toString(), launch.get("program").getAsString());
        assertEquals(project.resolve("tools").toString(), launch.get("cwd").getAsString(), "the script's folder");
        assertEquals(List.of("one", "two words"), strings(launch.getAsJsonArray("args")));
        assertFalse(launch.get("subProcess").getAsBoolean(), "children run without the debugger, not hang");
        assertEquals("internalConsole", launch.get("console").getAsString());
        String pythonPath = launch.getAsJsonObject("env").get("PYTHONPATH").getAsString();
        assertTrue(
                pythonPath.startsWith(project + ":" + project.resolve("src")),
                "the project root and src/ come first: " + pythonPath);
        assertFalse(
                pythonPath.contains(site.toString()), "Editora's debugpy folder stays the adapter's: " + pythonPath);
        assertEquals(venvPython, logged("__adapter__").get("exe").getAsString(), "venv imports debugpy: adapter too");
        JsonObject breakpoint =
                logged("setBreakpoints").getAsJsonArray("breakpoints").get(0).getAsJsonObject();
        assertEquals("% 2", breakpoint.get("hitCondition").getAsString(), "a hit count reaches debugpy");
    }

    @Test
    void anInterpreterCommandWithArgumentsAndAnEnvPrefixIsTakenApart() throws Exception {
        Path dir = Files.createDirectories(base.resolve("loose"));
        Path file = dir.resolve("script.py");
        Files.writeString(file, "print(1)\n");
        configure("env PROBE_ENV=hi python3 -X dev", false);

        debug(file, null, List.of());

        JsonObject launch = logged("launch");
        assertTrue(Path.of(launch.get("python").getAsString()).isAbsolute(), launch.toString());
        assertTrue(launch.get("python").getAsString().contains("python3"), launch.toString());
        assertEquals(List.of("-X", "dev"), strings(launch.getAsJsonArray("pythonArgs")));
        JsonObject env = launch.getAsJsonObject("env");
        assertEquals("hi", env.get("PROBE_ENV").getAsString());
        assertNull(launch.get("args"), "no arguments: none sent");
        assertEquals(dir.toString(), launch.get("cwd").getAsString());
    }

    @Test
    void withTheProgramConsoleTheAdapterHandsTheProgramBackSoItCanReadInput() throws Exception {
        Path dir = Files.createDirectories(base.resolve("loose"));
        Path file = dir.resolve("ask.py");
        Files.writeString(file, "name = input()\n");
        configure("", true);

        debug(file, null, List.of());

        assertTrue(logged("initialize").get("supportsRunInTerminalRequest").getAsBoolean());
        JsonObject launch = logged("launch");
        assertEquals("integratedTerminal", launch.get("console").getAsString());
        assertEquals("1", launch.getAsJsonObject("env").get("PYTHONUNBUFFERED").getAsString());
    }

    /** A test file of the project, and the folder of the venv's site-packages (for a stand-in pytest). */
    private Path testFile(Path project) throws Exception {
        Path test = Files.createDirectories(project.resolve("tests")).resolve("test_x.py");
        Files.writeString(
                test, "import unittest\n\nclass TestA(unittest.TestCase):\n    def test_a(self):\n        pass\n");
        return test;
    }

    private void debugTest(Path project, Path test, List<String> unittest) throws Exception {
        dap.setBreakpointsSupplier(() ->
                List.of(new DapModels.FileBreakpoints(test, List.of(new DapModels.LineBreakpoint(4, null, null)))));
        onFx(() -> {
            dap.setPythonProject(project, "");
            dap.startPythonTest(test, List.of("tests/test_x.py::TestA::test_a"), unittest, project);
        });
    }

    @Test
    void aTestIsDebuggedUnderPytestInTheProjectFolder() throws Exception {
        Path project = projectWithVenv();
        String version = new String(new ProcessBuilder(
                                "/usr/bin/python3", "-c", "import sys; print('%d.%d' % sys.version_info[:2])")
                        .start()
                        .getInputStream()
                        .readAllBytes())
                .strip();
        Path stand = Files.createDirectories(project.resolve(".venv/lib/python" + version + "/site-packages/pytest"));
        Files.writeString(stand.resolve("__init__.py"), "");
        Path test = testFile(project);
        configure("", false);

        debugTest(project, test, List.of("tests.test_x.TestA.test_a"));
        awaitStop();

        JsonObject launch = logged("launch");
        assertEquals("pytest", launch.get("module").getAsString());
        assertNull(launch.get("program"));
        assertEquals(List.of("tests/test_x.py::TestA::test_a"), strings(launch.getAsJsonArray("args")));
        assertEquals(project.toString(), launch.get("cwd").getAsString());
        assertEquals(
                project.resolve(".venv/bin/python").toString(),
                launch.get("python").getAsString());
    }

    @Test
    void withoutPytestAUnittestTestRunsUnderUnittest() throws Exception {
        Path project = projectWithVenv();
        Path test = testFile(project);
        configure("", false);

        debugTest(project, test, List.of("tests.test_x.TestA.test_a"));
        awaitStop();

        JsonObject launch = logged("launch");
        assertEquals("unittest", launch.get("module").getAsString());
        assertEquals(List.of("tests.test_x.TestA.test_a"), strings(launch.getAsJsonArray("args")));
    }

    @Test
    void withoutPytestAPytestStyleTestSaysWhichInterpreterLacksIt() throws Exception {
        Path project = projectWithVenv();
        Path test = testFile(project);
        configure("", false);

        debugTest(project, test, List.of());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (errors.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(
                errors.get(0).contains("pytest is not installed for " + project.resolve(".venv/bin/python")),
                errors.get(0));
        assertFalse(Files.exists(log) && Files.readString(log).contains("\"launch\""), "nothing was launched");
    }
}

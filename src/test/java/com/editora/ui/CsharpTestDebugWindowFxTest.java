package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * C#'s Debug Test at Caret through the window, against stand-ins: a {@code dotnet} that records what it is asked and,
 * as a test host waiting for a debugger does, prints its process id and waits; and a {@code netcoredbg} relaying to a
 * {@link FakeDebugAdapter}. The test at the caret is run filtered to it with {@code VSTEST_HOST_DEBUG=1}, netcoredbg
 * attaches to the printed process, Stop ends the run, and a file outside a test project is not taken.
 * {@code CsharpTestDebugProbeFxTest} drives the real SDK and netcoredbg.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class CsharpTestDebugWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;
    private Path dotnetLog;

    private static final String DOTNET = """
            #!/bin/sh
            echo "$@ VSTEST_HOST_DEBUG=$VSTEST_HOST_DEBUG" >> '%s'
            echo "Host debugging is enabled. Please attach debugger to testhost process to continue."
            echo "Process Id: 4242, Name: dotnet"
            exec sleep 60
            """;

    private static final String TESTS = """
            namespace Shop.Tests;

            public class PriceTests
            {
                [Fact]
                public void Total()
                {
                    Assert.Equal(21, 7 * 3);
                }
            }
            """;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-ins are shell scripts");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("Shop.Tests")).toRealPath();
        Files.writeString(
                root.resolve("Shop.Tests.csproj"),
                "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup>"
                        + "<PackageReference Include=\"Microsoft.NET.Test.Sdk\" Version=\"17.14.1\" />"
                        + "</ItemGroup></Project>");
        Files.writeString(root.resolve("PriceTests.cs"), TESTS);
        Path app = Files.createDirectories(fx.configDir.resolve("Shop")).toRealPath();
        Files.writeString(app.resolve("Shop.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk\"/>");
        Files.writeString(app.resolve("Program.cs"), TESTS); // test-looking code in a project that is no test one
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path netcoredbg = script(
                "netcoredbg",
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain stdio " + adapter.port() + "\n");
        dotnetLog = fx.configDir.resolve("dotnet-log");
        Path dotnet = script("dotnet", DOTNET.formatted(dotnetLog));
        FxTestSupport.runOnFx(() -> {
            var s = fx.shared.getSettings();
            s.setDebugSupport(true);
            s.setCsharpDebugPath(netcoredbg.toString());
            fx.shared.getTrustStore().trust(root);
            fx.shared.getTrustStore().trust(app);
            debug.dotnetCommand = List.of(dotnet.toString());
            debug.applySupport();
        });
        await("C# debugging available", () -> call(() -> debug.debugEffectiveFor("csharp")));
    }

    private Path script(String name, String text) throws Exception {
        Path p = fx.configDir.resolve(name);
        Files.writeString(p, text);
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(manager::stop);
            fx.dispose();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    private interface Check<T> {
        T get() throws Exception;
    }

    private static <T> T call(Check<T> check) {
        try {
            return FxTestSupport.callOnFx(check::get);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private EditorBuffer open(Path file, int caretLine) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        await(
                file.getFileName() + " in front",
                () -> call(() -> {
                    EditorBuffer b = fx.controller.activeBuffer();
                    return b != null && file.equals(b.getPath());
                }));
        EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(caretLine, 0));
        return b;
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    @Test
    void theTestAtTheCaretRunsUnderDotnetTestAndNetcoredbgAttachesToItsHost() throws Exception {
        EditorBuffer b = open(root.resolve("PriceTests.cs"), 7);
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(7));
        run("test.debugAtCaret");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("attach");
        assertEquals(4242L, ((Number) session.launchArgs.get("processId")).longValue(), "the host it printed");
        assertEquals("coreclr", session.launchArgs.get("type"));
        String log = Files.readString(dotnetLog);
        assertTrue(
                log.contains(
                                "test " + root.resolve("Shop.Tests.csproj") + " --filter "
                                        + "FullyQualifiedName=Shop.Tests.PriceTests.Total|FullyQualifiedName~Shop.Tests.PriceTests.Total\\(")
                        && log.contains("VSTEST_HOST_DEBUG=1"),
                log);
        assertEquals("PriceTests.Total", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
        await(
                "the breakpoint sent",
                () -> session.breakpoints.stream()
                        .anyMatch(a -> a.getSource().getPath().endsWith("PriceTests.cs")));

        // Stop ends the dotnet test run too.
        com.editora.run.RunService host = call(() -> debug.s().testHost);
        assertTrue(host != null && call(host::isRunning));
        run("debug.stop"); // the Debug window's Stop
        await("the run ended", () -> !call(host::isRunning));
    }

    /**
     * The session ends without Stop (the attach failed, the adapter went): the test host would wait for a debugger
     * forever, so the run is stopped a moment later (review 2026-11-17); meanwhile the session counts as live.
     */
    @Test
    void theRunIsStoppedWhenItsSessionEndsWithoutStop() throws Exception {
        CsharpTestDebug.summaryGrace = javafx.util.Duration.millis(200);
        try {
            open(root.resolve("PriceTests.cs"), 7);
            run("test.debugAtCaret");
            FakeDebugAdapter.Session session = adapter.awaitSession();
            session.awaitRequest("attach");
            com.editora.run.RunService host = call(() -> debug.s().testHost);
            assertTrue(host != null && call(host::isRunning));
            session.terminate(); // the adapter ends the session: no Stop
            await("the run ended", () -> !call(host::isRunning));
        } finally {
            CsharpTestDebug.summaryGrace = javafx.util.Duration.seconds(10);
        }
    }

    @Test
    void aFileOutsideATestProjectIsNotDebuggedAsATest() throws Exception {
        open(fx.configDir.resolve("Shop").toRealPath().resolve("Program.cs"), 7);
        long before = Files.exists(dotnetLog) ? Files.readAllLines(dotnetLog).size() : 0;
        run("test.debugAtCaret");
        FxTestSupport.drainFx();
        long after = Files.exists(dotnetLog) ? Files.readAllLines(dotnetLog).size() : 0;
        assertEquals(before, after, "no dotnet test for a project that has no test SDK");
        assertTrue(!status().isEmpty(), "it says why: " + status());
    }
}

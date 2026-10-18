package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Groovy script in a real window: Run File runs it with {@code groovy}, and Debug stops at a breakpoint inside
 * one of its closures (Editora's own JVM adapter). Needs Groovy installed (GROOVY_HOME, PATH or SDKMAN), so it is
 * skipped where there is none.
 */
@Tag("fx")
class GroovyRunDebugWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Assumptions.assumeTrue(com.editora.run.JvmTools.locate("groovy") != null, "Groovy is not installed here");
    }

    @TempDir
    Path dir;

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!FxTestSupport.callOnFx(condition::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what + " never happened");
            FxTestSupport.drainFx();
            Thread.sleep(20);
        }
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

    private Path script() throws Exception {
        Path script = dir.resolve("Tally.groovy");
        Files.writeString(
                script, "def total = 0\n[1, 2, 3].each { n ->\n    total += n\n}\nprintln \"total=$total\"\n");
        return script;
    }

    @Test
    void runFileRunsTheScriptWithGroovy() throws Exception {
        Path script = script();
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            EditorBuffer b = open(c, script);
            assertEquals("groovy", FxTestSupport.callOnFx(b::getLanguage));
            assertTrue(FxTestSupport.callOnFx(b::isRunnable), "the gutter ▶ and Run File are there");
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.run"));
            await("the script's output", () -> {
                CodeArea output = FxTestSupport.field(c.runCoordinator.panel(), "output");
                return output.getText().contains("total=6");
            });
        }
    }

    @Test
    void debugStopsAtABreakpointInAClosureAndRunsOnToTheEnd() throws Exception {
        Path script = script();
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            DapManager manager = FxTestSupport.field(c, "dapManager");
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setDebugSupport(true));
            FxTestSupport.runOnFx(() -> {
                try {
                    var f = DapManager.class.getDeclaredField("enabled");
                    f.setAccessible(true);
                    f.set(manager, true);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            EditorBuffer b = open(c, script);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(2)); // `total += n`, inside the closure
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            await("the breakpoint to stop the script", () -> manager.state() == DapManager.State.SUSPENDED);
            assertTrue(FxTestSupport.callOnFx(manager::currentThreadId) > 0);

            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(2)); // off: the loop runs to the end
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(manager::resume);
            await(
                    "the script to finish",
                    () -> manager.state() == DapManager.State.TERMINATED
                            || manager.state() == DapManager.State.INACTIVE);
        }
    }
}

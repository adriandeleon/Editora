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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Scala file outside any build, in a real window: Run File runs it with Scala CLI, and Debug stops at a
 * breakpoint in it (Scala CLI under Editora's own JVM adapter, the agent passed as {@code --java-opt}). Needs
 * Scala CLI ({@code scala-cli}, or the {@code scala} of Scala 3.5+) on PATH or in SDKMAN, so it is skipped where
 * there is none. Scala CLI compiles first, and on a first run fetches the compiler: the waits are minutes.
 */
@Tag("fx")
@Timeout(value = 10, unit = TimeUnit.MINUTES) // a first Scala CLI run downloads the compiler
class ScalaRunDebugWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Assumptions.assumeTrue(com.editora.run.JvmTools.locate("scala-cli") != null, "Scala CLI is not installed here");
    }

    @TempDir
    Path dir;

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        while (!FxTestSupport.callOnFx(condition::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what + " never happened");
            FxTestSupport.drainFx();
            Thread.sleep(50);
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

    private Path program() throws Exception {
        Path main = dir.resolve("Main.scala");
        Files.writeString(main, """
                package demo

                @main def tally(): Unit =
                  var total = 0
                  for n <- List(1, 2, 3) do
                    total += n
                  println(s"total=$total")
                """);
        return main;
    }

    @Test
    void runFileRunsTheProgramWithScalaCli() throws Exception {
        Path main = program();
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            EditorBuffer b = open(c, main);
            assertEquals("scala", FxTestSupport.callOnFx(b::getLanguage));
            assertTrue(FxTestSupport.callOnFx(b::isRunnable), "the gutter ▶ and Run File are there");
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.run"));
            await("the program's output", () -> {
                CodeArea output = FxTestSupport.field(c.runCoordinator.panel(), "output");
                return output.getText().contains("total=6");
            });
        }
    }

    @Test
    void debugStopsAtABreakpointAndRunsOnToTheEnd() throws Exception {
        Path main = program();
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
            EditorBuffer b = open(c, main);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(5)); // `total += n`
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            await("the breakpoint to stop the program", () -> manager.state() == DapManager.State.SUSPENDED);
            assertTrue(FxTestSupport.callOnFx(manager::currentThreadId) > 0);

            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(5));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(manager::resume);
            await(
                    "the program to finish",
                    () -> manager.state() == DapManager.State.TERMINATED
                            || manager.state() == DapManager.State.INACTIVE);
        }
    }
}

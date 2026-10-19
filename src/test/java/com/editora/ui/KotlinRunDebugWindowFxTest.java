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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kotlin in a real window: Run File runs a {@code .main.kts} script with {@code kotlinc -script} and compiles a
 * {@code .kt} program into its cached jar before {@code java -jar} runs it (the second run reuses the jar), and
 * Debug stops at a breakpoint in a program whose folder is not its package. Needs Kotlin's compiler (KOTLIN_HOME,
 * PATH or SDKMAN), so it is skipped where there is none.
 */
@Tag("fx")
class KotlinRunDebugWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Assumptions.assumeTrue(com.editora.run.JvmTools.locate("kotlinc") != null, "Kotlin is not installed here");
    }

    @TempDir
    Path dir;

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180); // kotlinc starts slowly
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

    private static String output(MainController c) {
        CodeArea output = FxTestSupport.field(c.runCoordinator.panel(), "output");
        return output.getText();
    }

    private Path program() throws Exception {
        Path source = dir.resolve("Hello.kt");
        Files.writeString(source, """
                package demo

                fun greet(name: String): String {
                    val msg = "Hello, $name"
                    return msg
                }

                fun main() {
                    println(greet("Kotlin"))
                }
                """);
        return source;
    }

    @Test
    void runFileRunsAScriptAndCompilesAProgramOnceThenReusesItsJar() throws Exception {
        Path script = dir.resolve("tally.main.kts");
        Files.writeString(script, "val total = listOf(1, 2, 3).sum()\nprintln(\"total=$total\")\n");
        Path source = program();
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            EditorBuffer s = open(c, script);
            assertEquals("kotlin", FxTestSupport.callOnFx(s::getLanguage));
            assertTrue(FxTestSupport.callOnFx(s::isRunnable), "a script has its ▶");
            FxTestSupport.runOnFx(() -> registry.run("file.run"));
            await("the script's output", () -> output(c).contains("total=6"));
            await("the script to end", () -> !c.runCoordinator.isRunning());

            EditorBuffer p = open(c, source);
            assertTrue(FxTestSupport.callOnFx(p::isRunnable), "a program has its ▶ on fun main");
            FxTestSupport.runOnFx(() -> registry.run("file.run"));
            await("the program's output", () -> output(c).contains("Hello, Kotlin"));
            await("the program to end", () -> !c.runCoordinator.isRunning());
            Path cache = com.editora.run.KotlinRun.cacheDir(fx.shared.getConfigDir(), Path.of("/unused"));
            long jars;
            try (var files = Files.list(cache)) {
                jars = files.filter(f -> f.getFileName().toString().startsWith("Hello-"))
                        .count();
            }
            assertEquals(1, jars, "one jar for the file");

            FxTestSupport.runOnFx(() -> registry.run("file.run")); // unchanged: straight to java -jar
            await("the second run's output", () -> output(c).contains("Hello, Kotlin"));
            assertFalse(output(c).contains("-include-runtime"), "not compiled again: " + output(c));
            await("the second run to end", () -> !c.runCoordinator.isRunning());

            // An edit, then Rerun: compiled again (not the old jar replayed), and the old jar is gone.
            Files.writeString(source, Files.readString(source).replace("\"Kotlin\"", "\"again\""));
            FxTestSupport.runOnFx(() -> registry.run("run.rerun"));
            await("the rerun's new output", () -> output(c).contains("Hello, again"));
            try (var files = Files.list(cache)) {
                jars = files.filter(f -> f.getFileName().toString().startsWith("Hello-"))
                        .count();
            }
            assertEquals(1, jars, "the edited file's jar replaced the old one");
        }
    }

    @Test
    void debugStopsAtABreakpointInAProgramAndRunsOnToTheEnd() throws Exception {
        Path source = program();
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
            EditorBuffer b = open(c, source);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(4)); // `return msg`
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            await("the breakpoint to stop the program", () -> manager.state() == DapManager.State.SUSPENDED);
            assertTrue(FxTestSupport.callOnFx(manager::currentThreadId) > 0);

            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(4));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(manager::resume);
            await(
                    "the program to finish",
                    () -> manager.state() == DapManager.State.TERMINATED
                            || manager.state() == DapManager.State.INACTIVE);
        }
    }
}

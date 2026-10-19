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
 * A Clojure namespace in a {@code deps.edn} project, in a real window: Run File runs its {@code -main} with
 * {@code clojure -M -m} in the project folder, and Debug stops at a breakpoint in one of its functions (Editora's
 * own JVM adapter). Needs the Clojure CLI, so it is skipped where there is none.
 */
@Tag("fx")
class ClojureRunDebugWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Assumptions.assumeTrue(
                com.editora.run.JvmTools.locate("clojure") != null, "the Clojure CLI is not installed here");
    }

    @TempDir
    Path dir;

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
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

    private Path core() throws Exception {
        Files.writeString(dir.resolve("deps.edn"), "{:paths [\"src\"]}\n");
        Path core = Files.createDirectories(dir.resolve("src/my_app")).resolve("core.clj");
        Files.writeString(core, """
                (ns my-app.core)

                (defn greet [who]
                  (let [msg (str "Hello, " who)]
                    (println msg)))

                (defn -main [& args]
                  (greet (or (first args) "world"))
                  (println "cwd" (System/getProperty "user.dir")))
                """);
        return core;
    }

    @Test
    void runFileRunsTheNamespacesMainInTheProject() throws Exception {
        Path core = core();
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            EditorBuffer b = open(c, core);
            assertEquals("clojure", FxTestSupport.callOnFx(b::getLanguage));
            assertTrue(FxTestSupport.callOnFx(b::isRunnable), "the gutter ▶ and Run File are there");
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.run"));
            await("the program's output", () -> {
                CodeArea output = FxTestSupport.field(c.runCoordinator.panel(), "output");
                return output.getText().contains("Hello, world")
                        && output.getText().contains("cwd ");
            });
            CodeArea output = FxTestSupport.field(c.runCoordinator.panel(), "output");
            String text = FxTestSupport.callOnFx(output::getText);
            assertTrue(text.contains("-M -m my-app.core"), text);
            assertTrue(text.contains("cwd " + dir.toRealPath()) || text.contains("cwd " + dir), text);
        }
    }

    @Test
    void debugStopsAtABreakpointInAFunctionAndRunsOnToTheEnd() throws Exception {
        Path core = core();
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
            EditorBuffer b = open(c, core);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(4)); // (println msg), inside greet
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

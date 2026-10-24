package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import com.editora.editor.lsp.LspDiagnostic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANUAL PROBE (not part of the suite): Metals in a real window on a two-project sbt build. Metals asks to
 * import the build (the window's question dialog is answered "Import build"), reports a type error in the
 * Problems list, and Debug on the build's main stops at a breakpoint through Metals' own debug adapter
 * ({@code debug-adapter-start}). Needs sbt on PATH and a Metals classpath as the installer lays it out:
 * {@code -Dtest=ScalaMetalsProbeFxTest -Dgroups=probe -Dlsp.probe.metalsLib=<dir of Metals' jars>}. An import
 * takes minutes (sbt, Bloop, the compiler); the waits are generous.
 */
@Tag("probe")
@Tag("fx")
class ScalaMetalsProbeFxTest {

    /** Not cleaned up: Metals' Bloop server outlives the window and still writes the build's {@code .bloop}. */
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path dir;

    private static void await(String what, long minutes, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (!FxTestSupport.callOnFx(condition::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what + " never happened");
            FxTestSupport.drainFx();
            Thread.sleep(200);
        }
    }

    /** Presses the button called {@code label} in any open dialog; true when there was one. */
    private static boolean press(String label) {
        for (Window w : Window.getWindows()) {
            if (w.getScene() != null && w.getScene().getRoot() instanceof DialogPane pane) {
                for (var type : pane.getButtonTypes()) {
                    if (type.getText().equals(label) && pane.lookupButton(type) instanceof Button b) {
                        System.out.println("probe: answering '" + pane.getContentText() + "' with " + label);
                        b.fire();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void metalsImportsTheBuildReportsAnErrorAndDebugsTheMain() throws Exception {
        String lib = System.getProperty("lsp.probe.metalsLib", "");
        Assumptions.assumeTrue(
                !lib.isBlank() && Files.isDirectory(Path.of(lib)), "opt-in: -Dlsp.probe.metalsLib=<dir of jars>");
        FxTestSupport.bootToolkit();
        Path root = dir.resolve("app");
        Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("build.sbt"), """
                ThisBuild / scalaVersion := "3.3.7"
                lazy val core = project.in(file("core"))
                lazy val root = project.in(file(".")).aggregate(core).dependsOn(core)
                """);
        Files.writeString(root.resolve("project/build.properties"), "sbt.version=1.11.7\n");
        Path util = root.resolve("core/src/main/scala/demo/Util.scala");
        Files.createDirectories(util.getParent());
        Files.writeString(util, "package demo\n\nobject Util:\n  def twice(n: Int): Int = n * 2\n");
        Path main = root.resolve("src/main/scala/demo/Main.scala");
        Files.createDirectories(main.getParent());
        Files.writeString(main, """
                package demo

                @main def hello(): Unit =
                  val n = Util.twice(21)
                  println(s"n = $n")
                """);
        Path broken = root.resolve("src/main/scala/demo/Broken.scala");
        Files.writeString(broken, "package demo\n\nobject Broken:\n  val x: Int = \"not a number\"\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> {
                var s = fx.shared.getSettings();
                s.setLspSupport(true);
                s.setScalaLspEnabled(true);
                s.setScalaLspCommand(com.editora.install.InstallCatalog.coursierLspCommand(
                        Path.of(lib), com.editora.install.InstallCatalog.METALS));
                s.setMavenJdkHome(System.getProperty("java.home"));
                s.setDebugSupport(true);
            });
            FxTestSupport.runOnFx(() -> c.openAndNavigate(main, 1));
            LspCoordinator lsp = FxTestSupport.field(c, "lspCoordinator");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(lsp, "applySupport", new Class<?>[] {}));
            await("Metals to ask to import the build", 3, () -> press("Import build"));

            FxTestSupport.runOnFx(() -> c.openAndNavigate(broken, 1));
            await("the type error in the Problems list", 15, () -> {
                Map<Path, List<LspDiagnostic>> problems = FxTestSupport.field(lsp, "problems");
                return problems.values().stream().anyMatch(d -> !d.isEmpty());
            });
            Map<Path, List<LspDiagnostic>> problems =
                    FxTestSupport.callOnFx(() -> FxTestSupport.field(lsp, "problems"));
            System.out.println("probe: metals problems: " + problems);
            // Fix it in the editor and save, as a user would: Metals recompiles, and only a compiled target has a main.
            FxTestSupport.runOnFx(() -> {
                EditorBuffer open = c.activeBuffer();
                open.getArea().replaceText("package demo\n\nobject Broken:\n  val x: Int = 1\n");
            });
            CommandRegistry saver = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> saver.run("file.save"));
            await("the type error to clear", 10, () -> {
                Map<Path, List<LspDiagnostic>> now = FxTestSupport.field(lsp, "problems");
                return now.values().stream().allMatch(List::isEmpty);
            });

            DapManager manager = FxTestSupport.field(c, "dapManager");
            FxTestSupport.runOnFx(() -> {
                try {
                    var f = DapManager.class.getDeclaredField("enabled");
                    f.setAccessible(true);
                    f.set(manager, true);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            FxTestSupport.runOnFx(() -> c.openAndNavigate(main, 1));
            await(
                    "Main.scala to be active",
                    1,
                    () -> c.activeBuffer() != null
                            && main.equals(c.activeBuffer().getPath()));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(4)); // println(s"n = $n")
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            await("Metals' adapter to stop at the breakpoint", 10, () -> manager.state() == DapManager.State.SUSPENDED);
            System.out.println("probe: stopped in thread " + FxTestSupport.callOnFx(manager::currentThreadId));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(4));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(manager::resume);
            await(
                    "the program to finish",
                    2,
                    () -> manager.state() == DapManager.State.TERMINATED
                            || manager.state() == DapManager.State.INACTIVE);
        }
    }
}

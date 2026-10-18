package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANUAL PROBE (not part of the suite): Run File on a Scala file of an sbt build and of a Mill build runs its
 * main through that build, in the build tool's console ({@code sbt "runMain demo.hello"},
 * {@code ./mill app.runMain demo.hello}). Needs sbt on PATH and Mill's launcher script:
 * {@code -Dtest=ScalaBuildRunProbeFxTest -Dgroups=probe -Dscala.probe.millLauncher=<mill-dist-1.3.0-mill.sh>}.
 */
@Tag("probe")
@Tag("fx")
class ScalaBuildRunProbeFxTest {

    @TempDir
    Path dir;

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (!FxTestSupport.callOnFx(condition::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what + " never happened");
            FxTestSupport.drainFx();
            Thread.sleep(200);
        }
    }

    /** All the build consoles' text. */
    private static String consoles(MainController c) {
        BuildOutputPanel panel = FxTestSupport.field(c, "buildOutputPanel");
        StringBuilder out = new StringBuilder();
        for (Tab t : panel.getTabs()) {
            if (t.getContent() instanceof BuildToolPanel tool) {
                org.fxmisc.richtext.CodeArea area = FxTestSupport.field(tool, "output");
                out.append(area.getText());
            }
        }
        return out.toString();
    }

    private static void runFile(MainController c, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
        await(
                "the file to open",
                () -> c.activeBuffer() != null && file.equals(c.activeBuffer().getPath()));
        EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
        FxTestSupport.runOnFx(b::settleRunScan);
        assertTrue(FxTestSupport.callOnFx(b::isRunnable), "the @main has a ▶");
        CommandRegistry registry = FxTestSupport.field(c, "registry");
        FxTestSupport.runOnFx(() -> registry.run("file.run"));
    }

    private static final String MAIN = """
            package demo

            @main def hello(): Unit =
              println(s"n = ${Util.twice(21)}")
            """;
    private static final String UTIL = "package demo\n\nobject Util:\n  def twice(n: Int): Int = n * 2\n";

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void runFileRunsTheMainThroughSbtAndThroughMill() throws Exception {
        String launcher = System.getProperty("scala.probe.millLauncher", "");
        Assumptions.assumeTrue(
                !launcher.isBlank() && Files.isRegularFile(Path.of(launcher)),
                "opt-in: -Dscala.probe.millLauncher=<mill-dist-1.3.0-mill.sh>");
        FxTestSupport.bootToolkit();

        Path sbt = dir.resolve("sbt-app");
        Files.createDirectories(sbt.resolve("project"));
        Files.writeString(sbt.resolve("build.sbt"), "ThisBuild / scalaVersion := \"3.3.7\"\n");
        Files.writeString(sbt.resolve("project/build.properties"), "sbt.version=1.11.7\n");
        Path sbtMain = sbt.resolve("src/main/scala/demo/Main.scala");
        Files.createDirectories(sbtMain.getParent());
        Files.writeString(sbtMain, MAIN);
        Files.writeString(sbtMain.resolveSibling("Util.scala"), UTIL);

        Path mill = dir.resolve("mill-app");
        Files.createDirectories(mill.resolve("app/src/demo"));
        Files.writeString(mill.resolve("build.mill"), """
                package build
                import mill.*, scalalib.*

                object app extends ScalaModule {
                  def scalaVersion = "3.3.7"
                }
                """);
        Path wrapper = Files.copy(Path.of(launcher), mill.resolve("mill"));
        wrapper.toFile().setExecutable(true);
        Path millMain = mill.resolve("app/src/demo/Main.scala");
        Files.writeString(millMain, MAIN);
        Files.writeString(millMain.resolveSibling("Util.scala"), UTIL);

        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setMavenJdkHome(System.getProperty("java.home")));
            runFile(c, sbtMain);
            await("sbt to run the main", () -> consoles(c).contains("n = 42"));
            System.out.println("probe: sbt console:\n" + FxTestSupport.callOnFx(() -> consoles(c)));

            // The repository's ./mill is code it ships: trust its folder, as the prompt would.
            com.editora.config.ConfigManager config = FxTestSupport.field(c, "config");
            FxTestSupport.runOnFx(() -> config.getTrustStore().trust(mill));
            int before = FxTestSupport.callOnFx(() -> consoles(c)).split("n = 42", -1).length;
            runFile(c, millMain);
            await("Mill to run the main", () -> consoles(c).split("n = 42", -1).length > before);
            System.out.println("probe: consoles:\n" + FxTestSupport.callOnFx(() -> consoles(c)));
        }
    }
}

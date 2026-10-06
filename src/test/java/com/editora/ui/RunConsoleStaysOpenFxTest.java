package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.control.Button;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Run console must outlive the run it was opened for.
 *
 * <p>Its availability was "the active tab is a single-file runnable, or a process is alive". A run's own exit
 * callback re-evaluates it with the process already gone, so for the ordinary case — a saved configuration or
 * a project main class, with a tab that is not itself runnable — the console closed on the output, the stack
 * trace or the build errors it had just streamed, and a program that exits at once never appeared at all.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the fixtures are /bin/sh scripts
class RunConsoleStaysOpenFxTest {

    @TempDir
    static Path work;

    private static FxWindowFixture fx;
    private static ToolWindowManager toolWindows;
    private static ToolWindow runWindow;
    private static RunCoordinator run;
    private static CommandRegistry registry;

    @BeforeAll
    static void boot() throws Exception {
        Path slow = script("slow.sh", "echo \"result: 42\"\nsleep 0.5\nexit 3\n");
        Path instant = script("instant.sh", "echo instant-output\n");
        Path failBuild = script("build-fail.sh", "echo \"[ERROR] BUILD FAILURE\"\nsleep 0.3\nexit 1\n");
        Files.writeString(work.resolve("README.md"), "# not runnable\n");

        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
        runWindow = FxTestSupport.field(fx.controller, "runToolWindow");
        run = FxTestSupport.field(fx.controller, "runCoordinator");
        registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            ConfigManager cfg = FxTestSupport.field(fx.controller, "config");
            cfg.getWorkspaceState()
                    .setRunConfigurations(new ArrayList<>(List.of(
                            config("Slow", slow, ""),
                            config("Instant", instant, ""),
                            config("FailBuild", slow, "sh " + failBuild))));
            FxTestSupport.invoke(FxTestSupport.field(fx.controller, "runConfigurations"), "refreshRunConfigs");
            fx.controller.openAndNavigate(work.resolve("README.md"), 0); // the active tab is not runnable
        });
        settle(800);
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void consoleStaysOpenAndAvailableAfterTheRunExits() throws Exception {
        runAndAwaitExit("Slow");

        assertConsoleShowing("result: 42");

        // The older gate: a tab switch re-evaluates availability with no process alive.
        FxTestSupport.runOnFx(() -> registry.run("file.new"));
        settle(300);
        assertConsoleShowing("result: 42");
    }

    @Test
    void anInstantlyExitingProgramStillShowsItsConsole() throws Exception {
        runAndAwaitExit("Instant");
        assertConsoleShowing("instant-output");
    }

    @Test
    void aFailedBeforeLaunchStepLeavesItsErrorsOnScreen() throws Exception {
        runAndAwaitExit("FailBuild");
        assertConsoleShowing("BUILD FAILURE");
    }

    private static void assertConsoleShowing(String output) throws Exception {
        assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(runWindow)), "Run window still open");
        assertFalse(
                FxTestSupport.callOnFx(() -> FxTestSupport.<Set<ToolWindow>>field(toolWindows, "unavailable")
                        .contains(runWindow)),
                "still available");
        assertTrue(
                FxTestSupport.callOnFx(() -> {
                    Button b = FxTestSupport.<Map<ToolWindow, Button>>field(toolWindows, "stripeButtons")
                            .get(runWindow);
                    return b != null && b.getParent() != null;
                }),
                "stripe button still shown");
        String text =
                FxTestSupport.callOnFx(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(run.panel(), "output")
                        .getText());
        assertTrue(text.contains(output), "console holds the output: " + text);
        assertEquals(false, FxTestSupport.callOnFx(run::isRunning));
    }

    private static void runAndAwaitExit(String name) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(RunConfiguration.commandIdFor(name)));
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            Thread.sleep(40);
            boolean done = FxTestSupport.callOnFx(() -> {
                Button stop = FxTestSupport.field(run.panel(), "stopButton");
                return stop.isDisable() && !run.isRunning();
            });
            String text = FxTestSupport.callOnFx(
                    () -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(run.panel(), "output")
                            .getText());
            if (done && !text.isEmpty()) {
                break;
            }
            assertTrue(System.nanoTime() < deadline, "run never finished: " + name);
        }
        settle(500);
    }

    private static RunConfiguration config(String name, Path target, String beforeLaunch) {
        return new RunConfiguration(
                name, "shell", target.toString(), "", "", "", "", work.toString(), "", beforeLaunch);
    }

    private static Path script(String name, String body) throws Exception {
        Path p = Files.writeString(work.resolve(name), "#!/bin/sh\n" + body);
        p.toFile().setExecutable(true);
        return p;
    }

    private static void settle(long ms) throws Exception {
        Thread.sleep(ms);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }
}

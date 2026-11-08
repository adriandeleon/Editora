package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the installed JDT LS and java-debug adapter, in a real window: a caught exception opens the
 * exception card under the line that threw, saying it is caught and offering to stop stopping on caught ones; that
 * button turns the caught stops off, and the uncaught exception after it opens the card as uncaught. Run it with
 * {@code ./mvnw test -Dtest=ExceptionCardProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class ExceptionCardProbeFxTest {

    private static final String SOURCE = """
            public class Main {
                public static void main(String[] args) throws Exception {
                    try {
                        check(-1);
                    } catch (IllegalArgumentException e) {
                        System.out.println("caught " + e.getMessage());
                    }
                    Thread.sleep(200);
                    check(-2);
                }
                static void check(int n) {
                    if (n < 0) {
                        throw new IllegalArgumentException("negative: " + n);
                    }
                }
            }
            """;

    private static final int THROW_LINE = 12;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void caughtThenUncaughtExceptionsOpenTheCard(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", Path.of(System.getProperty("user.home")))
                .orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null);
        Path file = Files.createDirectory(dir.resolve("loose")).resolve("Main.java");
        Files.writeString(file, SOURCE);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setLspSupport(true);
        seed.getSettings().setJavaLspEnabled(true);
        seed.getSettings().setJavaLspCommand(jdtls.toString());
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setJavaDebugPluginPath(plugin.toString());
        assertTrue(seed.saveSettings());
        seed.shutdown();

        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Java debugging available", 60, () -> call(debug::debugEffective));
            FxTestSupport.runOnFx(() -> {
                if (!debug.exceptionToggleOn(true)) {
                    debug.toggleExceptionKind(true);
                }
                if (!debug.exceptionToggleOn(false)) {
                    debug.toggleExceptionKind(false);
                }
            });

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(dap, THROW_LINE, debug);
            awaitCard(debug, "negative: -1");
            DapModels.ExceptionInfo caught = call(() -> debug.exceptionCard().shown());
            assertEquals("java.lang.IllegalArgumentException", caught.type());
            assertTrue(caught.caught(), "java-debug says always for a caught exception");
            assertEquals(tr("debug.exceptionCard.caught"), title(debug));
            assertEquals("negative: -1", DebugExceptionCard.message(caught));

            // Don't Stop on Caught Exceptions: the card closes and the caught stops are off.
            FxTestSupport.runOnFx(() -> caughtOff(debug).fire());
            await("the card closed", 10, () -> !call(() -> debug.exceptionCard().isShowing()));
            assertFalse(call(() -> debug.exceptionToggleOn(true)));

            FxTestSupport.runOnFx(dap::resume);
            awaitCard(debug, "negative: -2"); // the uncaught one, on the same line
            DapModels.ExceptionInfo uncaught = call(() -> debug.exceptionCard().shown());
            assertTrue(uncaught.uncaught());
            assertEquals(tr("debug.exceptionCard.uncaught"), title(debug));
            assertFalse(call(() -> caughtOff(debug).isVisible()), "nothing to turn off for an uncaught one");

            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 20, () -> !call(dap::isActive));
            assertFalse(call(() -> debug.exceptionCard().isShowing()), "the session's end closes it");
        } finally {
            fx.dispose();
        }
    }

    private static void awaitCard(DebugCoordinator debug, String message) throws InterruptedException {
        try {
            await(
                    "the card for " + message,
                    60,
                    () -> call(() -> {
                        DebugExceptionCard card = debug.exceptionCard();
                        return card != null
                                && card.isShowing()
                                && card.shown() != null
                                && message.equals(DebugExceptionCard.message(card.shown()));
                    }));
        } catch (AssertionError e) {
            String state = call(() -> {
                DebugExceptionCard card = debug.exceptionCard();
                return card == null ? "no card" : "showing=" + card.isShowing() + " shown=" + card.shown();
            });
            throw new AssertionError(e.getMessage() + " (" + state + ")", e);
        }
    }

    private static String title(DebugCoordinator debug) {
        return call(() -> ((Label) debug.exceptionCard().card().lookup(".debug-exception-title")).getText());
    }

    private static Button caughtOff(DebugCoordinator debug) {
        for (Node n : debug.exceptionCard().card().lookupAll(".button")) {
            if (n instanceof Button b && tr("debug.exceptionCard.caughtOff").equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("no caught-exceptions button");
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

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private static void awaitStop(DapManager dap, int line, DebugCoordinator debug) throws InterruptedException {
        await(
                "a stop on line " + (line + 1),
                90,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().get(0).line() == line));
    }
}

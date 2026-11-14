package com.editora.ui;

import javafx.scene.control.Label;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each find bar that is not the editor's has a command of its own: it opens the bar, and run again while the
 * bar is open it steps to the next match.
 */
@Tag("fx")
class FindBarCommandsFxTest {

    private static FxWindowFixture fx;

    @BeforeAll
    static void window() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    static void close() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static String echo() {
        StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.<Label>field(statusBar, "echo").getText();
    }

    /** Runs {@code command} twice over {@code console}: the bar opens on the first match, then steps to the second. */
    private static void opensThenSteps(String command, CodeArea console, ConsoleFindBar bar) {
        MainController c = fx.controller;
        console.replaceText("alpha one\nalpha two\n");
        console.moveTo(0);
        assertFalse(bar.isShowing(), command);
        assertTrue(c.registry.run(command));
        assertTrue(bar.isShowing(), command + " opens its bar");
        bar.field().setText("alpha");
        assertEquals(tr("find.count", 1, "2"), bar.countText(), command);
        assertTrue(c.registry.run(command));
        assertEquals(tr("find.count", 2, "2"), bar.countText(), command + " again steps to the next match");
        bar.close();
    }

    @Test
    void theRunExternalToolsDebugAndTestConsolesEachHaveAFindCommand() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            RunPanel run = c.runCoordinator.panel();
            opensThenSteps("run.findInOutput", FxTestSupport.field(run, "output"), FxTestSupport.field(run, "findBar"));

            ExternalToolPanel tools = c.externalToolCoordinator.panel();
            opensThenSteps(
                    "externalTool.findInOutput",
                    FxTestSupport.field(tools, "output"),
                    FxTestSupport.field(tools, "findBar"));

            TestRunnerPanel tests = c.testRunCoordinator.panel();
            opensThenSteps(
                    "test.findInOutput", FxTestSupport.field(tests, "detail"), FxTestSupport.field(tests, "findBar"));

            c.config.getSettings().setDebugSupport(true);
            try {
                DebugPanel debug = c.debugCoordinator.panel();
                opensThenSteps(
                        "debug.findInConsole",
                        FxTestSupport.field(debug, "console"),
                        FxTestSupport.field(debug, "consoleFindBar"));
            } finally {
                c.config.getSettings().setDebugSupport(false);
            }
        });
    }

    @Test
    void theOutputCommandSaysWhenThereIsNoConsoleAndTheLogCommandOpensTheLogWithItsBar() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            assertTrue(c.registry.run("buildOutput.find"));
            assertEquals(tr("status.output.noConsole"), echo(), "nothing has run yet: there is no console to search");

            assertTrue(c.registry.run("debugLog.find"));
            DebugLogWindow log = FxTestSupport.field(c, "debugLogWindow");
            javafx.stage.Stage stage = FxTestSupport.field(log, "stage");
            try {
                assertTrue(stage.isShowing(), "the command shows the log");
                ConsoleFindBar bar = FxTestSupport.field(log, "findBar");
                assertTrue(bar.isShowing(), "with its find bar open");
            } finally {
                stage.hide();
            }
        });
    }
}

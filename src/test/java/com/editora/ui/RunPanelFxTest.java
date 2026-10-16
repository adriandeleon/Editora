package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-FX coverage of {@link RunPanel}'s console lifecycle: started → appendOutput (stdout/stderr) →
 * finished/failed, plus clearConsole and idle, and the Stop-button enablement that tracks run state.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunPanelFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private RunPanel panel() throws Exception {
        return FxTestSupport.callOnFx(() -> new RunPanel(() -> {}));
    }

    private static String output(RunPanel p) throws Exception {
        CodeArea out = FxTestSupport.field(p, "output");
        return FxTestSupport.callOnFx(out::getText);
    }

    private static boolean stopDisabled(RunPanel p) throws Exception {
        Button stop = FxTestSupport.field(p, "stopButton");
        return FxTestSupport.callOnFx(stop::isDisable);
    }

    private static boolean spinnerShown(RunPanel p) throws Exception {
        ProgressIndicator busy = FxTestSupport.field(p, "busy");
        return FxTestSupport.callOnFx(() -> busy.isVisible() && busy.isManaged() && busy.isIndeterminate());
    }

    @Test
    void spinnerShowsOnlyWhileARunIsAlive() throws Exception {
        RunPanel p = panel();
        assertFalse(spinnerShown(p), "no spinner before any run");

        FxTestSupport.runOnFx(() -> p.started("python long_script.py"));
        assertTrue(spinnerShown(p), "spinner while the program runs");
        assertTrue(FxTestSupport.callOnFx(() -> p.runningProperty().get()), "the stripe badge's source follows it");
        FxTestSupport.runOnFx(() -> p.appendOutput("still going", false));
        assertTrue(spinnerShown(p), "output does not stop the spinner");

        FxTestSupport.runOnFx(() -> p.finished(0));
        assertFalse(spinnerShown(p), "spinner gone after exit");
        assertFalse(FxTestSupport.callOnFx(() -> p.runningProperty().get()), "not running after exit");

        FxTestSupport.runOnFx(() -> p.started("python again.py"));
        FxTestSupport.runOnFx(() -> p.finished(-1));
        assertFalse(spinnerShown(p), "spinner gone after Stop");

        FxTestSupport.runOnFx(() -> p.started("missing-binary"));
        FxTestSupport.runOnFx(() -> p.failed("not found"));
        assertFalse(spinnerShown(p), "spinner gone after a launch failure");

        FxTestSupport.runOnFx(() -> p.started("python app.py"));
        FxTestSupport.runOnFx(p::idle);
        assertFalse(spinnerShown(p), "spinner gone when reset to idle");
    }

    @Test
    void runLifecycleStreamsOutputAndTogglesStop() throws Exception {
        RunPanel p = panel();

        FxTestSupport.runOnFx(() -> p.started("java Main.java"));
        assertFalse(stopDisabled(p), "Stop enabled while running");
        assertTrue(output(p).isEmpty(), "console cleared at start");

        FxTestSupport.runOnFx(() -> {
            p.appendOutput("hello stdout", false);
            p.appendOutput("oops stderr", true);
        });
        String text = output(p);
        assertTrue(text.contains("hello stdout") && text.contains("oops stderr"), "both streams appended");

        FxTestSupport.runOnFx(() -> p.finished(0));
        assertTrue(stopDisabled(p), "Stop disabled after the process exits");
        assertTrue(output(p).contains("hello stdout"), "output retained after finish");
    }

    @Test
    void clearAndFailureAndIdleStates() throws Exception {
        RunPanel p = panel();
        FxTestSupport.runOnFx(() -> {
            p.started("python app.py");
            p.appendOutput("line", false);
        });
        FxTestSupport.runOnFx(p::clearConsole);
        assertTrue(output(p).isEmpty(), "clearConsole empties the console");

        FxTestSupport.runOnFx(() -> p.failed("java not found"));
        assertTrue(stopDisabled(p), "Stop disabled after a launch failure");

        FxTestSupport.runOnFx(p::idle);
        assertTrue(stopDisabled(p), "Stop disabled when idle");
    }

    @Test
    void longCommandCannotCollapseActionButtonsToEllipses() throws Exception {
        RunPanel p = panel();
        Button clear = FxTestSupport.field(p, "clearButton");
        Button stop = FxTestSupport.field(p, "stopButton");

        FxTestSupport.runOnFx(() -> {
            p.started("java -cp " + "dependency.jar:".repeat(300) + " App");
            new Scene(p, 640, 240);
            p.applyCss();
            p.layout();
        });

        assertTrue(
                FxTestSupport.callOnFx(clear::getWidth) + 0.5 >= FxTestSupport.callOnFx(() -> clear.prefWidth(-1)),
                "Clear must retain enough width to render its full label");
        assertTrue(
                FxTestSupport.callOnFx(stop::getWidth) + 0.5 >= FxTestSupport.callOnFx(() -> stop.prefWidth(-1)),
                "Stop must retain enough width to render its full label");
    }
}

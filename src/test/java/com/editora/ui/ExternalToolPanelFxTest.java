package com.editora.ui;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-FX coverage of {@link ExternalToolPanel#show}: the one-shot console renders the command header,
 * stdout, and stderr; the status line reflects success vs a non-zero exit; clearConsole empties it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalToolPanelFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private ExternalToolPanel panel() throws Exception {
        return FxTestSupport.callOnFx(ExternalToolPanel::new);
    }

    private static String output(ExternalToolPanel p) throws Exception {
        CodeArea out = FxTestSupport.field(p, "output");
        return FxTestSupport.callOnFx(out::getText);
    }

    private static String status(ExternalToolPanel p) throws Exception {
        javafx.scene.control.Label s = FxTestSupport.field(p, "status");
        return FxTestSupport.callOnFx(s::getText);
    }

    @Test
    void showRendersCommandStdoutAndStderr() throws Exception {
        ExternalToolPanel p = panel();
        FxTestSupport.runOnFx(
                () -> p.show("prettier", "prettier --write x.js", "formatted ok\n", "warning: tabs\n", 0));
        String text = output(p);
        assertTrue(text.contains("$ prettier --write x.js"), "command header");
        assertTrue(text.contains("formatted ok"), "stdout");
        assertTrue(text.contains("warning: tabs"), "stderr");
        assertFalse(status(p).isBlank(), "status line populated on success");
    }

    @Test
    void nonZeroExitIsReflectedAndClearEmpties() throws Exception {
        ExternalToolPanel p = panel();
        FxTestSupport.runOnFx(() -> p.show("lint", "lint x", "", "boom\n", 2));
        assertTrue(output(p).contains("boom"), "stderr shown for a failing tool");
        String failedStatus = status(p);

        FxTestSupport.runOnFx(p::clearConsole);
        assertTrue(output(p).isEmpty(), "clearConsole empties the console");
        assertFalse(status(p).equals(failedStatus), "status reset to idle after clear");
    }
}

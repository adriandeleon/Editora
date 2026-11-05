package com.editora.ui;

import java.lang.reflect.Proxy;

import javafx.scene.control.Label;

import com.editora.dap.DapManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review E107: the Debug console says when it has dropped its oldest output, as the Run console does. */
@Tag("fx")
class DebugConsoleTrimFxTest {

    @BeforeAll
    static void initFx() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static DebugPanel.Actions noop() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    @Test
    void theStatusSaysWhenEarlierOutputWasDroppedUntilTheConsoleIsCleared() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(noop());
            panel.setState(DapManager.State.RUNNING);
            Label status = FxTestSupport.field(panel, "status");
            String note = tr("run.outputTrimmed", 200_000);
            String line = "x".repeat(99) + "\n";
            for (int i = 0; i < 1_000; i++) {
                panel.appendOutput(line, "stdout");
            }
            assertFalse(status.getText().contains(note), "under the cap: nothing dropped yet");
            for (int i = 0; i < 2_000; i++) {
                panel.appendOutput(line, "stdout");
            }
            assertTrue(status.getText().contains(note), status.getText());
            panel.setState(DapManager.State.SUSPENDED);
            assertTrue(status.getText().contains(note), "kept across state changes: " + status.getText());
            panel.clearConsole();
            assertFalse(status.getText().contains(note), "a cleared console has dropped nothing: " + status.getText());
        });
    }
}

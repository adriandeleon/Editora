package com.editora.ui;

import javafx.scene.control.ComboBox;
import javafx.stage.Stage;

import com.editora.dap.DapManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings → Debugging → Evaluating expressions → Time limit: it shows the limit kept (10 s by default), a choice is
 * saved and reaches the debugger's next session, Off is each debugger's own, and a limit set in settings.json is shown
 * as it is. {@code EvaluationTimeLimitProbeFxTest} drives GDB and debugpy.
 */
@Tag("fx")
class SettingsEvaluationTimeLimitFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theTimeLimitRowSavesTheChoiceAndTellsTheDebugger() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
                window.show(new Stage());
                Stage stage = FxTestSupport.field(window, "stage");
                try {
                    SettingsDebugPage page = FxTestSupport.field(window, "debugPage");
                    ComboBox<Integer> combo = FxTestSupport.field(page, "debugEvaluationLimitCombo");
                    DapManager dap = FxTestSupport.field(fx.controller, "dapManager");
                    assertEquals(10, combo.getValue(), "10 s by default");
                    assertEquals(10, limitOf(dap));
                    assertEquals(
                            tr("settings.debug.evaluationTimeLimit.seconds", 10),
                            combo.getConverter().toString(10));
                    assertEquals(
                            tr("settings.debug.evaluationTimeLimit.off"),
                            combo.getConverter().toString(0));

                    combo.setValue(30);
                    assertEquals(30, fx.shared.getSettings().getDebugEvaluationTimeLimit(), "saved");
                    assertEquals(30, limitOf(dap), "the next session's debugger is told");
                    combo.setValue(0);
                    assertEquals(0, fx.shared.getSettings().getDebugEvaluationTimeLimit());
                    assertEquals(0, limitOf(dap), "off: each debugger's own");

                    fx.shared.getSettings().setDebugEvaluationTimeLimit(45); // as settings.json may say
                    page.load(fx.shared.getSettings());
                    assertEquals(45, combo.getValue());
                    assertTrue(combo.getItems().contains(45), "a limit set by hand is shown as it is");
                } finally {
                    stage.hide();
                }
            });
        }
    }

    private static int limitOf(DapManager dap) {
        Object shared = FxTestSupport.field(dap, "shared");
        return FxTestSupport.field(shared, "evaluationTimeLimit");
    }
}

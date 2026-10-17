package com.editora.ui;

import javafx.event.ActionEvent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings → Git in a real window: a wrong command path, and the rows that need Git switched on. */
@Tag("fx")
class SettingsGitPageFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    /**
     * A mistyped git path said "install git" and locked the Enable Git switch in the On position; now the pill
     * names the path, and the switch can still be turned off.
     */
    @Test
    void aWrongGitPathIsNamedAndTheSwitchStaysUsable() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = fx.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> w.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
            TextField path = FxTestSupport.field(w, "gitPathField");
            Label status = FxTestSupport.field(w, "gitStatusLabel");
            FxTestSupport.runOnFx(() -> {
                path.setText("/nonexistent/editora-test/git");
                path.fireEvent(new ActionEvent());
            });
            String expected = tr("settings.git.notFoundAt", "/nonexistent/editora-test/git");
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!expected.equals(FxTestSupport.callOnFx(status::getText)) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(expected, FxTestSupport.callOnFx(status::getText));
            CheckBox git = FxTestSupport.field(w, "gitCheck");
            assertFalse(git.isDisabled(), "the switch can still be turned off");
            FxTestSupport.runOnFx(() -> path.setText("")); // leave the fixture's real git alone
        }
    }

    /** Blame, pull mode and auto-fetch used to stay live with Git off; only blame was greyed. */
    @Test
    void theRowsThatNeedGitGreyOutWhileItIsOff() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow w = fx.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> {
                w.show(FxTestSupport.<Stage>field(fx.controller, "stage"));
                CheckBox git = FxTestSupport.field(w, "gitCheck");
                ComboBox<?> pull = FxTestSupport.field(w, "gitPullModeCombo");
                java.util.Map<?, javafx.scene.layout.Region> pages = FxTestSupport.field(w, "pages");
                javafx.scene.layout.Region page = pages.entrySet().stream()
                        .filter(e -> e.getKey().toString().equals("GIT"))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
                // The page's switches, in order: Enable Git, blame, fetch automatically.
                var switches = SettingsRemoteSitesFxTest.all(page, SettingSwitch.class, new java.util.ArrayList<>());
                assertEquals(3, switches.size());
                Spinner<?> minutes = FxTestSupport.field(w, "gitAutoFetchSpinner");
                git.setSelected(true);
                assertFalse(pull.isDisabled(), "pull mode on");
                assertFalse(switches.get(1).isDisabled() || switches.get(2).isDisabled(), "blame, fetch on");
                git.setSelected(false);
                assertTrue(pull.isDisabled(), "pull mode");
                assertTrue(switches.get(1).isDisabled(), "blame");
                assertTrue(switches.get(2).isDisabled(), "fetch automatically");
                assertTrue(minutes.isDisabled(), "minutes");
                assertFalse(switches.get(0).isDisabled(), "Enable Git itself");
                assertFalse(FxTestSupport.<TextField>field(w, "gitPathField").isDisabled(), "the path stays editable");
                git.setSelected(true);
            });
        }
    }
}

package com.editora.ui;

import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Settings row's state: the modified marker, the scope note and the "Reset to default" link. */
@Tag("fx")
class SettingRowStateFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aModifiedRowIsMarkedAnnouncedAndOffersItsReset() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CheckBox check = new CheckBox("Show minimap");
            SettingSwitch sw = SettingSwitch.boundTo(check);
            SettingRowPane row = (SettingRowPane) SettingsWindow.settingRow("Show minimap", "Desc", sw);
            assertNull(SettingRowState.peek(row), "no state until something marks the row");
            SettingRowState state = SettingRowState.of(row);
            assertSame(state, SettingRowState.of(row));
            int[] resets = {0};
            state.setResetAction(() -> resets[0]++);

            state.setModified(true);
            assertTrue(row.getPseudoClassStates().contains(SettingRowState.MODIFIED), "the marker shows");
            assertEquals(
                    Messages.tr("settings.row.modified"), sw.getAccessibleHelp().split("\\. ")[0]);
            assertTrue(state.resetLink().isVisible(), "the reset link shows while modified");
            assertTrue(((VBox) row.text())
                    .getChildren().stream()
                            .anyMatch(n -> n.isVisible() && n.getStyleClass().contains("settings-row-state")));
            state.resetLink().fire();
            assertEquals(1, resets[0]);

            state.setModified(false);
            assertFalse(row.getPseudoClassStates().contains(SettingRowState.MODIFIED));
            assertFalse(state.resetLink().isVisible());
            assertEquals("Desc", sw.getAccessibleHelp(), "the switch's own help is back");

            state.setScopeNote("Project");
            assertEquals("Project", state.scopeNote());
            state.setScopeNote(null);
            assertEquals("", state.scopeNote());
        });
    }
}

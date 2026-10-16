package com.editora.ui;

import javafx.scene.control.ColorPicker;
import javafx.scene.paint.Color;

import com.editora.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the General and Editor pages of Settings show and keep (Appearance, Interface, Toolbar, Workspace, Tool
 * Windows, Editor, Code Completion, TODO, Spell Check, Search), checked in a real Settings window.
 */
@Tag("fx")
class SettingsContentPagesFxTest {

    private SettingsRig rig;
    private Settings settings;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        rig = SettingsRig.create();
        settings = rig.settings;
    }

    @AfterEach
    void tearDown() throws Exception {
        rig.close();
    }

    private ColorPicker partPicker(String field) {
        return FxTestSupport.field(rig.<Object>control("todoSettings"), field);
    }

    @Test
    void theTodoPartColorPickersShowTheStoredColorsAfterAResetAndAfterAChangeMadeElsewhere() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.page("TODO");
            ColorPicker tag = partPicker("tagPicker");
            tag.setValue(Color.RED);
            assertEquals("#FF0000", settings.getTodoTagColor());

            // Reset to Defaults goes through commitReset → load(): the picker must follow the stored default.
            Settings.resetToDefaults(settings);
            FxTestSupport.call(rig.window, "commitReset", new Class<?>[] {});
            assertEquals(TodoSettingsPane.parseColor(settings.getTodoTagColor()), tag.getValue());

            // Changed while the window was closed (a palette command, another window): shown on reopen.
            rig.stage().hide();
            settings.setTodoPriorityHighColor("#00FF00");
            rig.show();
            assertEquals(Color.web("#00FF00"), partPicker("highPicker").getValue());
            assertEquals("#00FF00", settings.getTodoPriorityHighColor(), "showing the stored color is not an edit");
        });
    }
}

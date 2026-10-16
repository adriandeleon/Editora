package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.config.Settings;
import com.editora.toolbar.ToolbarCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A toolbar backend that keeps what it is given. */
    private static final class Toolbar implements SettingsWindow.ToolbarActions {
        List<String> layout;

        Toolbar(List<String> layout) {
            this.layout = new ArrayList<>(layout);
        }

        @Override
        public List<String> current() {
            return List.copyOf(layout);
        }

        @Override
        public void apply(List<String> next) {
            layout = new ArrayList<>(next);
        }

        @Override
        public void restoreDefault() {}
    }

    private static KeyEvent pressed(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    @Test
    void theToolbarEditorWorksFromTheKeyboardAndOnlyOffersButtonsThatDoSomething() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> ids =
                    ToolbarCatalog.items().stream().map(ToolbarCatalog.Item::id).toList();
            Toolbar toolbar = new Toolbar(List.of(ids.get(0), ToolbarCatalog.SEPARATOR, ids.get(1)));
            rig.window.setToolbarActions(toolbar);
            rig.show();
            Region page = rig.page("TOOLBAR");
            Object pane = rig.control("toolbarSettings");
            ListView<String> available = FxTestSupport.field(pane, "available");
            ListView<String> current = FxTestSupport.field(pane, "current");
            Button add = (Button) SettingsRig.button(page, tr("settings.toolbar.add"));
            Button remove = (Button) SettingsRig.button(page, tr("settings.toolbar.remove"));
            Button separator = (Button) SettingsRig.button(page, tr("settings.toolbar.addSeparator"));

            assertTrue(add.isDisabled(), "nothing chosen to add");
            assertTrue(remove.isDisabled(), "nothing chosen to remove");

            // Enter on an available item adds it after the selected bar item, and the new item is selected.
            current.getSelectionModel().select(2);
            String second = available.getItems().get(1);
            available.getSelectionModel().select(0);
            String first = available.getSelectionModel().getSelectedItem();
            available.fireEvent(pressed(KeyCode.ENTER));
            assertEquals(List.of(ids.get(0), ToolbarCatalog.SEPARATOR, ids.get(1), first), toolbar.layout);
            assertEquals(first, current.getSelectionModel().getSelectedItem(), "the added item is selected");
            assertEquals(second, available.getSelectionModel().getSelectedItem(), "Add can be pressed again");

            // A separator beside a separator would be dropped by the bar: the button says so by being disabled.
            current.getSelectionModel().select(1);
            assertTrue(separator.isDisabled());
            current.getSelectionModel().select(0);
            assertTrue(separator.isDisabled(), "the next item is already a separator");
            current.getSelectionModel().select(3);
            assertFalse(separator.isDisabled());

            // Delete on a bar item removes it.
            current.getSelectionModel().select(3);
            current.fireEvent(pressed(KeyCode.DELETE));
            assertEquals(List.of(ids.get(0), ToolbarCatalog.SEPARATOR, ids.get(1)), toolbar.layout);
            assertTrue(available.getItems().contains(first));
        });
    }

    @Test
    void theToolbarPageHasTheShowToolbarSwitch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("TOOLBAR");
            SettingSwitch show = SettingsRig.all(page, SettingSwitch.class).getFirst();
            assertEquals(tr("settings.showToolbar"), show.getAccessibleText());
            boolean was = settings.isShowToolbar();
            show.setSelected(!was);
            assertEquals(!was, settings.isShowToolbar());
        });
    }
}

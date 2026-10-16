package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.editora.config.Settings;
import com.editora.todo.TodoPattern;
import com.editora.todo.TodoPatterns;
import com.editora.todo.TodoScanner;
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

    private static boolean flagged(TextField field) {
        return field.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER);
    }

    @Test
    void rulerTextThatCannotBeReadIsFlaggedAndKeptUnsavedInsteadOfQuietlyReverted() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.page("EDITOR");
            Map<String, javafx.scene.control.Control> controls = rig.control("settingControls");
            TextField rulers = (TextField) controls.get("rulers");
            List<Integer> before = List.copyOf(settings.getRulers());

            SettingsRig.typeAndEnter(rulers, "80; 120");
            assertEquals(before, settings.getRulers(), "nothing saved");
            assertEquals("80; 120", rulers.getText(), "the typed text stays to be fixed");
            assertTrue(flagged(rulers));
            assertTrue(
                    rulers.getTooltip().getText().contains(";"),
                    rulers.getTooltip().getText());

            SettingsRig.typeAndEnter(rulers, "80, 120");
            assertEquals(List.of(80, 120), settings.getRulers());
            assertFalse(flagged(rulers));
        });
    }

    @Test
    void aLanguageRulerNameNoFileHasIsRefusedAndAnyCaseOfAKnownOneIsKeptLowerCased() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.page("EDITOR");
            Map<String, javafx.scene.control.Control> controls = rig.control("settingControls");
            TextField perLanguage = (TextField) controls.get("languageRulers");

            SettingsRig.typeAndEnter(perLanguage, "java: 100; bash: 61");
            assertEquals(Map.of(), settings.getLanguageRulers(), "nothing saved");
            assertTrue(flagged(perLanguage));
            assertTrue(perLanguage.getTooltip().getText().contains("bash"));

            SettingsRig.typeAndEnter(perLanguage, "Java: 100; Python: 79");
            assertEquals(Map.of("java", List.of(100), "python", List.of(79)), settings.getLanguageRulers());
            assertFalse(flagged(perLanguage));
            assertEquals("java: 100; python: 79", perLanguage.getText());
        });
    }

    private HBox todoRow(int i) {
        VBox rows = FxTestSupport.field(rig.<Object>control("todoSettings"), "patternsBox");
        return (HBox) rows.getChildren().get(i);
    }

    @Test
    void anAddedTodoPatternMatchesNothingUntilNamedAndThenMatchesItsName() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setTodoPatterns(new ArrayList<>(List.of(new TodoPattern("TODO", "\\bTODO\\b", "", true, true))));
            rig.show();
            Region page = rig.page("TODO");
            SettingsRig.button(page, tr("settings.todo.add")).fire();
            String line = "// TODO fix and BUG here";
            // Every TODO used to be matched twice: once by TODO and once by the new row's \\bTODO\\b.
            assertEquals(
                    1,
                    TodoScanner.scan(line, TodoPatterns.compile(settings.getTodoPatterns()))
                            .size());

            TextField name = (TextField) todoRow(1).getChildren().get(1);
            TextField regex = (TextField) todoRow(1).getChildren().get(2);
            SettingsRig.typeAndEnter(name, "BUG");
            assertEquals("\\bBUG\\b", regex.getText(), "the regex follows the name it was empty for");
            assertEquals("\\bBUG\\b", settings.getTodoPatterns().get(1).getPattern());
            assertEquals(
                    2,
                    TodoScanner.scan(line, TodoPatterns.compile(settings.getTodoPatterns()))
                            .size());

            SettingsRig.typeAndEnter(name, "FIX");
            assertEquals("\\bFIX\\b", settings.getTodoPatterns().get(1).getPattern(), "still its own default");
            SettingsRig.typeAndEnter(regex, "\\bFIX(ME)?\\b");
            SettingsRig.typeAndEnter(name, "FIXES");
            assertEquals("\\bFIX(ME)?\\b", settings.getTodoPatterns().get(1).getPattern(), "a hand-written one stays");
        });
    }

    @Test
    void aTodoPatternWithNoNameOrNothingToMatchIsFlaggedAndItsDeleteIsConfirmed() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setTodoPatterns(new ArrayList<>(List.of(new TodoPattern("HACK", "\\bHACK\\b", "", true, true))));
            rig.show();
            Region page = rig.page("TODO");
            TextField name = (TextField) todoRow(0).getChildren().get(1);
            TextField regex = (TextField) todoRow(0).getChildren().get(2);
            CheckBox matchCase = (CheckBox) todoRow(0).getChildren().get(4);
            assertEquals(tr("settings.todo.caseTip"), matchCase.getTooltip().getText(), "\"Aa\" says what it is");

            SettingsRig.typeAndEnter(name, "  ");
            assertEquals("HACK", settings.getTodoPatterns().get(0).getName(), "a blank name is not stored");
            assertTrue(flagged(name));
            SettingsRig.typeAndEnter(name, "HACK");
            assertFalse(flagged(name));
            SettingsRig.typeAndEnter(regex, "");
            assertEquals("\\bHACK\\b", settings.getTodoPatterns().get(0).getPattern());
            assertEquals("\\bHACK\\b", regex.getText(), "an emptied regex goes back to the name's own");

            Button remove = (Button) todoRow(0).getChildren().get(5);
            List<SettingsRig.Shown> asked = SettingsRig.answering(ButtonBar.ButtonData.CANCEL_CLOSE, remove::fire);
            assertEquals(1, asked.size());
            assertTrue(asked.get(0).content().contains("HACK"), asked.get(0).content());
            assertEquals(1, settings.getTodoPatterns().size(), "Cancel keeps it");
            SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, remove::fire);
            assertEquals(List.of(), settings.getTodoPatterns());
            SettingsRig.button(page, tr("settings.todo.add")).fire();
        });
    }
}

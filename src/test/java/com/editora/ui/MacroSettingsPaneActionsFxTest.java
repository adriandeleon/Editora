package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.command.KeybindingEdits;
import com.editora.config.ConfigManager;
import com.editora.macro.Macro;
import com.editora.macro.MacroService;
import com.editora.macro.MacroStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings → Macros with a keymap behind it: choosing the command of a step, reordering steps, the key-binding
 * row, and deleting a macro.
 */
@Tag("fx")
class MacroSettingsPaneActionsFxTest {

    @TempDir
    Path dir;

    private Stage stage;
    private ConfigManager config;
    private MacroSettingsPane pane;
    private final Map<String, String> chords = new LinkedHashMap<>();
    private final List<String> log = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
        if (config != null) {
            config.shared().shutdown();
        }
    }

    private static final Macro SAMPLE = new Macro(
            "sample",
            "Sample",
            List.of(MacroStep.command("edit.copy"), MacroStep.text("typed"), MacroStep.command("gone.command")));

    /** To be called on the FX thread. */
    private void open(Macro... macros) {
        config = new ConfigManager(dir);
        for (Macro m : macros) {
            config.getMacroStore().put(m);
        }
        chords.put("edit.copy", "C-c");
        chords.put("edit.paste", "C-v");
        chords.put("file.save", "C-s");
        chords.put("view.zoomIn", null);
        pane = new MacroSettingsPane(new MacroSettingsPane.Host() {
            @Override
            public ConfigManager config() {
                return config;
            }

            @Override
            public SettingsWindow.ShortcutActions shortcuts() {
                return new SettingsWindow.ShortcutActions() {
                    @Override
                    public List<SettingsWindow.Shortcut> rows() {
                        List<SettingsWindow.Shortcut> out = new ArrayList<>();
                        chords.forEach(
                                (id, chord) -> out.add(new SettingsWindow.Shortcut(id, "Title of " + id, chord)));
                        return out;
                    }

                    @Override
                    public List<KeybindingEdits.Conflict> conflicts(String chordSeq, String commandId) {
                        return List.of();
                    }

                    @Override
                    public void rebind(String commandId, String chordSeq) {
                        chords.put(commandId, chordSeq);
                    }

                    @Override
                    public void reset(String commandId) {
                        log.add("reset " + commandId);
                        chords.remove(commandId);
                    }

                    @Override
                    public void resetAll() {}
                };
            }

            @Override
            public void macrosChanged() {
                log.add("macrosChanged");
            }

            @Override
            public boolean rebind(String commandId, String chord) {
                log.add("rebind " + commandId + " " + chord);
                chords.put(commandId, chord);
                return true;
            }

            @Override
            public void shortcutsChanged() {
                log.add("shortcutsChanged");
            }
        });
        stage = new Stage();
        stage.setScene(new Scene(pane, 900, 620));
        stage.show();
        pane.applyCss();
        pane.layout();
    }

    private Button button(String text) {
        return (Button) SettingsRig.button(pane, text);
    }

    private javafx.scene.layout.Pane keybindingRow() {
        return FxTestSupport.field(pane, "keybinding");
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    @SuppressWarnings("unchecked")
    private static ListView<SettingsWindow.Shortcut> commandList(DialogPane dialog) {
        return (ListView<SettingsWindow.Shortcut>)
                SettingsRig.all(dialog, ListView.class).get(0);
    }

    @Test
    void aCommandStepIsChosenFromTheCommandsThatExist() throws Exception {
        FxTestSupport.runOnFx(() -> {
            open(SAMPLE);
            assertEquals(3, pane.steps().size());

            SettingsRig.inDialog(() -> button(tr("settings.macro.addCommand")).fire(), dialog -> {
                ListView<SettingsWindow.Shortcut> commands = commandList(dialog);
                TextField filter = SettingsRig.all(dialog, TextField.class).get(0);
                Button ok = (Button) dialog.lookupButton(ButtonType.OK);
                assertEquals(4, commands.getItems().size());
                assertTrue(ok.isDisable(), "nothing chosen yet");

                filter.setText("  PASTE ");
                assertEquals(1, commands.getItems().size(), "filtered by title or id, whatever the case");
                assertEquals(
                        "edit.paste",
                        commands.getSelectionModel().getSelectedItem().id());
                filter.setText("no command is called this");
                assertEquals(0, commands.getItems().size());
                filter.setText("");
                assertEquals(4, commands.getItems().size());

                // The arrow keys move the selection from the filter field, and stop at either end.
                commands.getSelectionModel().select(0);
                Event.fireEvent(filter, key(KeyCode.UP));
                assertEquals(0, commands.getSelectionModel().getSelectedIndex());
                Event.fireEvent(filter, key(KeyCode.DOWN));
                Event.fireEvent(filter, key(KeyCode.DOWN));
                assertEquals(
                        "file.save",
                        commands.getSelectionModel().getSelectedItem().id());
                Event.fireEvent(filter, key(KeyCode.DOWN));
                Event.fireEvent(filter, key(KeyCode.DOWN));
                assertEquals(3, commands.getSelectionModel().getSelectedIndex());
                Event.fireEvent(filter, key(KeyCode.UP));
                Event.fireEvent(filter, key(KeyCode.A)); // typing is not navigation
                assertEquals(
                        "file.save",
                        commands.getSelectionModel().getSelectedItem().id());
                assertFalse(ok.isDisable());
                ok.fire();
            });

            assertEquals(4, pane.steps().size());
            assertEquals(MacroStep.command("file.save"), pane.steps().get(3));
            assertTrue(pane.isDirty());
        });
    }

    @Test
    void cancellingTheCommandChooserAddsNothingAndADoubleClickChooses() throws Exception {
        FxTestSupport.runOnFx(() -> {
            open(SAMPLE);
            SettingsRig.inDialog(
                    () -> button(tr("settings.macro.addCommand")).fire(),
                    dialog -> ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire());
            assertEquals(3, pane.steps().size());
            assertFalse(pane.isDirty());

            // Changing the command of an existing step: the chooser opens on the one it has.
            pane.stepList().getSelectionModel().select(0);
            SettingsRig.inDialog(
                    () -> button(tr("settings.macro.chooseCommand")).fire(), dialog -> {
                        ListView<SettingsWindow.Shortcut> commands = commandList(dialog);
                        assertEquals(
                                "edit.copy",
                                commands.getSelectionModel().getSelectedItem().id());
                        commands.getSelectionModel().select(1);
                        Event.fireEvent(commands, click(1));
                        assertTrue(dialog.getScene().getWindow().isShowing(), "one click only selects");
                        Event.fireEvent(commands, click(2));
                    });
            assertEquals(MacroStep.command("edit.paste"), pane.steps().get(0));
            assertTrue(SettingsRig.texts(pane).contains("Title of edit.paste  (edit.paste)"));

            SettingsRig.inDialog(
                    () -> button(tr("settings.macro.chooseCommand")).fire(),
                    dialog -> ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire());
            assertEquals(MacroStep.command("edit.paste"), pane.steps().get(0), "cancelled: the step keeps its command");
        });
    }

    private static MouseEvent click(int count) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                count,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    @Test
    void aStepWhoseCommandNoLongerExistsIsMarkedAndNamed() throws Exception {
        FxTestSupport.runOnFx(() -> {
            open(SAMPLE);
            pane.applyCss();
            pane.layout();
            List<ListCell<?>> cells = new ArrayList<>();
            for (Node cell : pane.stepList().lookupAll(".list-cell")) {
                cells.add((ListCell<?>) cell);
            }
            ListCell<?> unknown = cells.stream()
                    .filter(c -> c.getItem() != null && c.getItem().equals(MacroStep.command("gone.command")))
                    .findFirst()
                    .orElseThrow();
            ListCell<?> known = cells.stream()
                    .filter(c -> c.getItem() != null && c.getItem().equals(MacroStep.command("edit.copy")))
                    .findFirst()
                    .orElseThrow();
            assertTrue(unknown.getStyleClass().contains("macro-step-unknown"));
            assertFalse(known.getStyleClass().contains("macro-step-unknown"));

            pane.stepList().getSelectionModel().select(2);
            assertTrue(
                    SettingsRig.texts(pane).contains(tr("settings.macro.step.unknownCommand", "gone.command")),
                    "the step editor says why it will not run");
        });
    }

    @Test
    void stepsMoveUpAndDownWithinTheListAndCanBeRemoved() throws Exception {
        FxTestSupport.runOnFx(() -> {
            open(SAMPLE);
            MacroStep copy = pane.steps().get(0);
            MacroStep text = pane.steps().get(1);
            MacroStep gone = pane.steps().get(2);
            Button up = button("▲");
            Button down = button("▼");

            pane.stepList().getSelectionModel().select(0);
            up.fire(); // already first
            assertEquals(List.of(copy, text, gone), List.copyOf(pane.steps()));
            assertFalse(pane.isDirty());

            down.fire();
            assertEquals(List.of(text, copy, gone), List.copyOf(pane.steps()));
            assertEquals(1, pane.stepList().getSelectionModel().getSelectedIndex(), "the moved step stays selected");
            assertTrue(pane.isDirty());
            down.fire();
            down.fire(); // already last
            assertEquals(List.of(text, gone, copy), List.copyOf(pane.steps()));
            up.fire();
            assertEquals(List.of(text, copy, gone), List.copyOf(pane.steps()));

            pane.stepList().getSelectionModel().clearSelection();
            up.fire(); // nothing selected
            button(tr("settings.macro.removeStep")).fire();
            assertEquals(3, pane.steps().size());

            pane.stepList().getSelectionModel().select(2);
            button(tr("settings.macro.removeStep")).fire();
            assertEquals(List.of(text, copy), List.copyOf(pane.steps()));

            button(tr("settings.save")).fire();
            assertEquals(
                    List.of(text, copy),
                    config.getMacroStore().findById("sample").steps());
            assertTrue(log.contains("macrosChanged"));
        });
    }

    @Test
    void aMacroKeepsItsNameWhenTheNameFieldIsEmptied() throws Exception {
        FxTestSupport.runOnFx(() -> {
            open(SAMPLE);
            TextField name = FxTestSupport.field(pane, "name");
            name.setText("   ");
            button(tr("settings.save")).fire();
            assertEquals("Sample", config.getMacroStore().findById("sample").name());
        });
    }

    @Test
    void theKeyBindingRowShowsRecordsAndClearsTheMacrosChord() throws Exception {
        FxTestSupport.runOnFx(() -> {
            open(SAMPLE);
            String command = MacroService.commandIdFor(SAMPLE);
            assertTrue(SettingsRig.texts(keybindingRow()).contains(tr("settings.shortcuts.unbound")));

            SettingsRig.button(keybindingRow(), tr("settings.shortcuts.record")).fire();
            TextField capture = (TextField) keybindingRow().getChildren().get(0);
            pane.refreshKeybinding(); // a keymap reload elsewhere leaves a recording in progress alone
            assertEquals(capture, keybindingRow().getChildren().get(0));
            SettingsRig.button(keybindingRow(), tr("settings.shortcuts.cancel")).fire();
            assertEquals(List.of("shortcutsChanged"), log);
            assertTrue(SettingsRig.texts(keybindingRow()).contains(tr("settings.shortcuts.unbound")));

            SettingsRig.button(keybindingRow(), tr("settings.shortcuts.record")).fire();
            capture = (TextField) keybindingRow().getChildren().get(0);
            Event.fireEvent(
                    capture, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F9, false, false, false, false));
            String typed = capture.getText();
            assertFalse(typed.isBlank());
            SettingsRig.button(keybindingRow(), tr("settings.shortcuts.save")).fire();
            assertTrue(log.contains("rebind " + command + " " + typed));
            assertTrue(SettingsRig.texts(keybindingRow()).contains(typed), "the row shows the chord now bound");

            log.clear();
            SettingsRig.button(keybindingRow(), tr("settings.shortcuts.reset")).fire();
            assertEquals(List.of("reset " + command, "shortcutsChanged"), log);
            assertTrue(SettingsRig.texts(keybindingRow()).contains(tr("settings.shortcuts.unbound")));
        });
    }

    @Test
    void deletingAMacroAsksFirstAndDropsItsKeyBindingWithIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Macro other = new Macro("other", "Other", List.of(MacroStep.text("o")));
            open(SAMPLE, other);
            String command = MacroService.commandIdFor(SAMPLE);
            chords.put(command, "F9");
            Button delete = button(tr("settings.macro.delete"));

            List<SettingsRig.Shown> asked = SettingsRig.answering(ButtonBar.ButtonData.CANCEL_CLOSE, delete::fire);
            assertEquals(1, asked.size());
            assertEquals(tr("settings.macro.deleteConfirmTitle"), asked.get(0).title());
            assertEquals(
                    tr("settings.macro.deleteConfirm", "Sample"), asked.get(0).content());
            assertNotNull(config.getMacroStore().findById("sample"), "declined: the macro stays");
            assertEquals(List.of(), log);

            SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, delete::fire);
            assertNull(config.getMacroStore().findById("sample"));
            assertEquals(List.of("reset " + command, "macrosChanged"), log);
            assertFalse(chords.containsKey(command));
            assertEquals(1, pane.macroList().getItems().size());
            assertEquals(
                    "other",
                    pane.macroList().getSelectionModel().getSelectedItem().id(),
                    "the next one is shown");

            SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, delete::fire);
            assertEquals(0, pane.macroList().getItems().size());
            assertTrue(delete.isDisable(), "nothing left to delete");
            Label placeholder = (Label) pane.macroList().getPlaceholder();
            assertEquals(tr("settings.macro.empty"), placeholder.getText());
        });
    }
}

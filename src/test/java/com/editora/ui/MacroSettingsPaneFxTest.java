package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.macro.Macro;
import com.editora.macro.MacroStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings → Macros step editor (M11, M19): each kind of step is shown, added and edited as what it is. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroSettingsPaneFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A pane on its own stage, over a config holding the given macros; no key-binding backend. */
    private record Rig(MacroSettingsPane pane, ConfigManager config, Stage stage, int[] changes) {}

    private static Rig rig(double width, Macro... macros) throws Exception {
        Path dir = Files.createTempDirectory("macro-pane");
        return FxTestSupport.callOnFx(() -> {
            ConfigManager config = new ConfigManager(dir);
            for (Macro m : macros) {
                config.getMacroStore().put(m);
            }
            int[] changes = {0};
            MacroSettingsPane pane = new MacroSettingsPane(new MacroSettingsPane.Host() {
                @Override
                public ConfigManager config() {
                    return config;
                }

                @Override
                public SettingsWindow.ShortcutActions shortcuts() {
                    return null;
                }

                @Override
                public void macrosChanged() {
                    changes[0]++;
                }

                @Override
                public boolean rebind(String commandId, String chord) {
                    return true;
                }

                @Override
                public void shortcutsChanged() {}
            });
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, width, 620));
            stage.show();
            pane.applyCss();
            pane.layout();
            return new Rig(pane, config, stage, changes);
        });
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    private static Button button(Node root, String text) {
        return all(root, Button.class, new ArrayList<>()).stream()
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static final Macro SAMPLE = new Macro(
            "sample",
            "Sample",
            List.of(
                    MacroStep.command("edit.copy"),
                    MacroStep.key("BACK_SPACE"),
                    MacroStep.key("S-TAB"),
                    MacroStep.text("a\nb\tc"),
                    MacroStep.text("foo", true)));

    /** A key step used to be listed as the text {@code "BACK_SPACE"} under the label Text. */
    @Test
    void everyKindOfStepIsLabelledAsWhatItIs() throws Exception {
        Rig r = rig(900, SAMPLE);
        List<String> labels = FxTestSupport.callOnFx(
                () -> r.pane().steps().stream().map(r.pane()::stepLabel).toList());
        assertTrue(labels.get(0).startsWith(tr("settings.macro.kind.command")), labels.get(0));
        assertTrue(labels.get(0).contains("edit.copy"));
        assertTrue(labels.get(1).startsWith(tr("settings.macro.kind.key")), labels.get(1));
        assertFalse(labels.get(1).contains("\""), "a key is not quoted like text: " + labels.get(1));
        assertTrue(labels.get(2).startsWith(tr("settings.macro.kind.key")), labels.get(2));
        assertTrue(labels.get(3).startsWith(tr("settings.macro.kind.text")), labels.get(3));
        assertTrue(labels.get(3).contains("a\\nb\\tc"), "line breaks and tabs are spelled out: " + labels.get(3));
        assertEquals(tr("settings.macro.step.inPrompt", tr("settings.macro.kind.text") + "   \"foo\""), labels.get(4));
        FxTestSupport.runOnFx(r.stage()::hide);
    }

    @Test
    void controlCharactersInTextAreVisibleAndAStepHoldingOnlyEnterIsNotBlank() {
        assertEquals("\\r", MacroSettingsPane.visible("\r"));
        assertEquals("a\\\\n", MacroSettingsPane.visible("a\\n"), "a literal backslash-n stays distinguishable");
        assertEquals("", MacroSettingsPane.keyLabel(""));
        assertEquals("NOT_A_KEY", MacroSettingsPane.keyLabel("NOT_A_KEY"));
        assertFalse(MacroSettingsPane.keyLabel("S-TAB").isBlank());
    }

    /** The single-line field stripped Enter and Tab from a text step; the multi-line one keeps them. */
    @Test
    void aTextStepKeepsItsLineBreaksThroughAnEdit() throws Exception {
        Rig r = rig(900, SAMPLE);
        FxTestSupport.runOnFx(() -> {
            r.pane().stepList().getSelectionModel().select(3);
            TextArea text = all(r.pane(), TextArea.class, new ArrayList<>()).get(0);
            assertEquals("a\nb\tc", text.getText());
            text.setText("a\nb\tc\nd");
            assertTrue(r.pane().isDirty());
            button(r.pane(), tr("settings.save")).fire();
        });
        Macro saved = FxTestSupport.callOnFx(() -> r.config().getMacroStore().findById("sample"));
        assertEquals(MacroStep.text("a\nb\tc\nd"), saved.steps().get(3));
        assertEquals("Sample", saved.name());
        assertEquals(1, r.changes()[0], "saving re-registers the macro commands");
        FxTestSupport.runOnFx(r.stage()::hide);
    }

    /** A key step can be added, and is set by pressing the key — including Tab, Enter and Escape. */
    @Test
    void aKeyStepIsAddedByPressingTheKey() throws Exception {
        Rig r = rig(900, SAMPLE);
        FxTestSupport.runOnFx(() -> {
            button(r.pane(), tr("settings.macro.addKey")).fire();
            assertEquals(6, r.pane().steps().size());
            TextField capture = all(r.pane(), TextField.class, new ArrayList<>()).stream()
                    .filter(f -> f.getStyleClass().contains("macro-step-input"))
                    .findFirst()
                    .orElseThrow();
            capture.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SHIFT, true, false, false, false));
            assertEquals(MacroStep.key(""), r.pane().steps().get(5), "a modifier on its own is not the key");
            capture.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, true, false, false, false));
            assertEquals(MacroStep.key("S-TAB"), r.pane().steps().get(5));
            capture.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertEquals(MacroStep.key("ESCAPE"), r.pane().steps().get(5), "Escape is captured, not acted on");
            assertTrue(r.stage().isShowing());
        });
        FxTestSupport.runOnFx(r.stage()::hide);
    }

    /** Edits used to be dropped when another macro was selected. They are saved instead; Revert drops them. */
    @Test
    void anEditIsKeptWhenAnotherMacroIsSelectedOrTheWindowCloses() throws Exception {
        Macro other = new Macro("other", "Other", List.of(MacroStep.text("o")));
        Rig r = rig(900, SAMPLE, other);
        FxTestSupport.runOnFx(() -> {
            r.pane().stepList().getSelectionModel().select(1);
            button(r.pane(), tr("settings.macro.removeStep")).fire();
            assertTrue(r.pane().isDirty());
            r.pane().macroList().getSelectionModel().select(1); // away, without pressing Save
        });
        assertEquals(
                4,
                FxTestSupport.callOnFx(() ->
                        r.config().getMacroStore().findById("sample").steps().size()));
        assertEquals(
                "other",
                FxTestSupport.callOnFx(() -> r.pane()
                        .macroList()
                        .getSelectionModel()
                        .getSelectedItem()
                        .id()));
        assertEquals(
                List.of(MacroStep.text("o")),
                FxTestSupport.callOnFx(() -> List.copyOf(r.pane().steps())));

        FxTestSupport.runOnFx(() -> {
            button(r.pane(), tr("settings.macro.addText")).fire();
            button(r.pane(), tr("settings.macro.revert")).fire();
            assertFalse(r.pane().isDirty());
            assertEquals(1, r.pane().steps().size(), "Revert drops the edit");
            button(r.pane(), tr("settings.macro.addText")).fire();
            r.stage().hide(); // closing with an edit pending
        });
        assertEquals(
                2,
                FxTestSupport.callOnFx(() ->
                        r.config().getMacroStore().findById("other").steps().size()));
    }

    /** Renaming keeps the id (and so the key binding); the unnamed recording shows its label as a prompt. */
    @Test
    void renamingKeepsTheIdAndTheUnnamedRecordingShowsItsLabel() throws Exception {
        Rig r = rig(900, SAMPLE, new Macro("unnamed-macro", "", List.of(MacroStep.text("x"))));
        FxTestSupport.runOnFx(() -> {
            r.config().getMacroStore().lastId = "unnamed-macro";
            TextField name = all(r.pane(), TextField.class, new ArrayList<>()).get(0);
            name.setText("Renamed");
            button(r.pane(), tr("settings.save")).fire();
            assertNotNull(r.config().getMacroStore().findById("sample"));
            assertEquals(
                    "Renamed", r.config().getMacroStore().findById("sample").name());

            r.pane().macroList().getSelectionModel().select(1);
            name = all(r.pane(), TextField.class, new ArrayList<>()).get(0);
            assertEquals("", name.getText());
            assertEquals(tr("macro.unnamedName"), name.getPromptText());
            r.stage().hide();
        });
    }

    /** M19: at the narrowest the Settings content gets, no button on the page is squeezed to "…". */
    @Test
    void noButtonIsTruncated() throws Exception {
        Rig r = rig(560, SAMPLE);
        FxTestSupport.runOnFx(() -> {
            r.pane().applyCss();
            r.pane().layout();
            for (Button b : all(r.pane(), Button.class, new ArrayList<>())) {
                if (b.getScene() == null || !b.isVisible()) {
                    continue;
                }
                assertTrue(
                        b.getWidth() + 0.5 >= b.prefWidth(-1),
                        "'" + b.getText() + "' is " + b.getWidth() + "px wide, needs " + b.prefWidth(-1));
            }
            r.stage().hide();
        });
    }
}

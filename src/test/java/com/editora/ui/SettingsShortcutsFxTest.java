package com.editora.ui;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.command.KeymapManager;
import com.editora.macro.Macro;
import com.editora.macro.MacroStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Keymaps and Macros pages in a real Settings window: what the user records, sees and keeps. */
@Tag("fx")
class SettingsShortcutsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    static Button button(Node root, String text) {
        return all(root, Button.class, new ArrayList<>()).stream()
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    /** Opens the window's Settings on {@code category}, as a user would, and returns it. */
    static SettingsWindow shown(FxWindowFixture fx, String category) {
        SettingsWindow w = FxTestSupport.field(fx.controller, "settingsWindow");
        Stage owner = FxTestSupport.field(fx.controller, "stage");
        w.show(owner);
        ListView<Object> sidebar = FxTestSupport.field(w, "sidebar");
        for (Object o : sidebar.getItems()) {
            if (o instanceof Enum<?> e && e.name().equals(category)) {
                sidebar.getSelectionModel().select(o);
            }
        }
        stage(w).getScene().getRoot().applyCss();
        stage(w).getScene().getRoot().layout();
        return w;
    }

    static Stage stage(SettingsWindow w) {
        return FxTestSupport.field(w, "stage");
    }

    static void press(Node target, KeyCode code) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    /** Escape in a macro's Key step is the key to record; the window's "Escape closes" filter used to win. */
    @Test
    void escapeInAMacroKeyStepIsRecordedAndSettingsStaysOpen() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                fx.shared.getMacroStore().put(new Macro("m1", "Wrap", new ArrayList<>(List.of(MacroStep.text("x")))));
                SettingsWindow w = shown(fx, "MACROS");
                w.refreshMacrosList();
                MacroSettingsPane pane = FxTestSupport.field(w, "macroPane");
                pane.macroList().getSelectionModel().select(0);
                button(pane, tr("settings.macro.addKey")).fire();
                TextField key = all(pane, TextField.class, new ArrayList<>()).stream()
                        .filter(t -> t.getStyleClass().contains("macro-step-input"))
                        .findFirst()
                        .orElseThrow();
                key.requestFocus();
                press(key, KeyCode.ESCAPE);
                assertTrue(stage(w).isShowing(), "Escape in the key field does not close Settings");
                assertEquals(
                        MacroStep.key("ESCAPE"), pane.steps().get(pane.steps().size() - 1));
                stage(w).hide();
            });
        }
    }

    /** A window on the CUA keymap: its notation ({@code Ctrl+S}, {@code ⌘S}) is not the keymap's own tokens. */
    static FxWindowFixture cua() throws Exception {
        return FxWindowFixture.create(
                Files.createTempDirectory("settings-shortcuts"),
                shared -> shared.getSettings().setKeymap("cua"));
    }

    static KeymapManager keymap(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "keymap");
    }

    /** The keymap's first chord bound to {@code commandId}, in keymap tokens. */
    static String rawChord(FxWindowFixture fx, String commandId) {
        return keymap(fx).bindings().entrySet().stream()
                .filter(e -> e.getValue().equals(commandId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    static Map<String, String> overrides(FxWindowFixture fx) {
        return fx.shared.getSettings().keybindingsFor(KeymapManager.isMac());
    }

    static HBox row(SettingsWindow w, String commandId) {
        Map<String, HBox> rows = FxTestSupport.field(w, "shortcutRowsById");
        return rows.get(commandId);
    }

    /** The chord (or "unbound") label a list row shows. */
    static String listed(SettingsWindow w, String commandId) {
        return all(row(w, commandId), Label.class, new ArrayList<>()).get(1).getText();
    }

    static void pressOn(Node target, KeyCode code, boolean shift, boolean ctrl, boolean alt) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false));
    }

    /** Enter on a row selects it; a second Enter starts recording. Returns the capture field. */
    static TextField record(SettingsWindow w, String commandId) throws Exception {
        FxTestSupport.runOnFx(() -> press(row(w, commandId), KeyCode.ENTER));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> press(row(w, commandId), KeyCode.ENTER));
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(
                () -> all(row(w, commandId), TextField.class, new ArrayList<>()).get(0));
    }

    /** Runs {@code action}, closing every dialog it opens; returns how many were closed. */
    static int dismissingDialogs(Runnable action) {
        AtomicInteger closed = new AtomicInteger();
        Platform.runLater(() -> {
            for (Window win : List.copyOf(Window.getWindows())) {
                if (win.getScene() != null && win.getScene().getRoot() instanceof DialogPane && win.isShowing()) {
                    closed.incrementAndGet();
                    win.hide();
                }
            }
        });
        action.run();
        return closed.get();
    }

    /** The recorder showed "C-S-k f5" under CUA while the list beside it said "Ctrl+Shift+S". */
    @Test
    void theRecorderShowsTheKeymapsNotationAndBindsTheKeymapTokens() throws Exception {
        try (var fx = cua()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "KEYMAPS"));
            TextField capture = record(w, "file.save");
            String expected = keymap(fx).display("C-M-S-f11");
            assertNotEquals("C-M-S-f11", expected, "precondition: CUA writes chords its own way");
            FxTestSupport.runOnFx(() -> {
                pressOn(capture, KeyCode.F11, true, true, true);
                assertEquals(expected, capture.getText());
                press(capture, KeyCode.ENTER);
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertEquals("file.save", overrides(fx).get("C-M-S-f11"), "stored in keymap tokens");
                assertEquals(expected, listed(w, "file.save"));
                stage(w).hide();
            });
        }
    }

    /** A bare "e" used to be bound, after which typing "e" anywhere ran the command. */
    @Test
    void aKeyThatTypesTextIsRefusedWithAMessage() throws Exception {
        try (var fx = cua()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "KEYMAPS"));
            String before = rawChord(fx, "file.save");
            TextField capture = record(w, "file.save");
            FxTestSupport.runOnFx(() -> {
                pressOn(capture, KeyCode.E, false, false, false);
                int dialogs = dismissingDialogs(() -> press(capture, KeyCode.ENTER));
                assertEquals(1, dialogs, "the user is told why");
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertNull(overrides(fx).get("e"));
                assertEquals(before, rawChord(fx, "file.save"), "the old shortcut stays");
                stage(w).hide();
            });
        }
    }

    /** There was no way to unbind a command; Delete on its row (or Remove) does it now. */
    @Test
    void deleteOnARowRemovesTheShortcut() throws Exception {
        try (var fx = cua()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "KEYMAPS"));
            String chord = rawChord(fx, "file.save");
            FxTestSupport.runOnFx(() -> press(row(w, "file.save"), KeyCode.DELETE));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertNull(rawChord(fx, "file.save"), "nothing runs File: Save any more");
                assertEquals("", overrides(fx).get(chord), "its default chord is suppressed");
                assertEquals(tr("settings.shortcuts.unbound"), listed(w, "file.save"));
                press(row(w, "file.save"), KeyCode.ENTER); // select it: Remove is there, and greyed
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertTrue(button(row(w, "file.save"), tr("settings.shortcuts.remove"))
                        .isDisable());
                stage(w).hide();
            });
        }
    }

    /** Reset on a command whose chord another command took did nothing, though the status said "reset". */
    @Test
    void resetTakesAChordBackAfterAskingFirst() throws Exception {
        try (var fx = cua()) {
            SettingsWindow w = FxTestSupport.callOnFx(() -> shown(fx, "KEYMAPS"));
            String open = rawChord(fx, "file.open");
            List<String> asked = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                SettingsWindow.ShortcutActions actions = FxTestSupport.field(w, "shortcutActions");
                actions.rebind("file.save", open);
                assertNull(rawChord(fx, "file.open"), "precondition: File: Open lost its chord");
                FxTestSupport.call(w, "refreshShortcuts", new Class<?>[] {});
                java.util.function.BiPredicate<String, String> answer = (title, body) -> asked.add(body);
                setField(w, "confirmLoss", answer);
                press(row(w, "file.open"), KeyCode.ENTER);
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                button(row(w, "file.open"), tr("settings.shortcuts.reset")).fire();
                assertEquals(1, asked.size(), "asked before taking the chord from File: Save");
                assertTrue(asked.get(0).contains(keymap(fx).display(open)), asked.get(0));
                assertEquals("file.open", keymap(fx).bindings().get(open), "the chord is back");
                assertFalse(overrides(fx).containsValue("file.open"));
                stage(w).hide();
            });
        }
    }

    /** "file save" found nothing, nor did "Ctrl+X Ctrl+S" under Emacs, which writes it "C-x C-s". */
    @Test
    void theFilterFindsWordsInAnyOrderAndChordsInAnyNotation() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow w = shown(fx, "KEYMAPS");
                TextField filter = FxTestSupport.field(w, "shortcutFilter");
                filter.setText("save file");
                assertTrue(row(w, "file.save") != null, "words in any order");
                filter.setText("Ctrl+X Ctrl+S");
                assertTrue(row(w, "file.save") != null, "a chord in another notation");
                assertNull(row(w, "file.saveAll"), "only the commands on that chord");
                stage(w).hide();
            });
        }
    }

    static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}

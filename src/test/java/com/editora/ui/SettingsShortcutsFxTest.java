package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.macro.Macro;
import com.editora.macro.MacroStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

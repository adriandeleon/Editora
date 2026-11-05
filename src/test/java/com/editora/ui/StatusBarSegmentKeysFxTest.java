package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status bar's built-in segments — the Python interpreter among them — are buttons a keyboard and a screen
 * reader reach, as a plugin's segment already was: they were mouse-only labels (Python review 2, C112).
 */
@Tag("fx")
class StatusBarSegmentKeysFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void thePythonInterpreterItemIsAKeyboardButton() throws Exception {
        List<String> ran = new ArrayList<>();
        Label item = FxTestSupport.callOnFx(() -> {
            CommandRegistry registry = new CommandRegistry();
            registry.register(Command.of("python.selectInterpreter", () -> ran.add("picker")));
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("x = 1\n");
            StatusBar sb = new StatusBar(() -> buffer, registry, Settings::new);
            sb.attach(buffer);
            sb.setPythonInterpreter(
                    "Python 3.13.5 (.venv)", "/p/.venv/bin/python\nClick to choose another interpreter.");
            new Scene(new StackPane(sb), 1200, 40);
            Label found = null;
            for (var n : sb.lookupAll(".status-python")) {
                found = (Label) n;
            }
            return found;
        });
        assertTrue(FxTestSupport.callOnFx(item::isFocusTraversable), "Tab reaches it");
        assertEquals(AccessibleRole.BUTTON, FxTestSupport.callOnFx(item::getAccessibleRole));
        assertTrue(FxTestSupport.callOnFx(item::getAccessibleHelp).contains("/p/.venv/bin/python"));
        FxTestSupport.runOnFx(() ->
                item.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        assertEquals(List.of("picker"), ran, "Enter opens the picker");
    }
}

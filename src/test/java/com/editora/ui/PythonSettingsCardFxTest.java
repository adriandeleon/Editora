package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings → LSP → Python (Python review 2, C101): the fallback interpreter sat on the Debugging page's Python
 * card, greyed out while debugging was off — the default — though Run and Pyright use it. It is now on its own card
 * on the LSP page, editable with language servers and debugging both off, beside Choose… and Format on save.
 */
@Tag("fx")
class PythonSettingsCardFxTest {

    private static FxWindowFixture fx;
    private static SettingsWindow window;
    private static Settings settings;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        settings = fx.shared.getSettings();
        settings.setDebugSupport(false); // on by default since 0.27.1; this case is about it being off
        window = FxTestSupport.field(fx.controller, "settingsWindow");
        FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
        FxTestSupport.drainFx();
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Stage>field(window, "stage").hide());
            fx.dispose();
        }
    }

    private static boolean inside(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static Region page(SettingsWindow.Category category) {
        Map<SettingsWindow.Category, Region> pages = FxTestSupport.field(window, "pages");
        return pages.get(category);
    }

    @Test
    void theInterpreterIsOnTheLspPageAndEditableWithDebuggingAndLspOff() throws Exception {
        assertFalse(settings.isDebugSupport(), "debugging is off (switched off in boot)");
        assertFalse(settings.isLspSupport(), "language servers are off (as a test window starts them)");
        Map<String, TextField> fields = FxTestSupport.field(window, "debugCommandFields");
        TextField python = fields.get("python");
        assertTrue(FxTestSupport.callOnFx(() -> inside(python, page(SettingsWindow.Category.LSP))));
        assertFalse(FxTestSupport.callOnFx(() -> inside(python, page(SettingsWindow.Category.DEBUG))));
        assertFalse(FxTestSupport.callOnFx(python::isDisabled), "greyed out with debugging and LSP off");

        FxTestSupport.runOnFx(() -> {
            python.setText("/opt/py/bin/python3");
            python.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("/opt/py/bin/python3", settings.getPythonDebugCommand());
    }

    @Test
    void formatOnSaveIsASwitchOnTheCard() throws Exception {
        Map<String, javafx.scene.control.Control> controls = FxTestSupport.field(window, "settingControls");
        CheckBox formatOnSave = (CheckBox) controls.get("lsp.pythonFormatOnSave");
        assertNotNull(formatOnSave);
        assertFalse(settings.isPythonFormatOnSave(), "off by default");
        FxTestSupport.runOnFx(() -> formatOnSave.setSelected(true));
        assertTrue(settings.isPythonFormatOnSave());
        FxTestSupport.runOnFx(() -> formatOnSave.setSelected(false));
        assertFalse(settings.isPythonFormatOnSave());
    }

    @Test
    void chooseRunsSelectInterpreter() throws Exception {
        List<String> ran = new ArrayList<>();
        FxTestSupport.runOnFx(() -> window.setRunCommand(ran::add));
        Button choose = FxTestSupport.callOnFx(() -> {
            for (Node n : SettingsRig.all((Parent) page(SettingsWindow.Category.LSP), Button.class)) {
                if (n instanceof Button b && tr("settings.lsp.pythonPick").equals(b.getText())) {
                    return b;
                }
            }
            throw new AssertionError("no Choose… button on the LSP page");
        });
        assertFalse(FxTestSupport.callOnFx(choose::isDisabled));
        FxTestSupport.runOnFx(choose::fire);
        assertEquals(List.of("python.selectInterpreter"), ran);
        FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
    }
}

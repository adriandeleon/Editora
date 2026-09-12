package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.test.JavaTestScanner;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class JUnitEditorDebugFxTest {

    @BeforeAll
    static void initFx() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void contextMenuDebugsTheJUnitMethodAtTheCaret(@TempDir Path dir) throws Exception {
        AtomicReference<JavaTestScanner.TestTarget> debugged = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(dir.resolve("CalculatorTest.java"));
            buffer.setTestGutterEnabled(true);
            buffer.setContent("""
                    class CalculatorTest {
                        @org.junit.jupiter.api.Test
                        void addsNumbers() {
                        }
                    }
                    """);
            buffer.setTestDebugHandler(debugged::set);
            CodeArea area = FxTestSupport.field(buffer, "area");
            area.moveTo(2, 8);

            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(buffer.getNode()), 600, 400));
            stage.show();
            Event.fireEvent(
                    area, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 50, 60, 50, 60, false, null));
            ContextMenu menu = FxTestSupport.field(buffer, "contextMenu");
            List<MenuItem> items = List.copyOf(menu.getItems());
            MenuItem debug = items.stream()
                    .filter(i -> tr("testrunner.menu.debugTest").equals(i.getText()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(debug, "method context menu should offer Debug This Test");
            debug.fire();
            menu.hide();
            stage.hide();
        });

        assertNotNull(debugged.get());
        assertEquals("CalculatorTest", debugged.get().className());
        assertEquals("addsNumbers", debugged.get().methodName());
    }

    @Test
    void debugAtCaretCommandIsLocalizedInEveryCatalog() {
        assertTrue(!tr("command.test.debugAtCaret").startsWith("command.test."));
        assertTrue(!tr("command.test.debugAtCaret.desc").startsWith("command.test."));
    }
}

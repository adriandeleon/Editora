package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.editops.Registers;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Copy / insert / point / jump registers through the real commands, the name typed as the next key. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmacsRegistersFxTest {

    private EditingFx e;
    private CommandRegistry registry;
    private MainController controller;

    @BeforeAll
    void setUp() throws Exception {
        Messages.init("en");
        e = EditingFx.create();
        controller = e.fx.controller;
        registry = FxTestSupport.field(controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (e != null) {
            e.dispose();
        }
    }

    @Test
    void copyAndInsertARegisterAcrossFiles(@TempDir Path dir) throws Exception {
        EditorBuffer a = e.open("a.txt", "alpha beta gamma\n");
        FxTestSupport.runOnFx(() -> a.getArea().selectRange(6, 10)); // "beta"
        run("edit.copyToRegister");
        assertEquals(tr("status.register.copyTo"), echo());
        type(a, "x");
        assertEquals(tr("status.register.copied", "x"), echo());
        assertEquals("alpha beta gamma\n", text(a), "copying leaves the text");

        EditorBuffer b = e.open("b.txt", "one two\n");
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(4));
        run("edit.insertRegister");
        type(b, "x");
        assertEquals("one betatwo\n", text(b));
        assertEquals(
                4, (int) FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition()), "the caret stays before it");
    }

    @Test
    void aPrefixArgumentCutsTheRegion() throws Exception {
        EditorBuffer a = e.open("cut.txt", "keep CUT keep\n");
        FxTestSupport.runOnFx(() -> a.getArea().selectRange(5, 9));
        EditingCoordinator editing = FxTestSupport.field(controller, "editing");
        FxTestSupport.runOnFx(() -> {
            editing.currentPrefixArg = 4; // C-u, as the dispatcher hands it to a count-aware command
            registry.run("edit.copyToRegister");
            editing.currentPrefixArg = null;
        });
        type(a, "c");
        assertEquals("keep keep\n", text(a));
        assertEquals(new Registers.Text("CUT "), registers().get('c'));
    }

    @Test
    void aSavedPositionFollowsEditsAndIsJumpedToFromAnotherFile() throws Exception {
        EditorBuffer a = e.open("pos.txt", "line one\nline two\nline three\n");
        FxTestSupport.runOnFx(() -> a.getArea().moveTo(14)); // "two"
        run("edit.pointToRegister");
        type(a, "p");
        FxTestSupport.runOnFx(() -> a.getArea().insertText(0, "new first line\n")); // 15 characters above it
        assertEquals(29, ((Registers.Position) registers().get('p')).offset(), "moved with the edit");

        EditorBuffer b = e.open("other.txt", "elsewhere\n");
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(3));
        run("edit.jumpToRegister");
        type(b, "p");
        EditorArea area = FxTestSupport.field(controller, "editorArea");
        assertTrue(e.await(() -> area.selectedTab().getUserData() == a), "back in pos.txt");
        assertEquals(29, (int) FxTestSupport.callOnFx(() -> a.getArea().getCaretPosition()));
    }

    @Test
    void escapeCancelsAndAnEmptyRegisterSaysSo() throws Exception {
        EditorBuffer a = e.open("esc.txt", "text\n");
        FxTestSupport.runOnFx(() -> a.getArea().selectRange(0, 4));
        run("edit.copyToRegister");
        type(a, "\u001b");
        assertNull(registers().get('\u001b'));
        assertEquals("", echo(), "the prompt is cleared");

        FxTestSupport.runOnFx(() -> a.getArea().moveTo(0));
        run("edit.insertRegister");
        type(a, "z");
        assertEquals(tr("status.register.empty", "z"), echo());
        assertEquals("text\n", text(a));
    }

    // --- helpers ---

    private Registers registers() throws Exception {
        RegisterCommands commands = FxTestSupport.field(controller, "registers");
        return commands.registers();
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private static void type(EditorBuffer b, String ch) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ch, ch, KeyCode.UNDEFINED, false, false, false, false)));
        FxTestSupport.runOnFx(() -> {});
    }

    private static String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private String echo() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }
}

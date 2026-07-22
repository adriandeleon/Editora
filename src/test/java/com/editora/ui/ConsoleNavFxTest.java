package com.editora.ui;

import javafx.event.Event;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.build.OutputStyle;
import com.editora.command.KeymapManager;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ConsoleNav}: a console {@link CodeArea} moves under the user's configured keybindings. The
 * consoles mark themselves {@code editora.ownsKeys}, so the scene-level dispatcher deliberately hands them
 * every {@code nav.*} chord — before this they were swallowed and only the raw arrows worked. Also pins the
 * {@link BuildToolPanel} follow rule: a running build keeps scrolling to the tail, but stops yanking the
 * view once the user has scrolled back.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleNavFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static KeymapManager emacs() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("emacs");
        return km;
    }

    private static void press(CodeArea area, KeyCode code, boolean ctrl, boolean alt, boolean shift) {
        Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, /*meta*/ false));
    }

    /** A console holding three lines, with the caret parked at the top. */
    private static CodeArea console() {
        CodeArea area = new CodeArea();
        area.setEditable(false);
        area.replaceText("first\nsecond\nthird\n");
        ConsoleNav.install(area, emacs());
        area.moveTo(0);
        return area;
    }

    @Test
    void emacsLineChordsMoveTheCaret() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = console();
            press(area, KeyCode.N, true, false, false); // C-n
            assertEquals(1, area.getCurrentParagraph(), "C-n moves down a line");
            press(area, KeyCode.P, true, false, false); // C-p
            assertEquals(0, area.getCurrentParagraph(), "C-p moves back up");
        });
    }

    @Test
    void emacsBufferEndsJumpToStartAndEnd() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = console();
            press(area, KeyCode.PERIOD, false, true, true); // M-> (M-S-.)
            assertEquals(area.getLength(), area.getCaretPosition(), "M-> goes to the end of the output");
            press(area, KeyCode.COMMA, false, true, true); // M-< (M-S-,)
            assertEquals(0, area.getCaretPosition(), "M-< goes back to the start");
        });
    }

    @Test
    void anUnmappedKeyIsLeftToTheAreasOwnHandling() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = console();
            KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false);
            Event.fireEvent(area, e);
            assertTrue(!e.isConsumed(), "a bare arrow must fall through to RichTextFX's native navigation");
        });
    }

    @Test
    void buildConsoleFollowsTheTailUntilTheUserScrollsBack() throws Exception {
        FxTestSupport.runOnFx(() -> {
            BuildToolPanel panel = new BuildToolPanel();
            panel.started("mvn test", OutputStyle.passthrough(), () -> {});
            CodeArea output = FxTestSupport.field(panel, "output");

            panel.appendOutput("one", false);
            panel.appendOutput("two", false);
            assertEquals(output.getLength(), output.getCaretPosition(), "an untouched console tracks the tail");

            output.moveTo(0); // the user scrolls back to read earlier output
            panel.appendOutput("three", false);
            assertEquals(0, output.getCaretPosition(), "new output must not yank a scrolled-back console");

            output.moveTo(output.getLength()); // back at the end → following resumes
            panel.appendOutput("four", false);
            assertEquals(output.getLength(), output.getCaretPosition(), "returning to the end resumes the follow");
        });
    }
}

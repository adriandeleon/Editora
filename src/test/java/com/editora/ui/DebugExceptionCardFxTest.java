package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.dap.DapModels;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The card an exception stop opens under the line that threw. */
@Tag("fx")
class DebugExceptionCardFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final DapModels.ExceptionInfo JAVA_UNCAUGHT = new DapModels.ExceptionInfo(
            "java.lang.IllegalStateException", "java.lang.IllegalStateException: boom", "userUnhandled", "", List.of());

    private static final DapModels.ExceptionInfo PYTHON = new DapModels.ExceptionInfo(
            "builtins.ValueError",
            "bad value",
            "",
            "File \"app.py\", line 3\n  run()",
            List.of(new DapModels.ExceptionInfo(
                    "KeyError", "'id'", "", "", List.of(new DapModels.ExceptionInfo("OSError", "")))));

    @Test
    void theMessageLosesTheTypeJavaDebugPutsInFront() {
        assertEquals("boom", DebugExceptionCard.message(JAVA_UNCAUGHT));
        assertEquals("", DebugExceptionCard.message(new DapModels.ExceptionInfo("a.B", "a.B")));
        assertEquals("a.Bc: x", DebugExceptionCard.message(new DapModels.ExceptionInfo("a.B", "a.Bc: x")));
        assertEquals("bad value", DebugExceptionCard.message(PYTHON));
        assertEquals(
                "boom",
                DebugExceptionCard.message(new DapModels.ExceptionInfo(
                        "java.lang.IllegalStateException", "\"java.lang.IllegalStateException: boom\"")),
                "java-debug's quoted string value");
    }

    @Test
    void theTitleSaysWhetherTheProgramCatchesIt() {
        assertEquals("debug.exceptionCard.uncaught", DebugExceptionCard.titleKey(JAVA_UNCAUGHT));
        assertEquals(
                "debug.exceptionCard.caught",
                DebugExceptionCard.titleKey(new DapModels.ExceptionInfo("a.B", "", "always", "", List.of())));
        assertEquals("debug.exceptionCard.thrown", DebugExceptionCard.titleKey(PYTHON));
    }

    @Test
    void copyTakesTheTypeMessageCausesAndTrace() {
        assertEquals(
                "builtins.ValueError: bad value\n"
                        + tr("debug.exceptionCard.causedBy", "KeyError: 'id'") + "\n"
                        + tr("debug.exceptionCard.causedBy", "OSError") + "\n"
                        + "File \"app.py\", line 3\n  run()",
                DebugExceptionCard.text(PYTHON));
        assertEquals("java.lang.IllegalStateException: boom", DebugExceptionCard.text(JAVA_UNCAUGHT));
    }

    /** Waits for the card to be showing ({@code on}) or not: it is placed once the editor has drawn the line. */
    private static void awaitShowing(DebugExceptionCard card, boolean on) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (FxTestSupport.callOnFx(card::isShowing) != on) {
            if (System.nanoTime() > end) {
                throw new AssertionError("the card never became " + (on ? "shown" : "hidden"));
            }
            Thread.sleep(20);
        }
    }

    private static String text() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("line ").append(i).append('\n');
        }
        return text.toString();
    }

    @Test
    void itShowsUnderTheLineClosesOnEscAndWhenTheLineScrollsAway() throws Exception {
        List<String> done = new ArrayList<>();
        DebugExceptionCard[] card = new DebugExceptionCard[1];
        CodeArea[] area = new CodeArea[1];
        Stage[] stage = new Stage[1];
        FxTestSupport.runOnFx(() -> {
            com.editora.editor.EditorBuffer buffer = new com.editora.editor.EditorBuffer();
            buffer.setContent(text());
            area[0] = buffer.getArea();
            javafx.scene.layout.StackPane host = new javafx.scene.layout.StackPane(buffer.getNode());
            stage[0] = new Stage();
            stage[0].setScene(new Scene(host, 800, 400));
            stage[0].show();
            host.applyCss();
            host.layout();
            area[0].showParagraphAtTop(0); // the headless toolkit draws the editor's lines only when asked
            host.layout();
            card[0] = new DebugExceptionCard(
                    new DebugExceptionCard.Actions(() -> done.add("caught off"), () -> done.add("copied")));
            card[0].show(area[0], 3, PYTHON, true);
        });
        awaitShowing(card[0], true);
        FxTestSupport.runOnFx(() -> {
            try {
                assertTrue(card[0].isShowing());
                Label title = (Label) card[0].card().lookup(".debug-exception-title");
                assertEquals(tr("debug.exceptionCard.thrown"), title.getText());
                assertTrue(card[0].card().lookup(".debug-exception-trace").isVisible());
                assertEquals(
                        2, card[0].card().lookupAll(".debug-exception-cause").size());

                // Esc in the editor closes it; typing never left the editor.
                area[0].fireEvent(
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                assertFalse(card[0].isShowing());

                // The uncaught Java one: no trace, no causes, no caught button; red title.
                card[0].show(area[0], 3, JAVA_UNCAUGHT, false);
            } catch (RuntimeException e) {
                stage[0].hide();
                throw e;
            }
        });
        awaitShowing(card[0], true);
        FxTestSupport.runOnFx(() -> {
            assertTrue(card[0].isShowing());
            assertFalse(card[0].card().lookup(".debug-exception-trace").isVisible());
            Label title = (Label) card[0].card().lookup(".debug-exception-title");
            assertTrue(title.getStyleClass().contains("debug-exception-uncaught"));
            boolean caughtButton = card[0].card().lookupAll(".button").stream()
                    .anyMatch(n ->
                            n.isVisible() && tr("debug.exceptionCard.caughtOff").equals(((Button) n).getText()));
            assertFalse(caughtButton);
            area[0].showParagraphAtTop(120); // the line leaves the screen
            stage[0].getScene().getRoot().layout();
        });
        awaitShowing(card[0], false);

        FxTestSupport.runOnFx(() -> {
            card[0].show(area[0], 125, PYTHON, true);
        });
        awaitShowing(card[0], true);
        FxTestSupport.runOnFx(() -> {
            assertTrue(card[0].isShowing());
            card[0].card().lookupAll(".button").stream()
                    .filter(n -> tr("debug.exceptionCard.caughtOff").equals(((Button) n).getText()))
                    .findFirst()
                    .map(Button.class::cast)
                    .orElseThrow()
                    .fire();
            assertFalse(card[0].isShowing(), "it closes, then turns the caught stops off");
            assertEquals(List.of("caught off"), done);
            stage[0].hide();
        });
    }
}

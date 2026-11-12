package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The find bar over read-only text: every console and the Debug Log window carry one in their own scene
 * graph, it searches as the query is typed and shows a count, Enter and Shift+Enter step and wrap, Escape
 * closes it and gives the keyboard back, and asking for Find again steps to the next match.
 */
@Tag("fx")
class ConsoleFindBarFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static KeyEvent key(KeyCode code, boolean shift) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false);
    }

    @Test
    void overAPlainTextAreaItSearchesAsTypedCountsStepsBothWaysWrapsAndEscapeGivesTheKeyboardBack() throws Exception {
        FxTestSupport.runOnFx(() -> {
            // The Debug Log's shape: a read-only TextArea, which draws one selection and nothing else.
            TextArea log = new TextArea("one warn\ntwo WARN\nthree warn\n");
            log.setEditable(false);
            ConsoleFindBar bar = ConsoleFindBar.overText(() -> log);
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(bar, log), 400, 300));
            stage.show();
            try {
                assertFalse(bar.isShowing());
                log.positionCaret(10); // on the second line: typing searches from here, not from the top
                bar.open(null);
                assertTrue(bar.isShowing());
                bar.field().setText("warn");
                assertEquals(tr("find.count", 2, "3"), bar.countText(), "the first match at or after the caret");
                assertEquals("WARN", log.getSelectedText(), "case-insensitive by default");

                // Refining the query stays at the anchor: it does not run on to the next match.
                bar.field().setText("war");
                assertEquals(tr("find.count", 2, "3"), bar.countText());

                bar.field().fireEvent(key(KeyCode.ENTER, false));
                assertEquals(tr("find.count", 3, "3"), bar.countText());
                bar.field().fireEvent(key(KeyCode.ENTER, false));
                assertEquals(tr("find.count", 1, "3"), bar.countText(), "Enter past the last match wraps to the first");
                bar.field().fireEvent(key(KeyCode.ENTER, true));
                assertEquals(
                        tr("find.count", 3, "3"), bar.countText(), "Shift+Enter before the first wraps to the last");
                bar.field().fireEvent(key(KeyCode.ENTER, true));
                assertEquals(tr("find.count", 2, "3"), bar.countText());

                // Find asked for again while the bar is open: the next match, not a second bar.
                bar.openOrNext(null);
                assertEquals(tr("find.count", 3, "3"), bar.countText());

                bar.field().setText("nothing like this");
                assertEquals(tr("search.none"), bar.countText());

                bar.field().fireEvent(key(KeyCode.ESCAPE, false));
                assertFalse(bar.isShowing());
                assertSame(log, stage.getScene().getFocusOwner(), "Escape gives the keyboard back to the log");
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void theFindChordPressedInTheFieldStepsToTheNextMatch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            KeymapManager before = TextInputKeymap.sharedKeymap();
            KeymapManager cua = new KeymapManager();
            cua.loadNamed("cua");
            TextInputKeymap.setShared(cua);
            try {
                TextArea log = new TextArea("ab ab ab");
                ConsoleFindBar bar = ConsoleFindBar.overText(() -> log);
                new Scene(new VBox(bar, log), 300, 200);
                bar.open(null);
                bar.field().setText("ab");
                assertEquals(tr("find.count", 1, "3"), bar.countText());
                // Ctrl+F (Cmd+F on macOS) is find.show in the CUA keymap.
                boolean mac = KeymapManager.isMac();
                KeyEvent find = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, !mac, false, mac);
                assertTrue(ConsoleFindBar.isFindChord(find));
                bar.field().fireEvent(find);
                assertEquals(tr("find.count", 2, "3"), bar.countText());
                assertFalse(ConsoleFindBar.isFindChord(key(KeyCode.F, false)), "a plain F is typed, not a chord");
            } finally {
                if (before != null) {
                    TextInputKeymap.setShared(before);
                }
            }
        });
    }

    @Test
    void theRunExternalToolsAndDebugLogViewsEachCarryABarInTheirOwnSceneGraph() throws Exception {
        FxTestSupport.runOnFx(() -> {
            RunPanel run = new RunPanel(() -> {});
            run.appendOutput("alpha beta", false);
            ConsoleFindBar runBar = (ConsoleFindBar) run.lookup(".console-find-bar");
            assertNotNull(runBar, "the Run console has a find bar");
            assertFalse(runBar.isShowing(), "closed until asked for");
            CodeArea runOutput = FxTestSupport.field(run, "output");
            runBar.open(null);
            runBar.field().setText("beta");
            assertEquals("beta", runOutput.getSelectedText());
            assertEquals(tr("find.count", 1, "1"), runBar.countText());

            ExternalToolPanel tools = new ExternalToolPanel();
            assertNotNull(tools.lookup(".console-find-bar"), "the External Tools console has a find bar");
        });

        DebugLogWindow window = FxTestSupport.callOnFx(DebugLogWindow::new);
        FxTestSupport.runOnFx(() -> {
            window.show(null);
            try {
                Stage stage = FxTestSupport.field(window, "stage");
                ConsoleFindBar bar = (ConsoleFindBar) stage.getScene().getRoot().lookup(".console-find-bar");
                assertNotNull(bar, "the Debug Log window has a find bar");
                assertSame(stage.getScene(), bar.getScene(), "a node of the window, not a window of its own");
                assertFalse(Window.getWindows().stream().anyMatch(w -> w instanceof Popup), "and never a popup");
                // The window's Find button opens it.
                SettingsRig.button(stage.getScene().getRoot(), tr("find.prompt"))
                        .fire();
                assertTrue(bar.isShowing());
            } finally {
                ((Stage) FxTestSupport.field(window, "stage")).close();
            }
        });
    }
}

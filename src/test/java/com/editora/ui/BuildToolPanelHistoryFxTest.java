package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.build.OutputStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maven review B22 and B5 on {@link BuildToolPanel}: a new run keeps the last one reachable behind the
 * "Previous run" toggle instead of clearing it, the find bar searches the buffer on screen, "Save output…"
 * writes it, and a build the user stopped reads "Stopped" rather than SIGTERM's exit code.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildToolPanelHistoryFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Label status(BuildToolPanel panel) {
        return FxTestSupport.field(panel, "status");
    }

    private static ToggleButton previousButton(BuildToolPanel panel) {
        return FxTestSupport.field(panel, "previousButton");
    }

    @Test
    void aNewRunKeepsTheLastOnesOutputBehindThePreviousRunToggle() throws Exception {
        FxTestSupport.runOnFx(() -> {
            BuildToolPanel panel = new BuildToolPanel();
            ToggleButton previous = previousButton(panel);
            assertTrue(previous.isDisable(), "nothing to show before a second run");

            panel.started("compile", OutputStyle.maven(), () -> {});
            panel.appendOutput("[INFO] first run line", false);
            panel.appendOutput("[INFO] BUILD FAILURE", false);
            panel.finished(1);
            assertTrue(previous.isDisable(), "the first run is the live console, not a previous one");

            panel.started("test", OutputStyle.maven(), () -> {});
            panel.appendOutput("[INFO] second run line", false);
            assertFalse(previous.isDisable(), "the first run moved to the previous-run buffer");
            assertFalse(panel.isShowingPrevious());
            assertTrue(panel.shownText().contains("second run line"), "the live console shows the new run");
            assertFalse(panel.shownText().contains("first run line"), "and not the old one");

            previous.setSelected(true);
            assertTrue(panel.isShowingPrevious());
            assertTrue(panel.shownText().contains("first run line"), "the toggle shows the previous run");
            assertTrue(panel.shownText().contains("BUILD FAILURE"));
            assertEquals(
                    tr("console.previousRun.header", tr("run.exited", 1)),
                    status(panel).getText(),
                    "the header names the previous run's verdict");

            panel.appendOutput("[INFO] still streaming", false);
            panel.finished(0);
            assertEquals(
                    tr("console.previousRun.header", tr("run.exited", 1)),
                    status(panel).getText(),
                    "the live run's exit does not take over the previous run's header");

            previous.setSelected(false);
            assertFalse(panel.isShowingPrevious());
            assertEquals(tr("run.exited", 0), status(panel).getText(), "back to the live run's header");
            assertTrue(panel.shownText().contains("still streaming"), "output kept streaming into the live console");

            panel.started("package", OutputStyle.maven(), () -> {});
            previous.setSelected(true);
            assertTrue(panel.shownText().contains("second run line"), "the previous run is now the second one");
            assertFalse(panel.shownText().contains("first run line"), "one run back, not two");

            panel.started("verify", OutputStyle.maven(), () -> {});
            assertFalse(panel.isShowingPrevious(), "a new run starting while the previous one is up shows the new run");
        });
    }

    @Test
    void aStoppedBuildReadsStoppedNotItsExitCode() throws Exception {
        FxTestSupport.runOnFx(() -> {
            BuildToolPanel panel = new BuildToolPanel();
            panel.started("clean verify", OutputStyle.maven(), () -> {});
            panel.appendOutput("[INFO] Scanning for projects...", false);
            panel.stopped();
            assertEquals(tr("run.stopped"), status(panel).getText());
            assertFalse(panel.runningProperty().get(), "the spinner stops");

            panel.started("compile", OutputStyle.maven(), () -> {});
            panel.finished(143);
            assertEquals(tr("run.exited", 143), status(panel).getText(), "a code the user did not cause stays a code");
        });
    }

    @Test
    void theFindBarSearchesTheShownBufferAndStepsThroughMatches() throws Exception {
        FxTestSupport.runOnFx(() -> {
            BuildToolPanel panel = new BuildToolPanel();
            panel.started("compile", OutputStyle.maven(), () -> {});
            panel.appendOutput("[ERROR] alpha one", false);
            panel.appendOutput("[INFO] nothing here", false);
            panel.appendOutput("[ERROR] Alpha two", false);
            ConsoleFindBar bar = panel.findBar();
            assertFalse(bar.isShowing());

            panel.openFind();
            assertTrue(bar.isShowing());
            bar.field().setText("alpha");
            assertEquals(tr("find.count", 1, "2"), bar.countText(), "case-insensitive by default");
            org.fxmisc.richtext.CodeArea out = FxTestSupport.field(panel, "output");
            assertEquals("alpha", out.getSelectedText());

            KeyEvent enter = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
            bar.field().fireEvent(enter);
            assertEquals(tr("find.count", 2, "2"), bar.countText());
            assertEquals("Alpha", out.getSelectedText());
            bar.field().fireEvent(enter);
            assertEquals(tr("find.count", 1, "2"), bar.countText(), "wraps around");

            bar.field().setText("zzz");
            assertEquals(tr("search.none"), bar.countText());

            panel.appendOutput("[INFO] zzz arrived later", false);
            bar.field().fireEvent(enter);
            assertEquals(tr("find.count", 1, "1"), bar.countText(), "output appended after opening is searched too");

            KeyEvent escape = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false);
            bar.field().fireEvent(escape);
            assertFalse(bar.isShowing());
        });
    }

    @Test
    void saveOutputWritesTheShownBuffer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("maven-output.txt");
        FxTestSupport.runOnFx(() -> {
            BuildToolPanel panel = new BuildToolPanel();
            panel.started("compile", OutputStyle.maven(), () -> {});
            panel.appendOutput("[INFO] line one", false);
            panel.appendOutput("[ERROR] line two", true);
            panel.finished(1);
            panel.saveOutputTo(file);
        });
        assertEquals("[INFO] line one\n[ERROR] line two\n", Files.readString(file));
    }

    @Test
    void aTranscriptTabHasNoPreviousRun() throws Exception {
        FxTestSupport.runOnFx(() -> {
            BuildToolPanel panel = new BuildToolPanel();
            panel.setLogMode(true);
            ToggleButton previous = previousButton(panel);
            assertFalse(previous.isVisible());
            assertFalse(previous.isManaged());
        });
    }
}

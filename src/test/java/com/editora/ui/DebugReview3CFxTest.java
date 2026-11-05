package com.editora.ui;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;

import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.dap.DapManager;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review 3, reviewer C: the Debug window's states, its Variables menu from the keyboard, and the consoles' colours. */
@Tag("fx")
class DebugReview3CFxTest {

    @BeforeAll
    static void initFx() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static DebugPanel.Actions noop() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    /** C204: during the build before a launch the window said "Not debugging" (or the last exit) with the idle hint. */
    @Test
    void aLaunchGettingReadySaysBuildingWithStopAndHowToCancel() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(noop());
            Label status = FxTestSupport.field(panel, "status");
            Label hint = FxTestSupport.field(panel, "stackHint");
            Button stop = FxTestSupport.field(panel, "stop");
            panel.setState(DapManager.State.INACTIVE);
            panel.setExitCode(0);
            panel.setSessionFile("Main.java"); // the next launch's file (nameSession)
            assertTrue(status.getText().startsWith(tr("debugpanel.state.exited", 0)), status.getText());

            panel.setPreparing(true);
            assertEquals(tr("debugpanel.state.building") + " — Main.java", status.getText());
            assertEquals(tr("debugpanel.preparingHint"), hint.getText());
            assertFalse(stop.isDisabled(), "Stop cancels the build");

            panel.setPreparing(true, tr("debugpanel.state.waitingTest"));
            assertTrue(status.getText().startsWith(tr("debugpanel.state.waitingTest")), status.getText());

            panel.setPreparing(false);
            assertTrue(status.getText().startsWith(tr("debugpanel.state.exited", 0)), "back to what it was");
            assertEquals(tr("debugpanel.endedHint"), hint.getText());
            assertTrue(stop.isDisabled());
        });
    }

    /** Choice 27: with the program console off a flood's output trails its exit, and the state still said Running. */
    @Test
    void aProgramWhoseOutputTrailsItsEndSaysSoInOneMutedLine() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(noop());
            Label status = FxTestSupport.field(panel, "status");
            Button pause = FxTestSupport.field(panel, "pause");
            CodeArea console = FxTestSupport.field(panel, "console");
            panel.setState(DapManager.State.RUNNING);
            panel.appendOutput("line 1\nline 2 without its end", "stdout");
            assertFalse(pause.isDisabled());

            panel.showOutputTrailing();
            assertTrue(status.getText().startsWith(tr("debugpanel.state.ending")), status.getText());
            assertTrue(pause.isDisabled(), "nothing left to pause");
            String text = console.getText();
            String notice = tr("debug.notice.outputTrailing");
            assertTrue(text.contains("without its end\n" + notice + "\n"), "on a line of its own: " + text);
            assertTrue(
                    console.getStyleOfChar(text.indexOf(notice)).contains("run-command"), "muted, as Editora's lines");

            panel.appendOutput("line 3\n", "stdout"); // the output keeps arriving after it
            assertTrue(status.getText().startsWith(tr("debugpanel.state.ending")), status.getText());
            panel.setState(DapManager.State.INACTIVE);
            assertFalse(status.getText().startsWith(tr("debugpanel.state.ending")), status.getText());
        });
    }

    /** A104: build errors and the Attach hint were cut at the console's edge at 1400 px. */
    @Test
    void theDebugConsoleWrapsItsLines() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea console = FxTestSupport.field(new DebugPanel(noop()), "console");
            assertTrue(console.isWrapText());
        });
    }

    /** C207: from the keyboard the Variables menu opened in the middle of the tree, not at the selected row. */
    @Test
    void theVariablesMenuFromTheKeyboardOpensAtTheSelectedRow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(noop());
            Stage stage = new Stage();
            try {
                stage.setScene(new Scene(new StackPane(panel), 1200, 500));
                stage.show();
                panel.applyCss();
                panel.layout();
                TreeView<?> variables = FxTestSupport.field(panel, "variables");
                variables.getSelectionModel().select(1); // "+ Add watch…" under Watches
                Bounds row = RowContextMenu.selectedRowOnScreen(variables);
                assertNotNull(row);
                Bounds tree = variables.localToScreen(variables.getBoundsInLocal());
                variables.fireEvent(new ContextMenuEvent(
                        ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        variables.getWidth() / 2,
                        variables.getHeight() / 2,
                        tree.getCenterX(),
                        tree.getCenterY(),
                        true,
                        null));
                ContextMenu menu = Objects.requireNonNull(variables.getContextMenu());
                assertTrue(menu.isShowing());
                assertEquals(row.getMaxY(), menu.getAnchorY(), 1.0, "under the selected row");
                assertTrue(Math.abs(tree.getCenterY() - menu.getAnchorY()) > 5, "not in the middle of the tree");
                menu.hide();
            } finally {
                stage.hide();
            }
        });
    }

    /**
     * C205: the console's own lines are drawn in the UI's colours; on a dark UI with a light editor theme (and the
     * other way round) stderr measured 3.3:1 on the editor's background. Marked, the console takes the UI's
     * background: the Debug console and the Run console alike.
     */
    @Test
    void theConsolesUnderMixedThemesDrawStderrOnTheBackgroundItWasMadeFor() throws Exception {
        for (String[] themes : List.of(
                new String[] {"Editora Dark", "editora-light"}, new String[] {"Editora Light", "editora-dark"})) {
            double[] contrast = FxTestSupport.callOnFx(() -> {
                DebugPanel debug = new DebugPanel(noop());
                debug.appendOutput("Exception in thread main\n", "stderr");
                RunPanel run = new RunPanel(() -> {});
                run.appendOutput("Traceback (most recent call last):", true);
                CodeArea debugConsole = FxTestSupport.field(debug, "console");
                CodeArea runConsole = FxTestSupport.field(run, "output");
                double before = stderrContrast(debug, debugConsole, themes);
                debug.setPlainValueColours(true);
                run.setUiColours(true);
                return new double[] {
                    before, stderrContrast(debug, debugConsole, themes), stderrContrast(run, runConsole, themes)
                };
            });
            String which = themes[0] + " UI, " + themes[1] + " editor";
            assertTrue(contrast[0] < 4.5, which + ": the problem the fix is for, " + contrast[0]);
            assertTrue(contrast[1] >= 4.5, which + ": Debug console stderr " + contrast[1]);
            assertTrue(contrast[2] >= 4.5, which + ": Run console stderr " + contrast[2]);
        }
    }

    /** The contrast of {@code console}'s stderr text on its background, {@code panel} rendered under {@code themes}. */
    private static double stderrContrast(Parent panel, CodeArea console, String[] themes) {
        StackPane root = new StackPane(panel);
        Stage stage = new Stage();
        try {
            Scene scene = new Scene(root, 800, 300);
            scene.getStylesheets().add(Objects.requireNonNull(Themes.stylesheetFor(themes[0])));
            for (String sheet : List.of("app.css", "syntax.css", "editor-themes/" + themes[1] + ".css")) {
                scene.getStylesheets()
                        .add(Objects.requireNonNull(
                                        DebugReview3CFxTest.class.getResource("/com/editora/styles/" + sheet))
                                .toExternalForm());
            }
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            Text text = console.lookupAll(".run-stderr").stream()
                    .filter(Text.class::isInstance)
                    .map(Text.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no stderr text"));
            Background bg = console.getBackground();
            Color back = bg == null || bg.getFills().isEmpty()
                    ? Color.WHITE
                    : (Color) bg.getFills().get(bg.getFills().size() - 1).getFill();
            return contrast((Color) text.getFill(), back);
        } finally {
            stage.hide();
            root.getChildren().clear();
        }
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}

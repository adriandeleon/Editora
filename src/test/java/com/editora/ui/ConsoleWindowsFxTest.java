package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.run.StackTraceLinks;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Run console's mouse — a URL opens on one click, a stack-trace line jumps on two — and the Debug Log
 * window's buttons, each in a window that is really shown.
 */
@Tag("fx")
class ConsoleWindowsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private Stage stage;

    @AfterEach
    void close() throws Exception {
        FxTestSupport.runOnFx(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
        DebugLog.clear();
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, double y, int clicks) {
        return new MouseEvent(
                type,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                clicks,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                null);
    }

    private RunPanel shownRunPanel(Runnable onStop) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            RunPanel panel = new RunPanel(onStop);
            stage = new Stage();
            stage.setScene(new Scene(panel, 900, 500));
            stage.show();
            return panel;
        });
    }

    private static void layout(RunPanel panel) {
        panel.applyCss();
        panel.layout();
    }

    /**
     * A point over a character of the console whose index satisfies {@code wanted} — found by asking the
     * console itself what is under each point, as its mouse handlers do — in scene coordinates, which is
     * what a mouse event is delivered in. FX thread.
     */
    private static Point2D pointOver(CodeArea output, java.util.function.IntPredicate wanted) {
        for (double y = 2; y < 80; y += 3) {
            for (double x = 2; x < output.getWidth(); x += 3) {
                var hit = output.hit(x, y);
                if (hit.getCharacterIndex().isPresent()
                        && wanted.test(hit.getCharacterIndex().getAsInt())) {
                    return output.localToScene(x, y);
                }
            }
        }
        throw new AssertionError("no such character is on screen");
    }

    // --- Run console -------------------------------------------------------------------------------------

    @Test
    void aUrlInProgramOutputOpensOnOneClickAndShowsAHandUnderThePointer() throws Exception {
        RunPanel panel = shownRunPanel(null);
        List<String> opened = new ArrayList<>();
        CodeArea output = FxTestSupport.field(panel, "output");
        String line = "Started on http://localhost:8080/app in 2 s";
        FxTestSupport.runOnFx(() -> {
            panel.setOnUrl(opened::add);
            panel.started("server");
            panel.appendOutput(line, false);
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> layout(panel));
        FxTestSupport.drainFx();

        FxTestSupport.runOnFx(() -> {
            int url = line.indexOf("http://");
            Point2D onUrl = pointOver(output, i -> i > url + 4 && i < url + 20);
            Point2D onText = pointOver(output, i -> i > 0 && i < url - 4);

            output.fireEvent(mouse(MouseEvent.MOUSE_MOVED, onText.getX(), onText.getY(), 0));
            assertEquals(Cursor.TEXT, output.getCursor());
            output.fireEvent(mouse(MouseEvent.MOUSE_MOVED, onUrl.getX(), onUrl.getY(), 0));
            assertEquals(Cursor.HAND, output.getCursor(), "a link looks like one");
            output.fireEvent(mouse(MouseEvent.MOUSE_EXITED, onUrl.getX(), onUrl.getY(), 0));
            assertNull(output.getCursor());

            output.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, onText.getX(), onText.getY(), 1));
            assertEquals(List.of(), opened, "a click on ordinary output opens nothing");
            output.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, onUrl.getX(), onUrl.getY(), 2));
            assertEquals(List.of(), opened, "a double click is for stack-trace lines");
            output.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, onUrl.getX(), onUrl.getY(), 1));
            assertEquals(List.of("http://localhost:8080/app"), opened);

            panel.setOnUrl(null);
            output.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, onUrl.getX(), onUrl.getY(), 1));
            assertEquals(1, opened.size(), "with nowhere to open it, a click is only a click");
        });
    }

    @Test
    void aDoubleClickOnAStackTraceLineJumpsToItsLocation() throws Exception {
        RunPanel panel = shownRunPanel(null);
        List<StackTraceLinks.Link> jumped = new ArrayList<>();
        CodeArea output = FxTestSupport.field(panel, "output");
        FxTestSupport.runOnFx(() -> {
            panel.setOnLink(jumped::add);
            panel.started("App");
            panel.appendOutput("Exception in thread \"main\" java.lang.IllegalStateException: boom", true);
            panel.appendOutput("\tat demo.App.run(App.java:12)", true);
            panel.appendOutput("done", false);
        });
        FxTestSupport.drainFx();

        FxTestSupport.runOnFx(() -> {
            String text = output.getText();
            output.moveTo(text.indexOf("demo.App.run") + 3);
            output.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 1));
            assertEquals(List.of(), jumped, "one click only places the caret");
            output.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 2));
            assertEquals(1, jumped.size());
            assertEquals("App.java", jumped.get(0).file());
            assertEquals(12, jumped.get(0).line());

            output.moveTo(text.length()); // the last line, which names no location
            output.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 2));
            output.moveTo(2); // the exception's own line: no file there either
            output.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 2));
            assertEquals(1, jumped.size());

            panel.setOnLink(null);
            output.moveTo(text.indexOf("demo.App.run"));
            output.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 2));
            assertEquals(1, jumped.size());
        });
    }

    @Test
    void aPlainTextConsoleJumpsFromAStackTraceLineTheSameWay() throws Exception {
        List<StackTraceLinks.Link> jumped = new ArrayList<>();
        List<java.util.function.Consumer<StackTraceLinks.Link>> handler = new ArrayList<>();
        handler.add(jumped::add);
        FxTestSupport.runOnFx(() -> {
            TextArea console = new TextArea("$ make test\n  File \"/work/tool.py\", line 7, in main\nexit 1");
            RunPanel.installLinkClicks(console, () -> handler.get(0));

            console.positionCaret(console.getText().indexOf("tool.py"));
            console.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 1));
            assertEquals(List.of(), jumped);
            console.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 2));
            assertEquals(1, jumped.size());
            assertEquals(7, jumped.get(0).line());
            assertTrue(jumped.get(0).file().endsWith("tool.py"), jumped.get(0).file());

            console.positionCaret(console.getText().length()); // "exit 1": the last line, no newline after it
            console.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 2));
            console.positionCaret(0);
            console.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 2));
            assertEquals(1, jumped.size(), "a line with no location jumps nowhere");

            handler.set(0, null);
            console.positionCaret(console.getText().indexOf("tool.py"));
            console.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, 5, 5, 2));
            assertEquals(1, jumped.size());
        });
    }

    @Test
    void stopAndClearActOnTheRunAndAKilledRunReadsAsStopped() throws Exception {
        int[] stops = {0};
        RunPanel panel = shownRunPanel(() -> stops[0]++);
        Button stop = FxTestSupport.field(panel, "stopButton");
        Button clear = FxTestSupport.field(panel, "clearButton");
        Label status = FxTestSupport.field(panel, "status");
        CodeArea output = FxTestSupport.field(panel, "output");

        FxTestSupport.runOnFx(() -> {
            assertTrue(stop.isDisabled(), "nothing is running yet");
            panel.started("server --port 8080");
            assertEquals(tr("run.running", "server --port 8080"), status.getText());
            panel.appendPartialOutput("password: ", false);
            panel.appendOutput("", false);
            panel.setOutputFont("Monospaced", 15);
            assertFalse(stop.isDisabled());
            stop.fire();
        });
        FxTestSupport.drainFx();
        assertEquals(1, stops[0]);
        assertEquals("password: \n", FxTestSupport.callOnFx(output::getText));
        assertTrue(FxTestSupport.callOnFx(output::getStyle).contains("15px"));

        FxTestSupport.runOnFx(() -> {
            panel.finished(-1);
            assertEquals(tr("run.stopped"), status.getText(), "a negative exit is a run that was killed");
            assertTrue(stop.isDisabled());
            clear.fire();
            assertEquals("", output.getText());
            panel.failed("Cannot run program \"server\"");
            assertEquals(tr("run.failed", "Cannot run program \"server\""), status.getText());
            panel.idle();
            assertEquals(tr("run.idle"), status.getText());
        });

        RunPanel unwired = FxTestSupport.callOnFx(() -> new RunPanel(null));
        FxTestSupport.runOnFx(() -> {
            unwired.started("x");
            ((Button) FxTestSupport.field(unwired, "stopButton")).fire(); // no handler: nothing to do, no failure
            javafx.scene.control.TextField input = FxTestSupport.field(unwired, "input");
            input.setText("typed with nobody listening");
            input.fireEvent(new javafx.event.ActionEvent());
            assertEquals("typed with nobody listening", input.getText(), "an unsent line is not swallowed");
        });
    }

    // --- Debug Log window --------------------------------------------------------------------------------

    private static Button button(Stage window, String label) {
        return (Button) window.getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && label.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + label + "' button"));
    }

    @Test
    void theDebugLogWindowShowsTheLogAndItsButtonsRefreshClearAndClose() throws Exception {
        DebugLog.clear();
        DebugLog.append("first record");
        Path sessionFile = Path.of("config", "editora-session.log").toAbsolutePath();
        DebugLogWindow window = FxTestSupport.callOnFx(DebugLogWindow::new);
        FxTestSupport.runOnFx(() -> {
            window.setSessionFile(sessionFile);
            window.show(null);
        });
        stage = FxTestSupport.field(window, "stage");
        TextArea area = FxTestSupport.field(window, "area");
        Label fileLabel = FxTestSupport.field(window, "fileLabel");

        FxTestSupport.runOnFx(() -> {
            assertTrue(stage.isShowing());
            assertEquals(tr("debuglog.title"), stage.getTitle());
            assertEquals("first record", area.getText());
            assertEquals(tr("debuglog.file", sessionFile.toString()), fileLabel.getText());

            DebugLog.append("second record");
            assertEquals("first record", area.getText(), "the window shows a snapshot");
            button(stage, tr("debuglog.refresh")).fire();
            assertTrue(area.getText().endsWith("second record"));
            assertEquals(area.getLength(), area.getCaretPosition(), "the newest entry is where the caret is");

            window.show(null); // already open: brought forward and brought up to date, not built twice
            assertTrue(stage.isShowing());

            button(stage, tr("debuglog.clear")).fire();
            assertEquals("", area.getText());
            assertEquals("", DebugLog.snapshot(), "Clear empties the captured log, not only the view");

            button(stage, tr("debuglog.close")).fire();
            assertFalse(stage.isShowing());

            window.setSessionFile(null);
            DebugLog.append("after reopening");
            window.show(null);
            assertTrue(stage.isShowing());
            assertEquals("after reopening", area.getText());
            assertEquals("", fileLabel.getText(), "no session file: nothing to name");
        });
    }
}

package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Debug console's one input field does two jobs once the debugged program can be typed to: while the
 * program <em>runs</em>, Enter sends the line to its standard input; while it is <em>paused</em>, Enter
 * evaluates an expression, as it always did. Before, Enter while running only printed a hint and a program
 * reading {@code System.in} waited forever.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugPanelProgramInputFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Records what the panel asks for: {@code in:<line>}, {@code eof}, {@code eval:<expression>}. */
    private static DebugPanel.Actions recording(List<String> calls) {
        return new DebugPanel.Actions() {
            @Override
            public void start() {}

            @Override
            public void pause() {}

            @Override
            public void stepOver() {}

            @Override
            public void stepInto() {}

            @Override
            public void stepOut() {}

            @Override
            public void runToCursor() {}

            @Override
            public void stop() {}

            @Override
            public void restart() {}

            @Override
            public void selectThread(int threadId) {}

            @Override
            public void selectFrame(DapModels.StackFrameInfo frame) {}

            @Override
            public void loadChildren(int variablesReference, Consumer<List<DapModels.VariableInfo>> cb) {}

            @Override
            public void evaluate(String expression, int frameId, Consumer<String> cb) {
                calls.add("eval:" + expression);
                cb.accept("42");
            }

            @Override
            public void evaluateWatch(String expression, int frameId, Consumer<DapModels.EvalResult> cb) {}

            @Override
            public void setVariable(int parentRef, String name, String value, Consumer<String> cb) {}

            @Override
            public void sendInput(String line) {
                calls.add("in:" + line);
            }

            @Override
            public void endInput() {
                calls.add("eof");
            }
        };
    }

    private static TextField field(DebugPanel panel) {
        return FxTestSupport.field(panel, "evalInput");
    }

    private static String console(DebugPanel panel) {
        CodeArea area = FxTestSupport.field(panel, "console");
        return area.getText();
    }

    private static void enter(TextField field, String text) {
        field.setText(text);
        field.fireEvent(new javafx.event.ActionEvent());
    }

    private static void controlD(TextField field) {
        field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.D, false, true, false, false));
    }

    @Test
    void whileTheProgramRunsEnterSendsTheLineToItsInput() throws Exception {
        List<String> calls = new ArrayList<>();
        String[] seen = FxTestSupport.callOnFx(() -> {
            DebugPanel panel = new DebugPanel(recording(calls));
            TextField field = field(panel);
            panel.setState(DapManager.State.STARTING);
            panel.setState(DapManager.State.RUNNING);
            panel.setProgramInput(true); // the program was started: it can be typed to
            String prompt = field.getPromptText();
            enter(field, "hello world");
            String after = field.getText();
            enter(field, ""); // an empty line is input too
            controlD(field);
            return new String[] {prompt, after, console(panel)};
        });
        assertEquals(tr("debugpanel.inputPrompt"), seen[0]);
        assertEquals("", seen[1], "the sent line leaves the field");
        assertEquals(List.of("in:hello world", "in:", "eof"), calls);
        assertEquals("hello world\n\n", seen[2], "typed lines are echoed: the program does not echo them");
    }

    @Test
    void whileTheProgramIsPausedTheSameFieldEvaluates() throws Exception {
        List<String> calls = new ArrayList<>();
        String[] seen = FxTestSupport.callOnFx(() -> {
            DebugPanel panel = new DebugPanel(recording(calls));
            TextField field = field(panel);
            panel.setState(DapManager.State.RUNNING);
            panel.setProgramInput(true);
            panel.setState(DapManager.State.SUSPENDED);
            String paused = field.getPromptText();
            enter(field, "a + b");
            controlD(field); // not the program's input now: nothing to end
            panel.setState(DapManager.State.RUNNING);
            String resumed = field.getPromptText();
            enter(field, "typed");
            return new String[] {paused, resumed, console(panel)};
        });
        assertEquals(tr("debugpanel.evalPrompt"), seen[0]);
        assertEquals(tr("debugpanel.inputPrompt"), seen[1], "running again: the field is the program's input again");
        assertEquals(List.of("eval:a + b", "in:typed"), calls);
        assertEquals("> a + b\n42\ntyped\n", seen[2]);
    }

    @Test
    void aProgramThatCannotBeTypedToStillGetsTheHintAndTheNextSessionStartsWithoutInput() throws Exception {
        List<String> calls = new ArrayList<>();
        String[] seen = FxTestSupport.callOnFx(() -> {
            DebugPanel panel = new DebugPanel(recording(calls));
            TextField field = field(panel);
            panel.setState(DapManager.State.RUNNING);
            panel.setProgramInput(true);
            panel.setProgramInput(false); // its input was ended, or it exited
            enter(field, "late");
            String kept = field.getText();
            String hint = console(panel);
            panel.setProgramInput(true);
            panel.setState(DapManager.State.INACTIVE);
            panel.setState(DapManager.State.STARTING);
            panel.setState(DapManager.State.RUNNING); // an attach, say: nobody said it takes input
            return new String[] {kept, hint, field.getPromptText()};
        });
        assertEquals(List.of(), calls);
        assertEquals("late", seen[0], "nothing was sent, so nothing is taken away");
        assertEquals(tr("debugpanel.evalNeedsPause") + "\n", seen[1]);
        assertEquals(tr("debugpanel.evalPrompt"), seen[2]);
    }

    /** A prompt ends without a newline; what Editora itself says must not read as part of it. */
    @Test
    void aNoticeStartsOnItsOwnLineAfterAnUnfinishedLineOfOutput() throws Exception {
        String console = FxTestSupport.callOnFx(() -> {
            DebugPanel panel = new DebugPanel(recording(new ArrayList<>()));
            panel.appendNotice("first");
            panel.appendOutput("more? ", "stdout");
            panel.showInputEnded();
            panel.appendNotice("last");
            return console(panel);
        });
        assertEquals("first\nmore? \n" + tr("debugpanel.inputEnded") + "\nlast\n", console);
    }
}

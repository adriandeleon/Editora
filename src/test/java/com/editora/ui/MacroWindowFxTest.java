package com.editora.ui;

import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.editor.EditorBuffer;
import com.editora.macro.Macro;
import com.editora.macro.MacroService;
import com.editora.macro.MacroStep;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyboard macros in a real window: keys go in through the scene (so through the {@code KeyDispatcher}, as
 * the keyboard's do), and what a replay does is compared with what the same keys did when pressed by hand.
 *
 * <p>The window uses the default (Emacs) keymap: {@code F3}/{@code F4} record, {@code C-x e} replays,
 * {@code C-s} finds, {@code C-u} is the prefix argument and {@code C-g} cancels.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroWindowFxTest {

    private FxWindowFixture fx;
    private CommandRegistry reg;
    private MacroCoordinator macros;
    private MacroService service;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            reg = FxTestSupport.field(fx.controller, "registry");
            macros = FxTestSupport.field(fx.controller, "macroCoordinator");
            service = FxTestSupport.field(macros, "service");
            reg.run("file.new");
        });
        settle();
    }

    @AfterAll
    void tearDown() throws Exception {
        fx.dispose();
    }

    @BeforeEach
    void clean() throws Exception {
        on(() -> {
            if (macros.isRecording()) {
                reg.run("macro.stopRecording");
            }
            OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
            overlay.hide();
            reg.run("edit.cancel");
            buf().setViewMode(false);
            buf().setLanguageOverride(null);
            set("", 0);
            area().requestFocus();
        });
    }

    // ---------------------------------------------------------------- harness

    private EditorBuffer buf() {
        return (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
    }

    private CodeArea area() {
        return buf().getArea();
    }

    /**
     * Whatever has the keyboard focus. A control shown in this event-loop turn has no skin — nothing that
     * handles a key — until the next pulse; a person never types that fast, so the harness lets it settle.
     */
    private Node focus() {
        Node owner = area().getScene().getFocusOwner();
        if (owner instanceof javafx.scene.control.Control control && control.getSkin() == null) {
            control.applyCss();
        }
        return owner == null ? area() : owner;
    }

    private void set(String text, int caret) {
        buf().setContent(text);
        area().moveTo(caret);
        area().getUndoManager().forgetHistory();
    }

    private String text() {
        return area().getText();
    }

    private String status() {
        StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
        Label echo = FxTestSupport.field(bar, "echo");
        return echo.getText();
    }

    /** A key press aimed at whatever has the focus, like the keyboard's. */
    private void press(KeyCode code, boolean shift, boolean ctrl) {
        focus().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, false));
    }

    private void press(KeyCode code) {
        press(code, false, false);
    }

    private void typed(String ch) {
        focus().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, false, false, false));
    }

    /** A whole physical key: the press, then the character it types. */
    private void key(KeyCode code, String ch) {
        press(code);
        typed(ch);
    }

    private void type(String s) {
        for (char c : s.toCharArray()) {
            KeyCode code = c == ' ' ? KeyCode.SPACE : KeyCode.getKeyCode(String.valueOf(Character.toUpperCase(c)));
            key(code == null ? KeyCode.UNDEFINED : code, String.valueOf(c));
        }
    }

    private void enter() {
        key(KeyCode.ENTER, "\r");
    }

    private void tab(boolean shift) {
        press(KeyCode.TAB, shift, false);
        typed("\t");
    }

    private List<MacroStep> last() {
        Macro m = service.last();
        return m == null ? null : m.steps();
    }

    /** Runs {@code r} on the FX thread, then lets deferred work (an overlay taking the focus) and any replay finish. */
    private void on(Runnable r) throws Exception {
        FxTestSupport.runOnFx(r);
        settle();
    }

    private void settle() throws Exception {
        await(() -> !macros.isReplaying(), 60_000);
        for (int i = 0; i < 4; i++) {
            FxTestSupport.drainFx();
        }
    }

    private void await(BooleanSupplier done, long millis) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (!FxTestSupport.callOnFx(done::getAsBoolean)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting; status=" + FxTestSupport.callOnFx(this::status));
            }
            Thread.sleep(5);
        }
    }

    /** Waits for an overlay prompt's field to hold the focus — it takes it a turn or two after it is shown. */
    private void awaitPromptFocus() throws Exception {
        await(() -> focus() instanceof javafx.scene.control.TextInputControl, 5_000);
    }

    private <T> T get(java.util.concurrent.Callable<T> c) throws Exception {
        return FxTestSupport.callOnFx(c);
    }

    /** {@code C-x e}, as typed: Ctrl+X, then E — which also delivers the character "e". */
    private void replayChord() {
        press(KeyCode.X, false, true);
        key(KeyCode.E, "e");
    }

    // ---------------------------------------------------------------- M1

    /**
     * M1: replaying through the real chord. A key step fires a key press at the editor; that used to re-enter
     * the dispatcher and clear the flag that swallows the chord's own "e", which was then typed.
     */
    @Test
    void replayingByChordDoesNotTypeTheChordsLastKey() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("ab");
            press(KeyCode.BACK_SPACE);
            type("c");
            reg.run("macro.stopRecording");
        });
        assertEquals(List.of(MacroStep.text("ab"), MacroStep.key("BACK_SPACE"), MacroStep.text("c")), get(this::last));
        on(() -> {
            set("", 0);
            replayChord();
        });
        assertEquals("ac", get(this::text));
    }

    // ---------------------------------------------------------------- M2

    /** M2: {@code C-s foo Enter Esc Z} replays as a search followed by typing Z in the editor. */
    @Test
    void aSearchTypedIntoTheFindBarReplaysAsASearch() throws Exception {
        String doc = "one foo two foo three foo";
        on(() -> {
            set(doc, 0);
            reg.run("macro.startRecording");
            press(KeyCode.S, false, true); // C-s
        });
        assertFalse(get(() -> buf().ownsKeyTarget(focus())), "the find bar has the focus");
        on(() -> type("foo"));
        on(() -> enter());
        on(() -> press(KeyCode.ESCAPE));
        assertTrue(get(() -> macros.isRecording()), "Escape in the find bar closes it; it does not cancel the macro");
        on(() -> {
            area().requestFocus();
            type("Z");
            reg.run("macro.stopRecording");
        });
        String live = get(this::text);
        assertEquals(
                List.of(
                        MacroStep.command("find.show"),
                        MacroStep.text("foo", true),
                        MacroStep.key("ENTER", true),
                        MacroStep.key("ESCAPE", true),
                        MacroStep.text("Z")),
                get(this::last));
        assertNotEqualsDoc(doc, live);

        on(() -> {
            set(doc, 0);
            area().requestFocus();
            reg.run("macro.replayLast");
        });
        assertEquals(live, get(this::text), "the replay searched, then typed Z where the search left the caret");
        assertTrue(get(() -> buf().ownsKeyTarget(focus())), "the find bar is closed again");
    }

    private static void assertNotEqualsDoc(String doc, String live) {
        assertFalse(doc.equals(live), "the live keys changed the document");
        assertFalse(live.startsWith("Z"), "Z went where the search put the caret, not to the start: " + live);
    }

    /** M2: an overlay prompt takes the focus a turn after it opens; the replay waits for it. */
    @Test
    void aLineNumberTypedIntoTheGoToLinePromptReplays() throws Exception {
        String doc = "a\nb\nc\nd\ne";
        on(() -> {
            set(doc, 0);
            reg.run("macro.startRecording");
            reg.run("nav.goToLine");
        });
        assertFalse(get(() -> buf().ownsKeyTarget(focus())), "the prompt has the focus");
        on(() -> type("4"));
        on(() -> enter());
        on(() -> {
            type("X");
            reg.run("macro.stopRecording");
        });
        assertEquals("a\nb\nc\nXd\ne", get(this::text));
        assertEquals(
                List.of(
                        MacroStep.command("nav.goToLine"),
                        MacroStep.text("4", true),
                        MacroStep.key("ENTER", true),
                        MacroStep.text("X")),
                get(this::last));

        on(() -> {
            set(doc, 0);
            reg.run("macro.replayLast");
        });
        assertEquals("a\nb\nc\nXd\ne", get(this::text));
        // Twice in one replay: the prompt opens, is driven and closes once per pass.
        on(() -> {
            set(doc, 0);
            FxTestSupport.call(macros, "replayLast", new Class<?>[] {int.class}, 2);
        });
        assertEquals("a\nb\nc\nXXd\ne", get(this::text));
    }

    /** M2: a command run from the palette is recorded as that command — not as the keys that found it. */
    @Test
    void aCommandRunFromThePaletteReplaysAsThatCommand() throws Exception {
        on(() -> {
            set("hello", 5);
            reg.run("macro.startRecording");
            reg.run("palette.show");
        });
        on(() -> type(tr("command.edit.selectAll")));
        on(() -> enter());
        on(() -> {
            type("Q");
            reg.run("macro.stopRecording");
        });
        assertEquals("Q", get(this::text));
        assertEquals(List.of(MacroStep.command("edit.selectAll"), MacroStep.text("Q")), get(this::last));
        on(() -> {
            set("something else", 3);
            reg.run("macro.replayLast");
        });
        assertEquals("Q", get(this::text));
    }

    /** M2: a picker's keys are recorded, and the action Enter runs there is not recorded a second time. */
    @Test
    void aChoiceMadeInAPickerReplays() throws Exception {
        on(() -> reg.run("file.new"));
        EditorBuffer start = get(this::buf);
        on(() -> {
            reg.run("macro.startRecording");
            reg.run("buffer.jump");
        });
        on(() -> press(KeyCode.DOWN));
        on(() -> enter());
        EditorBuffer picked = get(this::buf);
        on(() -> {
            type("P");
            reg.run("macro.stopRecording");
        });
        List<MacroStep> steps = get(this::last);
        assertEquals(MacroStep.command("buffer.jump"), steps.get(0));
        assertEquals(MacroStep.key("DOWN", true), steps.get(1));
        assertEquals(MacroStep.key("ENTER", true), steps.get(2));
        assertEquals(MacroStep.text("P"), steps.get(steps.size() - 1));
        assertEquals(4, steps.size(), "nothing Enter ran was recorded on top of the Enter: " + steps);

        // Back to where the recording began, then replay: the same tab is picked and typed into.
        for (int i = 0; i < 4 && get(this::buf) != start; i++) {
            on(() -> reg.run("buffer.next"));
        }
        assertTrue(get(this::buf) == start, "precondition: back on the starting tab");
        on(() -> picked.setContent(""));
        on(() -> reg.run("macro.replayLast"));
        assertTrue(get(this::buf) == picked, "the replay picked the same tab");
        assertEquals("P", get(() -> picked.getArea().getText()));
        on(() -> picked.setContent("")); // left open and unmodified: closing a modified tab asks first
    }

    /**
     * The "this command was the last key's doing" window is short: a command the user picks afterwards
     * from a menu that sends the window no key or mouse press (the macOS system menu bar) is recorded.
     */
    @Test
    void aCommandRunLaterWithoutAKeyPressIsStillRecorded() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("ab");
        });
        on(() -> reg.run("edit.selectAll"));
        on(() -> {
            type("Q");
            reg.run("macro.stopRecording");
        });
        assertEquals(
                List.of(MacroStep.text("ab"), MacroStep.command("edit.selectAll"), MacroStep.text("Q")),
                get(this::last));
    }

    /** A command that blocks in a dialog cannot be driven by a replay: recording says so at once. */
    @Test
    void aCommandThatOpensABlockingDialogIsFlaggedWhileRecording() throws Exception {
        on(() -> {
            reg.register(Command.of("test.modal", "Modal Thing", () -> {
                Object key = new Object();
                Platform.runLater(() -> Platform.exitNestedEventLoop(key, null));
                Platform.enterNestedEventLoop(key);
            }));
            reg.run("macro.startRecording");
            reg.run("test.modal");
        });
        assertEquals(tr("status.macro.dialogCannotReplay", "Modal Thing"), get(this::status));
        on(() -> {
            reg.run("macro.stopRecording");
            reg.remove("test.modal");
        });
    }

    // ---------------------------------------------------------------- M3

    /** M3: a macro that runs a saved macro replays it in place. It used to record the step and skip it. */
    @Test
    void aMacroThatRunsASavedMacroReplaysIt() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("A");
            reg.run("macro.stopRecording");
            service.saveLastAs("inner", null);
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
        });
        on(() -> {
            set("", 0);
            reg.run("macro.startRecording");
            type("x");
            reg.run("macro.run.inner");
            type("y");
            reg.run("macro.stopRecording");
        });
        assertEquals("xAy", get(this::text));
        assertEquals(
                List.of(MacroStep.text("x"), MacroStep.command("macro.run.inner"), MacroStep.text("y")),
                get(this::last));
        on(() -> {
            set("", 0);
            reg.run("macro.replayLast");
        });
        assertEquals("xAy", get(this::text));
        on(() -> {
            service.delete("inner");
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
        });
    }

    /** M3: a macro that runs itself stops with a message instead of looping. */
    @Test
    void aMacroThatRunsItselfStopsWithAnError() throws Exception {
        on(() -> {
            fx.shared
                    .getMacroStore()
                    .put(new Macro("loop", "Loop", List.of(MacroStep.text("a"), MacroStep.command("macro.run.loop"))));
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
            set("", 0);
            reg.run("macro.run.loop");
        });
        assertEquals("a", get(this::text), "one pass, then the cycle is refused");
        assertEquals(tr("status.macro.cycleError", "Loop"), get(this::status));
        on(() -> {
            service.delete("loop");
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
        });
    }

    // ---------------------------------------------------------------- M4 / N10

    /** M4: Shift+Tab was recorded as a tab character and replayed as an indent. */
    @Test
    void shiftTabReplaysAsAnOutdent() throws Exception {
        String doc = "        indented";
        on(() -> {
            set(doc, doc.length());
            reg.run("macro.startRecording");
            tab(true);
            reg.run("macro.stopRecording");
        });
        String live = get(this::text);
        assertTrue(live.length() < doc.length(), "Shift+Tab outdents: '" + live + "'");
        assertEquals(List.of(MacroStep.key("S-TAB")), get(this::last));
        on(() -> {
            set(doc, doc.length());
            reg.run("macro.replayLast");
        });
        assertEquals(live, get(this::text));
    }

    /** M4 / N10: Tab after a snippet prefix expands it on replay, and the next Tab moves between tab stops. */
    @Test
    void aSnippetExpandedByTabReplaysAsThatExpansion() throws Exception {
        on(() -> {
            buf().setLanguageOverride("java");
            set("", 0);
            reg.run("macro.startRecording");
            type("sysout");
            tab(false);
            type("q");
            tab(false);
            reg.run("macro.stopRecording");
        });
        String live = get(this::text);
        assertTrue(live.contains("System.out.println"), "the live Tab expanded the snippet: " + live);
        assertEquals(
                List.of(MacroStep.text("sysout"), MacroStep.key("TAB"), MacroStep.text("q"), MacroStep.key("TAB")),
                get(this::last));
        on(() -> {
            press(KeyCode.ESCAPE); // leave any snippet session before starting over
            set("", 0);
            reg.run("macro.replayLast");
        });
        assertEquals(live, get(this::text));
        on(() -> press(KeyCode.ESCAPE));
    }

    /** M4: Enter replays through the Enter key's own path (auto-indent after an opener). */
    @Test
    void enterReplaysThroughTheEnterKey() throws Exception {
        on(() -> {
            buf().setLanguageOverride("java");
            set("", 0);
            reg.run("macro.startRecording");
            type("if");
            key(KeyCode.OPEN_BRACKET, "{");
            enter();
            type("x");
            reg.run("macro.stopRecording");
        });
        String live = get(this::text);
        assertTrue(live.contains("\n"), live);
        on(() -> {
            set("", 0);
            reg.run("macro.replayLast");
        });
        assertEquals(live, get(this::text));
    }

    /** A macro saved before Enter and Tab were keys holds them as text; it still replays through the keys. */
    @Test
    void anOldMacroWithEnterAndTabAsTextStillReplays() throws Exception {
        on(() -> {
            fx.shared.getMacroStore().put(new Macro("old", "Old", List.of(MacroStep.text("a\rb\n\tc"))));
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
            set("", 0);
            reg.run("macro.run.old");
        });
        String out = get(this::text);
        assertEquals(3, out.split("\n", -1).length, "two Enters: '" + out + "'");
        assertTrue(out.startsWith("a\nb\n") && out.endsWith("c"), out);
        assertTrue(out.length() > "a\nb\nc".length(), "the Tab indented: '" + out + "'");
        on(() -> {
            service.delete("old");
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
        });
    }

    // ---------------------------------------------------------------- M5

    /** M5: typing with several carets replays at all of them, not just the primary. */
    @Test
    void multiCaretTypingReplaysAtEveryCaret() throws Exception {
        String doc = "aaa\nbbb\nccc";
        on(() -> {
            set(doc, 0);
            reg.run("macro.startRecording");
            reg.run("edit.addCaretBelow");
            reg.run("edit.addCaretBelow");
            type("X");
            reg.run("macro.stopRecording");
        });
        assertEquals("Xaaa\nXbbb\nXccc", get(this::text));
        on(() -> reg.run("edit.cancel"));
        on(() -> {
            set(doc, 0);
            reg.run("macro.replayLast");
        });
        assertEquals("Xaaa\nXbbb\nXccc", get(this::text));
        on(() -> reg.run("edit.cancel"));
    }

    // ---------------------------------------------------------------- M6

    /** M6: one replay is one undo step — however it is started, and however many passes it makes. */
    @Test
    void aReplayIsOneUndoStep() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("foo");
            enter();
            type("bar");
            press(KeyCode.HOME);
            type("x");
            reg.run("macro.stopRecording");
        });
        String once = get(this::text);
        assertEquals("foo\nxbar", once);

        on(() -> {
            set("", 0);
            reg.run("macro.replayLast");
        });
        assertEquals(once, get(this::text));
        on(() -> reg.run("edit.undo"));
        assertEquals("", get(this::text), "one undo takes the whole replay back");
        on(() -> reg.run("edit.redo"));
        assertEquals(once, get(this::text), "and one redo brings it back");

        // C-u 3 C-x e: three passes, still one step.
        on(() -> {
            set("", 0);
            press(KeyCode.U, false, true);
            key(KeyCode.DIGIT3, "3");
            replayChord();
        });
        String thrice = get(this::text);
        assertEquals(3, thrice.split("foo", -1).length - 1, thrice);
        on(() -> reg.run("edit.undo"));
        assertEquals("", get(this::text), "one undo for all three passes");

        // The saved macro's own command, which is also what the Run Saved picker runs.
        on(() -> {
            service.saveLastAs("stepper", null);
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
            set("", 0);
            reg.run("macro.run.stepper");
        });
        assertEquals(once, get(this::text));
        assertEquals(tr("status.macro.ran", "stepper", 1), get(this::status));
        on(() -> reg.run("edit.undo"));
        assertEquals("", get(this::text));
        on(() -> {
            service.delete("stepper");
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
        });
    }

    /** Typing before a replay is not swallowed into the replay's undo step. */
    @Test
    void undoingAReplayLeavesWhatWasTypedBeforeIt() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("zz");
            reg.run("macro.stopRecording");
            set("", 0);
            type("keep ");
        });
        on(() -> reg.run("macro.replayLast"));
        assertEquals("keep zz", get(this::text));
        on(() -> reg.run("edit.undo"));

        assertEquals("keep ", get(this::text));
    }

    // ---------------------------------------------------------------- M7

    /**
     * M7: a long replay runs in slices. The command returns while it is still going, the window keeps
     * turning, and the whole thing is still one undo step.
     */
    @Test
    void aLongReplayRunsInSlicesAndIsStillOneUndoStep() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("hello world");
            enter();
            reg.run("macro.stopRecording");
            set("", 0);
        });
        boolean stillRunning = get(() -> {
            FxTestSupport.call(macros, "replayLast", new Class<?>[] {int.class}, 400);
            return macros.isReplaying();
        });
        assertTrue(stillRunning, "400 passes do not fit in the first slice: the command returned mid-replay");
        settle();
        assertEquals(401, get(() -> area().getParagraphs().size()));
        assertEquals(tr("status.macro.replayed", 400), get(this::status));
        on(() -> reg.run("edit.undo"));
        assertEquals("", get(this::text), "one undo for 400 passes across many slices");
    }

    /** M7: Escape (delivered by the key dispatcher as a cancel request) stops a replay part-way. */
    @Test
    void aLongReplayCanBeCancelled() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("hello world");
            enter();
            reg.run("macro.stopRecording");
            set("", 0);
        });
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                macros, "replayLast", new Class<?>[] {int.class}, MacroCoordinator.MAX_REPLAY_TIMES));
        assertTrue(get(() -> macros.isReplaying()));
        FxTestSupport.runOnFx(() -> press(KeyCode.ESCAPE)); // a real key, mid-replay
        settle();
        int lines = get(() -> area().getParagraphs().size());
        assertTrue(lines > 1 && lines < MacroCoordinator.MAX_REPLAY_TIMES, "stopped part-way: " + lines);
        assertTrue(get(this::status)
                .startsWith(tr("status.macro.replayCancelled", lines - 1, 0).split("\\d")[0]));
        assertFalse(get(this::text).contains("\u001b"));
    }

    // ---------------------------------------------------------------- M9 / M17

    /** M9: starting and stopping at once keeps the macro recorded before, and says so. */
    @Test
    void anEmptyRecordingKeepsThePreviousMacro() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("keep");
            reg.run("macro.stopRecording");
            reg.run("macro.startRecording");
            reg.run("macro.stopRecording");
        });
        assertEquals(tr("status.macro.recordedNothingKept"), get(this::status));
        on(() -> {
            set("", 0);
            reg.run("macro.replayLast");
        });
        assertEquals("keep", get(this::text));
    }

    /** M17: Escape in the editor, with nothing else to dismiss, cancels the recording. */
    @Test
    void escapeCancelsARecordingAndKeepsThePreviousMacro() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("good");
            reg.run("macro.stopRecording");
            set("", 0);
            reg.run("macro.startRecording");
            type("junk");
            press(KeyCode.ESCAPE);
        });
        assertFalse(get(() -> macros.isRecording()));
        assertEquals(tr("status.macro.recordingCancelledKept"), get(this::status));
        assertEquals(List.of(MacroStep.text("good")), get(this::last));
        assertEquals(
                List.of(MacroStep.text("good")),
                get(() -> fx.shared.getMacroStore().placeholder().steps()));
    }

    /** M17: so does the keymap's cancel chord ({@code C-g}), i.e. the {@code edit.cancel} command. */
    @Test
    void theCancelChordCancelsARecording() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("junk");
            press(KeyCode.G, false, true);
        });
        assertFalse(get(() -> macros.isRecording()));
        assertTrue(get(this::status).startsWith(tr("status.macro.recordingCancelled")));
    }

    // ---------------------------------------------------------------- M10

    /** M10: {@code C-u 3 x} is typing, and is recorded as the three characters it inserted. */
    @Test
    void prefixArgumentSelfInsertIsRecorded() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            press(KeyCode.U, false, true);
            key(KeyCode.DIGIT3, "3");
            key(KeyCode.X, "x");
            type("y");
            reg.run("macro.stopRecording");
        });
        assertEquals("xxxy", get(this::text));
        assertEquals(List.of(MacroStep.text("xxxy")), get(this::last));
        on(() -> {
            set("", 0);
            reg.run("macro.replayLast");
        });
        assertEquals("xxxy", get(this::text));
    }

    // ---------------------------------------------------------------- M14

    /** M14: a replay that could not type says so, instead of "Replayed macro". */
    @Test
    void replayOnAReadOnlyBufferSaysTheTypingWasSkipped() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("abc");
            reg.run("macro.stopRecording");
            set("fixed", 0);
            buf().setViewMode(true); // read-only
            reg.run("macro.replayLast");
        });
        assertEquals("fixed", get(this::text));
        assertEquals(tr("status.macro.replayCannotReadOnly"), get(this::status));
    }

    /** M14: a step whose command no longer exists is reported, not skipped in silence. */
    @Test
    void aStepWhoseCommandIsGoneIsReported() throws Exception {
        on(() -> {
            fx.shared
                    .getMacroStore()
                    .put(new Macro(
                            "stale", "Stale", List.of(MacroStep.command("no.such.command"), MacroStep.text("z"))));
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
            set("", 0);
            reg.run("macro.run.stale");
        });
        assertEquals("z", get(this::text), "the rest still ran");
        assertEquals(tr("status.macro.replayCannotRunCommand", 1, "no.such.command"), get(this::status));
        on(() -> {
            service.delete("stale");
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
        });
    }

    // ---------------------------------------------------------------- M8 / M13 / M16 / M20

    /** M16 + M20: the unnamed recording is a command under a fixed id, titled "Macro: …" in the palette. */
    @Test
    void theLastRecordingIsACommandWithAFixedIdAndAMacroPrefix() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            type("a");
            reg.run("macro.stopRecording");
        });
        Macro slot = get(() -> fx.shared.getMacroStore().placeholder());
        assertNotNull(slot);
        assertEquals("", slot.name(), "no translated name is stored");
        Command c = get(() -> reg.get(MacroService.commandIdFor(slot)).orElse(null));
        assertNotNull(c);
        assertEquals(tr("palette.macro.runTitle", tr("macro.unnamedName")), c.title());
        assertEquals(tr("status.macro.recorded", 1), get(this::status));
        assertTrue(get(this::status).contains("1 step") && !get(this::status).contains("steps"), "M18: singular");
    }

    /** M8: Name and Save reports an empty name and asks before replacing — in the prompt, which stays open. */
    @Test
    void nameAndSaveRefusesAnEmptyNameAndConfirmsAReplacement() throws Exception {
        OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
        on(() -> {
            reg.run("macro.startRecording");
            type("one");
            reg.run("macro.stopRecording");
            reg.run("macro.nameAndSave");
        });
        awaitPromptFocus();
        on(() -> enter()); // nothing typed
        assertTrue(get(overlay::isShowing), "an empty name keeps the prompt open");
        assertNull(get(() -> service.findByName("Build")));
        awaitPromptFocus();
        on(() -> type("Build"));
        on(() -> enter());
        assertFalse(get(overlay::isShowing));
        assertEquals(
                List.of(MacroStep.text("one")),
                get(() -> service.findByName("Build").steps()));
        assertNull(get(() -> fx.shared.getMacroStore().placeholder()), "the unnamed slot was this recording: gone");

        on(() -> {
            reg.run("macro.startRecording");
            type("two");
            reg.run("macro.stopRecording");
            reg.run("macro.nameAndSave");
        });
        awaitPromptFocus();
        on(() -> type("Build"));
        on(() -> enter());
        assertTrue(get(overlay::isShowing), "an existing name is not replaced on the first Enter");
        assertEquals(
                List.of(MacroStep.text("one")),
                get(() -> service.findByName("Build").steps()));
        awaitPromptFocus();
        on(() -> enter()); // the same name again: yes, replace
        assertFalse(get(overlay::isShowing));
        assertEquals(
                List.of(MacroStep.text("two")),
                get(() -> service.findByName("Build").steps()));
        assertEquals(
                1,
                get(() -> service.saved().stream()
                        .filter(m -> m.name().equals("Build"))
                        .count()));
        on(() -> {
            service.delete(service.findByName("Build").id());
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
        });
    }

    /** M13: deleting a saved macro from the palette also removes its key binding. */
    @Test
    void deletingASavedMacroDropsItsKeyBinding() throws Exception {
        KeymapManager keymap = FxTestSupport.field(fx.controller, "keymap");
        on(() -> {
            reg.run("macro.startRecording");
            type("a");
            reg.run("macro.stopRecording");
            service.saveLastAs("Bound", null);
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
            Object settings = FxTestSupport.field(fx.controller, "editorSettings");
            FxTestSupport.call(
                    settings,
                    "rebindShortcut",
                    new Class<?>[] {String.class, String.class},
                    "macro.run.bound",
                    "C-M-S-f9");
        });
        assertEquals("macro.run.bound", get(() -> keymap.commandFor("C-M-S-f9")));
        on(() -> macros.delete(service.findByName("Bound")));
        assertNull(get(() -> keymap.commandFor("C-M-S-f9")), "the chord no longer points at a deleted macro");
        assertTrue(get(() -> reg.get("macro.run.bound").isEmpty()));
    }

    // ---------------------------------------------------------------- M21

    /** M21: a mouse click cannot be replayed; the first one in the document during a recording says so. */
    @Test
    void aMouseClickWhileRecordingShowsAHint() throws Exception {
        on(() -> {
            reg.run("macro.startRecording");
            area().fireEvent(new MouseEvent(
                    MouseEvent.MOUSE_PRESSED,
                    5,
                    5,
                    5,
                    5,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null));
        });
        assertEquals(tr("status.macro.mouseCannotReplay"), get(this::status));
        on(() -> reg.run("macro.stopRecording"));
    }

    /**
     * M21: with abbreviations on, the caret fix-up after an expansion is deferred to the next event-loop
     * turn. A replay delivers its keys in one turn, so the fix-up used to arrive after the keys that
     * depended on it.
     */
    @Test
    void abbreviationExpansionReplaysInOrder() throws Exception {
        on(() -> buf().setAbbrevs(java.util.Map.of("teh", "the quick"), true));
        on(() -> {
            reg.run("macro.startRecording");
            type("teh");
        });
        on(() -> type(" "));
        on(() -> type("fox"));
        on(() -> reg.run("macro.stopRecording"));
        String live = get(this::text);
        assertEquals("the quick fox", live);
        on(() -> {
            set("", 0);
            reg.run("macro.replayLast");
        });
        assertEquals(live, get(this::text));
        on(() -> buf().setAbbrevs(java.util.Map.of(), false));
    }
}

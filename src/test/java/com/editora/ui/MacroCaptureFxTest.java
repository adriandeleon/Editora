package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.event.EventTarget;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.command.MacroCapture;
import com.editora.editor.EditorBuffer;
import com.editora.macro.MacroKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the {@link KeyDispatcher} reports to the macro machinery, driven through a real dispatcher on a real
 * scene.
 *
 * <p>The dispatcher is a <b>scene</b> filter, so it sees every key in the window. It reports text and the
 * keys it leaves to the focused control <em>with their target</em> — the recorder decides from that whether
 * a key belongs to the document or to a prompt. And it stands aside completely for the keys a replay fires.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroCaptureFxTest {

    /** Mirrors KeyDispatcher.IS_MAC — the Alt-consume that gates what this hook can see is per-OS. */
    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A capture that logs what it is told, tagging each entry with where the key was aimed. */
    private static final class Log implements MacroCapture {
        final EditorBuffer buffer;
        final List<String> captured = new ArrayList<>();
        int mode = RECORDING;
        int cancels;

        Log(EditorBuffer buffer) {
            this.buffer = buffer;
        }

        private String where(EventTarget target) {
            return buffer.ownsKeyTarget(target) ? "" : "prompt ";
        }

        @Override
        public int mode() {
            return mode;
        }

        @Override
        public void keySeen() {}

        @Override
        public void text(String chars, EventTarget target) {
            captured.add(where(target) + "txt:" + chars);
        }

        @Override
        public void key(KeyEvent e, EventTarget target) {
            captured.add(where(target)
                    + "key:"
                    + MacroKey.encode(
                            e.isControlDown(),
                            e.isAltDown(),
                            e.isMetaDown(),
                            e.isShiftDown(),
                            e.getCode().name()));
        }

        @Override
        public void cancelReplay() {
            cancels++;
        }
    }

    /** A dispatcher installed on a scene holding an editor area + an unrelated text field. */
    private record Rig(KeyDispatcher dispatcher, EditorBuffer buffer, TextField field, Log log, List<String> ran) {
        List<String> captured() {
            return log.captured;
        }
    }

    private static Rig rig() {
        EditorBuffer b = new EditorBuffer();
        TextField field = new TextField(); // stands in for the find bar / a prompt's input
        StackPane root = new StackPane(b.getNode(), field);
        Scene scene = new Scene(root, 400, 300);
        CommandRegistry registry = new CommandRegistry();
        List<String> ran = new ArrayList<>();
        registry.register(Command.of("test.bound", "Bound", () -> ran.add("test.bound")));
        KeymapManager keymap = new KeymapManager();
        keymap.applyOverrides(java.util.Map.of("f9", "test.bound"));
        KeyDispatcher d = new KeyDispatcher(registry, keymap, s -> {});
        d.install(scene);
        Log log = new Log(b);
        d.setMacroCapture(log);
        return new Rig(d, b, field, log, ran);
    }

    private static void press(javafx.scene.Node target, KeyCode code) {
        fire(target, code, false, false, false);
    }

    private static void type(javafx.scene.Node target, String ch) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, false, false, false));
    }

    /** Backspace/Delete/arrows/Home/End must be captured — no keymap binds them, so nothing else can. */
    @Test
    void bareEditingAndNavigationKeysAreCaptured() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        FxTestSupport.runOnFx(() -> {
            type(r.buffer().getArea(), "x");
            press(r.buffer().getArea(), KeyCode.BACK_SPACE);
            type(r.buffer().getArea(), "y");
            press(r.buffer().getArea(), KeyCode.DOWN);
            press(r.buffer().getArea(), KeyCode.HOME);
            press(r.buffer().getArea(), KeyCode.DELETE);
        });
        assertEquals(List.of("txt:x", "key:BACK_SPACE", "txt:y", "key:DOWN", "key:HOME", "key:DELETE"), r.captured());
    }

    /**
     * M4 / N10: Tab, Shift+Tab, Enter and Escape are recorded as the <em>key</em>, and the control character
     * each also delivers as KEY_TYPED is not recorded as text. A tab character could not say Shift+Tab from
     * Tab, and replaying it never reached the snippet / table / completion handlers.
     */
    @Test
    void tabEnterAndEscapeAreKeysNotCharacters() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        FxTestSupport.runOnFx(() -> {
            press(r.buffer().getArea(), KeyCode.TAB);
            type(r.buffer().getArea(), "\t");
            fire(r.buffer().getArea(), KeyCode.TAB, true, false, false);
            type(r.buffer().getArea(), "\t");
            press(r.buffer().getArea(), KeyCode.ENTER);
            type(r.buffer().getArea(), "\r");
            press(r.buffer().getArea(), KeyCode.ESCAPE);
            type(r.buffer().getArea(), "\u001b");
            fire(r.buffer().getArea(), KeyCode.INSERT, true, false, false); // Shift+Insert: paste (M21)
        });
        assertEquals(List.of("key:TAB", "key:S-TAB", "key:ENTER", "key:ESCAPE", "key:S-INSERT"), r.captured());
    }

    /**
     * M2: keys aimed at another control — the find bar, a prompt, a picker — are reported too, with that
     * control as their target, so a macro can replay a search. (They used to be dropped, and before that
     * recorded as if typed into the document.)
     */
    @Test
    void keysAimedAtAnotherControlAreReportedAsThatControls() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        FxTestSupport.runOnFx(() -> {
            type(r.field(), "c");
            type(r.field(), "u");
            press(r.field(), KeyCode.BACK_SPACE);
            press(r.field(), KeyCode.ENTER);
        });
        assertEquals(
                List.of("prompt txt:c", "prompt txt:u", "prompt key:BACK_SPACE", "prompt key:ENTER"), r.captured());
    }

    /**
     * The area acts on modified variants too — Shift-Down extends the selection, Ctrl-Left goes a word left —
     * and no keymap binds them, so they reach this hook. The modifiers must ride along in the token: replaying
     * a Shift-Down as a bare DOWN would move the caret instead of selecting.
     */
    @Test
    void modifiersRideAlongInTheRecordedToken() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        FxTestSupport.runOnFx(() -> {
            fire(r.buffer().getArea(), KeyCode.DOWN, true, false, false); // S-down: extend selection
            fire(r.buffer().getArea(), KeyCode.LEFT, false, true, false); // C-left: word left
        });
        assertEquals(List.of("key:S-DOWN", "key:C-LEFT"), r.captured());
    }

    /**
     * An unbound plain-Alt chord is recorded only where the editor will actually act on it — i.e. macOS,
     * where Option is Meta and the area handles Option-Down.
     *
     * <p>On Windows/Linux {@code KeyDispatcher} deliberately <em>consumes</em> an unbound key held with plain
     * Alt, before this hook's fall-through: letting it through makes the OS treat it as a menu mnemonic and
     * freezes the keyboard app-wide. The key therefore never reaches the area, so recording it would be
     * wrong — replaying it would do nothing either.
     */
    @Test
    void anUnboundAltChordIsRecordedOnlyWhereTheEditorWillSeeIt() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        FxTestSupport.runOnFx(() -> fire(r.buffer().getArea(), KeyCode.DOWN, false, false, true)); // M-down
        assertEquals(IS_MAC ? List.of("key:M-DOWN") : List.of(), r.captured());
    }

    /** M21: text an input method commits never arrives as KEY_TYPED; it is reported as typed text all the same. */
    @Test
    void textCommittedByAnInputMethodIsReportedAsText() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        FxTestSupport.runOnFx(() -> {
            r.buffer()
                    .getArea()
                    .fireEvent(new javafx.scene.input.InputMethodEvent(
                            javafx.scene.input.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, List.of(), "日本", 0));
            r.buffer()
                    .getArea()
                    .fireEvent(new javafx.scene.input.InputMethodEvent(
                            javafx.scene.input.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, List.of(), "", 0));
        });
        assertEquals(List.of("txt:日本"), r.captured(), "the commit, not the empty composition update");
    }

    /** A bound chord is a command, which the registry reports — it is not also a key step. */
    @Test
    void aBoundChordIsNotReportedAsAKey() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        FxTestSupport.runOnFx(() -> press(r.buffer().getArea(), KeyCode.F9));
        assertEquals(List.of("test.bound"), r.ran());
        assertTrue(r.captured().isEmpty(), r.captured().toString());
    }

    /**
     * M1: a key a replay fires must not be looked at. Treated as a chord it would run the command bound to
     * it; merely examined, it cleared the flag that swallows the replay chord's own typed character.
     */
    @Test
    void aReplayedKeyIsNotDispatchedAndDoesNotDisturbTheSwallowOfTheChordsCharacter() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        boolean swallowed = FxTestSupport.callOnFx(() -> {
            press(r.buffer().getArea(), KeyCode.F9); // a real bound key: its KEY_TYPED is to be swallowed
            r.log().mode = MacroCapture.SYNTHETIC; // …and its command replays keys before that arrives
            press(r.buffer().getArea(), KeyCode.F9);
            press(r.buffer().getArea(), KeyCode.BACK_SPACE);
            type(r.buffer().getArea(), "z");
            r.buffer()
                    .getArea()
                    .fireEvent(new KeyEvent(
                            KeyEvent.KEY_RELEASED, "", "", KeyCode.BACK_SPACE, false, false, false, false));
            r.log().mode = MacroCapture.IDLE;
            type(r.field(), "e"); // the chord's own KEY_TYPED, arriving wherever the focus now is
            return r.field().getText().isEmpty()
                    && !r.buffer().getArea().getText().contains("e");
        });
        assertEquals(List.of("test.bound"), r.ran(), "the replayed F9 ran nothing");
        assertTrue(r.captured().isEmpty(), "replayed keys are never recorded: " + r.captured());
        assertTrue(swallowed, "the chord's own character is still swallowed after the replay");
    }

    /** M7: while a long replay is between slices, real keys are kept out of it and Escape stops it. */
    @Test
    void duringASlicedReplayRealKeysAreSwallowedAndEscapeCancels() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        boolean typedConsumed = FxTestSupport.callOnFx(() -> {
            r.log().mode = MacroCapture.REPLAYING;
            press(r.buffer().getArea(), KeyCode.F9);
            type(r.buffer().getArea(), "q");
            type(r.field(), "q");
            press(r.buffer().getArea(), KeyCode.ESCAPE);
            return r.field().getText().isEmpty();
        });
        assertTrue(typedConsumed);
        assertTrue(r.ran().isEmpty(), "no command runs in the middle of a replay");
        assertEquals(1, r.log().cancels);
        assertFalse(r.buffer().getArea().getText().contains("q"));
    }

    private static void fire(javafx.scene.Node target, KeyCode code, boolean shift, boolean ctrl, boolean alt) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false));
    }
}

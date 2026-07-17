package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two macro capture hooks, driven through a real {@link KeyDispatcher} on a real scene.
 *
 * <p>Both hooks are <b>scene</b> filters, which run in the capture phase and therefore see every key in the
 * window — so they need a target gate. Without it, text typed into the command palette / find bar / any
 * picker was recorded and replayed into the document. And bare Backspace/arrows reach neither hook on their
 * own (the area handles them, no keymap binds them, and Backspace is 0x08 — below the printable range), so
 * a recorded macro used to omit them entirely.
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

    /** A dispatcher installed on a scene holding an editor area + an unrelated text field. */
    private record Rig(KeyDispatcher dispatcher, EditorBuffer buffer, TextField field, List<String> captured) {}

    private static Rig rig() {
        EditorBuffer b = new EditorBuffer();
        TextField field = new TextField(); // stands in for the palette / find bar input
        StackPane root = new StackPane(b.getNode(), field);
        Scene scene = new Scene(root, 400, 300);
        KeyDispatcher d = new KeyDispatcher(new CommandRegistry(), new KeymapManager(), s -> {});
        d.install(scene);
        List<String> captured = new ArrayList<>();
        d.setTypedListener(c -> captured.add("txt:" + c));
        d.setKeyListener(k -> captured.add("key:" + k));
        d.setRecordTarget(t -> b.ownsKeyTarget(t));
        return new Rig(d, b, field, captured);
    }

    private static void press(javafx.scene.Node target, KeyCode code) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
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

    /** Keys aimed at anything but the editor — the palette, the find bar, a picker — must not be recorded. */
    @Test
    void keysAimedAtAnotherControlAreNotCaptured() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        FxTestSupport.runOnFx(() -> {
            type(r.field(), "c");
            type(r.field(), "u");
            type(r.field(), "t");
            press(r.field(), KeyCode.BACK_SPACE);
            press(r.field(), KeyCode.DOWN);
        });
        assertTrue(r.captured().isEmpty(), "a scene filter sees these, but they are not the editor's: " + r.captured());
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
     * wrong — replaying it would do nothing either. (This assertion was macOS-only and unguarded, which is
     * what broke CI on the Linux runner.)
     */
    @Test
    void anUnboundAltChordIsRecordedOnlyWhereTheEditorWillSeeIt() throws Exception {
        Rig r = FxTestSupport.callOnFx(MacroCaptureFxTest::rig);
        FxTestSupport.runOnFx(() -> fire(r.buffer().getArea(), KeyCode.DOWN, false, false, true)); // M-down
        assertEquals(IS_MAC ? List.of("key:M-DOWN") : List.of(), r.captured());
    }

    private static void fire(javafx.scene.Node target, KeyCode code, boolean shift, boolean ctrl, boolean alt) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false));
    }
}

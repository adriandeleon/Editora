package com.editora.command;

import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code C-u} prefix argument driven through the real {@link KeyDispatcher} by synthetic key events.
 * {@link PrefixArgTest} covers the number the state machine accumulates; this covers the dispatcher wiring
 * the pure test cannot — that {@code C-u 5 C-n} runs the command five times, that a count-aware command
 * reads the value once instead, and that {@code C-u 3 x} self-inserts three characters.
 *
 * <p>{@code KeyEvent} is a plain data object and {@code handle}/{@code handleTyped} are direct method
 * calls (no scene dispatch), so this needs no JavaFX toolkit.
 */
class PrefixArgDispatchTest {

    private KeymapManager keymap;
    private CommandRegistry registry;
    private KeyDispatcher dispatcher;

    private final AtomicInteger lineDown = new AtomicInteger();
    private final AtomicInteger setMarkRuns = new AtomicInteger();
    private Integer countAwareArg; // live during a count-aware command
    private Integer setMarkSawArg; // what set-mark saw when it ran
    private final StringBuilder selfInserted = new StringBuilder();

    @BeforeEach
    void setUp() {
        keymap = new KeymapManager();
        keymap.loadNamed("emacs");
        registry = new CommandRegistry();
        registry.register(com.editora.command.Command.of("edit.universalArgument", () -> {}));
        registry.register(com.editora.command.Command.of("nav.lineDown", lineDown::incrementAndGet));
        registry.register(com.editora.command.Command.of("edit.setMark", () -> {
            setMarkRuns.incrementAndGet();
            setMarkSawArg = countAwareArg;
        }));
        dispatcher = new KeyDispatcher(registry, keymap, s -> {});
        lineDown.set(0);
        setMarkRuns.set(0);
        countAwareArg = null;
        setMarkSawArg = null;
        selfInserted.setLength(0);
        dispatcher.setPrefixArgumentSupport(
                "edit.setMark"::equals,
                arg -> countAwareArg = arg,
                (ch, n) -> selfInserted.append(String.valueOf(ch).repeat(n)));
    }

    // --- event construction helpers --------------------------------------------------------------

    private KeyEvent press(KeyCode code, boolean ctrl) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false);
    }

    private void ctrl(KeyCode code) {
        dispatcher.handle(press(code, true));
    }

    private void plain(KeyCode code) {
        dispatcher.handle(press(code, false));
    }

    private void typeChar(char c) {
        // A press then its paired KEY_TYPED, as the toolkit delivers them.
        dispatcher.handle(press(keyCodeOf(c), false));
        dispatcher.handleTyped(
                new KeyEvent(KeyEvent.KEY_TYPED, String.valueOf(c), "", KeyCode.UNDEFINED, false, false, false, false));
    }

    private static KeyCode keyCodeOf(char c) {
        if (c >= 'a' && c <= 'z') {
            return KeyCode.getKeyCode(String.valueOf(Character.toUpperCase(c)));
        }
        return c == '-' ? KeyCode.MINUS : KeyCode.UNDEFINED;
    }

    private static KeyCode digit(int d) {
        return KeyCode.getKeyCode(String.valueOf(d));
    }

    // --- repeat ----------------------------------------------------------------------------------

    @Test
    void bareUniversalRepeatsFourTimes() {
        ctrl(KeyCode.U); // C-u
        ctrl(KeyCode.N); // C-n
        assertEquals(4, lineDown.get());
    }

    @Test
    void digitsSetTheRepeatCount() {
        ctrl(KeyCode.U);
        plain(digit(5));
        ctrl(KeyCode.N);
        assertEquals(5, lineDown.get());
    }

    @Test
    void multiDigitCountsAccumulate() {
        ctrl(KeyCode.U);
        plain(digit(1));
        plain(digit(2));
        ctrl(KeyCode.N);
        assertEquals(12, lineDown.get());
    }

    @Test
    void repeatedUniversalsMultiply() {
        ctrl(KeyCode.U);
        ctrl(KeyCode.U); // 16
        ctrl(KeyCode.N);
        assertEquals(16, lineDown.get());
    }

    @Test
    void withoutAPrefixTheCommandRunsExactlyOnce() {
        ctrl(KeyCode.N);
        assertEquals(1, lineDown.get());
    }

    @Test
    void zeroArgumentRunsTheCommandZeroTimes() {
        ctrl(KeyCode.U);
        plain(digit(0));
        ctrl(KeyCode.N);
        assertEquals(0, lineDown.get());
    }

    @Test
    void cancellingWithControlGLeavesTheNextCommandUnprefixed() {
        ctrl(KeyCode.U);
        plain(digit(9));
        ctrl(KeyCode.G); // cancel the argument (edit.cancel is not run as a command here)
        ctrl(KeyCode.N);
        assertEquals(1, lineDown.get(), "the 9 was cancelled, so C-n runs once");
    }

    // --- count-aware -----------------------------------------------------------------------------

    @Test
    void aCountAwareCommandReadsTheValueAndRunsOnce() {
        ctrl(KeyCode.U);
        plain(digit(3));
        ctrl(KeyCode.SPACE); // C-u 3 C-SPC → set-mark reads the arg, runs once
        assertEquals(1, setMarkRuns.get(), "count-aware commands are not repeated");
        assertEquals(3, setMarkSawArg, "and they see the numeric argument while running");
    }

    // --- self-insert -----------------------------------------------------------------------------

    @Test
    void selfInsertRepeatsTheCharacter() {
        ctrl(KeyCode.U);
        plain(digit(3));
        typeChar('x');
        assertEquals("xxx", selfInserted.toString());
    }

    @Test
    void minusAfterDigitsSelfInsertsDashes() {
        ctrl(KeyCode.U);
        plain(digit(4));
        typeChar('-'); // C-u 4 - : the minus is the command, not a sign → four dashes
        assertEquals("----", selfInserted.toString());
    }

    @Test
    void aLeadingMinusThenDigitsIsANegativeRepeatCount() {
        ctrl(KeyCode.U);
        plain(KeyCode.MINUS);
        plain(digit(3));
        ctrl(KeyCode.N);
        assertEquals(3, lineDown.get(), "the repeat count is the magnitude — negative does not reverse");
    }

    @Test
    void aStrayUnboundKeyEndsTheArgument() {
        ctrl(KeyCode.U);
        plain(digit(5));
        plain(KeyCode.F5); // unbound, not a self-insert → discards the argument
        ctrl(KeyCode.N);
        assertEquals(1, lineDown.get(), "the F5 cancelled the pending 5");
    }
}

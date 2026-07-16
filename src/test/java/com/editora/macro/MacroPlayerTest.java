package com.editora.macro;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay: dispatch order, N-repeat, and the re-entrancy guard against self-referential macros. */
class MacroPlayerTest {

    private static Macro macro(MacroStep... steps) {
        return new Macro("m", List.of(steps));
    }

    @Test
    void dispatchesStepsInOrderToTheRightCallback() {
        List<String> log = new ArrayList<>();
        Macro m = macro(MacroStep.text("hi"), MacroStep.command("nav.lineStart"), MacroStep.text("x"));
        new MacroPlayer().play(m, 1, id -> log.add("cmd:" + id), t -> log.add("txt:" + t), k -> log.add("key:" + k));
        assertEquals(List.of("txt:hi", "cmd:nav.lineStart", "txt:x"), log);
    }

    /** Backspace/Delete/arrows replay as key steps — they are neither a command nor typed text. */
    @Test
    void keyStepsGoToThePressKeyCallback() {
        List<String> log = new ArrayList<>();
        Macro m = macro(MacroStep.text("x"), MacroStep.key("BACK_SPACE"), MacroStep.text("y"), MacroStep.key("DOWN"));
        new MacroPlayer().play(m, 1, id -> log.add("cmd:" + id), t -> log.add("txt:" + t), k -> log.add("key:" + k));
        assertEquals(List.of("txt:x", "key:BACK_SPACE", "txt:y", "key:DOWN"), log);
    }

    @Test
    void repeatsNTimes() {
        List<String> log = new ArrayList<>();
        Macro m = macro(MacroStep.command("nav.lineDown"));
        new MacroPlayer().play(m, 3, id -> log.add(id), t -> {}, k -> {});
        assertEquals(List.of("nav.lineDown", "nav.lineDown", "nav.lineDown"), log);
    }

    @Test
    void zeroOrNegativeTimesDoesNothing() {
        List<String> log = new ArrayList<>();
        Macro m = macro(MacroStep.command("x"));
        MacroPlayer p = new MacroPlayer();
        assertFalse(p.play(m, 0, log::add, t -> {}, k -> {}));
        assertFalse(p.play(m, -1, log::add, t -> {}, k -> {}));
        assertEquals(List.of(), log);
    }

    @Test
    void reentrantPlayIsIgnored() {
        List<String> log = new ArrayList<>();
        MacroPlayer p = new MacroPlayer();
        Macro inner = macro(MacroStep.command("inner"));
        // The command callback tries to re-trigger replay; the guard must drop the nested call.
        Macro outer = macro(MacroStep.command("a"), MacroStep.command("b"));
        List<Boolean> nested = new ArrayList<>();
        assertTrue(p.play(
                outer,
                1,
                id -> {
                    log.add(id);
                    nested.add(p.play(inner, 1, log::add, t -> {}, k -> {})); // nested — should be a no-op
                },
                t -> {},
                k -> {}));
        assertEquals(List.of("a", "b"), log, "nested replay suppressed; only outer steps ran");
        assertEquals(List.of(false, false), nested, "a dropped nested replay must report that it did nothing");
        assertFalse(p.isPlaying());
    }
}

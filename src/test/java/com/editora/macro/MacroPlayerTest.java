package com.editora.macro;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Replay: dispatch order, N-repeat, and the re-entrancy guard against self-referential macros. */
class MacroPlayerTest {

    private static Macro macro(MacroStep... steps) {
        return new Macro("m", List.of(steps));
    }

    @Test
    void dispatchesStepsInOrderToTheRightCallback() {
        List<String> log = new ArrayList<>();
        Macro m = macro(MacroStep.text("hi"), MacroStep.command("nav.lineStart"), MacroStep.text("x"));
        new MacroPlayer().play(m, 1, id -> log.add("cmd:" + id), t -> log.add("txt:" + t));
        assertEquals(List.of("txt:hi", "cmd:nav.lineStart", "txt:x"), log);
    }

    @Test
    void repeatsNTimes() {
        List<String> log = new ArrayList<>();
        Macro m = macro(MacroStep.command("nav.lineDown"));
        new MacroPlayer().play(m, 3, id -> log.add(id), t -> {});
        assertEquals(List.of("nav.lineDown", "nav.lineDown", "nav.lineDown"), log);
    }

    @Test
    void zeroOrNegativeTimesDoesNothing() {
        List<String> log = new ArrayList<>();
        Macro m = macro(MacroStep.command("x"));
        MacroPlayer p = new MacroPlayer();
        p.play(m, 0, log::add, t -> {});
        p.play(m, -1, log::add, t -> {});
        assertEquals(List.of(), log);
    }

    @Test
    void reentrantPlayIsIgnored() {
        List<String> log = new ArrayList<>();
        MacroPlayer p = new MacroPlayer();
        Macro inner = macro(MacroStep.command("inner"));
        // The command callback tries to re-trigger replay; the guard must drop the nested call.
        Macro outer = macro(MacroStep.command("a"), MacroStep.command("b"));
        p.play(
                outer,
                1,
                id -> {
                    log.add(id);
                    p.play(inner, 1, log::add, t -> {}); // nested — should be a no-op
                },
                t -> {});
        assertEquals(List.of("a", "b"), log, "nested replay suppressed; only outer steps ran");
        assertFalse(p.isPlaying());
    }
}

package com.editora.macro;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.macro.MacroPlayer.Push;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroPlayerTest {

    /** Drives the cursor the way the window does; a {@code run:<id>} command plays that macro in place. */
    private static List<String> play(MacroPlayer p, Map<String, Macro> saved, List<Push> refused) {
        List<String> log = new ArrayList<>();
        MacroStep step;
        int guard = 0;
        while ((step = p.peek()) != null && guard++ < 10_000) {
            p.advance();
            if (step.isCommand() && step.value().startsWith("run:")) {
                String id = step.value().substring(4);
                Push push = p.push(id, saved.get(id), 1);
                if (push != Push.OK) {
                    refused.add(push);
                    break;
                }
            } else {
                log.add(step.kind().charAt(0) + ":" + step.value());
            }
        }
        p.end();
        return log;
    }

    @Test
    void stepsComeInOrderOncePerPass() {
        Macro m = new Macro("m", List.of(MacroStep.command("a"), MacroStep.text("xy"), MacroStep.key("DOWN")));
        MacroPlayer p = new MacroPlayer();
        assertTrue(p.begin("m", m, 2));
        assertTrue(p.isPlaying());
        assertEquals(2, p.rootTimes());
        assertEquals(List.of("c:a", "t:xy", "k:DOWN", "c:a", "t:xy", "k:DOWN"), play(p, Map.of(), new ArrayList<>()));
        assertFalse(p.isPlaying());
        assertNull(p.peek());
    }

    @Test
    void progressCountsCompletedPasses() {
        MacroPlayer p = new MacroPlayer();
        p.begin("m", new Macro("m", List.of(MacroStep.text("x"))), 3);
        assertEquals(0, p.rootIteration());
        p.peek();
        p.advance();
        p.peek(); // rolls over into the second pass
        assertEquals(1, p.rootIteration());
    }

    @Test
    void nothingToPlayIsRefusedWithoutStarting() {
        MacroPlayer p = new MacroPlayer();
        assertFalse(p.begin("m", null, 1));
        assertFalse(p.begin("m", new Macro("m", List.of()), 1));
        assertFalse(p.begin("m", new Macro("m", List.of(MacroStep.text("x"))), 0));
        assertFalse(p.isPlaying());
        assertEquals(Push.EMPTY, p.push("x", new Macro("x", List.of(MacroStep.text("x"))), 1), "not playing");
    }

    @Test
    void aSecondBeginWhileOneIsRunningChangesNothing() {
        MacroPlayer p = new MacroPlayer();
        Macro m = new Macro("m", List.of(MacroStep.text("x")));
        assertTrue(p.begin("m", m, 1));
        assertFalse(p.begin("m", m, 5));
        assertEquals(1, p.rootTimes());
    }

    /** M3: a step that runs a saved macro replays it in place, then the outer macro carries on. */
    @Test
    void aMacroThatRunsAnotherReplaysItInPlace() {
        Macro inner = new Macro("inner", List.of(MacroStep.text("A")));
        Macro outer =
                new Macro("outer", List.of(MacroStep.text("x"), MacroStep.command("run:inner"), MacroStep.text("y")));
        MacroPlayer p = new MacroPlayer();
        p.begin("outer", outer, 2);
        List<Push> refused = new ArrayList<>();
        assertEquals(List.of("t:x", "t:A", "t:y", "t:x", "t:A", "t:y"), play(p, Map.of("inner", inner), refused));
        assertTrue(refused.isEmpty());
    }

    @Test
    void aMacroThatRunsItselfIsRefusedNotLooped() {
        Macro self = new Macro("self", List.of(MacroStep.text("a"), MacroStep.command("run:self")));
        MacroPlayer p = new MacroPlayer();
        p.begin("self", self, 1);
        List<Push> refused = new ArrayList<>();
        assertEquals(List.of("t:a"), play(p, Map.of("self", self), refused));
        assertEquals(List.of(Push.CYCLE), refused);
    }

    @Test
    void anIndirectCycleIsRefusedToo() {
        Macro a = new Macro("a", List.of(MacroStep.text("a"), MacroStep.command("run:b")));
        Macro b = new Macro("b", List.of(MacroStep.text("b"), MacroStep.command("run:a")));
        MacroPlayer p = new MacroPlayer();
        p.begin("a", a, 1);
        List<Push> refused = new ArrayList<>();
        assertEquals(List.of("t:a", "t:b"), play(p, Map.of("a", a, "b", b), refused));
        assertEquals(List.of(Push.CYCLE), refused);
    }

    @Test
    void aPileDeeperThanTheLimitIsRefused() {
        MacroPlayer p = new MacroPlayer();
        Macro m = new Macro("m", List.of(MacroStep.text("x")));
        p.begin("k0", m, 1);
        for (int i = 1; i < MacroPlayer.MAX_DEPTH; i++) {
            assertEquals(Push.OK, p.push("k" + i, m, 1));
        }
        assertEquals(Push.TOO_DEEP, p.push("one-more", m, 1));
    }
}

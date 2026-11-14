package com.editora.command;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatcher tells its prefix listener when a multi-key prefix starts, grows and ends, which is what the
 * window's prefix hint shows and hides on (KBD-14 in Pauta 6.0.1).
 */
class KeyDispatcherPrefixListenerTest {

    private final List<String> heard = new ArrayList<>();

    private KeyDispatcher dispatcher() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("emacs", false);
        KeyDispatcher d = new KeyDispatcher(new CommandRegistry(), km, s -> {}, false);
        d.setPrefixListener(heard::add);
        return d;
    }

    private static KeyEvent press(KeyCode code, boolean ctrl) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false);
    }

    @Test
    void aPrefixIsToldWhenItStartsGrowsAndRuns() {
        KeyDispatcher d = dispatcher();
        d.handle(press(KeyCode.X, true)); // C-x
        d.handle(press(KeyCode.V, false)); // C-x v
        KeyEvent l = press(KeyCode.L, false); // C-x v l: File History
        d.handle(l);
        assertTrue(l.isConsumed());
        assertEquals(List.of("C-x", "C-x v", ""), heard);
    }

    @Test
    void anUndefinedKeyEndsThePrefix() {
        KeyDispatcher d = dispatcher();
        d.handle(press(KeyCode.X, true));
        d.handle(press(KeyCode.F12, false)); // C-x f12: bound to nothing
        assertEquals(List.of("C-x", ""), heard);
    }

    @Test
    void cancellingEndsThePrefix() {
        KeyDispatcher d = dispatcher();
        d.handle(press(KeyCode.X, true));
        d.handle(press(KeyCode.G, true)); // C-x C-g
        assertEquals(List.of("C-x", ""), heard);
    }

    @Test
    void aKeyWithNoPrefixPendingTellsNothing() {
        KeyDispatcher d = dispatcher();
        d.handle(press(KeyCode.T, true)); // C-t: a one-key command
        d.handle(press(KeyCode.A, false)); // typing
        assertTrue(heard.isEmpty(), "no prefix started or ended: " + heard);
    }
}

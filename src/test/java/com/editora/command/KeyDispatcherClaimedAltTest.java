package com.editora.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A component can claim a plain-Alt chord no keymap binds (the Project Map's Alt+Left / Alt+Right history).
 * Such a chord used to be swallowed by the Windows menu-mnemonic guard before the component saw it. The
 * guard still holds everywhere else, and a claimed press the component leaves alone is consumed on its way
 * back up, so it can never reach the native menu either way.
 */
class KeyDispatcherClaimedAltTest {

    private static KeyEvent press(Node target, KeyCode code, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(target, target, KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, alt, meta);
    }

    private static Region claiming(String... chords) {
        Region node = new Region();
        node.getProperties().put(KeyDispatcher.CLAIMED_KEYS, Set.of(chords));
        return node;
    }

    private static KeyDispatcher dispatcher(String keymap, boolean mac, List<String> ran, String... commands) {
        KeymapManager km = new KeymapManager();
        km.loadNamed(keymap, mac);
        CommandRegistry registry = new CommandRegistry();
        for (String id : commands) {
            registry.register(Command.of(id, id, () -> ran.add(id)));
        }
        return new KeyDispatcher(registry, km, s -> {}, mac);
    }

    @Test
    void anUnboundAltChordReachesTheComponentThatClaimsIt() {
        for (String keymap : List.of("emacs", "cua", "vscode", "intellij", "sublime")) {
            KeyDispatcher d = dispatcher(keymap, false, new ArrayList<>());
            Region map = claiming("M-left", "M-right");
            KeyEvent back = press(map, KeyCode.LEFT, false, true, false);
            d.handle(back);
            assertFalse(back.isConsumed(), keymap + ": Alt+Left must reach the component that claimed it");
        }
    }

    @Test
    void theGuardStillConsumesUnboundAltChordsNobodyClaims() {
        KeyDispatcher d = dispatcher("cua", false, new ArrayList<>());
        KeymapManager km = new KeymapManager();
        km.loadNamed("cua", false);
        assertNull(km.commandFor("M-left"), "precondition: Alt+Left is unbound");

        KeyEvent elsewhere = press(new Region(), KeyCode.LEFT, false, true, false);
        d.handle(elsewhere);
        assertTrue(elsewhere.isConsumed(), "outside a claiming component the menu-mnemonic guard is unchanged");

        // A claim covers only the chords it names.
        KeyEvent other = press(claiming("M-left"), KeyCode.UP, false, true, false);
        d.handle(other);
        assertTrue(other.isConsumed(), "an Alt chord the component did not claim is still guarded");
    }

    @Test
    void aClaimedAltPressTheComponentIgnoresIsConsumedOnTheWayBackUp() {
        KeyDispatcher d = dispatcher("cua", false, new ArrayList<>());
        KeyEvent back = press(claiming("M-left"), KeyCode.LEFT, false, true, false);
        d.handle(back); // capture phase: left to the component
        d.consumeClaimedAlt(back); // bubble phase: nobody consumed it
        assertTrue(back.isConsumed(), "it must not go on to the scene's mnemonics or the native menu");
    }

    @Test
    void theBackstopDoesNotOutliveItsOwnPress() {
        KeyDispatcher d = dispatcher("cua", false, new ArrayList<>());
        Region map = claiming("M-left");
        d.handle(press(map, KeyCode.LEFT, false, true, false)); // the component consumed it: no bubble phase

        KeyEvent next = press(map, KeyCode.DOWN, false, false, false);
        d.handle(next);
        d.consumeClaimedAlt(next);
        assertFalse(next.isConsumed(), "an ordinary key after a claimed Alt chord is nobody's business");
    }

    @Test
    void aClaimStillWinsOverTheShortcutEveryKeymapBindsToTextZoom() {
        // Non-macOS: Ctrl+0. macOS: Cmd+0 in the .mac keymaps.
        for (String keymap : List.of("emacs", "cua", "vscode", "intellij", "sublime")) {
            List<String> ran = new ArrayList<>();
            KeyDispatcher d = dispatcher(keymap, false, ran, "view.textZoomReset");
            KeyEvent fit = press(claiming("C-0"), KeyCode.DIGIT0, true, false, false);
            d.handle(fit);
            assertFalse(fit.isConsumed(), keymap);
            assertTrue(ran.isEmpty(), keymap + ": the claimed chord must not reset the editor's text zoom");
        }
        for (String keymap : List.of("cua", "vscode", "intellij", "sublime")) {
            List<String> ran = new ArrayList<>();
            KeyDispatcher d = dispatcher(keymap, true, ran, "view.textZoomReset");
            KeyEvent unclaimed = press(new Region(), KeyCode.DIGIT0, false, false, true);
            d.handle(unclaimed);
            assertEquals(List.of("view.textZoomReset"), ran, keymap + " (mac) precondition: Cmd+0 is bound");
            ran.clear();
            KeyEvent fit = press(claiming("Cmd-0"), KeyCode.DIGIT0, false, false, true);
            d.handle(fit);
            assertFalse(fit.isConsumed(), keymap + " (mac)");
            assertTrue(ran.isEmpty(), keymap + " (mac)");
        }
    }

    @Test
    void onMacAnOptionChordWasNeverGuardedAndStillReachesItsComponent() {
        KeyDispatcher d = dispatcher("cua", true, new ArrayList<>());
        KeyEvent back = press(claiming("M-left"), KeyCode.LEFT, false, true, false);
        d.handle(back);
        assertFalse(back.isConsumed());
        d.consumeClaimedAlt(back);
        assertFalse(back.isConsumed(), "no menu mode on macOS, so nothing to back-stop");
    }
}

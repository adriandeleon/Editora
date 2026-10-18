package com.editora.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A terminal takes the whole keyboard ({@link KeyDispatcher#SHELL_KEYS}): Ctrl+C is the shell's SIGINT, not
 * Copy, and Emacs's C-x prefix is the shell's too. Only the commands it gives back still run.
 */
class KeyDispatcherShellKeysTest {

    private static KeyEvent press(Node target, KeyCode code, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(target, target, KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, alt, meta);
    }

    /** A terminal that lets {@code allowed} command ids through, with the focus on a child of it. */
    private static Region terminal(String... allowed) {
        StackPane terminal = new StackPane();
        Region focus = new Region();
        terminal.getChildren().add(focus);
        List<String> through = List.of(allowed);
        BiPredicate<String, String> reachesEditora = (token, command) -> command != null && through.contains(command);
        terminal.getProperties().put(KeyDispatcher.SHELL_KEYS, reachesEditora);
        return focus;
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
    void ctrlCReachesTheShellInsteadOfCopying() {
        List<String> ran = new ArrayList<>();
        KeyDispatcher d = dispatcher("vscode", false, ran, "edit.copy");
        KeyEvent ctrlC = press(terminal(), KeyCode.C, true, false, false);
        d.handle(ctrlC);
        assertFalse(ctrlC.isConsumed(), "left to the terminal, which sends ^C");
        assertTrue(ran.isEmpty());
    }

    @Test
    void aCommandTheTerminalGivesBackStillRuns() {
        List<String> ran = new ArrayList<>();
        KeyDispatcher d = dispatcher("vscode", false, ran, "palette.show");
        Region focus = terminal("palette.show");
        KeyEvent palette =
                new KeyEvent(focus, focus, KeyEvent.KEY_PRESSED, "", "", KeyCode.P, true, true, false, false); // C-S-p
        d.handle(palette);
        assertEquals(List.of("palette.show"), ran);
        assertTrue(palette.isConsumed());
    }

    @Test
    void emacsPrefixesBelongToTheShell() {
        List<String> ran = new ArrayList<>();
        KeyDispatcher d = dispatcher("emacs", false, ran, "file.save");
        Region focus = terminal();
        KeyEvent ctrlX = press(focus, KeyCode.X, true, false, false);
        d.handle(ctrlX);
        assertFalse(ctrlX.isConsumed(), "C-x goes to the shell, not to Editora's prefix");
        KeyEvent ctrlS = press(focus, KeyCode.S, true, false, false);
        d.handle(ctrlS);
        assertFalse(ctrlS.isConsumed());
        assertTrue(ran.isEmpty(), "no C-x C-s save from inside the terminal");
    }

    @Test
    void anUnboundAltChordIsTheShellsMetaAndIsBackstopped() {
        KeyDispatcher d = dispatcher("cua", false, new ArrayList<>());
        KeyEvent altB = press(terminal(), KeyCode.B, false, true, false);
        d.handle(altB);
        assertFalse(altB.isConsumed(), "readline's M-b reaches the terminal");
        d.consumeClaimedAlt(altB);
        assertTrue(altB.isConsumed(), "and is consumed on the way back up if the terminal did not");
    }

    @Test
    void outsideATerminalNothingChanges() {
        List<String> ran = new ArrayList<>();
        KeyDispatcher d = dispatcher("vscode", false, ran, "edit.copy");
        KeyEvent ctrlC = press(new Region(), KeyCode.C, true, false, false);
        d.handle(ctrlC);
        assertEquals(List.of("edit.copy"), ran);
    }
}

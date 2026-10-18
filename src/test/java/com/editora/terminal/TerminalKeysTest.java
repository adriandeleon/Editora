package com.editora.terminal;

import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which keys a focused terminal keeps. Getting one wrong either breaks the shell or strands the user in it. */
class TerminalKeysTest {

    private static final List<String> DEFAULTS = TerminalKeys.DEFAULT_IDE_COMMANDS;

    @Test
    void theSettingsDefaultIsTheTerminalsOwn() {
        // Settings cannot name TerminalKeys (config stays below the feature packages), so it repeats the list.
        assertEquals(TerminalKeys.DEFAULT_IDE_COMMANDS, com.editora.config.Settings.DEFAULT_TERMINAL_IDE_COMMANDS);
    }

    @Test
    void theShellsControlKeysStayInTheShell() {
        assertFalse(TerminalKeys.reachesEditora("C-c", "edit.copy", DEFAULTS, false), "SIGINT, not Copy");
        assertFalse(TerminalKeys.reachesEditora("C-w", "buffer.close", DEFAULTS, false), "readline word delete");
        assertFalse(TerminalKeys.reachesEditora("C-r", "edit.redo", DEFAULTS, false), "history search");
        assertFalse(TerminalKeys.reachesEditora("escape", null, DEFAULTS, false), "vim's");
        assertFalse(TerminalKeys.reachesEditora("C-x", null, DEFAULTS, false), "an unbound key is the shell's");
    }

    @Test
    void theAllowListReachesEditora() {
        assertTrue(TerminalKeys.reachesEditora("C-S-p", "palette.show", DEFAULTS, false));
        assertTrue(TerminalKeys.reachesEditora("M-f12", "tool.terminal", DEFAULTS, false), "hide the terminal");
        assertTrue(TerminalKeys.reachesEditora("M-1", "tool.project", DEFAULTS, false));
        assertTrue(TerminalKeys.reachesEditora("C-S-back-quote", "terminal.new", DEFAULTS, false));
    }

    @Test
    void anEmptyAllowListLeavesOnlyCmdOnMac() {
        assertFalse(TerminalKeys.reachesEditora("C-S-p", "palette.show", List.of(), false));
        assertTrue(TerminalKeys.reachesEditora("Cmd-S-p", "palette.show", List.of(), true));
    }

    @Test
    void onMacEveryCmdChordReachesEditoraButTheTerminalsOwn() {
        assertTrue(TerminalKeys.reachesEditora("Cmd-s", "file.save", List.of(), true));
        assertTrue(TerminalKeys.reachesEditora("Cmd-w", "buffer.close", List.of(), true));
        assertFalse(TerminalKeys.reachesEditora("Cmd-c", "edit.copy", DEFAULTS, true), "the terminal copies");
        assertFalse(TerminalKeys.reachesEditora("Cmd-v", "edit.paste", DEFAULTS, true), "the terminal pastes");
        assertFalse(TerminalKeys.reachesEditora("C-c", "edit.copy", DEFAULTS, true), "Ctrl is the shell's");
    }

    @Test
    void theTerminalsCopyAndPasteNeverReachEditora() {
        // Even when a keymap binds them (some bind Ctrl+Shift+V to paste-from-history).
        assertFalse(TerminalKeys.reachesEditora("C-S-c", "tool.whatever", List.of("tool.*"), false));
        assertFalse(TerminalKeys.reachesEditora("C-S-v", "palette.show", DEFAULTS, false));
    }

    @Test
    void prefixEntriesMatchOnlyWholeSegments() {
        assertTrue(TerminalKeys.matches("tool.run", List.of("tool.*")));
        assertFalse(TerminalKeys.matches("toolbox.run", List.of("tool.*")));
        assertTrue(TerminalKeys.matches("palette.show", List.of(" palette.show ")));
        assertFalse(TerminalKeys.matches("palette.showAll", List.of("palette.show")));
        assertFalse(TerminalKeys.matches("x", null));
    }

    @Test
    void copyAndPasteChordsPerPlatform() {
        assertTrue(TerminalKeys.isCopy(key(KeyCode.C, true, true, false), false));
        assertFalse(TerminalKeys.isCopy(key(KeyCode.C, false, true, false), false), "plain Ctrl+C is SIGINT");
        assertTrue(TerminalKeys.isPaste(key(KeyCode.V, true, true, false), false));
        assertTrue(TerminalKeys.isCopy(key(KeyCode.C, false, false, true), true));
        assertFalse(TerminalKeys.isCopy(key(KeyCode.C, true, true, false), true), "Ctrl+Shift+C is not mac copy");
        assertTrue(TerminalKeys.isPaste(key(KeyCode.V, false, false, true), true));
    }

    private static KeyEvent key(KeyCode code, boolean shift, boolean ctrl, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, meta);
    }

    @Test
    void thePromptJumpsAreTheTerminalsOwn() {
        assertFalse(TerminalKeys.reachesEditora("C-S-up", "edit.moveLineUp", DEFAULTS, false));
        assertFalse(TerminalKeys.reachesEditora("Cmd-down", "nav.end", DEFAULTS, true), "even a Cmd chord on mac");
        assertTrue(TerminalKeys.isPreviousPrompt(key(KeyCode.UP, true, true, false), false));
        assertTrue(TerminalKeys.isNextPrompt(key(KeyCode.DOWN, false, false, true), true));
        assertFalse(
                TerminalKeys.isPreviousPrompt(key(KeyCode.UP, false, true, false), false), "Ctrl+Up is the shell's");
    }

    @Test
    void theFindChordIsTheTerminals() {
        assertFalse(TerminalKeys.reachesEditora("C-S-f", "tool.find", List.of("tool.*"), false));
        assertFalse(TerminalKeys.reachesEditora("Cmd-f", "find.show", List.of(), true));
        assertTrue(TerminalKeys.reachesEditora("Cmd-g", "find.next", List.of(), true));
    }
}

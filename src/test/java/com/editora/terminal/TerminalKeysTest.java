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
        assertFalse(TerminalKeys.reachesEditora("M-1", "tool.project", DEFAULTS, false), "readline's digit-argument");
        assertTrue(
                TerminalKeys.reachesEditora("M-1", "tool.project", List.of("tool.*", "M-1"), false), "unless listed");
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

    @Test
    void noBundledKeymapTakesAShellKeyFromAFocusedTerminal() {
        // Walks every keymap: a bare Ctrl+letter, Alt+letter, Alt+digit or F-key bound to something Editora lets
        // through must stay with the shell, apart from the terminal's own commands and Emacs's M-x palette.
        List<String> wrong = new java.util.ArrayList<>();
        for (String name : List.of("emacs", "intellij", "vscode", "cua", "sublime")) {
            for (boolean mac : new boolean[] {false, true}) {
                com.editora.command.KeymapManager.defaults(name, mac).forEach((token, id) -> {
                    if (token.contains(" ")) return; // a sequence: its first chord is a prefix, the shell's
                    boolean reaches = TerminalKeys.reachesEditora(token, id, DEFAULTS, mac);
                    boolean ownOrPalette = id.equals("tool.terminal")
                            || id.startsWith("terminal.")
                            || (id.equals("palette.show") && token.equals("M-x"));
                    if (reaches && TerminalKeys.isShellChord(token) && !ownOrPalette) {
                        wrong.add(name + (mac ? ".mac" : "") + " " + token + " -> " + id);
                    }
                });
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void theKeysTheReviewFoundTakenNowReachTheShell() {
        var intellij = com.editora.command.KeymapManager.defaults("intellij", false);
        var vscode = com.editora.command.KeymapManager.defaults("vscode", false);
        var emacs = com.editora.command.KeymapManager.defaults("emacs", false);
        assertFalse(TerminalKeys.reachesEditora("C-k", intellij.get("C-k"), DEFAULTS, false), "kill-line");
        assertFalse(TerminalKeys.reachesEditora("M-4", intellij.get("M-4"), DEFAULTS, false));
        assertFalse(TerminalKeys.reachesEditora("C-b", vscode.get("C-b"), DEFAULTS, false), "tmux's prefix");
        assertFalse(TerminalKeys.reachesEditora("f1", vscode.get("f1"), DEFAULTS, false), "help in htop and mc");
        assertFalse(TerminalKeys.reachesEditora("M-1", emacs.get("M-1"), DEFAULTS, false));
        assertTrue(TerminalKeys.reachesEditora("M-x", emacs.get("M-x"), DEFAULTS, false), "the Emacs palette stays");
        assertTrue(TerminalKeys.reachesEditora("M-f12", intellij.get("M-f12"), DEFAULTS, false), "IntelliJ's way out");
        assertTrue(TerminalKeys.reachesEditora("C-back-quote", vscode.get("C-back-quote"), DEFAULTS, false));
        assertTrue(TerminalKeys.reachesEditora("C-S-p", vscode.get("C-S-p"), DEFAULTS, false), "the palette");
    }

    @Test
    void shellChords() {
        for (String t : List.of("C-k", "M-x", "M-1", "f1", "S-f1", "C-M-f", "escape", "a", "C-[")) {
            assertTrue(TerminalKeys.isShellChord(t), t);
        }
        for (String t : List.of("C-S-p", "M-S-x", "M-f12", "C-back-quote", "C-S-back-quote", "Cmd-k", "C-pagedown")) {
            assertFalse(TerminalKeys.isShellChord(t), t);
        }
    }

    private static KeyEvent mods(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta);
    }

    @Test
    void theTabChordsTakeExactlyTheirModifiers() {
        assertTrue(TerminalKeys.isNextTab(mods(KeyCode.PAGE_DOWN, false, true, false, false)));
        assertFalse(TerminalKeys.isNextTab(mods(KeyCode.PAGE_UP, false, true, false, false)));
        assertFalse(TerminalKeys.isNextTab(mods(KeyCode.PAGE_DOWN, true, true, false, false)), "Shift moves the tab");
        assertFalse(TerminalKeys.isNextTab(mods(KeyCode.PAGE_DOWN, false, true, true, false)));
        assertFalse(TerminalKeys.isNextTab(mods(KeyCode.PAGE_DOWN, false, true, false, true)));
        assertFalse(TerminalKeys.isNextTab(mods(KeyCode.PAGE_DOWN, false, false, false, false)));
        assertTrue(TerminalKeys.isPreviousTab(mods(KeyCode.PAGE_UP, false, true, false, false)));
        assertFalse(TerminalKeys.isPreviousTab(mods(KeyCode.PAGE_DOWN, false, true, false, false)));

        assertTrue(TerminalKeys.isMoveTabRight(mods(KeyCode.PAGE_DOWN, true, true, false, false)));
        assertFalse(TerminalKeys.isMoveTabRight(mods(KeyCode.PAGE_UP, true, true, false, false)));
        assertFalse(TerminalKeys.isMoveTabRight(mods(KeyCode.PAGE_DOWN, false, true, false, false)));
        assertFalse(TerminalKeys.isMoveTabRight(mods(KeyCode.PAGE_DOWN, true, false, false, false)));
        assertFalse(TerminalKeys.isMoveTabRight(mods(KeyCode.PAGE_DOWN, true, true, true, false)));
        assertFalse(TerminalKeys.isMoveTabRight(mods(KeyCode.PAGE_DOWN, true, true, false, true)));
        assertTrue(TerminalKeys.isMoveTabLeft(mods(KeyCode.PAGE_UP, true, true, false, false)));
        assertFalse(TerminalKeys.isMoveTabLeft(mods(KeyCode.PAGE_DOWN, true, true, false, false)));
    }

    @Test
    void theClipboardAndFindChordsRefuseExtraModifiers() {
        assertTrue(TerminalKeys.isFind(mods(KeyCode.F, true, true, false, false), false));
        assertFalse(TerminalKeys.isFind(mods(KeyCode.G, true, true, false, false), false));
        assertFalse(TerminalKeys.isFind(mods(KeyCode.F, true, true, true, false), false), "Alt is not Find");
        assertFalse(TerminalKeys.isFind(mods(KeyCode.F, true, true, false, true), false));
        assertFalse(TerminalKeys.isFind(mods(KeyCode.F, false, true, false, false), false));
        assertTrue(TerminalKeys.isFind(mods(KeyCode.F, false, false, false, true), true));
        assertFalse(TerminalKeys.isFind(mods(KeyCode.F, false, true, false, true), true));
        assertFalse(TerminalKeys.isFind(mods(KeyCode.F, true, false, false, true), true));
        assertFalse(TerminalKeys.isFind(mods(KeyCode.F, false, false, false, false), true));
        assertFalse(TerminalKeys.isNextPrompt(mods(KeyCode.UP, true, true, false, false), false));
        assertFalse(TerminalKeys.isPaste(mods(KeyCode.C, true, true, false, false), false));
        assertTrue(TerminalKeys.isPaste(mods(KeyCode.V, true, true, false, false), false));
    }

    @Test
    void theTerminalsOwnTokensNeverReachEditora() {
        for (String t : List.of(
                "C-S-c",
                "C-S-v",
                "C-S-up",
                "C-S-down",
                "C-S-f",
                "C-pageup",
                "C-pagedown",
                "C-S-pageup",
                "C-S-pagedown")) {
            assertFalse(TerminalKeys.reachesEditora(t, "tool.files", DEFAULTS, false), t);
        }
        for (String t : List.of("Cmd-c", "Cmd-v", "Cmd-up", "Cmd-down", "Cmd-f")) {
            assertFalse(TerminalKeys.reachesEditora(t, "tool.files", DEFAULTS, true), t);
        }
        assertFalse(TerminalKeys.reachesEditora(null, "tool.files", DEFAULTS, false));
        assertTrue(TerminalKeys.reachesEditora("Cmd-s", null, DEFAULTS, true), "a Cmd chord is never the shell's");
        assertTrue(TerminalKeys.reachesEditora("C-S-c", "tool.files", DEFAULTS, true), "on macOS Copy is Cmd+C");
    }

    @Test
    void theAllowListCanNameAChordAndSkipsBlankEntries() {
        List<String> list = java.util.Arrays.asList(null, "  ", "C-x", "file.save", "build.*");
        assertTrue(TerminalKeys.listsChord("C-x", list));
        assertFalse(TerminalKeys.listsChord("file.save", list), "an entry with a dot is a command id");
        assertFalse(TerminalKeys.listsChord("C-y", list));
        assertFalse(TerminalKeys.listsChord("C-x", null));
        assertTrue(TerminalKeys.reachesEditora("C-x", "edit.cut", list, false), "a listed chord passes");
        assertFalse(TerminalKeys.matches("tool.files", null));
        assertTrue(TerminalKeys.matches("build.run", list));
        assertTrue(TerminalKeys.matches("file.save", list));
        assertFalse(TerminalKeys.matches("file.open", list));
        assertTrue(TerminalKeys.reachesEditora("C-S-b", "build.run", list, false));
        assertFalse(TerminalKeys.reachesEditora("C-b", "build.run", list, false), "a shell chord stays in the shell");
        assertTrue(TerminalKeys.reachesEditora("M-x", "palette.show", DEFAULTS, false), "Emacs's palette");
        assertFalse(TerminalKeys.reachesEditora("M-y", "palette.show", DEFAULTS, false));
        assertTrue(TerminalKeys.reachesEditora("C-t", "tool.terminal", DEFAULTS, false), "the way out");
    }

    @Test
    void aLoneModifierPrefixIsAKeyNotAModifier() {
        assertTrue(TerminalKeys.isShellChord("C-"), "C- alone is a key named C-");
        assertTrue(TerminalKeys.isShellChord("M-"));
        assertTrue(TerminalKeys.isShellChord("S-"));
        assertTrue(TerminalKeys.isShellChord("Cmd-"));
        assertTrue(TerminalKeys.isShellChord("S-a"), "Shift alone types");
        assertFalse(TerminalKeys.isShellChord("C-Cmd-k"));
        assertTrue(TerminalKeys.isShellChord("C-M-a"), "a C-M chord is a shell chord");
        assertFalse(TerminalKeys.isShellChord("C-f5"));
    }
}

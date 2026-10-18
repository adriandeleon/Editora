package com.editora.terminal;

import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Which keys a focused terminal keeps and which still reach Editora.
 *
 * <p>The shell owns the keyboard: Ctrl+C is SIGINT, Ctrl+W deletes a word, Ctrl+R searches history, Escape is
 * vim's, and Emacs's C-x is Emacs's. Editora gets back only the commands named in the allow-list (by default
 * the tool windows, the command palette and Search Everywhere), plus, on macOS, every Cmd chord — Cmd
 * means nothing to a shell, so taking it costs the shell nothing.
 *
 * <p>An allowed command passes only on a chord a shell does not use ({@link #isShellChord}): a bare
 * Ctrl+letter, Alt+letter, Alt+digit or F-key stays with the shell whatever a keymap binds to it. Going by the
 * command alone took IntelliJ's Ctrl+K (kill-line) and VS Code's Ctrl+B (backward-char, tmux's prefix) and F1 from
 * the shell. The terminal's own commands ({@code tool.terminal}, {@code terminal.*}) pass on any chord, and so does
 * the Emacs keymap's M-x for the palette (adl's call). A chord typed into the setting itself ({@code M-1}) passes
 * whatever it is bound to.
 *
 * <p>The terminal's own Copy and Paste are Ctrl+Shift+C/V off macOS and Cmd+C/V on it: plain Ctrl+C and Ctrl+V
 * belong to the shell (SIGINT, readline's literal-next).
 */
public final class TerminalKeys {

    private TerminalKeys() {}

    /** What a terminal gives back by default: tool windows, the palette, Search Everywhere. */
    public static final List<String> DEFAULT_IDE_COMMANDS =
            List.of("tool.*", "palette.show", "search.everywhere", "terminal.*");

    /**
     * Whether a chord still reaches Editora from a focused terminal.
     *
     * @param token the dispatcher's chord token ({@code C-S-p}, {@code Cmd-s}, {@code M-x})
     * @param commandId what the keymap binds to it, or {@code null}
     * @param ideCommands command ids that pass, each exact or ending in {@code .*} for a prefix
     */
    public static boolean reachesEditora(String token, String commandId, List<String> ideCommands, boolean mac) {
        if (token == null
                || isCopyOrPaste(token, mac)
                || isPromptJump(token, mac)
                || isFindToken(token, mac)
                || token.equals("C-pageup")
                || token.equals("C-pagedown")) {
            return false;
        }
        if (mac && token.contains("Cmd-")) {
            return true;
        }
        if (commandId == null) {
            return false;
        }
        if (listsChord(token, ideCommands)) {
            return true;
        }
        if (!matches(commandId, ideCommands)) {
            return false;
        }
        if (commandId.equals("tool.terminal") || commandId.startsWith("terminal.")) {
            return true; // the terminal's own: there must always be a way out of it
        }
        if (commandId.equals("palette.show") && token.equals("M-x")) {
            return true; // the Emacs keymap's palette, kept from inside a terminal
        }
        return !isShellChord(token);
    }

    /** Whether the allow-list names {@code token} itself (an entry without a dot is a chord, not a command id). */
    static boolean listsChord(String token, List<String> ideCommands) {
        if (ideCommands == null) {
            return false;
        }
        for (String entry : ideCommands) {
            String e = entry == null ? "" : entry.strip();
            if (!e.isEmpty() && !e.contains(".") && e.equals(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a chord token is one shells and terminal programs use: any key with no modifier or Shift alone
     * (typing, F-keys, arrows), and a character key with Ctrl, Alt or both (control bytes, Meta, C-M chords).
     * Ctrl+Shift+letter, Alt+Shift+letter, Alt+F12 and Ctrl+` are not.
     */
    static boolean isShellChord(String token) {
        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        String key = token;
        while (true) {
            if (key.startsWith("C-") && key.length() > 2) {
                ctrl = true;
                key = key.substring(2);
            } else if (key.startsWith("M-") && key.length() > 2) {
                alt = true;
                key = key.substring(2);
            } else if (key.startsWith("S-") && key.length() > 2) {
                shift = true;
                key = key.substring(2);
            } else if (key.startsWith("Cmd-") && key.length() > 4) {
                return false;
            } else {
                break;
            }
        }
        if (!ctrl && !alt) {
            return true;
        }
        return !shift && key.length() == 1;
    }

    /** Whether {@code commandId} is in an allow-list of exact ids and {@code prefix.*} entries. */
    static boolean matches(String commandId, List<String> ideCommands) {
        if (ideCommands == null) {
            return false;
        }
        for (String entry : ideCommands) {
            String e = entry == null ? "" : entry.strip();
            if (e.endsWith(".*") ? commandId.startsWith(e.substring(0, e.length() - 1)) : commandId.equals(e)) {
                return true;
            }
        }
        return false;
    }

    /** The terminal's own Copy/Paste chord tokens. */
    static boolean isCopyOrPaste(String token, boolean mac) {
        return mac ? token.equals("Cmd-c") || token.equals("Cmd-v") : token.equals("C-S-c") || token.equals("C-S-v");
    }

    /** The terminal's own jump-to-prompt chord tokens. */
    static boolean isPromptJump(String token, boolean mac) {
        return mac
                ? token.equals("Cmd-up") || token.equals("Cmd-down")
                : token.equals("C-S-up") || token.equals("C-S-down");
    }

    /** The terminal's own Find chord token. */
    static boolean isFindToken(String token, boolean mac) {
        return mac ? token.equals("Cmd-f") : token.equals("C-S-f");
    }

    /** Find in the terminal's output: Ctrl+Shift+F, or Cmd+F on macOS. */
    public static boolean isFind(KeyEvent e, boolean mac) {
        return e.getCode() == KeyCode.F && clipboardModifier(e, mac);
    }

    /** Scroll to the previous prompt: Ctrl+Shift+Up, or Cmd+Up on macOS (shell integration's prompt marks). */
    public static boolean isPreviousPrompt(KeyEvent e, boolean mac) {
        return e.getCode() == KeyCode.UP && clipboardModifier(e, mac);
    }

    /** Scroll to the next prompt: Ctrl+Shift+Down, or Cmd+Down on macOS. */
    public static boolean isNextPrompt(KeyEvent e, boolean mac) {
        return e.getCode() == KeyCode.DOWN && clipboardModifier(e, mac);
    }

    /**
     * The next terminal tab: Ctrl+PageDown, as in VS Code, browsers and most terminal emulators. Kept by the
     * terminal on every platform; few programs read the chord.
     */
    public static boolean isNextTab(KeyEvent e) {
        return e.getCode() == KeyCode.PAGE_DOWN && onlyControl(e);
    }

    /** The previous terminal tab: Ctrl+PageUp. */
    public static boolean isPreviousTab(KeyEvent e) {
        return e.getCode() == KeyCode.PAGE_UP && onlyControl(e);
    }

    private static boolean onlyControl(KeyEvent e) {
        return e.isControlDown() && !e.isShiftDown() && !e.isAltDown() && !e.isMetaDown();
    }

    /** Copy: Ctrl+Shift+C, or Cmd+C on macOS. */
    public static boolean isCopy(KeyEvent e, boolean mac) {
        return e.getCode() == KeyCode.C && clipboardModifier(e, mac);
    }

    /** Paste: Ctrl+Shift+V, or Cmd+V on macOS. */
    public static boolean isPaste(KeyEvent e, boolean mac) {
        return e.getCode() == KeyCode.V && clipboardModifier(e, mac);
    }

    private static boolean clipboardModifier(KeyEvent e, boolean mac) {
        if (e.isAltDown()) {
            return false;
        }
        return mac
                ? e.isMetaDown() && !e.isControlDown() && !e.isShiftDown()
                : e.isControlDown() && e.isShiftDown() && !e.isMetaDown();
    }
}

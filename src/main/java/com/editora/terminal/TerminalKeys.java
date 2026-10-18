package com.editora.terminal;

import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Which keys a focused terminal keeps and which still reach Editora.
 *
 * <p>The shell owns the keyboard: Ctrl+C is SIGINT, Ctrl+W deletes a word, Ctrl+R searches history, Escape is
 * vim's, and Emacs's C-x is Emacs's. Editora gets back only the commands named in the allow-list (by default
 * the tool-window toggles, the command palette and Search Everywhere), plus, on macOS, every Cmd chord — Cmd
 * means nothing to a shell, so taking it costs the shell nothing.
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
        if (token == null || isCopyOrPaste(token, mac)) {
            return false;
        }
        if (mac && token.contains("Cmd-")) {
            return true;
        }
        return commandId != null && matches(commandId, ideCommands);
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

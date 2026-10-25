package com.editora.editor;

import java.util.regex.Pattern;

/**
 * A command's title as a menu row shows it. The palette needs its category prefixes ("LSP:", "Edit:", "Go:") to
 * group its rows; a menu already says where it is, so a prefix only repeated itself down Code ▸ Refactor ("LSP:
 * Extract Variable") and in the editor's LSP submenu ("LSP ▸ LSP: Go to Definition…" — Java review B14/E3), and a
 * menu that dropped only "LSP:" read half-cleaned beside "Edit: Complete Statement" (third review ET3).
 */
public final class MenuTitles {

    /** One or two words and a colon ("LSP:", "Edit:", "Go:", "LSP :" in French) at the start. */
    private static final Pattern PREFIX = Pattern.compile("^[\\p{L}]+(?: [\\p{L}]+)?\\s?:\\s+");

    private MenuTitles() {}

    /**
     * {@code title} without its category prefix, first letter capitalized ("extraer variable"), when what is
     * left still says what the row does: two words or more, or any "LSP:" row. "Snippet: Insert…" keeps its prefix,
     * which "Insert…" alone would not. Pure.
     */
    public static String of(String title) {
        var m = PREFIX.matcher(title);
        if (!m.find() || m.end() >= title.length()) {
            return title;
        }
        String rest = title.substring(m.end()).strip();
        if (!rest.contains(" ") && !title.startsWith("LSP")) { // "LSP: Generate…" is a Code-menu row either way
            return title;
        }
        return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }
}

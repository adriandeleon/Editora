package com.editora.editor;

import java.util.regex.Pattern;

/**
 * A command's title as a menu row shows it. The palette needs the "LSP:" prefix to group its rows; a menu
 * already says where it is, so the prefix only repeated itself down Code ▸ Refactor ("LSP: Extract Variable")
 * and in the editor's LSP submenu ("LSP ▸ LSP: Go to Definition…" — Java review B14/E3).
 */
public final class MenuTitles {

    private static final Pattern LSP_PREFIX = Pattern.compile("^LSP\\s?:\\s+");

    private MenuTitles() {}

    /** {@code title} without a leading "LSP:", its first letter capitalized ("extraer variable"); pure. */
    public static String of(String title) {
        var m = LSP_PREFIX.matcher(title);
        if (!m.find() || m.end() >= title.length()) {
            return title;
        }
        String rest = title.substring(m.end());
        return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }
}

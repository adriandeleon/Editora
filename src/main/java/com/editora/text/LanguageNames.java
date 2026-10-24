package com.editora.text;

import java.util.Locale;

/**
 * How a language id ({@link LanguageRegistry}) reads to a person — in the status bar and the Problems tool
 * window. Proper nouns, deliberately untranslated. An id with no entry is shown with its first letter
 * upper-cased ({@code python} → {@code Python}). Pure.
 */
public final class LanguageNames {

    private LanguageNames() {}

    /** The display name for {@code id}; {@code ""} for a null or empty id. */
    public static String display(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        return switch (id) {
            case "java" -> "Java";
            case "javascript" -> "JavaScript";
            case "javascriptreact" -> "JavaScript (JSX)";
            case "typescript" -> "TypeScript";
            case "typescriptreact" -> "TypeScript (TSX)";
            case "pip-requirements" -> "pip requirements"; // the tool's own spelling: lower-case pip
            default -> id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
        };
    }
}

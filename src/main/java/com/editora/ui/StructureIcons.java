package com.editora.ui;

import javafx.scene.Node;

/**
 * Maps a symbol kind (the lowercased LSP {@code SymbolKind} id, e.g. {@code class}/{@code method}/{@code
 * field}) to a small category glyph for the Structure tool window rows. Rendered via {@link Icons#of} so the
 * glyphs carry the theme-aware {@code toolbar-icon} style. Kinds are grouped into a handful of recognizable
 * categories; unknown kinds get a neutral dot.
 */
final class StructureIcons {

    private StructureIcons() {}

    // Material-style 24dp single-path glyphs.
    private static final String CLASS = "M12 2l10 6v8l-10 6L2 16V8z"; // hexagon-ish "type"
    private static final String INTERFACE = "M12 3a9 9 0 100 18 9 9 0 000-18zm0 4a5 5 0 110 10 5 5 0 010-10z"; // ring
    private static final String ENUM =
            "M3 5h2v2H3zM7 5h14v2H7zM3 11h2v2H3zM7 11h14v2H7zM3 17h2v2H3zM7 17h14v2H7z"; // list
    private static final String METHOD = "M18 4H6v2l6.5 6L6 18v2h12v-3h-7l5-5-5-5h7z"; // functions
    private static final String FIELD = "M12 8a4 4 0 100 8 4 4 0 000-8z"; // member dot (filled circle)
    private static final String MODULE = "M20 8h-3V4H3v16h18V8zM5 6h10v2H5zM5 10h14v8H5z"; // package/box
    private static final String HEADING = "M5 4v16h2v-7h6v7h2V4h-2v7H7V4z"; // an "H" (Markdown heading)
    private static final String DOT = "M9 9h6v6H9z"; // neutral square

    /** A fresh glyph Node for {@code kind} (the lowercased SymbolKind id). */
    static Node forKind(String kind) {
        return Icons.of(pathFor(kind));
    }

    /** The SVG path for {@code kind}'s category (package-visible for unit testing). */
    static String pathFor(String kind) {
        return switch (kind == null ? "" : kind) {
            case "class", "struct", "object", "typeparameter" -> CLASS;
            case "interface" -> INTERFACE;
            case "enum", "enummember" -> ENUM;
            case "method", "function", "constructor", "operator", "event" -> METHOD;
            case "field", "property", "variable", "constant", "key" -> FIELD;
            case "module", "namespace", "package", "file" -> MODULE;
            case "heading" -> HEADING;
            default -> DOT;
        };
    }
}

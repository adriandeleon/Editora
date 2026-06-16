package com.editora.completion;

/**
 * The <b>display</b> category of a completion, used only to pick a per-row icon (IntelliJ-style). This is
 * distinct from {@link Completion.Kind}, which drives <em>accept behavior</em> (snippet session vs. literal
 * insert). The enum lives in {@code completion/} (not {@code lsp/}) so the editor package can read it
 * without importing lsp4j — {@code lsp/CompletionMapper} maps lsp4j's {@code CompletionItemKind} onto it.
 *
 * <p>{@link #iconKey()} is pure (no toolkit), so it is unit-testable; {@code editor/CompletionIcons} turns
 * the key into a glyph node.
 */
public enum CompletionIconKind {
    METHOD,
    FUNCTION,
    CONSTRUCTOR,
    FIELD,
    PROPERTY,
    VARIABLE,
    PARAMETER,
    CLASS,
    INTERFACE,
    ENUM,
    ENUM_MEMBER,
    STRUCT,
    MODULE,
    KEYWORD,
    CONSTANT,
    SNIPPET,
    TEXT,
    FILE,
    FOLDER,
    VALUE,
    REFERENCE,
    OPERATOR,
    TYPE_PARAMETER,
    COLOR,
    UNIT,
    EVENT,
    OTHER;

    /** Glyph key for this kind — several kinds share a glyph. {@code editor/CompletionIcons} maps it to SVG. */
    public String iconKey() {
        return switch (this) {
            case METHOD, FUNCTION, CONSTRUCTOR, OPERATOR, EVENT -> "method";
            case FIELD, PROPERTY, ENUM_MEMBER, CONSTANT -> "field";
            case VARIABLE, PARAMETER, VALUE, REFERENCE -> "variable";
            case CLASS, STRUCT, TYPE_PARAMETER -> "class";
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case MODULE -> "module";
            case KEYWORD -> "keyword";
            case SNIPPET -> "snippet";
            case TEXT, UNIT -> "text";
            case FILE -> "file";
            case FOLDER -> "folder";
            case COLOR -> "color";
            case OTHER -> "other";
        };
    }
}

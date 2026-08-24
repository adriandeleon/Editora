package com.editora.index;

/**
 * What a {@link Symbol} declares.
 *
 * <p>A deliberately small set — the kinds a regex can identify honestly across many languages, and the
 * ones a navigation picker actually distinguishes with an icon. It is not LSP's {@code SymbolKind}: that
 * has twenty-six members, most of which require real type resolution to tell apart (a Field from a
 * Property, a Constant from a Variable), and guessing between them from a pattern match would put
 * confident-looking noise in front of the user.
 */
public enum SymbolKind {
    /** A class, struct, or other nominal type. */
    TYPE,
    /** An interface, protocol, or trait. */
    INTERFACE,
    /** An enum or union. */
    ENUM,
    /** A method — a function declared inside a type. */
    METHOD,
    /** A free function. */
    FUNCTION,
    /** A field, property, or member variable. */
    FIELD,
    /** A top-level constant or variable. */
    VARIABLE,
    /** A module, namespace, or package declaration. */
    MODULE
}

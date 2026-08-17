package com.editora.index;

/**
 * One declaration found in a file: what it is called, what kind of thing it is, and where it sits.
 *
 * <p>Deliberately the same shape a language server's {@code documentSymbol} reduces to, so the index can
 * stand in for one where no server is configured and step aside where one is. Lines and columns are
 * 0-based, matching {@code lsp/SymbolNode} and the rest of the navigation code.
 *
 * @param name the declared identifier, as written
 * @param kind what was declared
 * @param line 0-based line of the declaration
 * @param column 0-based column at which {@code name} starts
 * @param container the enclosing type or namespace as written, or {@code ""} when unknown — this is a
 *     best-effort hint for disambiguating a picker row, never a resolved qualified name
 */
public record Symbol(String name, SymbolKind kind, int line, int column, String container) {

    public Symbol {
        name = name == null ? "" : name;
        container = container == null ? "" : container;
    }

    /** A symbol with no known container. */
    public Symbol(String name, SymbolKind kind, int line, int column) {
        this(name, kind, line, column, "");
    }

    /** {@code Container.name}, or just the name when the container is unknown — a picker's detail column. */
    public String qualified() {
        return container.isEmpty() ? name : container + "." + name;
    }
}

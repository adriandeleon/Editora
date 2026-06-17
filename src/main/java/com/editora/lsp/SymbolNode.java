package com.editora.lsp;

import java.util.List;

/**
 * A neutral, lsp4j-free node of a file's symbol outline ({@code textDocument/documentSymbol}), so {@code ui}
 * can render the Structure tool window without depending on LSP4J types. {@link DocumentSymbolMapper} builds
 * these from the server's response.
 *
 * @param name     the symbol name (e.g. a method or class name)
 * @param detail   optional extra detail (e.g. a signature), or {@code ""}
 * @param kind     a lowercase kind id (e.g. {@code class}/{@code method}/{@code field}) — see
 *                 {@link DocumentSymbolMapper#kindName}
 * @param line     0-based line to navigate to (the symbol's selection/name range start)
 * @param endLine  0-based last line of the symbol's full range (for ordering; {@code >= line})
 * @param children nested symbols (members), in document order
 */
public record SymbolNode(String name, String detail, String kind, int line, int endLine, List<SymbolNode> children) {}

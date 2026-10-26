package com.editora.lsp;

import java.util.List;

/**
 * A member of a file's symbol tree with its whole extent, for finding the member at a line: unlike {@link SymbolNode}
 * (the Structure window's outline), it starts at the member's annotations and Javadoc and keeps a method's children,
 * so an anonymous or local class inside a method is a member too (eighth review BQ1, BQ2).
 *
 * @param name       the server's name ({@code area()}, {@code Base(String)}, {@code new Runnable() {...}})
 * @param kind       a lowercase kind id, as {@link DocumentSymbolMapper#kindName} gives it
 * @param startLine  0-based first line of the full range (its annotations and Javadoc)
 * @param endLine    0-based last line of the full range
 * @param nameLine   0-based line of the name (selection range)
 * @param nameColumn 0-based column of the name
 * @param children   nested members, in document order
 */
public record MemberSpan(
        String name, String kind, int startLine, int endLine, int nameLine, int nameColumn, List<MemberSpan> children) {

    /** Whether this is a class, interface, enum, record or anonymous class. */
    public boolean isType() {
        return switch (kind) {
            case "class", "interface", "enum", "struct" -> true;
            default -> false;
        };
    }

    /** Whether this is an anonymous class ({@code new Runnable() {...}}). */
    public boolean isAnonymous() {
        return isType() && name.startsWith("new ");
    }

    /** Whether this is a method or a constructor. */
    public boolean isCallable() {
        return kind.equals("method") || kind.equals("constructor");
    }
}

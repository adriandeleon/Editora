package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

/**
 * Maps the LSP {@code textDocument/documentSymbol} response into the neutral {@link SymbolNode} tree the
 * Structure tool window renders — the only place outside the session that touches LSP4J symbol types.
 * Servers return either the modern hierarchical {@link DocumentSymbol} (nested members) or the legacy flat
 * {@link SymbolInformation}; both are handled.
 */
final class DocumentSymbolMapper {

    private DocumentSymbolMapper() {}

    static List<SymbolNode> map(List<Either<SymbolInformation, DocumentSymbol>> raw) {
        List<SymbolNode> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        List<SymbolInformation> flat = new ArrayList<>();
        for (Either<SymbolInformation, DocumentSymbol> e : raw) {
            if (e == null) {
                continue;
            }
            if (e.isRight()) {
                SymbolNode n = fromDocumentSymbol(e.getRight());
                if (n != null) {
                    out.add(n);
                }
            } else if (e.isLeft() && e.getLeft() != null) {
                flat.add(e.getLeft());
            }
        }
        out.addAll(nest(flat));
        return out;
    }

    /** The hierarchical symbols of {@code raw} as {@link MemberSpan}s, every child kept; a flat answer gives none. */
    static List<MemberSpan> members(List<Either<SymbolInformation, DocumentSymbol>> raw) {
        List<MemberSpan> out = new ArrayList<>();
        if (raw != null) {
            for (Either<SymbolInformation, DocumentSymbol> e : raw) {
                if (e != null && e.isRight() && e.getRight() != null) {
                    MemberSpan m = member(e.getRight());
                    if (m != null) {
                        out.add(m);
                    }
                }
            }
        }
        return out;
    }

    private static @Nullable MemberSpan member(DocumentSymbol s) {
        if (s.getName() == null || s.getRange() == null || s.getRange().getStart() == null) {
            return null;
        }
        var sel = s.getSelectionRange() != null && s.getSelectionRange().getStart() != null
                ? s.getSelectionRange()
                : s.getRange();
        List<MemberSpan> children = new ArrayList<>();
        if (s.getChildren() != null) {
            for (DocumentSymbol c : s.getChildren()) {
                MemberSpan m = c == null ? null : member(c);
                if (m != null) {
                    children.add(m);
                }
            }
        }
        int start = Math.max(0, s.getRange().getStart().getLine());
        int end = s.getRange().getEnd() != null ? s.getRange().getEnd().getLine() : start;
        return new MemberSpan(
                s.getName(),
                kindName(s.getKind()),
                start,
                Math.max(start, end),
                Math.max(0, sel.getStart().getLine()),
                Math.max(0, sel.getStart().getCharacter()),
                children,
                Math.max(0, s.getRange().getStart().getCharacter()),
                s.getRange().getEnd() != null ? s.getRange().getEnd().getCharacter() : Integer.MAX_VALUE);
    }

    /**
     * A flat {@link SymbolInformation} list as a tree: each symbol under the innermost other symbol whose range
     * contains its own, and a variable that repeats in one scope listed once (its first occurrence; see
     * {@link #reassignment}). bash-language-server answers flat, listing every assignment: without this a function's
     * locals sat at top level beside the functions and a loop's {@code total=…} appeared once per assignment.
     */
    static List<SymbolNode> nest(List<SymbolInformation> flat) {
        record Item(SymbolInformation info, org.eclipse.lsp4j.Range range, List<Item> children) {}
        List<Item> items = new ArrayList<>();
        for (SymbolInformation s : flat) {
            if (s.getName() != null
                    && s.getLocation() != null
                    && s.getLocation().getRange() != null) {
                items.add(new Item(s, s.getLocation().getRange(), new ArrayList<>()));
            }
        }
        // Document order; of two starting together, the wider one first, so it can hold the other.
        items.sort((x, y) -> {
            int c = compare(x.range().getStart(), y.range().getStart());
            return c != 0 ? c : compare(y.range().getEnd(), x.range().getEnd());
        });
        List<Item> roots = new ArrayList<>();
        java.util.ArrayDeque<Item> open = new java.util.ArrayDeque<>();
        for (Item item : items) {
            while (!open.isEmpty() && !strictlyContains(open.peek().range(), item.range())) {
                open.pop();
            }
            (open.isEmpty() ? roots : open.peek().children()).add(item);
            open.push(item);
        }
        java.util.function.Function<List<Item>, List<SymbolNode>> build = new java.util.function.Function<>() {
            @Override
            public List<SymbolNode> apply(List<Item> level) {
                List<SymbolNode> nodes = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Item item : level) {
                    String kind = kindName(item.info().getKind());
                    if (reassignment(kind, item.children().isEmpty())
                            && !seen.add(kind + '\u0000' + item.info().getName())) {
                        continue; // the same variable again in this scope: a reassignment, not a symbol
                    }
                    int line = Math.max(0, item.range().getStart().getLine());
                    int endLine = Math.max(line, item.range().getEnd().getLine());
                    nodes.add(new SymbolNode(item.info().getName(), "", kind, line, endLine, apply(item.children())));
                }
                return nodes;
            }
        };
        return build.apply(roots);
    }

    /**
     * Whether a repeat of this symbol's name in one scope is a reassignment to list once: only a variable or a
     * constant with nothing inside it. vscode-html-language-server also answers flat, and its sibling elements
     * share a name and a kind ({@code a} four times in a {@code nav}, two {@code script}s): dropping those took
     * every repeated element away, and with it whatever it held, such as the functions of a page's second script
     * (HTML review B4).
     */
    static boolean reassignment(String kind, boolean leaf) {
        return leaf && ("variable".equals(kind) || "constant".equals(kind));
    }

    private static int compare(org.eclipse.lsp4j.Position a, org.eclipse.lsp4j.Position b) {
        int byLine = Integer.compare(a.getLine(), b.getLine());
        return byLine != 0 ? byLine : Integer.compare(a.getCharacter(), b.getCharacter());
    }

    /** Whether {@code outer} contains {@code inner} and is larger than it. */
    private static boolean strictlyContains(org.eclipse.lsp4j.Range outer, org.eclipse.lsp4j.Range inner) {
        boolean contains =
                compare(outer.getStart(), inner.getStart()) <= 0 && compare(inner.getEnd(), outer.getEnd()) <= 0;
        boolean same = compare(outer.getStart(), inner.getStart()) == 0 && compare(outer.getEnd(), inner.getEnd()) == 0;
        return contains && !same;
    }

    private static @Nullable SymbolNode fromDocumentSymbol(DocumentSymbol s) {
        if (s == null || s.getName() == null) {
            return null;
        }
        // Navigate to the name (selectionRange); fall back to the full range. endLine from the full range.
        var sel = s.getSelectionRange() != null ? s.getSelectionRange() : s.getRange();
        int line = sel != null && sel.getStart() != null ? sel.getStart().getLine() : 0;
        int endLine = s.getRange() != null && s.getRange().getEnd() != null
                ? s.getRange().getEnd().getLine()
                : line;
        String kind = kindName(s.getKind());
        // Methods/constructors are leaves in the outline: some servers (e.g. jdtls) report a method's body
        // internals (local variables, lambdas, nested calls) as children, which clutter a class outline with
        // "methods inside methods". Every other symbol keeps its children.
        List<SymbolNode> children = new ArrayList<>();
        if (descendInto(kind) && s.getChildren() != null) {
            for (DocumentSymbol c : s.getChildren()) {
                SymbolNode cn = fromDocumentSymbol(c);
                if (cn != null) {
                    children.add(cn);
                }
            }
        }
        String detail = s.getDetail() == null ? "" : s.getDetail();
        return new SymbolNode(s.getName(), detail, kind, Math.max(0, line), Math.max(line, endLine), children);
    }

    /** Lowercased, stable kind id for the UI's icon/sort mapping; {@code "other"} for null/unknown. */
    static String kindName(SymbolKind kind) {
        return kind == null ? "other" : kind.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether to keep a symbol's children in the outline. Every <b>callable</b> (method, function,
     * constructor) is a leaf: servers such as jdtls report a body's locals, lambdas and anonymous classes as
     * its children, and the outline must not show "methods inside methods". (Nested functions in Python/JS
     * therefore appear flat.) Everything else keeps its children — not only type-like containers: lemminx
     * reports XML elements as {@code Field}, and the JSON/YAML servers nest under {@code Array} and
     * property kinds, so a whitelist of type kinds collapsed those documents to their root.
     */
    static boolean descendInto(String kind) {
        return switch (kind == null ? "" : kind) {
            case "method", "function", "constructor" -> false;
            default -> true;
        };
    }
}

package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

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
        for (Either<SymbolInformation, DocumentSymbol> e : raw) {
            if (e == null) {
                continue;
            }
            if (e.isRight()) {
                SymbolNode n = fromDocumentSymbol(e.getRight());
                if (n != null) {
                    out.add(n);
                }
            } else if (e.isLeft()) {
                SymbolNode n = fromSymbolInformation(e.getLeft());
                if (n != null) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private static SymbolNode fromDocumentSymbol(DocumentSymbol s) {
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
        // "methods inside methods". Type-like containers (class/interface/enum/…) keep their members.
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

    private static SymbolNode fromSymbolInformation(SymbolInformation s) {
        if (s == null || s.getName() == null || s.getLocation() == null) {
            return null;
        }
        var range = s.getLocation().getRange();
        int line = range != null && range.getStart() != null ? range.getStart().getLine() : 0;
        int endLine = range != null && range.getEnd() != null ? range.getEnd().getLine() : line;
        return new SymbolNode(
                s.getName(), "", kindName(s.getKind()), Math.max(0, line), Math.max(line, endLine), List.of());
    }

    /** Lowercased, stable kind id for the UI's icon/sort mapping; {@code "other"} for null/unknown. */
    static String kindName(SymbolKind kind) {
        return kind == null ? "other" : kind.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether to keep a symbol's children in the outline. Only <b>type-like containers</b> (class, interface,
     * enum, struct, namespace, module, package, object) keep their members; every callable (method, function,
     * constructor — including lambdas/anonymous classes a server reports as their children) is a leaf, so the
     * outline never shows "methods inside methods". (Nested functions in Python/JS therefore appear flat.)
     */
    static boolean descendInto(String kind) {
        return switch (kind == null ? "" : kind) {
            case "class", "interface", "enum", "struct", "namespace", "module", "package", "object" -> true;
            default -> false;
        };
    }
}

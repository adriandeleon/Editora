package com.editora.lsp;

import java.util.List;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSymbolMapperTest {

    private static Range range(int sl, int el) {
        return new Range(new Position(sl, 0), new Position(el, 0));
    }

    @Test
    void kindNameLowercasesEnumAndDefaultsForNull() {
        assertEquals("class", DocumentSymbolMapper.kindName(SymbolKind.Class));
        assertEquals("method", DocumentSymbolMapper.kindName(SymbolKind.Method));
        assertEquals("enummember", DocumentSymbolMapper.kindName(SymbolKind.EnumMember));
        assertEquals("other", DocumentSymbolMapper.kindName(null));
    }

    @Test
    void mapsHierarchicalDocumentSymbolsWithChildrenAndNavigationLine() {
        DocumentSymbol method = new DocumentSymbol("run", SymbolKind.Method, range(5, 8), range(5, 5));
        DocumentSymbol clazz = new DocumentSymbol("Foo", SymbolKind.Class, range(2, 20), range(2, 2));
        clazz.setChildren(List.of(method));

        List<SymbolNode> out = DocumentSymbolMapper.map(List.of(Either.forRight(clazz)));

        assertEquals(1, out.size());
        SymbolNode root = out.get(0);
        assertEquals("Foo", root.name());
        assertEquals("class", root.kind());
        assertEquals(2, root.line()); // selectionRange start (the name)
        assertEquals(20, root.endLine()); // full range end
        assertEquals(1, root.children().size());
        assertEquals("run", root.children().get(0).name());
        assertEquals(5, root.children().get(0).line());
    }

    @Test
    void methodChildrenArePrunedButTypeChildrenKept() {
        // A method whose body the server reports as children (jdtls does this) — must be a leaf.
        DocumentSymbol local = new DocumentSymbol("getText", SymbolKind.Method, range(6, 6), range(6, 6));
        DocumentSymbol method = new DocumentSymbol("toggleComment", SymbolKind.Method, range(5, 9), range(5, 5));
        method.setChildren(List.of(local));
        DocumentSymbol clazz = new DocumentSymbol("Foo", SymbolKind.Class, range(1, 30), range(1, 1));
        clazz.setChildren(List.of(method));

        SymbolNode root =
                DocumentSymbolMapper.map(List.of(Either.forRight(clazz))).get(0);
        assertEquals(1, root.children().size()); // class keeps its method
        SymbolNode m = root.children().get(0);
        assertEquals("toggleComment", m.name());
        assertTrue(m.children().isEmpty(), "method-body children must be pruned (no methods-inside-methods)");
        assertFalse(DocumentSymbolMapper.descendInto("method"));
        assertFalse(DocumentSymbolMapper.descendInto("constructor"));
        assertFalse(DocumentSymbolMapper.descendInto("function")); // lambdas/nested fns reported as children
        assertTrue(DocumentSymbolMapper.descendInto("class"));
        assertTrue(DocumentSymbolMapper.descendInto("interface"));
        assertTrue(DocumentSymbolMapper.descendInto("enum"));
    }

    /** lemminx reports XML elements as Field and the JSON server nests under Array: both must keep children. */
    @Test
    void nonTypeContainersKeepTheirChildren() {
        DocumentSymbol leaf = new DocumentSymbol("leaf", SymbolKind.Field, range(2, 2), range(2, 2));
        DocumentSymbol child = new DocumentSymbol("child", SymbolKind.Field, range(1, 3), range(1, 1));
        child.setChildren(List.of(leaf));
        DocumentSymbol rootElement = new DocumentSymbol("root", SymbolKind.Field, range(0, 4), range(0, 0));
        rootElement.setChildren(List.of(child));
        DocumentSymbol item = new DocumentSymbol("0", SymbolKind.String, range(1, 1), range(1, 1));
        DocumentSymbol array = new DocumentSymbol("contributors", SymbolKind.Array, range(0, 2), range(0, 0));
        array.setChildren(List.of(item));

        List<SymbolNode> out = DocumentSymbolMapper.map(List.of(Either.forRight(rootElement), Either.forRight(array)));

        assertEquals("leaf", out.get(0).children().get(0).children().get(0).name());
        assertEquals(1, out.get(1).children().size());
    }

    @Test
    void mapsFlatSymbolInformation() {
        SymbolInformation s =
                new SymbolInformation("CONST", SymbolKind.Constant, new Location("file:///a", range(9, 9)));
        List<SymbolNode> out = DocumentSymbolMapper.map(List.of(Either.forLeft(s)));
        assertEquals(1, out.size());
        assertEquals("CONST", out.get(0).name());
        assertEquals("constant", out.get(0).kind());
        assertEquals(9, out.get(0).line());
        assertTrue(out.get(0).children().isEmpty());
    }

    private static Either<SymbolInformation, DocumentSymbol> sym(
            String name, SymbolKind kind, int sl, int sc, int el, int ec) {
        return Either.forLeft(new SymbolInformation(
                name, kind, new Location("file:///s.sh", new Range(new Position(sl, sc), new Position(el, ec)))));
    }

    private static String outline(List<SymbolNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (SymbolNode n : nodes) {
            sb.append(n.name());
            if (!n.children().isEmpty()) {
                sb.append('[').append(outline(n.children())).append(']');
            }
            sb.append(' ');
        }
        return sb.toString().strip();
    }

    /**
     * bash-language-server's outline is flat and lists every assignment. A function's locals now sit under the
     * function, and a variable assigned twice in one scope is listed once (shell review C11).
     */
    @Test
    void aFlatShellOutlineIsNestedByRangeAndReassignmentsAreListedOnce() {
        List<SymbolNode> out = DocumentSymbolMapper.map(List.of(
                sym("VERSION", SymbolKind.Variable, 1, 9, 1, 20),
                sym("LOG_DIR", SymbolKind.Variable, 2, 0, 2, 17),
                sym("log", SymbolKind.Function, 4, 0, 7, 1),
                sym("level", SymbolKind.Variable, 5, 8, 5, 16),
                sym("msg", SymbolKind.Variable, 5, 17, 5, 23),
                sym("build", SymbolKind.Function, 9, 0, 16, 1),
                sym("i", SymbolKind.Variable, 10, 8, 10, 9),
                sym("total", SymbolKind.Variable, 12, 4, 12, 24),
                sym("total", SymbolKind.Variable, 13, 4, 13, 24),
                sym("total", SymbolKind.Variable, 18, 0, 18, 7)));

        assertEquals("VERSION LOG_DIR log[level msg] build[i total] total", outline(out));
        assertEquals(4, out.get(2).line());
        assertEquals(7, out.get(2).endLine());
        assertEquals(12, out.get(3).children().get(1).line(), "the first assignment is the one listed");
    }

    /**
     * vscode-html-language-server answers flat too, and sibling elements share a name and a kind: every link of a
     * nav, both {@code meta}s and the second {@code script} with its function stay (HTML review B4/C5).
     */
    @Test
    void aFlatHtmlOutlineKeepsRepeatedSiblingElementsAndWhatTheyHold() {
        List<SymbolNode> out = DocumentSymbolMapper.map(List.of(
                sym("html", SymbolKind.Field, 0, 0, 42, 7),
                sym("head", SymbolKind.Field, 1, 0, 6, 7),
                sym("meta", SymbolKind.Field, 3, 2, 3, 24),
                sym("meta", SymbolKind.Field, 4, 2, 4, 60),
                sym("body", SymbolKind.Field, 7, 0, 41, 7),
                sym("nav", SymbolKind.Field, 8, 2, 13, 8),
                sym("a", SymbolKind.Field, 9, 4, 9, 30),
                sym("a", SymbolKind.Field, 10, 4, 10, 30),
                sym("a", SymbolKind.Field, 11, 4, 11, 30),
                sym("a", SymbolKind.Field, 12, 4, 12, 30),
                sym("script", SymbolKind.Field, 36, 2, 36, 40),
                sym("script", SymbolKind.Field, 37, 2, 40, 11),
                sym("greet", SymbolKind.Function, 38, 4, 38, 40),
                sym("msg", SymbolKind.Variable, 39, 4, 39, 30)));

        assertEquals("html[head[meta meta] body[nav[a a a a] script script[greet msg]]]", outline(out));
        SymbolNode body = out.get(0).children().get(1);
        assertEquals(37, body.children().get(2).line(), "the second script, where it is");
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertTrue(DocumentSymbolMapper.map(null).isEmpty());
        assertTrue(DocumentSymbolMapper.map(List.of()).isEmpty());
    }

    @Test
    void documentSymbolProviderCapabilityIsNullSafe() {
        assertFalse(LspManager.documentSymbolProvider(null));
        ServerCapabilities caps = new ServerCapabilities();
        assertFalse(LspManager.documentSymbolProvider(caps));
        caps.setDocumentSymbolProvider(true);
        assertTrue(LspManager.documentSymbolProvider(caps));
    }
}

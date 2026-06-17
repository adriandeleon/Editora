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

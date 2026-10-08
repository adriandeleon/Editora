package com.editora.ui;

import java.util.List;

import com.editora.lsp.SymbolNode;
import com.editora.mcp.McpBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The outline an MCP client gets for {@code document_symbols}: the server's tree, with lines counted from 1. */
class McpSymbolMappingTest {

    @Test
    void theOutlineKeepsItsNestingAndShiftsZeroBasedLinesToOneBased() {
        SymbolNode field = new SymbolNode("count", "int", "field", 2, 2, List.of());
        SymbolNode method = new SymbolNode("run", "void", "method", 4, 9, List.of());
        SymbolNode type = new SymbolNode("Outer", "", "class", 0, 10, List.of(field, method));

        List<McpBridge.Symbol> mapped = WindowMcpBridge.mapMcpSymbols(List.of(type));

        assertEquals(
                List.of(new McpBridge.Symbol(
                        "Outer",
                        "",
                        "class",
                        1,
                        11,
                        List.of(
                                new McpBridge.Symbol("count", "int", "field", 3, 3, List.of()),
                                new McpBridge.Symbol("run", "void", "method", 5, 10, List.of())))),
                mapped);
        assertEquals(List.of(), WindowMcpBridge.mapMcpSymbols(List.of()));
    }
}

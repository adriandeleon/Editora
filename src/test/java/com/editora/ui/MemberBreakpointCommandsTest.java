package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.MemberBreakpoint;
import com.editora.dap.DapModels;
import com.editora.lsp.LspManager;
import com.editora.lsp.SymbolNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which member the caret is on, read from a Java outline as jdtls gives it, and what the adapter is sent. */
class MemberBreakpointCommandsTest {

    private static SymbolNode node(String name, String kind, int line, int end, SymbolNode... children) {
        return new SymbolNode(name, "", kind, line, end, List.of(children));
    }

    /**
     * <pre>
     *  2 public class Order {
     *  3     private int count;
     *  4     Order() {
     *  6     }
     *  7     long total(int n) {
     * 10     }
     * 11     static class Line {
     * 12         String sku;
     * 13         void add() {
     * 15         }
     * 16     }
     * 17 }
     * </pre>
     */
    private static final List<SymbolNode> OUTLINE = List.of(
            node("demo.shop", "package", 0, 0),
            node(
                    "Order",
                    "class",
                    2,
                    17,
                    node("count", "field", 3, 3),
                    node("Order()", "constructor", 4, 6),
                    node("total(int) : long", "method", 7, 10),
                    node("Line", "class", 11, 16, node("sku", "field", 12, 12), node("add()", "method", 13, 15))));

    private static MemberBreakpointCommands.Member at(int line, MemberBreakpoint.Kind kind) {
        return MemberBreakpointCommands.memberAt("demo.shop", OUTLINE, line, kind);
    }

    @Test
    void theMethodTheCaretIsIn() {
        MemberBreakpoint.Kind m = MemberBreakpoint.Kind.METHOD;
        assertEquals(new MemberBreakpointCommands.Member("demo.shop.Order", "total"), at(8, m));
        assertEquals(new MemberBreakpointCommands.Member("demo.shop.Order", "total"), at(7, m), "on its name");
        assertEquals(new MemberBreakpointCommands.Member("demo.shop.Order", "<init>"), at(5, m), "a constructor");
        assertEquals(new MemberBreakpointCommands.Member("demo.shop.Order$Line", "add"), at(14, m), "nested class");
        assertNull(at(3, m), "a field is no method");
        assertNull(at(1, m), "outside the class");
    }

    @Test
    void theFieldOnTheCaretLine() {
        MemberBreakpoint.Kind f = MemberBreakpoint.Kind.FIELD;
        assertEquals(new MemberBreakpointCommands.Member("demo.shop.Order", "count"), at(3, f));
        assertEquals(new MemberBreakpointCommands.Member("demo.shop.Order$Line", "sku"), at(12, f));
        assertNull(at(8, f), "inside a method there is no field to watch");
    }

    @Test
    void elsewhereThePromptStartsFromTheCaretsClass() {
        assertEquals("demo.shop.Order$Line#", MemberBreakpointCommands.defaultClass("demo.shop", OUTLINE, 14));
        assertEquals("demo.shop.Order#", MemberBreakpointCommands.defaultClass("demo.shop", OUTLINE, 2));
        assertEquals("", MemberBreakpointCommands.defaultClass("demo.shop", OUTLINE, 0));
        assertEquals(
                new MemberBreakpointCommands.Member("Order", "total"),
                MemberBreakpointCommands.memberAt("", OUTLINE, 8, MemberBreakpoint.Kind.METHOD),
                "the default package");
    }

    @Test
    void thePackageASourceDeclares() {
        assertEquals("demo.shop", MemberBreakpointCommands.packageOf("// c\n/* x */\npackage demo.shop;\nclass A {}"));
        assertEquals("", MemberBreakpointCommands.packageOf("class A { String s = \"package x;\"; }"));
        assertEquals("a.b", MemberBreakpointCommands.packageOf("@Deprecated\npackage  a.b ;"));
    }

    @Test
    void jdtlsNamesAreCutToTheMembersName() {
        assertEquals("total", MemberBreakpointCommands.memberName("total(int) : long"));
        assertEquals("count", MemberBreakpointCommands.memberName("count : int"));
        assertEquals("List", MemberBreakpointCommands.memberName("List<T>"));
        assertEquals("run", MemberBreakpointCommands.memberName("run"));
    }

    @Test
    void onlyTheEnabledOnesAreSentAsTheAdapterNamesThem() {
        List<DapModels.MemberBreakpoint> sent = MemberBreakpointCommands.adapterBreakpoints(List.of(
                MemberBreakpoint.method("a.Order", "total").withCondition("n > 2"),
                MemberBreakpoint.field("a.Order", "count", "read").withEnabled(false),
                MemberBreakpoint.field("a.Order", "sum", "readWrite").withHitCondition("4")));
        assertEquals(
                List.of(
                        new DapModels.MemberBreakpoint(false, "a.Order#total", "", "n > 2", ""),
                        new DapModels.MemberBreakpoint(true, "a.Order#sum", "readWrite", "", "4")),
                sent);
    }

    @Test
    void aJumpGoesToTheMemberOfTheRightClass() {
        Path order = Path.of("/p/src/a/Order.java");
        Path other = Path.of("/p/src/a/Other.java");
        List<LspManager.WorkspaceSymbolMatch> matches = List.of(
                new LspManager.WorkspaceSymbolMatch("total(int)", "Other", "method", other, 3, 4),
                new LspManager.WorkspaceSymbolMatch("totals()", "Order", "method", order, 5, 4),
                new LspManager.WorkspaceSymbolMatch("total(int)", "Order", "method", order, 9, 4));
        var best = MemberBreakpointCommands.bestMatch(MemberBreakpoint.method("a.Order", "total"), matches);
        assertEquals(9, best.line());
        var inner = MemberBreakpointCommands.bestMatch(
                MemberBreakpoint.field("a.Order$Line", "sku", "write"),
                List.of(new LspManager.WorkspaceSymbolMatch("sku", "Order.Line", "field", order, 12, 8)));
        assertEquals(12, inner.line());
        assertNull(MemberBreakpointCommands.bestMatch(MemberBreakpoint.method("a.Missing", "total"), matches));
    }
}

package com.editora.ui;

import java.util.List;
import java.util.Map;

import com.editora.config.MemberBreakpoint;
import com.editora.lsp.SymbolNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a method breakpoint's or field watchpoint's mark goes: its member's declaration lines, from the outline. */
class MemberBreakpointMarksTest {

    private static SymbolNode node(String name, String kind, int line, SymbolNode... children) {
        return new SymbolNode(name, "", kind, line, line + 2, List.of(children));
    }

    /** {@code class Order { int count; Order() {} long total() {} long total(int) {} class Line { void add() {} } }}. */
    private static final List<SymbolNode> OUTLINE = List.of(node(
            "Order",
            "class",
            2,
            node("count", "field", 3),
            node("Order()", "constructor", 5),
            node("total() : long", "method", 8),
            node("total(int) : long", "method", 12),
            node("Line", "class", 16, node("add()", "method", 17), node("count", "field", 18))));

    @Test
    void eachMemberIsMarkedOnItsDeclarationsInItsOwnClass() {
        MemberBreakpoint total = MemberBreakpoint.method("shop.Order", "total");
        MemberBreakpoint ctor = MemberBreakpoint.method("shop.Order", "<init>");
        MemberBreakpoint count = MemberBreakpoint.field("shop.Order", "count", MemberBreakpoint.WRITE);
        MemberBreakpoint add = MemberBreakpoint.method("shop.Order$Line", "add");
        MemberBreakpoint elsewhere = MemberBreakpoint.method("shop.Invoice", "total");
        MemberBreakpoint countAsMethod = MemberBreakpoint.method("shop.Order", "count");
        Map<Integer, List<MemberBreakpoint>> lines = MemberBreakpointMarks.declarationLines(
                "shop", OUTLINE, List.of(total, ctor, count, add, elsewhere, countAsMethod));
        assertEquals(
                Map.of(3, List.of(count), 5, List.of(ctor), 8, List.of(total), 12, List.of(total), 17, List.of(add)),
                lines,
                "every overload; Line's own count is another field; a method named like a field is not it");
    }

    @Test
    void aFileWithNoPackageAndNoBreakpointsHasNone() {
        MemberBreakpoint total = MemberBreakpoint.method("Order", "total");
        assertEquals(
                List.of(total),
                MemberBreakpointMarks.declarationLines("", OUTLINE, List.of(total))
                        .get(8));
        assertTrue(MemberBreakpointMarks.declarationLines("shop", OUTLINE, List.of())
                .isEmpty());
        assertTrue(MemberBreakpointMarks.declarationLines("shop", List.of(), List.of(total))
                .isEmpty());
        // A function outside any type (another language's outline) is no member.
        assertTrue(MemberBreakpointMarks.declarationLines("", List.of(node("total", "function", 0)), List.of(total))
                .isEmpty());
    }

    @Test
    void theHoverNamesEachOne() {
        MemberBreakpoint total = MemberBreakpoint.method("shop.Order", "total").withCondition("n > 1");
        String tip = MemberBreakpointMarks.tooltip(List.of(total));
        assertTrue(tip.startsWith("Order.total()\n"), tip);
        assertTrue(tip.contains(BreakpointsPicker.memberDetail(total)), tip);
    }

    @Test
    void aChangeShowsOnTheMarksAtOnce() {
        MemberBreakpoint total = MemberBreakpoint.method("shop.Order", "total");
        MemberBreakpoint count = MemberBreakpoint.field("shop.Order", "count", MemberBreakpoint.WRITE);
        var marks = Map.of(
                8, new com.editora.editor.debug.BreakpointManager.MemberMark(List.of(total), ""),
                3, new com.editora.editor.debug.BreakpointManager.MemberMark(List.of(count), ""));
        var now = MemberBreakpointMarks.current(marks, List.of(total.withEnabled(false)));
        assertEquals(List.of(8), List.copyOf(now.keySet()), "the removed watchpoint's mark is gone");
        assertEquals(List.of(total.withEnabled(false)), now.get(8).members());
        assertTrue(!now.get(8).enabled() && !now.get(8).field());
        assertEquals(
                MemberBreakpointMarks.tooltip(List.of(total.withEnabled(false))),
                now.get(8).tooltip());
        assertTrue(marks.get(3).field() && marks.get(3).enabled());
    }
}

package com.editora.ui;

import java.util.List;

import com.editora.lsp.SymbolNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Go to Super Method asks jdtls about the method the caret is in, from that method's name, and about a type's
 * supertypes on the type: jdtls answers only on a method's name (seventh review BR2).
 */
class SuperMethodTargetTest {

    private static SymbolNode node(String name, String kind, int line, int end, SymbolNode... children) {
        return new SymbolNode(name, "", kind, line, end, List.of(children));
    }

    private static final List<SymbolNode> OUTLINE = List.of(node(
            "Sub",
            "class",
            2,
            20,
            node("label", "field", 3, 3),
            node("Sub()", "constructor", 5, 7),
            node("kind() : String", "method", 9, 12),
            node("Inner", "class", 14, 19, node("go()", "method", 15, 18))));

    @Test
    void theCaretAnywhereInAMethodIsThatMethod() {
        assertEquals(
                "kind() : String", LspCoordinator.enclosingMember(OUTLINE, 11).name(), "inside the body");
        assertEquals("go()", LspCoordinator.enclosingMember(OUTLINE, 16).name(), "a nested class's method");
        assertEquals("Sub()", LspCoordinator.enclosingMember(OUTLINE, 6).name());
    }

    @Test
    void elsewhereInATypeIsTheType() {
        assertEquals("Sub", LspCoordinator.enclosingMember(OUTLINE, 3).name(), "a field's line: its type");
        assertEquals("Inner", LspCoordinator.enclosingMember(OUTLINE, 14).name());
        assertNull(LspCoordinator.enclosingMember(OUTLINE, 0), "above the class");
    }

    @Test
    void theNameIsFoundOnItsLine() {
        assertEquals(11, LspCoordinator.nameColumn("    String kind() {", "kind() : String"));
        assertEquals(12, LspCoordinator.nameColumn("final class Sub extends Base {", "Sub"));
        assertEquals(4, LspCoordinator.nameColumn("    @Override", "missing"), "not found: the line's indent");
    }
}

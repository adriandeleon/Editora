package com.editora.ui;

import java.util.List;

import com.editora.dap.TypeRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeRenderersTest {

    private static TypeRenderer renderer(String language, String type, String value) {
        return new TypeRenderer(true, language, type, value, "");
    }

    @Test
    void aTypeMatchesByItsNameAndAgreeingQualifier() {
        TypeRenderer money = renderer("java", "com.shop.Money", "this.amount");
        List<TypeRenderer> all = List.of(money);
        assertSame(money, TypeRenderers.match(all, "java", "Money", "Money@12"), "java-debug's bare name");
        assertSame(money, TypeRenderers.match(all, "java", "com.shop.Money", ""));
        assertSame(money, TypeRenderers.match(all, "java", "shop.Money", ""));
        assertNull(TypeRenderers.match(all, "java", "com.bank.Money", ""), "another package's Money");
        assertNull(TypeRenderers.match(all, "java", "MoneyBag", ""));
        assertNull(TypeRenderers.match(all, "python", "Money", ""), "another language's");
        assertNull(TypeRenderers.match(all, "java", "Money[]", ""), "an array of them");
    }

    @Test
    void genericsPointersAndJsObjectsAreTheirTypes() {
        TypeRenderer box = renderer("", "Box", "this.item");
        List<TypeRenderer> all = List.of(box);
        assertSame(box, TypeRenderers.match(all, "java", "Box<String>", ""));
        assertSame(box, TypeRenderers.match(all, "go", "*main.Box", ""));
        assertSame(box, TypeRenderers.match(all, "cpp", "const Box &", ""));
        assertSame(box, TypeRenderers.match(all, "javascript", "object", "Box {item: 1}"), "js-debug's class");
    }

    @Test
    void anOffOrEmptyRendererIsNotUsedButTheFirstMatchIs() {
        TypeRenderer off = new TypeRenderer(false, "", "Money", "this.a", "");
        TypeRenderer empty = renderer("", "Money", "");
        TypeRenderer first = renderer("java", "Money", "this.b");
        TypeRenderer second = renderer("", "Money", "this.c");
        assertSame(first, TypeRenderers.match(List.of(off, empty, first, second), "java", "Money", ""));
        TypeRenderer childrenOnly = new TypeRenderer(true, "", "Money", "", "a = this.a");
        assertSame(childrenOnly, TypeRenderers.match(List.of(childrenOnly), "java", "Money", ""));
    }

    @Test
    void thisAndSelfBecomeTheValuesExpression() {
        assertEquals("(order.total).amount", TypeRenderers.substitute("this.amount", "order.total"));
        assertEquals("(p).x * (p).y", TypeRenderers.substitute("self.x * self.y", "p"));
        assertEquals(
                "(m).amount + \" this \" + (m).currency",
                TypeRenderers.substitute("this.amount + \" this \" + this.currency", "m"),
                "not inside a string");
        assertEquals("thisValue + (m).a", TypeRenderers.substitute("thisValue + this.a", "m"), "a word of its own");
        assertEquals("Outer.this.x", TypeRenderers.substitute("Outer.this.x", "m"), "a member called this");
        assertEquals("'it''s' + (m)", TypeRenderers.substitute("'it''s' + this", "m"));
        assertEquals("\"a\\\"this\" + (m)", TypeRenderers.substitute("\"a\\\"this\" + this", "m"), "an escaped quote");
        assertEquals("(d['k']).x", TypeRenderers.substitute("this.x", "d['k']"));
    }

    @Test
    void childrenAreNamedExpressionsOneALine() {
        assertEquals(
                List.of(
                        new TypeRenderers.Child("owner", "this.owner.name"),
                        new TypeRenderers.Child("size", "this.items.size()"),
                        new TypeRenderers.Child("this.a == b", "this.a == b"),
                        new TypeRenderers.Child("ok", "this.a >= 1"),
                        new TypeRenderers.Child("f(x) = 1", "f(x) = 1")),
                TypeRenderers.children("""
                        owner = this.owner.name
                          size=this.items.size()

                        # a comment
                        // another
                        this.a == b
                        ok = this.a >= 1
                        f(x) = 1
                        """));
        assertTrue(TypeRenderers.children("").isEmpty());
    }

    @Test
    void onlyAnExpressionWithoutEffectsIsEvaluatedAgain() {
        assertTrue(TypeRenderers.safeToRepeat("order.total"));
        assertTrue(TypeRenderers.safeToRepeat("items[3].price"));
        assertTrue(TypeRenderers.safeToRepeat("a == b && c != d && e <= f"));
        assertTrue(TypeRenderers.safeToRepeat("name + \"(x) = y\""), "a string's parentheses are not a call");
        assertFalse(TypeRenderers.safeToRepeat("list.remove(0)"));
        assertFalse(TypeRenderers.safeToRepeat("x = 3"));
        assertFalse(TypeRenderers.safeToRepeat("i++"));
        assertFalse(TypeRenderers.safeToRepeat("--i"));
        assertFalse(TypeRenderers.safeToRepeat("new Money()"));
    }

    @Test
    void aStringValueReadsWithoutItsQuotes() {
        assertEquals("12.50 EUR", TypeRenderers.unquote("\"12.50 EUR\""));
        assertEquals("Ann", TypeRenderers.unquote("'Ann'"));
        assertEquals("12.50", TypeRenderers.unquote("12.50"));
        assertEquals("\"", TypeRenderers.unquote("\""));
    }

    @Test
    void customizeFindsARendererOnOrOffAndNamesANewOneAsTheAdapterDid() {
        TypeRenderer off = new TypeRenderer(false, "java", "Money", "", "");
        List<TypeRenderer> all = List.of(renderer("python", "Money", "self.a"), off);
        assertEquals(1, TypeRenderers.indexOf(all, "java", "Money", ""));
        assertEquals(-1, TypeRenderers.indexOf(all, "go", "Money", ""));
        assertEquals(
                "com.shop.Money",
                TypeRenderers.typeName(DebugTypeSource.parse("com.shop.Money<T>", null), "com.shop.Money<T>"));
        assertEquals("app::Point", TypeRenderers.typeName(DebugTypeSource.parse("app::Point", null), "app::Point"));
        assertEquals("Money", TypeRenderers.typeName(DebugTypeSource.parse("Money", null), "Money"));
        assertEquals("Money (java)", TypeRenderers.label(off));
        assertEquals("Money", TypeRenderers.label(renderer("", "Money", "")));
    }
}

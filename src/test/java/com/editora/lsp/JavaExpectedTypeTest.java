package com.editora.lsp;

import java.util.List;

import com.editora.lsp.JavaExpectedType.Expected;
import com.editora.lsp.JavaExpectedType.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaExpectedTypeTest {

    /** The expectation at the {@code |} in {@code marked}. */
    private static Expected at(String marked) {
        int caret = marked.indexOf('|');
        return JavaExpectedType.at(marked.substring(0, caret) + marked.substring(caret + 1), caret);
    }

    private static final String CLASS = """
            class A {
                private final List<String> names = new ArrayList<>();
                int[] counts;

                String label(int width) {
                    if (width > 0) {
                        for (String n : names) {
                            BODY
                        }
                    }
                    return "";
                }
            }
            """;

    private static Expected inBody(String body) {
        return at(CLASS.replace("BODY", body));
    }

    @Test
    void aDeclarationExpectsItsType() {
        assertEquals(new Expected(Source.DECLARATION, "String", false), inBody("String s = |"));
        assertEquals(
                new Expected(Source.DECLARATION, "Map<String, List<Integer>>", false),
                inBody("final Map<String, List<Integer>> m = na|"));
        assertEquals(new Expected(Source.DECLARATION, "int[]", false), inBody("int copy[] = |"));
        assertEquals(new Expected(Source.DECLARATION, "List<String>", true), inBody("List<String> l = new Arr|"));
        assertNull(inBody("var v = |"), "var states no type");
        assertEquals("int", at("for (int i = |").type());
    }

    @Test
    void anAssignmentExpectsTheTypeTheNameWasDeclaredWith() {
        assertEquals(new Expected(Source.ASSIGNMENT, "List<String>", false), inBody("this.names = |"));
        assertEquals(new Expected(Source.ASSIGNMENT, "int", false), inBody("counts[0] = |"));
        assertEquals("int", inBody("width = |").type(), "a parameter");
        assertNull(inBody("unknown = |"));
        assertNull(inBody("width += |"), "a compound assignment is left alone");
        assertNull(inBody("if (width == |"));
    }

    @Test
    void returnExpectsTheEnclosingMethodsReturnType() {
        assertEquals(new Expected(Source.RETURN, "String", false), inBody("return |"));
        assertEquals("String", inBody("return lab|").type());
        assertNull(at("void run() {\n    return |"), "void");
        assertNull(at("String f() {\n    Runnable r = () -> {\n        return |"), "a lambda's own return");
        assertEquals(
                "List<String>",
                at("@Override\npublic <T> List<String> all() throws IOException {\n    return |")
                        .type());
    }

    @Test
    void conditionsExpectABoolean() {
        assertEquals(new Expected(Source.CONDITION, "boolean", false), inBody("if (|"));
        assertEquals("boolean", inBody("while (|").type());
        assertEquals("boolean", inBody("boolean b = ok && |").type());
        assertEquals("boolean", inBody("if (!|").type());
    }

    @Test
    void anArgumentAsksSignatureHelp() {
        assertEquals(new Expected(Source.ARGUMENT, null, false), inBody("label(|"));
        assertEquals(new Expected(Source.ARGUMENT, null, false), inBody("map.put(key, |"));
        assertEquals(new Expected(Source.ARGUMENT, null, true), inBody("use(new |"));
        assertEquals(Source.ARGUMENT, inBody("List.<String>of(|").source());
        assertNull(inBody("for (|"));
        assertNull(inBody("catch (|"));
        assertNull(inBody("int[] a = {1, |"), "an initializer, not a call");
    }

    @Test
    void nothingIsExpectedElsewhere() {
        assertNull(inBody("|"));
        assertNull(inBody("names.|"));
        assertNull(at("|"));
    }

    @Test
    void aParameterLabelGivesItsType() {
        assertEquals("int", JavaExpectedType.parameterType("int n"));
        assertEquals("List<? extends T>", JavaExpectedType.parameterType("final List<? extends T> c"));
        assertEquals("String", JavaExpectedType.parameterType("String... args"), "a varargs argument is one element");
        assertEquals(List.of("Object", "Object[]"), JavaExpectedType.parameterTypes("Object... args"));
        assertEquals(List.of("int"), JavaExpectedType.parameterTypes("int n"));
        assertEquals("Path", JavaExpectedType.parameterType("@NonNull Path file"));
        assertNull(JavaExpectedType.parameterType(null));
        assertTrue(JavaExpectedType.parameterType("x") != null, "a bare name is taken as the type");
    }

    @Test
    void aMemberAfterADotIsExpectedAsTheWholeExpressionIs() {
        assertEquals(new Expected(Source.DECLARATION, "String", false), inBody("String t = name.|"));
        assertEquals("String", inBody("String t = name.tr|").type());
        assertEquals("String", inBody("String t = users.get(0).name().|").type());
        assertEquals(
                Source.ARGUMENT,
                inBody("String t = names.stream()\n        .findFirst()\n        .orElse(|")
                        .source(),
                "an argument of the chain's last call asks signature help");
        assertEquals("String", inBody("String t = names.get(0)\n        .tr|").type(), "a chain across lines");
        assertEquals(new Expected(Source.RETURN, "String", false), inBody("return label(1).|"));
        assertEquals(
                "String", inBody("Shape sh = c\n        String t = name.|").type(), "a line above still being typed");
        assertEquals(Source.ARGUMENT, inBody("label(other.|").source());
    }

    @Test
    void aBranchOfAConditionalIsExpectedAsTheConditionalIs() {
        assertEquals(new Expected(Source.DECLARATION, "String", false), inBody("String q = ready ? n|"));
        assertEquals("String", inBody("String q = ready ? \"a\" : |").type());
        assertEquals("String", inBody("String q = (a == b) ? x : |").type());
        assertEquals(new Expected(Source.RETURN, "String", false), inBody("return ok ? |"));
        assertEquals(Source.ARGUMENT, inBody("label(ok ? |").source());
        assertNull(at("for (String s : |"), "a for-each colon is no branch");
        assertNull(at("switch (k) {\n    case A:|"), "nor is a label");
    }
}

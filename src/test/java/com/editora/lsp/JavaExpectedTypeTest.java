package com.editora.lsp;

import java.util.List;

import com.editora.lsp.JavaExpectedType.Expected;
import com.editora.lsp.JavaExpectedType.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void aTypeVariableIsBoundToWhatTheDeclarationExpects() {
        String listOf = "    List<String> l = List.of(n";
        assertEquals("List<String>", JavaExpectedType.declaredTarget(listOf, listOf.length()));
        assertEquals(List.of("String"), JavaExpectedType.bindTypeVariables(List.of("E"), "List<String>"));
        assertEquals(
                List.of("String", "String[]"), JavaExpectedType.bindTypeVariables(List.of("T", "T[]"), "List<String>"));
        assertEquals(List.of("String"), JavaExpectedType.bindTypeVariables(List.of("T"), "String"), "requireNonNull");
        assertEquals(List.of("Object"), JavaExpectedType.bindTypeVariables(List.of("E"), null), "take(Set.of(");
        assertEquals(List.of("Object"), JavaExpectedType.bindTypeVariables(List.of("V"), "Map<String, Integer>"));
        assertEquals(
                List.of("int"),
                JavaExpectedType.bindTypeVariables(List.of("int"), "List<String>"),
                "a real type stays");
        String plain = "    take(java.util.Set.of(n";
        assertEquals(null, JavaExpectedType.declaredTarget(plain, plain.length()));
        org.junit.jupiter.api.Assertions.assertTrue(JavaExpectedType.isTypeVariable("E"));
        org.junit.jupiter.api.Assertions.assertFalse(JavaExpectedType.isTypeVariable("Item"));
    }

    /**
     * A type variable is bound by matching the method's return type against the declaration, so an array target
     * strips its {@code []} and a call nested in another call is not bound by the outer declaration (fourth review
     * AF1): {@code toArray(} looked for {@code String[][]}, {@code contains(requireNonNull(} for {@code boolean}.
     */
    @Test
    void aTypeVariableIsBoundByTheReturnTypeItFillsIn() {
        assertEquals(
                List.of("String[]"),
                JavaExpectedType.bindTypeVariables(List.of("T[]"), "String[]", "T[]"),
                "names.toArray(");
        assertEquals(
                List.of("String[]", "int"),
                JavaExpectedType.bindTypeVariables(List.of("T[]", "int"), "String[]", "T[]"),
                "Arrays.copyOf(");
        assertEquals(List.of("String"), JavaExpectedType.bindTypeVariables(List.of("E"), "List<String>", "List<E>"));
        assertEquals(
                List.of("Integer"),
                JavaExpectedType.bindTypeVariables(List.of("V"), "Map<String, Integer>", "Map<K, V>"),
                "the second argument binds the second variable");
        assertEquals(List.of("Integer"), JavaExpectedType.bindTypeVariables(List.of("T"), "int", "T"), "boxed");
        assertEquals(
                List.of("Object"),
                JavaExpectedType.bindTypeVariables(List.of("T"), "String[]", "List<T>"),
                "a shape that does not match binds nothing");
        assertEquals(List.of("Object"), JavaExpectedType.bindTypeVariables(List.of("T"), null, "T"));

        String nested = "    boolean has = names.contains(java.util.Objects.requireNonNull(n";
        assertEquals(null, JavaExpectedType.declaredTarget(nested, nested.length()), "the outer call takes it");
        String closed = "    String s = String.valueOf(1) + java.util.Objects.requireNonNull(n";
        assertEquals("String", JavaExpectedType.declaredTarget(closed, closed.length()), "a closed call is not");
    }

    /** Which argument the caret is in, for Smart Type when jdtls names none (fifth review AV8). */
    @Test
    void theArgumentAtTheCaretIsCountedAtTheCallsOwnDepth() {
        String a = "String s = String.valueOf(words, c";
        assertEquals(1, JavaExpectedType.argumentIndex(a, a.length()));
        String b = "f(g(x, y), \"a,b\", ";
        assertEquals(2, JavaExpectedType.argumentIndex(b, b.length()));
        String c = "f(";
        assertEquals(0, JavaExpectedType.argumentIndex(c, c.length()));
        String d = "int x = 1;";
        assertNull(JavaExpectedType.argumentIndex(d, d.length()));
    }

    /** A bound type variable inside a parameter type is named, not left as T (fifth review AV8). */
    @Test
    void aBoundTypeVariableInsideAGenericParameterIsSubstituted() {
        assertEquals(
                List.of("Collection<? extends String>"),
                JavaExpectedType.bindTypeVariables(List.of("Collection<? extends T>"), "String", "T"));
    }

    /** A bracket in a literal or a comment is no bracket (sixth review AS3). */
    @Test
    void literalsAndCommentsAreNotCode() {
        for (String text : List.of(
                "String f = String.format(\"(\", n",
                "log.info(\"done :)\", n",
                "name.replace('(', c",
                "foo(\"a\\\"(\", c",
                "foo(\"\"\"\n  (\n  \"\"\", c")) {
            assertNotNull(JavaExpectedType.at(text, text.length()), text);
        }
        String a = "foo(a, // note (x\n b";
        assertEquals(1, JavaExpectedType.argumentIndex(a, a.length()));
        String b = "foo(a, /* it's */ b";
        assertEquals(1, JavaExpectedType.argumentIndex(b, b.length()));
    }
}

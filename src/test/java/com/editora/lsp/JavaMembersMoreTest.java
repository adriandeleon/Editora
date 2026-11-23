package com.editora.lsp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * More of {@link JavaMembers}: text that ends inside a comment, a literal or a bracket, and the readers of names,
 * parameters and type parameters on the shapes {@link JavaMembersTest} does not write.
 */
class JavaMembersMoreTest {

    @Test
    void commentsAndLiteralsThatRunToTheEndAreBlankedToTheEnd() {
        assertEquals("int a;       ", JavaMembers.code("int a; // end"));
        assertEquals("int a;           ", JavaMembers.code("int a; /* open (("));
        assertTrue(JavaMembers.code("s = \"\"\"\n  open").startsWith("s = \"\"\"\n   "), "an unclosed text block");
        assertEquals("c = '  ';", JavaMembers.code("c = '\\'';"), "an escaped quote");
        assertEquals("s = \"   \nx", JavaMembers.code("s = \"abc\nx"), "a string ends at its line");
        assertEquals("s = \"  ", JavaMembers.code("s = \"a\\"), "an escape at the very end");
        assertEquals("a / b", JavaMembers.code("a / b"));
        assertEquals("/", JavaMembers.code("/"));
        assertEquals("x = \"\"\"\n   \n\"\"\";", JavaMembers.codeOf("x = \"\"\"\n a \n\"\"\";"));
    }

    @Test
    void aMethodReferenceIsFoundAcrossArraysTypeArgumentsAndBlanks() {
        String arrays = "f(String[]::new);";
        assertEquals(arrays.indexOf("::"), JavaMembers.methodReference(arrays, arrays.indexOf("String")));
        String generic = "g(ArrayList<String>::new);";
        assertEquals(generic.indexOf("::"), JavaMembers.methodReference(generic, generic.indexOf("Array")));
        String explicit = "h(Collections::<List<String>>emptyList);";
        assertEquals(explicit.indexOf("::"), JavaMembers.methodReference(explicit, explicit.indexOf("empty") + 2));
        String spaced = "h(Foo::   bar);";
        assertEquals(spaced.indexOf("::"), JavaMembers.methodReference(spaced, spaced.indexOf("bar")));
        String unmatched = "x; a]::new;";
        assertEquals(-1, JavaMembers.methodReference(unmatched, 3), "an unmatched bracket ends the qualifier");
        assertEquals(unmatched.indexOf("::"), JavaMembers.methodReference(unmatched, unmatched.indexOf("new")));
        String closed = "{ x = y; } int[]::clone;";
        assertEquals(closed.indexOf("::"), JavaMembers.methodReference(closed, closed.indexOf("int")));
        String unclosedArgs = "h(Foo::<List<String> bar";
        assertEquals(-1, JavaMembers.methodReference(unclosedArgs, unclosedArgs.indexOf("bar") + 1));
        assertEquals(-1, JavaMembers.methodReference("a.b(c);", 3), "no reference");
        assertEquals(3, JavaMembers.methodReference("Foo::bar", 0), "the qualifier's start counts");
    }

    @Test
    void theLastSegmentAndTheQualifiedNameAreReadAroundDots() {
        String qualified = "x = new java.util.Comparator<>();";
        int last = JavaMembers.lastSegment(qualified, qualified.indexOf("new"));
        assertEquals(qualified.indexOf("Comparator"), last);
        assertEquals(1, JavaMembers.lastSegment("(a)", 1), "a bare name");
        assertEquals(0, JavaMembers.lastSegment("(a)", 0), "no name at the offset");
        assertEquals(1, JavaMembers.lastSegment("a", 99), "past the end: no name there");
        String spaced = "a . \n b";
        assertEquals(spaced.indexOf('b'), JavaMembers.lastSegment(spaced, 0), "blanks after the last dot");

        String nested = "new Outer . Mid.Sink<>()";
        var name = JavaMembers.qualifiedName(nested, nested.indexOf("Sink"));
        assertEquals(nested.indexOf("Outer"), name.getKey());
        assertEquals(List.of("Outer", "Mid", "Sink"), name.getValue());
        var dangling = JavaMembers.qualifiedName("(.Sink)", 2);
        assertEquals(2, dangling.getKey(), "a dot with no name before it");
        assertEquals(List.of("Sink"), dangling.getValue());
        var atStart = JavaMembers.qualifiedName("Sink", 0);
        assertEquals(List.of("Sink"), atStart.getValue());
        var leadingDot = JavaMembers.qualifiedName(".Sink", 1);
        assertEquals(1, leadingDot.getKey());
    }

    @Test
    void aMethodSymbolsNameAndParametersAreRead() {
        assertEquals("go", JavaMembers.methodName("go"));
        assertEquals("map", JavaMembers.methodName(" map(T) : R"));
        assertEquals(List.of(), JavaMembers.parameters("go"));
        assertEquals(List.of(), JavaMembers.parameters("go()"));
        assertEquals(List.of(), JavaMembers.parameters("go)("));
        assertEquals(
                List.of("Map<String, int[]>", "BiFunction<A, B, C>", "int[]"),
                JavaMembers.parameters("go(Map<String, int[]>, BiFunction<A, B, C>, int[])"));
        assertEquals(List.of("Runnable"), JavaMembers.parameters("go(Runnable) : void"));
    }

    @Test
    void aParameterMatchesBySimpleNameArityAndTypeVariable() {
        assertTrue(JavaMembers.sameParameter("java.util.List<String>", "List<T>"));
        assertTrue(JavaMembers.sameParameter("String...", "String[]"), "varargs is an array");
        assertTrue(JavaMembers.sameParameter("@NonNull String", "String"));
        assertTrue(JavaMembers.sameParameter("String[]", "T[]"));
        assertTrue(JavaMembers.sameParameter("String[][]", "T[]"), "a type variable may be an array itself");
        assertFalse(JavaMembers.sameParameter("String", "T[]"), "fewer dimensions");
        assertFalse(JavaMembers.sameParameter("int", "T"), "a primitive is no type variable's");
        assertTrue(JavaMembers.sameParameter("int[]", "T"), "an int array is an object");
        assertTrue(JavaMembers.sameParameter("Item", "Key", Set.of("Key")), "a named type variable");
        assertFalse(JavaMembers.sameParameter("Item", "Key"), "a class name is not one");
        assertTrue(JavaMembers.sameParameter("String", "T", Set.of(), Map.of("T", "java.lang.String")));
        assertFalse(JavaMembers.sameParameter("Integer", "T", Set.of(), Map.of("T", "String")), "bound otherwise");
        assertTrue(JavaMembers.sameParameter("String[]", "T[]", Set.of(), Map.of("T", "String")));
        assertFalse(JavaMembers.sameParameter("[]", "T"), "nothing but brackets");
    }

    @Test
    void typeParametersAndTheirErasuresAreReadFromOutlineNames() {
        assertEquals(List.of("K", "V"), JavaMembers.typeParameters("EnumMap<K extends Enum<K>, V>"));
        assertEquals(List.of("T"), JavaMembers.typeParameters("Box<@NonNull T>"));
        assertEquals(List.of(), JavaMembers.typeParameters("new Comparator<String>() {...}"), "an anonymous class");
        assertEquals(List.of(), JavaMembers.typeParameters("Plain"));
        assertEquals(List.of(), JavaMembers.typeParameters("Odd>x<"));
        assertEquals(List.of(), JavaMembers.typeParameters("Empty< >"), "nothing named");

        assertEquals(
                Map.of("T", "Number", "U", "Object"),
                JavaMembers.typeParameterErasures("Narrow<T extends java.lang.Number, U>"));
        assertEquals(Map.of("E", "Object"), JavaMembers.typeParameterErasures("Annotated<@A E>"));
        assertEquals(Map.of(), JavaMembers.typeParameterErasures("Plain"));
        assertEquals(Map.of(), JavaMembers.typeParameterErasures("Odd>x<"));
        assertEquals(Map.of(), JavaMembers.typeParameterErasures("Q<?>"), "a wildcard names no parameter");

        assertEquals(Set.of(), JavaMembers.typeParameters(List.of(), span("Lone<T>", "class")), "not among them");
        MemberSpan method = span("run()", "method");
        MemberSpan outer = new MemberSpan("Outer<A>", "class", 0, 9, 0, 6, List.of(method), 0);
        assertEquals(Set.of("A"), JavaMembers.typeParameters(List.of(outer), method));
    }

    private static MemberSpan span(String name, String kind) {
        return new MemberSpan(name, kind, 1, 2, 1, 0, List.of(), 0);
    }

    @Test
    void aMethodsOwnTypeParametersAreReadFromItsHead() {
        String generic = "public static <T extends Comparable<T>, U> T max(U u) {}";
        assertEquals(List.of("T", "U"), JavaMembers.methodTypeParameters(generic, 0, generic.indexOf("max")));
        String annotated = "@SafeVarargs(x = 1) final <E> E first(E... es) {}";
        assertEquals(List.of("E"), JavaMembers.methodTypeParameters(annotated, 0, annotated.indexOf("first")));
        String plain = "void run() {}";
        assertEquals(List.of(), JavaMembers.methodTypeParameters(plain, 0, plain.indexOf("run")));
        String unclosed = "<T T run() {}";
        assertEquals(List.of(), JavaMembers.methodTypeParameters(unclosed, 0, unclosed.indexOf("run")));
        String after = "int x; <K> K key() {}";
        assertEquals(List.of("K"), JavaMembers.methodTypeParameters(after, 0, after.indexOf("key")));
    }

    @Test
    void aSignatureIsReadFromTheDeclaration() {
        String decl = "int compare(@NonNull final String a, java.util.Map<String, Integer> m, int[] xs, int ys[]) {";
        assertEquals(
                "compare(String, java.util.Map<String, Integer>, int[], int)",
                JavaMembers.signatureAt(decl, decl.indexOf("compare")));
        String none = "void run() {}";
        assertEquals("run()", JavaMembers.signatureAt(none, none.indexOf("run")));
        String field = "int count";
        assertEquals("count()", JavaMembers.signatureAt(field, field.indexOf("count")), "no parameter list");
        String open = "void go(String a, int";
        assertEquals("go(String)", JavaMembers.signatureAt(open, open.indexOf("go")), "a list that does not close");
    }

    @Test
    void typeParametersWrittenAfterANameAreKept() {
        String decl = "public interface LKeyed< Key ,  Val > {}";
        assertEquals("LKeyed< Key , Val >", JavaMembers.withTypeParameters(decl, decl.indexOf("LKeyed"), "LKeyed"));
        assertEquals("Plain", JavaMembers.withTypeParameters("class Plain {}", 6, "Plain"));
        assertEquals("Open", JavaMembers.withTypeParameters("class Open<T", 6, "Open"), "never closed");
        assertEquals("End", JavaMembers.withTypeParameters("class End", 99, "End"));
        assertEquals("A<B>", JavaMembers.substitute("A<T>", Map.of("T", "B")));
        assertSame("A<T>", JavaMembers.substitute("A<T>", Map.of()));
    }

    @Test
    void typeArgumentsAndRawNamesAreReadInTheHeaderOnly() {
        String header = "class Store implements Comparable<Store<Integer>>, Iterable<Item> {";
        assertEquals(List.of("Store<Integer>"), JavaMembers.typeArguments(header, 0, "Comparable"));
        assertEquals(
                List.of(),
                JavaMembers.typeArguments(header, header.indexOf("Store"), "Store", false),
                "Store<Integer> sits inside another type's arguments");
        String own = "class Store<T> extends Store<String> {";
        assertEquals(List.of("String"), JavaMembers.typeArguments(own, own.indexOf("Store"), "Store", true));
        assertEquals(
                List.of("T"),
                JavaMembers.typeArguments(own, own.indexOf("Store"), "Store", false),
                "read as a mention, its own name gives its own parameters");
        assertEquals(List.of(), JavaMembers.typeArguments("interface R extends java.util.List", 0, "List"));
        assertEquals(List.of(), JavaMembers.typeArguments("class A extends B<> {", 0, "B"), "a diamond names none");
        assertTrue(JavaMembers.namesRaw("interface R extends Store {", 0, "Store", false));
        assertTrue(JavaMembers.namesRaw("interface R extends Store", 0, "a.b.Store<T>", false), "at the end");
        assertFalse(JavaMembers.namesRaw("interface R extends Store<T> {", 0, "Store", false));
        assertFalse(JavaMembers.namesRaw("interface R extends Store.Inner {", 0, "Store", false));
        assertFalse(JavaMembers.namesRaw("interface R extends Other {", 0, "Store", false));
        assertNull(JavaMembers.typeAt(List.of(span("run()", "method")), 1));
    }
}

package com.editora.lsp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Go to Super Method reads around the caret (eighth review BQ1, BQ2, BR2): the member is found from its whole
 * range, anonymous classes count, a lambda's arrow and a constructor's {@code super(...)} are found in the text.
 */
class JavaMembersTest {

    private static MemberSpan span(String name, String kind, int start, int end, int nameLine, MemberSpan... children) {
        return new MemberSpan(name, kind, start, end, nameLine, 4, List.of(children));
    }

    // 0 class Poly {
    // 1     /** The area. */
    // 2     @Override
    // 3     public double area() {
    // 4         Runnable r = new Runnable() {
    // 5             public void run() {
    // 6             }
    // 7         };
    // 8     }
    // 9 }
    private static final List<MemberSpan> OUTLINE = List.of(span(
            "Poly",
            "class",
            0,
            9,
            0,
            span(
                    "area()",
                    "method",
                    1,
                    8,
                    3,
                    span("new Runnable() {...}", "class", 4, 7, 4, span("run()", "method", 5, 6, 5)))));

    private static String last(List<MemberSpan> chain) {
        return chain.isEmpty() ? null : chain.get(chain.size() - 1).name();
    }

    @Test
    void aMethodStartsAtItsJavadocAndAnnotations() {
        assertEquals("area()", last(JavaMembers.enclosing(OUTLINE, 1)), "its Javadoc");
        assertEquals("area()", last(JavaMembers.enclosing(OUTLINE, 2)), "its @Override line");
        assertEquals("Poly", last(JavaMembers.enclosing(OUTLINE, 0)));
        assertTrue(JavaMembers.enclosing(OUTLINE, 12).isEmpty(), "below every type");
    }

    @Test
    void anAnonymousClassesMethodIsItsOwnMember() {
        List<MemberSpan> chain = JavaMembers.enclosing(OUTLINE, 6);
        assertEquals("run()", last(chain));
        assertTrue(chain.get(chain.size() - 2).isAnonymous(), "inside new Runnable() {...}");
        assertEquals("new Runnable() {...}", last(JavaMembers.enclosing(OUTLINE, 4)));
    }

    @Test
    void theLambdaAroundTheCaretIsFound() {
        String text = "void f() {\n    Runnable r = () -> System.out.println(\"x\");\n}\n";
        int caret = text.indexOf("println");
        assertEquals(text.indexOf("->"), JavaMembers.lambdaArrow(text, 0, caret), "an expression lambda");

        String block = "void f() {\n    run(s -> {\n        int n = s.length();\n    });\n}\n";
        assertEquals(block.indexOf("->"), JavaMembers.lambdaArrow(block, 0, block.indexOf("length")), "a block lambda");

        String after = "void f() {\n    Runnable r = () -> go();\n    int x = 1;\n}\n";
        assertEquals(-1, JavaMembers.lambdaArrow(after, 0, after.indexOf("x = 1")), "a lambda that has ended");

        String arg = "void f() {\n    call(a -> a, b);\n}\n";
        assertEquals(-1, JavaMembers.lambdaArrow(arg, 0, arg.indexOf("b)")), "the next argument");

        String nested = "void f() {\n    run(() -> {\n        list.forEach(x -> use(x));\n    });\n}\n";
        assertEquals(
                nested.indexOf("x ->") + 2,
                JavaMembers.lambdaArrow(nested, 0, nested.indexOf("use")),
                "the innermost lambda");
    }

    @Test
    void aSwitchRuleIsNoLambda() {
        String text =
                "int f(int k) {\n    return switch (k) {\n        case 1 -> 10;\n        default -> 0;\n    };\n}\n";
        assertEquals(-1, JavaMembers.lambdaArrow(text, 0, text.indexOf("10")));
        assertEquals(-1, JavaMembers.lambdaArrow(text, 0, text.indexOf("0;")));
    }

    @Test
    void arrowsInStringsAndCommentsAreNotLambdas() {
        String text = "void f() {\n    String s = \"a -> b\"; // c -> d\n    go();\n}\n";
        assertEquals(-1, JavaMembers.lambdaArrow(text, 0, text.indexOf("go")));
    }

    @Test
    void aConstructorsSuperCallIsFound() {
        String text = "App() {\n    // super(\"no\")\n    super(\"app\");\n}\nvoid g() { this.super_(); }\n";
        assertEquals(text.indexOf("super(\"app"), JavaMembers.superCall(text, 0, text.indexOf("void g")));
        assertEquals(-1, JavaMembers.superCall("App() {\n    this(1);\n}\n", 0, 20));
    }

    @Test
    void anOverriddenMethodHasTheSameNameAndParameters() {
        List<MemberSpan> members = List.of(
                span("go()", "method", 1, 1, 1),
                span("go(int)", "method", 2, 2, 2),
                span("remove(Object)", "method", 3, 3, 3),
                span("remove(int)", "method", 4, 4, 4),
                span("go", "field", 5, 5, 5));
        assertEquals(List.of(members.get(0)), JavaMembers.overridden(members, "go()"));
        assertTrue(JavaMembers.overridden(members, "go(String)").isEmpty(), "go(int) is another overload");
        assertEquals(List.of(members.get(3)), JavaMembers.overridden(members, "remove(int)"), "the exact one of two");
        assertEquals(List.of("Map<String, Integer>", "int"), JavaMembers.parameters("put(Map<String, Integer>, int)"));
        assertEquals("label", JavaMembers.methodName("label() : String"));
    }

    @Test
    void aTypeIsFoundByItsNameLine() {
        assertEquals("Poly", JavaMembers.typeAt(OUTLINE, 0).name());
        assertEquals("new Runnable() {...}", JavaMembers.typeAt(OUTLINE, 4).name());
        assertNull(JavaMembers.typeAt(OUTLINE, 3), "a method's line");
    }

    /** A switch rule with several labels is no lambda (ninth review BP1). */
    @Test
    void aSwitchRulesLabelsRunOnAcrossCommas() {
        String text = "int f(int k) {\n    return switch (k) {\n        case 1, 2 -> {\n            go();\n"
                + "            yield 1;\n        }\n        default -> 0;\n    };\n}\n";
        assertEquals(-1, JavaMembers.lambdaArrow(text, 0, text.indexOf("go()")));
        String lambda = "void f() {\n    call(1, x -> use(x));\n}\n";
        assertEquals(lambda.indexOf("->"), JavaMembers.lambdaArrow(lambda, 0, lambda.indexOf("use")), "an argument");
    }

    /** A constructor chained with this(...) (ninth review BP3). */
    @Test
    void aChainedConstructorsThisCallIsFound() {
        String text = "Ctors() {\n    this(\"default\"); // chained\n}\n";
        assertEquals(text.indexOf("this("), JavaMembers.thisCall(text, 0, text.length()));
        assertEquals(-1, JavaMembers.thisCall("Ctors() {\n    this.x = 1;\n}\n", 0, 30));
    }

    /** The modifiers before a method's name; annotations and their arguments are not modifiers (ninth review DP1). */
    @Test
    void aDeclarationsModifiersAreRead() {
        String text =
                "    /** private, as docs say */\n    @SuppressWarnings(\"static\")\n    private static void util() {}\n";
        assertEquals(java.util.Set.of("private", "static"), JavaMembers.modifiers(text, 0, text.indexOf("util")));
        String plain = "    @Override\n    public String label() {}\n";
        assertEquals(java.util.Set.of("public"), JavaMembers.modifiers(plain, 0, plain.indexOf("label")));
    }

    /** In a library source an anonymous class's method is found in the text (ninth review BP2). */
    @Test
    void anAnonymousClassesMethodIsFoundInTheText() {
        String text = "public ListIterator<E> listIterator(int index) {\n    return new ListIterator<E>() {\n"
                + "        int cursor;\n        public boolean hasNext() {\n            return cursor != size;\n"
                + "        }\n    };\n}\n";
        int[] found = JavaMembers.anonymousMethod(text, 0, text.indexOf("cursor != size"));
        assertEquals(text.indexOf("ListIterator<E>() {"), found[0], "the instantiated type");
        assertEquals(text.indexOf("hasNext"), found[1], "the method");
        int[] field = JavaMembers.anonymousMethod(text, 0, text.indexOf("int cursor"));
        assertEquals(-1, field[1], "in its body, not a method: the anonymous class itself (twelfth review EL2)");
        assertNull(JavaMembers.anonymousMethod(text, 0, text.indexOf("return new")), "outside it");
    }

    /** Only an override-equivalent method is a super method: no other overload (ninth review DP1, EP2). */
    @Test
    void anOverloadIsNoSuperMethod() {
        List<MemberSpan> object = List.of(span("equals(Object)", "method", 1, 1, 1));
        assertTrue(JavaMembers.overridden(object, "equals(Circle)").isEmpty());
        List<MemberSpan> box = List.of(span("put(T)", "method", 1, 1, 1), span("put(int)", "method", 2, 2, 2));
        assertEquals(List.of(box.get(1)), JavaMembers.overridden(box, "put(int)"), "the exact one; T is no int");
        assertEquals(List.of(box.get(0)), JavaMembers.overridden(box, "put(String)"), "T stands for String");
        List<MemberSpan> gen = List.of(span("put(String, int)", "method", 1, 1, 1));
        assertTrue(JavaMembers.overridden(gen, "put(int, String)").isEmpty(), "the order counts");
        List<MemberSpan> qualified = List.of(span("addAll(java.util.Collection<? extends E>)", "method", 1, 1, 1));
        assertEquals(qualified, JavaMembers.overridden(qualified, "addAll(Collection<? extends E>)"));
    }

    /**
     * A type variable is any name the super method's type, or a type around it, declares, not only a one-letter
     * one: {@code Store<Item>.add(Item)} is overridden by {@code add(String)} (tenth review EO1).
     */
    @Test
    void aTypeVariableIsWhatTheTypeDeclares() {
        assertEquals(List.of("Key", "Val"), JavaMembers.typeParameters("KeyStore<Key, Val>"));
        assertEquals(List.of("K", "V"), JavaMembers.typeParameters("EnumMap<K extends Enum<K>, V>"));
        assertEquals(List.of("Req"), JavaMembers.typeParameters("Handler<@NonNull Req>"));
        assertTrue(JavaMembers.typeParameters("new Store<String>() {...}").isEmpty(), "type arguments, not variables");
        MemberSpan add = span("add(Item)", "method", 2, 2, 2);
        MemberSpan in = span("In", "class", 3, 5, 3, span("take(Elem)", "method", 4, 4, 4));
        MemberSpan store = span("Store<Item>", "interface", 1, 2, 1, add);
        MemberSpan outer = span("Outer<Elem>", "class", 3, 6, 3, in);
        List<MemberSpan> outline = List.of(store, outer);
        java.util.Set<String> vars = JavaMembers.typeParameters(outline, store);
        assertEquals(java.util.Set.of("Item"), vars);
        assertTrue(JavaMembers.overridden(store.children(), "add(String)").isEmpty(), "by shape alone, Item is a type");
        assertEquals(List.of(add), JavaMembers.overridden(store.children(), "add(String)", vars));
        assertTrue(JavaMembers.overridden(store.children(), "add(int)", vars).isEmpty(), "no primitive");
        assertEquals(java.util.Set.of("Elem"), JavaMembers.typeParameters(outline, in), "the outer class's");
        assertEquals(
                in.children(),
                JavaMembers.overridden(in.children(), "take(String)", JavaMembers.typeParameters(outline, in)));
    }

    /** {@code @Override} before a method's name, by its simple or qualified name, not in a comment. */
    @Test
    void anOverrideAnnotationIsRead() {
        String text = "    @Override\n    public void take(String t) {}\n";
        assertTrue(JavaMembers.annotated(text, 0, text.indexOf("take"), "Override"));
        String qualified = "    @java.lang.Override void take(String t) {}\n";
        assertTrue(JavaMembers.annotated(qualified, 0, qualified.indexOf("take"), "Override"));
        String comment = "    /** Not an @Override. */\n    @Overrides void take(String t) {}\n";
        assertFalse(JavaMembers.annotated(comment, 0, comment.indexOf("take"), "Override"));
    }

    /**
     * A type variable stands for the type argument the subtype's header gives it: {@code Store<String>}'s
     * {@code save(T)} is no super method of {@code save(Integer)} (tenth review DO2, EP2).
     */
    @Test
    void aTypeArgumentBindsTheSupertypesTypeVariable() {
        String text = "interface StrStore extends Store<String> {\n    void save(Integer other);\n}\n";
        assertEquals(List.of("String"), JavaMembers.typeArguments(text, text.indexOf("StrStore"), "Store<T>"));
        String bounded = "class Node<T extends Comparable<T>> implements Comparable<Node<T>> {}";
        assertEquals(List.of("Node<T>"), JavaMembers.typeArguments(bounded, 6, "Comparable<T>"), "not the bound");
        String handler = "class H implements java.util.function.BiFunction<String, List<Integer>, Long> {}";
        assertEquals(
                List.of("String", "List<Integer>", "Long"),
                JavaMembers.typeArguments(handler, 6, "BiFunction<T, U, R>"));
        assertTrue(JavaMembers.typeArguments("new Store<>() {", 4, "Store<T>").isEmpty(), "a diamond names none");
        assertEquals("Map<String, K>", JavaMembers.substitute("Map<T, K>", java.util.Map.of("T", "String")));

        List<MemberSpan> store = List.of(span("save(T)", "method", 1, 1, 1));
        var bound = java.util.Map.of("T", "String");
        assertTrue(JavaMembers.overridden(store, "save(Integer)", java.util.Set.of("T"), bound)
                .isEmpty());
        assertEquals(store, JavaMembers.overridden(store, "save(String)", java.util.Set.of("T"), bound));
        List<MemberSpan> takes = List.of(span("take(T[])", "method", 1, 1, 1));
        assertEquals(takes, JavaMembers.overridden(takes, "take(String[])", java.util.Set.of("T"), bound));
        assertTrue(JavaMembers.overridden(takes, "take(Integer[])", java.util.Set.of("T"), bound)
                .isEmpty());
        assertEquals(
                store,
                JavaMembers.overridden(store, "save(Integer)", java.util.Set.of("T"), java.util.Map.of()),
                "unbound (raw, or a class file between): any type");
    }

    /**
     * A one-line nested class or a field's anonymous class: the modifiers before the method's name are the method's
     * own, not the class's or the field's on that line (tenth review DO1).
     */
    @Test
    void aOneLineDeclarationsModifiersAreTheMethodsOwn() {
        String nested = "    private static class Base3 { public String toString() { return \"\"; } }\n";
        assertEquals(java.util.Set.of("public"), JavaMembers.modifiers(nested, 0, nested.indexOf("toString")));
        String field = "    private final Runnable r = new Runnable() { public void run() { } };\n";
        assertEquals(java.util.Set.of("public"), JavaMembers.modifiers(field, 0, field.indexOf("run()")));
        String annotated = "    @SuppressWarnings({\"a\"}) static void util() {}\n";
        assertEquals(java.util.Set.of("static"), JavaMembers.modifiers(annotated, 0, annotated.indexOf("util")));
        String oneLine = "static class V2 extends V { @Override void v(String[] b) { } }\n";
        assertTrue(JavaMembers.annotated(oneLine, 0, oneLine.indexOf("v(String"), "Override"));
        assertTrue(
                JavaMembers.modifiers(oneLine, 0, oneLine.indexOf("v(String")).isEmpty());
    }

    /** A lambda's parameters and a method reference are their functional expression's (tenth review BO3). */
    @Test
    void aLambdasParametersAndAMethodReferenceAreFound() {
        String text = "void f() {\n    list.forEach((String item) -> use(item));\n    run(x -> x + 1);\n"
                + "    call(a, y -> y);\n    int n = s.mapToInt(String::length).sum();\n"
                + "    switch (k) { case ONE -> go(); default -> { } }\n}\n";
        int typed = text.indexOf("->");
        assertEquals(typed, JavaMembers.lambdaParameterArrow(text, 0, text.indexOf("item)")));
        assertEquals(typed, JavaMembers.lambdaParameterArrow(text, 0, text.indexOf("String item") + 2));
        int bare = text.indexOf("->", typed + 2);
        assertEquals(bare, JavaMembers.lambdaParameterArrow(text, 0, text.indexOf("x ->")));
        assertEquals(-1, JavaMembers.lambdaParameterArrow(text, 0, text.indexOf("a, y")), "an argument before it");
        assertEquals(-1, JavaMembers.lambdaParameterArrow(text, 0, text.indexOf("ONE")), "a switch label");
        assertEquals(-1, JavaMembers.lambdaParameterArrow(text, 0, text.indexOf("use(")), "the lambda's body");
        int ref = text.indexOf("::");
        assertEquals(ref, JavaMembers.methodReference(text, text.indexOf("length")));
        assertEquals(ref, JavaMembers.methodReference(text, text.indexOf("String::")));
        assertEquals(-1, JavaMembers.methodReference(text, text.indexOf("sum")));
    }

    /** A method no outline holds is named from its declaration, as jdtls names one (tenth review BO2). */
    @Test
    void aMethodsSignatureIsReadFromItsDeclaration() {
        String text = "new Comparator<>() { public int compare(final String a, @NonNull String b) { return 0; } }";
        assertEquals("compare(String, String)", JavaMembers.signatureAt(text, text.indexOf("compare")));
        String generic = "void put(Map<String, Integer> m, int... xs, String[] grid) {}";
        assertEquals(
                "put(Map<String, Integer>, int..., String[])",
                JavaMembers.signatureAt(generic, generic.indexOf("put")));
        assertEquals("run()", JavaMembers.signatureAt("public void run() {}", 12));
    }

    /** A field holding an anonymous class is part of the chain, so the class is found (ninth review DP2). */
    @Test
    void aFieldsAnonymousClassIsFound() {
        List<MemberSpan> outline = List.of(span(
                "Circle",
                "class",
                0,
                9,
                0,
                span(
                        "BY_LEN",
                        "field",
                        1,
                        5,
                        1,
                        span(
                                "new Comparator<>() {...}",
                                "class",
                                1,
                                5,
                                1,
                                span("compare(String, String)", "method", 2, 4, 3)))));
        List<MemberSpan> chain = JavaMembers.enclosing(outline, 3);
        assertEquals("compare(String, String)", last(chain));
        assertTrue(chain.get(chain.size() - 2).isAnonymous());
        assertEquals("field", chain.get(1).kind());
    }

    /** A type found by its name when the hierarchy's line is not its outline's (ninth review DP3). */
    @Test
    void aTypeIsFoundByNameWhenTheLineDiffers() {
        assertEquals("Poly", JavaMembers.typeAt(OUTLINE, 7, "Poly").name());
        assertNull(JavaMembers.typeAt(OUTLINE, 7, "Missing"));
    }

    /**
     * A member that starts on its parent's name line holds the caret only from its start column: jdtls starts the first
     * member's range at a comment ending the class line (eleventh review DM3).
     */
    @Test
    void aClassLinesCommentIsNotItsFirstMember() {
        MemberSpan area = new MemberSpan("area()", "method", 0, 1, 1, 18, List.of(), 52);
        MemberSpan sub = new MemberSpan("Sub", "class", 0, 2, 0, 17, List.of(area), 4);
        assertEquals(List.of(sub), JavaMembers.enclosing(List.of(sub), 0, 18), "on the class name");
        assertEquals(List.of(sub, area), JavaMembers.enclosing(List.of(sub), 0, 60), "in the comment, the member's");
        assertEquals(List.of(sub, area), JavaMembers.enclosing(List.of(sub), 1, 0), "a later line");
    }

    /**
     * On an anonymous class method's header (its name, modifiers, annotation) that method is the anchor, as in its
     * body; between members, none (eleventh review EM2, BP2).
     */
    @Test
    void anAnonymousMethodsHeaderIsThatMethod() {
        String text = "static {\n    BY = new java.util.Comparator<String>() {\n        int seen = 0;\n\n"
                + "        @Override\n        public int compare(String a, String b) {\n            return 0;\n"
                + "        }\n    };\n}\n";
        int type = text.indexOf("Comparator<String>");
        int name = text.indexOf("compare(");
        for (String at : List.of("compare(", "public int", "@Override", "return 0")) {
            int[] found = JavaMembers.anonymousMethod(text, 0, text.indexOf(at) + 2);
            assertEquals(type, found[0], at + ": the name's last segment");
            assertEquals(name, found[1], at);
        }
        assertEquals(-1, JavaMembers.anonymousMethod(text, 0, text.indexOf("seen"))[1], "a field: the class (EL2)");
        assertEquals(-1, JavaMembers.anonymousMethod(text, 0, text.indexOf("\n\n") + 1)[1], "between members");
        String annotated = "new Runnable() {\n    @SuppressWarnings({\"a\"}) public void run() {}\n}";
        int[] run = JavaMembers.anonymousMethod(annotated, 0, annotated.indexOf("Suppress"));
        assertEquals(annotated.indexOf("run()"), run[1], "an annotation's braces are no member's end");
    }

    /** Method references with arrays and explicit type arguments, and a caret before a lambda's "(" (BO3, EM7). */
    @Test
    void referencesAndParametersAtTheirEdges() {
        String text =
                "void f() {\n    Supplier<String[]> s = n -> new String[n];\n    IntFunction<String[]> g = String[]::new;\n"
                        + "    Supplier<List<String>> e = java.util.Collections::<String>emptyList;\n    run((x) -> x);\n"
                        + "    run(() -> 1);\n}\n";
        int ref = text.indexOf("::new");
        assertEquals(ref, JavaMembers.methodReference(text, text.indexOf("String[]::")));
        assertEquals(ref, JavaMembers.methodReference(text, text.indexOf("[]::") + 1));
        int typed = text.indexOf("::<String>");
        assertEquals(typed, JavaMembers.methodReference(text, text.indexOf("emptyList")));
        int paren = text.indexOf("(x)");
        assertEquals(text.indexOf("->", paren), JavaMembers.lambdaParameterArrow(text, 0, paren));
        int empty = text.indexOf("() -> 1");
        assertEquals(text.indexOf("->", empty), JavaMembers.lambdaParameterArrow(text, 0, empty));
    }

    /**
     * Type arguments from a supertype's top-level mention only; raw and erased; a method's own type parameters
     * (eleventh review DM1, BM4, BM2).
     */
    @Test
    void headersAreReadAtTheirTopLevel() {
        String matrix = "interface Matrix extends List<List<Double>> {";
        assertEquals(List.of("List<Double>"), JavaMembers.typeArguments(matrix, 10, "List<E>", true));
        String odd = "interface Odd extends Store<String>, Comparable<Store<Integer>> {";
        assertEquals(List.of("String"), JavaMembers.typeArguments(odd, 10, "Store<T>", true));
        String same = "class Box<T> extends other.Box<String> {";
        assertEquals(List.of("String"), JavaMembers.typeArguments(same, 6, "Box<T>", true), "not its own name");
        assertTrue(JavaMembers.namesRaw("interface R extends Store {", 10, "Store<T>", true));
        assertFalse(JavaMembers.namesRaw("interface R extends Store<String> {", 10, "Store<T>", true));
        assertFalse(JavaMembers.namesRaw("x = new Comparator<>() {", 8, "Comparator<T>", false), "a diamond");
        assertEquals(java.util.Map.of("T", "Number"), JavaMembers.typeParameterErasures("Narrow<T extends Number>"));
        assertEquals(java.util.Map.of("T", "Object"), JavaMembers.typeParameterErasures("Store<T>"));
        String conv = "    @Override <U> U conv(String s, U o);";
        assertEquals(List.of("U"), JavaMembers.methodTypeParameters(conv, 0, conv.indexOf("conv")));
        String two = "    public static <K, V extends Comparable<V>> Map<K, V> f() {}";
        assertEquals(List.of("K", "V"), JavaMembers.methodTypeParameters(two, 0, two.indexOf("f()")));
        assertTrue(JavaMembers.methodTypeParameters("    void g(T t) {}", 0, 9).isEmpty());
        assertEquals(18, JavaMembers.lastSegment("x = new java.util.Comparator<>()", 4));
        List<MemberSpan> generic = List.of(span("conv(String, Out)", "method", 1, 1, 1));
        java.util.Map<String, String> outIsU = new java.util.HashMap<>();
        outIsU.put("Out", "U");
        assertEquals(
                generic,
                JavaMembers.overridden(
                        generic, "conv(String, U)", java.util.Set.of(), java.util.Map.of(), m -> outIsU));
        assertTrue(JavaMembers.overridden(generic, "conv(String, U)").isEmpty(), "Out is no shape");
    }

    /**
     * An anonymous method's parameter list (a two-line one, an annotated parameter) and its Javadoc are that method's,
     * and in a class body outside every method the anonymous class itself answers (twelfth review DL1, EM2, EL2).
     */
    @Test
    void anAnonymousMethodsParametersAndJavadocAreThatMethod() {
        String text = "static {\n    C1 = new Comparator<>() {\n        /** Compares. */\n"
                + "        public int compare(@SuppressWarnings(\"unused\") String a,\n                String b) {\n"
                + "            return 0;\n        }\n    };\n}\n";
        int name = text.indexOf("compare(");
        for (String at : List.of("String a", "String b", "unused", "Compares", "compare(")) {
            int[] found = JavaMembers.anonymousMethod(text, 0, text.indexOf(at) + 1);
            assertEquals(name, found[1], at);
        }
        int[] call = JavaMembers.anonymousMethod(
                "new Runnable() {\n    Object o = new Object();\n    public void run() {}\n}", 0, 30);
        assertEquals(-1, call[1], "a field's new Object(): no method header");
    }

    /**
     * A member holds the caret only up to its end column, and a range jdtls starts at a comment ending the header's
     * second line is not the member's on that line (twelfth review BL3, DM3).
     */
    @Test
    void aOneLineClassAndAWrappedHeaderPlaceTheCaret() {
        // class Hdr12c extends AbstractShape { /* H4 */ Hdr12c() { super("c"); } public double area() { return 3; } }
        MemberSpan ctor = new MemberSpan("Hdr12c()", "constructor", 0, 0, 0, 48, List.of(), 37, 71);
        MemberSpan area = new MemberSpan("area()", "method", 0, 0, 0, 86, List.of(), 72, 106);
        MemberSpan one = new MemberSpan("Hdr12c", "class", 0, 0, 0, 6, List.of(ctor, area), 0, 108);
        assertEquals(List.of(one, area), JavaMembers.enclosing(List.of(one), 0, 88), "a later member");
        assertEquals(List.of(one, ctor), JavaMembers.enclosing(List.of(one), 0, 50), "the first");
        assertEquals(List.of(one), JavaMembers.enclosing(List.of(one), 0, 107), "after both");
        String text = "class Hdr12b\n        extends AbstractShape { // H3\n    Hdr12b() { super(\"b\"); }\n}\n";
        int comment = "        extends AbstractShape { ".length();
        MemberSpan wrappedCtor = new MemberSpan("Hdr12b()", "constructor", 1, 2, 2, 4, List.of(), comment, 28);
        MemberSpan wrapped = new MemberSpan("Hdr12b", "class", 0, 3, 0, 6, List.of(wrappedCtor), 0, 1);
        assertEquals(List.of(wrapped), JavaMembers.enclosing(List.of(wrapped), 1, 10, text), "on AbstractShape");
        assertEquals(List.of(wrapped), JavaMembers.enclosing(List.of(wrapped), 1, comment + 3, text), "the comment");
        assertEquals(List.of(wrapped, wrappedCtor), JavaMembers.enclosing(List.of(wrapped), 2, 6, text));
        String javadoc = "class J {\n    /** Doc. */\n    void f() {}\n}\n";
        MemberSpan f = new MemberSpan("f()", "method", 1, 2, 2, 9, List.of(), 4, 16);
        MemberSpan j = new MemberSpan("J", "class", 0, 3, 0, 6, List.of(f), 0, 1);
        assertEquals(List.of(j, f), JavaMembers.enclosing(List.of(j), 1, 8, javadoc), "its own Javadoc stays its");
    }

    /** Nested type arguments of a method reference, a decompiled header's type parameters, a qualified name (BO3, DL2, BL1). */
    @Test
    void nestedTypeArgumentsAndQualifiedNames() {
        String text = "Supplier<List<List<String>>> e = java.util.Collections::<List<String>>emptyList;";
        assertEquals(text.indexOf("::<"), JavaMembers.methodReference(text, text.indexOf("emptyList")));
        String keyed = "public interface LKeyed<Key> {\n    void use(Key k);\n}\n";
        assertEquals("LKeyed<Key>", JavaMembers.withTypeParameters(keyed, keyed.indexOf("LKeyed"), "LKeyed"));
        String mid = "public interface LMid<T extends Comparable<T>> extends LStore<T> {";
        assertEquals("LMid<T extends Comparable<T>>", JavaMembers.withTypeParameters(mid, mid.indexOf("LMid"), "LMid"));
        assertEquals("A", JavaMembers.withTypeParameters("class A {", 6, "A"));
        String sink = "Mid.Sink<String> s = new Wrong12.Mid.Sink<>() {";
        var name = JavaMembers.qualifiedName(sink, sink.lastIndexOf("Sink"));
        assertEquals(sink.indexOf("Wrong12"), name.getKey());
        assertEquals(List.of("Wrong12", "Mid", "Sink"), name.getValue());
        MemberSpan inner = span("Sink<V>", "interface", 2, 2, 2);
        MemberSpan middle = span("Mid", "class", 1, 3, 1, inner);
        MemberSpan top = span("Wrong12", "class", 0, 4, 0, middle);
        assertEquals(inner, JavaMembers.nestedType(top, List.of("Mid", "Sink")));
        assertNull(JavaMembers.nestedType(top, List.of("Other")));
    }

    /**
     * On an anonymous class's header, from its type's name to its body's brace, the class itself, also wrapped and with
     * arguments; on {@code new} the enclosing member's (thirteenth review BK2, EL2, DK4).
     */
    @Test
    void anAnonymousClassesHeaderIsTheClass() {
        String text = "static {\n    A = new Comparator<>() {\n        public int compare(String a, String b) {\n"
                + "            return 0;\n        }\n    };\n    S = new AbstractShape(\"x\") { int f; };\n"
                + "    W = new Wrong13.Mid\n            .Sink<>() { int g; };\n}\n";
        int comparator = text.indexOf("Comparator<>");
        for (String at : List.of("Comparator<>", "<>()", "() {")) {
            int[] found = JavaMembers.anonymousMethod(text, 0, text.indexOf(at) + 1);
            assertEquals(comparator, found[0], at);
            assertEquals(-1, found[1], at);
        }
        assertNull(JavaMembers.anonymousMethod(text, 0, text.indexOf("new Comparator")), "on new: the initializer's");
        int[] shape = JavaMembers.anonymousMethod(text, 0, text.indexOf("\"x\"") + 1);
        assertEquals(text.indexOf("AbstractShape"), shape[0], "in its arguments");
        int[] wrapped = JavaMembers.anonymousMethod(text, 0, text.indexOf("Mid\n"));
        assertEquals(text.indexOf("Sink<>"), wrapped[0], "a wrapped name's last segment");
        assertEquals(text.indexOf("Sink<>"), JavaMembers.anonymousMethod(text, 0, text.indexOf("int g"))[0]);
    }

    /**
     * A comment right above an anonymous method's header is the method's: a {@code //} line with no blank line before
     * the header, and a Javadoc with a line comment after it; a trailing comment or one after a blank line is not
     * (thirteenth review BK6, EM2).
     */
    @Test
    void aCommentAboveAnAnonymousMethodIsTheMethods() {
        String text = "new Comparator<>() {\n    int seen; // after a field\n    // C1 right above\n"
                + "    /** Compares. */ // note\n    public int compare(String a, String b) {\n        return 0;\n"
                + "    }\n    // far above\n\n    public boolean equals(Object o) {\n        return false;\n    }\n}\n";
        int compare = text.indexOf("compare(");
        for (String at : List.of("C1 right", "Compares", "note")) {
            assertEquals(compare, JavaMembers.anonymousMethod(text, 0, text.indexOf(at) + 1)[1], at);
        }
        assertEquals(-1, JavaMembers.anonymousMethod(text, 0, text.indexOf("after a field") + 1)[1], "a field's");
        assertEquals(-1, JavaMembers.anonymousMethod(text, 0, text.indexOf("far above") + 1)[1], "a blank line before");
        assertTrue(JavaMembers.inLeadingComment("\n    /* a */\n    // b\n    ", 6));
    }

    /**
     * A class file's member types are listed flat beside their outer type, nested only by range, and are found there;
     * of several types on one line, the one at the column or of the name (thirteenth review DK1, BK4).
     */
    @Test
    void aFlatOutlinesAndAOneLineTypesMemberTypesAreFound() {
        MemberSpan outer = new MemberSpan("LOuter", "class", 3, 15, 3, 13, List.of());
        MemberSpan mid = new MemberSpan("Mid", "class", 7, 14, 7, 17, List.of());
        MemberSpan sink = new MemberSpan("Sink<V>", "interface", 11, 13, 11, 20, List.of());
        List<MemberSpan> flat = List.of(outer, mid, sink);
        assertNull(JavaMembers.nestedType(outer, List.of("Mid", "Sink")), "not among its children");
        assertEquals(sink, JavaMembers.nestedType(flat, outer, List.of("Mid", "Sink")));
        // class One13 { static class Mid { interface Sink<V> {} } }
        MemberSpan s1 = new MemberSpan("Sink<V>", "interface", 0, 0, 0, 45, List.of());
        MemberSpan m1 = new MemberSpan("Mid", "class", 0, 0, 0, 27, List.of(s1));
        MemberSpan one = new MemberSpan("One13", "class", 0, 0, 0, 6, List.of(m1));
        assertEquals(m1, JavaMembers.typeAt(List.of(one), 0, 27));
        assertEquals(s1, JavaMembers.typeAt(List.of(one), 0, "Sink"));
        MemberSpan ctor = new MemberSpan("A()", "constructor", 2, 2, 2, 4, List.of());
        MemberSpan a = new MemberSpan("A", "class", 0, 4, 0, 6, List.of(ctor));
        assertEquals(a, JavaMembers.typeAt(List.of(a), 2, 4), "a constructor's line: its class (BK3)");
    }
}

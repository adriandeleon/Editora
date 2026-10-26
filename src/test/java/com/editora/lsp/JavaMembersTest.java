package com.editora.lsp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(List.of(members.get(1)), JavaMembers.overridden(members, "go(String)"), "same count, one choice");
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
}

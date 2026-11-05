package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JUnit test detection: annotation kinds, FQCN derivation, comment/string safety, nested classes. */
class JavaTestScannerTest {

    private static String src(String... lines) {
        return String.join("\n", lines);
    }

    @Test
    void classTargetFirstThenMethodsOnTheirDeclLines() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "package com.foo;", // 0
                "import org.junit.jupiter.api.Test;", // 1
                "class FooTest {", // 2
                "    @Test", // 3
                "    void addsNumbers() {", // 4
                "        assertEquals(2, 1 + 1);", // 5
                "    }", // 6
                "}")); // 7
        assertEquals(2, t.size());
        assertEquals(2, t.get(0).line());
        assertEquals("com.foo.FooTest", t.get(0).className());
        assertNull(t.get(0).methodName()); // class-level target
        assertEquals(4, t.get(1).line()); // the method decl line, not the @Test line
        assertEquals("addsNumbers", t.get(1).methodName());
    }

    @Test
    void allJunit5AnnotationKinds() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "class T {",
                "  @Test void a() {}",
                "  @ParameterizedTest void b() {}",
                "  @RepeatedTest(3) void c() {}",
                "  @TestFactory java.util.stream.Stream<Object> d() { return null; }",
                "  @TestTemplate void e() {}",
                "}"));
        assertEquals(
                List.of("a", "b", "c", "d", "e"),
                t.stream().skip(1).map(JavaTestScanner.TestTarget::methodName).toList());
    }

    @Test
    void dynamicFlagSetForParameterizedFamilyOnly() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(
                src("class T {", "  @Test void plain() {}", "  @ParameterizedTest void param() {}", "}"));
        JavaTestScanner.TestTarget plain = t.stream()
                .filter(x -> "plain".equals(x.methodName()))
                .findFirst()
                .orElseThrow();
        JavaTestScanner.TestTarget param = t.stream()
                .filter(x -> "param".equals(x.methodName()))
                .findFirst()
                .orElseThrow();
        assertFalse(plain.dynamic());
        assertTrue(param.dynamic());
        assertFalse(t.get(0).dynamic()); // class target
    }

    @Test
    void junit4AndFullyQualifiedAndArgs() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "class T {",
                "  @org.junit.jupiter.api.Test",
                "  void a() {}",
                "  @Test(timeout = 5)",
                "  void b() {}",
                "}"));
        assertEquals(
                List.of("a", "b"),
                t.stream().skip(1).map(JavaTestScanner.TestTarget::methodName).toList());
    }

    @Test
    void stackedAnnotationsBetweenTestAndMethod() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "class T {", "  @ParameterizedTest", "  @ValueSource(ints = {1, 2})", "  void param(int x) {}", "}"));
        assertEquals(1, t.stream().skip(1).count());
        assertEquals("param", t.get(1).methodName());
    }

    @Test
    void noPackageYieldsBareClassName() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src("class T {", "  @Test void a() {}", "}"));
        assertEquals("T", t.get(0).className());
    }

    @Test
    void testInStringOrCommentIsNotATarget() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "class T {",
                "  // @Test",
                "  void notReal() {}",
                "  String s = \"@Test also here\";",
                "  @Test void real() {}",
                "}"));
        assertEquals(1, t.stream().skip(1).count());
        assertEquals("real", t.get(1).methodName());
    }

    private static List<String> names(List<JavaTestScanner.TestTarget> targets) {
        return targets.stream()
                .map(t -> t.line() + ":" + t.className() + (t.methodName() == null ? "" : "#" + t.methodName()))
                .toList();
    }

    @Test
    void nestedClassMethodsCarryTheNestedBinaryName() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "package com.x;", // 0
                "class Outer {", // 1
                "  @Nested", // 2
                "  class Inner {", // 3
                "    @Test", // 4
                "    void inner1() {}", // 5
                "  }", // 6
                "  @Test", // 7
                "  void outer1() {}", // 8
                "}"));
        assertEquals(
                List.of("1:com.x.Outer", "3:com.x.Outer$Inner", "5:com.x.Outer$Inner#inner1", "8:com.x.Outer#outer1"),
                names(t));
    }

    @Test
    void deeperNestingAndAnOuterClassWithNoTestsOfItsOwn() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "class Outer {", // 0
                "  @Nested class WhenEmpty", // 1
                "  {", // 2
                "    @Nested", // 3
                "    class AndClosed {", // 4
                "      @ParameterizedTest", // 5
                "      void rejects(int n) {}", // 6
                "    }", // 7
                "  }", // 8
                "  static class Fixture {", // 9 — no tests: no target
                "    void helper() {}", // 10
                "  }", // 11
                "}"));
        assertEquals(
                List.of(
                        "0:Outer",
                        "1:Outer$WhenEmpty",
                        "4:Outer$WhenEmpty$AndClosed",
                        "6:Outer$WhenEmpty$AndClosed#rejects"),
                names(t));
        assertTrue(t.get(3).dynamic());
    }

    @Test
    void anAnnotationOnANestedClassIsNotCarriedToItsFirstMethod() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "class Outer {",
                "  @Test",
                "  class NotAMethod {",
                "    void helper() {}",
                "  }",
                "  @Test void real() {}",
                "}"));
        assertEquals(List.of("0:Outer", "5:Outer#real"), names(t));
    }

    @Test
    void localAndAnonymousClassesAreNotWalked() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "class Outer {",
                "  @Test",
                "  void outer1() {",
                "    class Local {",
                "      @Test void notATest() {}",
                "    }",
                "    Runnable r = new Runnable() {",
                "      @Test public void run() {}",
                "    };",
                "  }",
                "  @Test void outer2() {}",
                "}"));
        assertEquals(List.of("0:Outer", "2:Outer#outer1", "10:Outer#outer2"), names(t));
    }

    @Test
    void nonTestClassYieldsEmpty() {
        assertTrue(JavaTestScanner.scan(src("class Plain {", "  void helper() {}", "}"))
                .isEmpty());
        assertTrue(JavaTestScanner.scan("").isEmpty());
        assertTrue(JavaTestScanner.scan(null).isEmpty());
    }

    @Test
    void nonAsciiTestAndClassNamesAreFound() {
        String src = """
                package demo;
                class GrößeTest {
                    @Test
                    void plainAscii() {}
                    @Test
                    void größeIstKorrekt() {}
                    @Test
                    void 正常系_ログインできる() {}
                    @Test
                    void test_ログイン() {}
                }
                """;
        java.util.List<JavaTestScanner.TestTarget> targets = JavaTestScanner.scan(src);
        assertEquals("demo.GrößeTest", targets.get(0).className());
        assertEquals(
                java.util.List.of("plainAscii", "größeIstKorrekt", "正常系_ログインできる", "test_ログイン"),
                targets.stream()
                        .skip(1)
                        .map(JavaTestScanner.TestTarget::methodName)
                        .toList());
    }

    // --- Java debug review 3, A205: the test a caret line means ------------------------------------------------

    private static final String MORE = """
            package com.example.shelf;

            import org.junit.jupiter.api.Nested;
            import org.junit.jupiter.api.Test;

            class MoreTest {

                @Test
                void outer() {
                    int a = 1;
                }

                @Nested
                class Inner {
                    @Test
                    void inner() {
                        int b = 2;
                    }
                }

                // the cases
                @org.junit.jupiter.params.ParameterizedTest
                @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
                void param(int x) {
                    int c = x;
                }

                void helper() {
                }
            }
            """;

    private static int lineOf(String text) {
        List<String> lines = MORE.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        throw new AssertionError(text);
    }

    private static String at(int line) {
        java.util.Map<Integer, JavaTestScanner.TestTarget> byLine = new java.util.HashMap<>();
        for (JavaTestScanner.TestTarget t : JavaTestScanner.scan(MORE)) {
            byLine.putIfAbsent(t.line(), t);
        }
        JavaTestScanner.TestTarget t = JavaTestScanner.targetAt(MORE, byLine, line);
        return t == null ? null : t.className() + (t.methodName() == null ? "" : "#" + t.methodName());
    }

    @Test
    void aTestsAnnotationsAndTheCommentAboveThemAreThatTests() {
        assertEquals("com.example.shelf.MoreTest#param", at(lineOf("@org.junit.jupiter.params.ParameterizedTest")));
        assertEquals("com.example.shelf.MoreTest#param", at(lineOf("ValueSource")));
        assertEquals("com.example.shelf.MoreTest#param", at(lineOf("// the cases")), "a comment above it");
        assertEquals("com.example.shelf.MoreTest", at(lineOf("// the cases") - 1), "a blank line: the class's");
        assertEquals("com.example.shelf.MoreTest#outer", at(lineOf("    @Test")));
        assertEquals("com.example.shelf.MoreTest$Inner", at(lineOf("@Nested")), "a nested class's annotation");
        assertEquals("com.example.shelf.MoreTest$Inner#inner", at(lineOf("        @Test")));
    }

    @Test
    void insideATestItIsThatTestAndAfterANestedClassItIsTheOuterClass() {
        assertEquals("com.example.shelf.MoreTest#outer", at(lineOf("int a = 1")));
        assertEquals("com.example.shelf.MoreTest$Inner#inner", at(lineOf("int b = 2")));
        assertEquals("com.example.shelf.MoreTest#param", at(lineOf("int c = x")));
        assertEquals("com.example.shelf.MoreTest", at(lineOf("void helper()")), "not the test above it");
        assertEquals("com.example.shelf.MoreTest", at(lineOf("class MoreTest")));
        assertEquals("com.example.shelf.MoreTest", at(lineOf("class MoreTest") + 1), "before the first test");
        assertNull(at(lineOf("import org.junit.jupiter.api.Test")), "outside the class");
        assertNull(JavaTestScanner.targetAt(MORE, java.util.Map.of(), 3));
    }
}

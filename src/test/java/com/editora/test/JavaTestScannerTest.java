package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JUnit test detection: annotation kinds, FQCN derivation, comment/string safety, nested-class skipping. */
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

    @Test
    void nestedClassMethodsAreSkippedButOuterFound() {
        List<JavaTestScanner.TestTarget> t = JavaTestScanner.scan(src(
                "class Outer {",
                "  @Nested",
                "  class Inner {",
                "    @Test",
                "    void inner1() {}",
                "  }",
                "  @Test",
                "  void outer1() {}",
                "}"));
        assertEquals(
                List.of("outer1"),
                t.stream().skip(1).map(JavaTestScanner.TestTarget::methodName).toList());
        assertEquals("Outer", t.get(0).className());
    }

    @Test
    void nonTestClassYieldsEmpty() {
        assertTrue(JavaTestScanner.scan(src("class Plain {", "  void helper() {}", "}"))
                .isEmpty());
        assertTrue(JavaTestScanner.scan("").isEmpty());
        assertTrue(JavaTestScanner.scan(null).isEmpty());
    }
}

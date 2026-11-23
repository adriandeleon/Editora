package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link JavaTestScanner#scan(String, String)} on Kotlin and Groovy test files. */
class JvmTestScannerDialectsTest {

    private static JavaTestScanner.TestTarget t(int line, String cls, String method) {
        return new JavaTestScanner.TestTarget(line, cls, method, false);
    }

    @Test
    void kotlinTestsWithBacktickNamesNestedClassesAndNoSemicolons() {
        String kt = """
                package demo.calc

                import org.junit.jupiter.api.Test

                class CalcTest {
                    @Test
                    fun `adds two`() {
                        val s = "not { a brace"
                    }

                    @Test fun subtracts() {}

                    fun helper() {}

                    @Nested
                    inner class Inner {
                        @org.junit.jupiter.api.Test
                        fun multiplies() {}
                    }

                    companion object {
                        fun util() {}
                    }
                }
                """;
        assertEquals(
                List.of(
                        t(4, "demo.calc.CalcTest", null),
                        t(6, "demo.calc.CalcTest", "adds two"),
                        t(10, "demo.calc.CalcTest", "subtracts"),
                        t(15, "demo.calc.CalcTest$Inner", null),
                        t(17, "demo.calc.CalcTest$Inner", "multiplies")),
                JavaTestScanner.scan("kotlin", kt));
    }

    @Test
    void aKotestSpecIsATestClassAsAWhole() {
        String kt = """
                package demo

                class MathSpec : StringSpec({
                    "adds" { 1 + 1 shouldBe 2 }
                })
                """;
        assertEquals(List.of(t(2, "demo.MathSpec", null)), JavaTestScanner.scan("kotlin", kt));
        assertEquals(List.of(), JavaTestScanner.scan("kotlin", "package demo\n\nclass Plain {\n    fun f() {}\n}\n"));
    }

    @Test
    void groovyJunitTestsAndSpockFeatures() {
        String groovy = """
                package demo;

                class ShapeTest {
                    @Test
                    void area() {}

                    @Test
                    def perimeter() {}
                }
                """;
        assertEquals(
                List.of(
                        t(2, "demo.ShapeTest", null),
                        t(4, "demo.ShapeTest", "area"),
                        t(7, "demo.ShapeTest", "perimeter")),
                JavaTestScanner.scan("groovy", groovy));
        String spock = """
                package demo

                class MathSpec extends Specification {
                    def "adds two numbers"() {
                        expect:
                        1 + 1 == 2
                    }

                    def 'doubles'() {
                        expect: 2 * 2 == 4
                    }

                    def helper() {}
                }
                """;
        assertEquals(
                List.of(
                        t(2, "demo.MathSpec", null),
                        t(3, "demo.MathSpec", "adds two numbers"),
                        t(8, "demo.MathSpec", "doubles")),
                JavaTestScanner.scan("groovy", spock));
    }

    @Test
    void onlyTheJvmLanguagesWithBuildRunTestsAreScanned() {
        assertTrue(JavaTestScanner.supports("kotlin"));
        assertTrue(JavaTestScanner.supports("groovy"));
        assertFalse(JavaTestScanner.supports("scala"), "Metals debugs Scala, not Editora's JVM adapter");
        assertEquals(List.of(), JavaTestScanner.scan("scala", "class A { @Test def a(): Unit = () }"));
        assertEquals("kotlin", JavaTestScanner.languageOf("CalcTest.kt"));
        assertEquals("groovy", JavaTestScanner.languageOf("MathSpec.groovy"));
        assertNull(JavaTestScanner.languageOf("build.gradle.kts"));
        assertTrue(TestSourceLocator.isTestSourcePath("src/test/kotlin/demo/CalcTest.kt"));
        assertFalse(TestSourceLocator.isTestSourcePath("src/main/kotlin/demo/Calc.kt"));
    }
}

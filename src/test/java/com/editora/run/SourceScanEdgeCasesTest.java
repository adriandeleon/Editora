package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.build.BuildTool;
import com.editora.test.JavaTestScanner;
import com.editora.test.TestRunRecognizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source text that only looks like a main method or a test — inside a block comment, a text block, a
 * string or a char literal — and the argument lists the test-run recognizer has to tell apart.
 */
class SourceScanEdgeCasesTest {

    // --- MainMethodScanner ------------------------------------------------------------------------------

    @Test
    void aMainInsideABlockCommentOrATextBlockIsNotAnEntryPoint() {
        String source = """
                package demo;

                public class App {
                    /*
                    public static void main(String[] args) {}
                    */
                    String doc = \"\"\"
                        public static void main(String[] args) {}
                        \"\"\";
                    String one = "static void main(String[] a) { \\" }";
                    char brace = '{';
                    char quote = '\\'';

                    public static void main(String... args) {} // the real one
                }
                """;

        List<MainMethodScanner.MainMethod> mains = MainMethodScanner.scan(source);

        assertEquals(List.of(new MainMethodScanner.MainMethod(13, "demo.App")), mains);
    }

    @Test
    void aCommentOrLiteralLeftOpenAtTheEndOfTheFileDoesNotBreakTheScan() {
        String method = "class A {\n  public static void main(String[] a) {}\n";

        assertEquals(1, MainMethodScanner.scan(method + "  /* never closed").size());
        assertEquals(
                1,
                MainMethodScanner.scan(method + "  String s = \"never closed").size());
        assertEquals(
                1,
                MainMethodScanner.scan(method + "  String t = \"\"\"\n never closed")
                        .size());
        assertEquals(
                1,
                MainMethodScanner.scan(method + "  // a line comment with no newline")
                        .size());
    }

    @Test
    void aMainOutsideAnyClassOrAfterStrayClosingBracesIsNotReported() {
        assertEquals(List.of(), MainMethodScanner.scan("static void main(String[] args) {}\n"));
        // More closers than openers: the depth is held at the top level rather than going negative, so the
        // class that follows is still read as a top-level one.
        String unbalanced = "}\n}\nclass Late {\n  static void main(String[] args) {}\n}\n";
        assertEquals(List.of(new MainMethodScanner.MainMethod(3, "Late")), MainMethodScanner.scan(unbalanced));
        assertEquals(List.of(), MainMethodScanner.scan(null));
        assertEquals(List.of(), MainMethodScanner.scan("  \n"));
    }

    @Test
    void aMainNestedInAMethodOrAnInnerClassIsNotATopLevelEntryPoint() {
        String source = """
                class Outer {
                    static class Inner {
                        public static void main(String[] args) {}
                    }
                    void run() {
                        Runnable r = () -> { };
                    }
                }
                """;

        assertEquals(List.of(), MainMethodScanner.scan(source));
    }

    // --- JavaTestScanner --------------------------------------------------------------------------------

    @Test
    void aTestInsideACommentOrATextBlockIsNotATarget() {
        String source = """
                package demo;

                class CalcTest {
                    /*
                    @Test
                    void commentedOut() {}
                    */
                    String sample = \"\"\"
                        @Test
                        void insideText() {}
                        \"\"\";
                    String s = "@Test void inString() { \\" }";
                    char c = '{';
                    char q = '\\'';

                    @Test
                    void adds() {}
                }
                """;

        List<JavaTestScanner.TestTarget> targets = JavaTestScanner.scan(source);

        assertEquals(2, targets.size(), targets.toString());
        assertEquals("demo.CalcTest", targets.get(0).className());
        assertEquals(null, targets.get(0).methodName(), "the class itself comes first");
        assertEquals("adds", targets.get(1).methodName());
        assertEquals(16, targets.get(1).line());
    }

    @Test
    void anUnterminatedCommentOrLiteralEndsTheScanWithWhatWasFound() {
        String test = "class T {\n  @Test\n  void runs() {}\n";

        assertEquals(2, JavaTestScanner.scan(test + "  /* never closed").size());
        assertEquals(
                2, JavaTestScanner.scan(test + "  String s = \"never closed").size());
        assertEquals(
                2,
                JavaTestScanner.scan(test + "  String t = \"\"\"\n never closed")
                        .size());
        assertEquals(2, JavaTestScanner.scan(test + "  // trailing comment").size());
    }

    @Test
    void aFileWithNoTestsOrNoClassHasNoTargets() {
        assertEquals(List.of(), JavaTestScanner.scan("class Plain {\n  void helper() {}\n}\n"));
        assertEquals(List.of(), JavaTestScanner.scan("@Test\nvoid loose() {}\n"), "an annotation outside any class");
        assertEquals(List.of(), JavaTestScanner.scan(null));
        assertEquals(List.of(), JavaTestScanner.scan(" \n"));
    }

    @Test
    void aNestedClassWithTestsIsATargetOfItsOwnAndOneWithoutIsNot() {
        String source = """
                class OuterTest {
                    @Nested
                    class Inner {
                        @Test
                        void one() {}
                    }

                    static class Fixture {
                        void helper() {}
                    }

                    @Test
                    void two() {}
                }
                """;

        List<JavaTestScanner.TestTarget> targets = JavaTestScanner.scan(source);

        assertEquals(
                List.of("OuterTest:null", "OuterTest$Inner:null", "OuterTest$Inner:one", "OuterTest:two"),
                targets.stream().map(t -> t.className() + ":" + t.methodName()).toList());
        assertEquals(
                List.of(0, 2, 4, 12),
                targets.stream().map(JavaTestScanner.TestTarget::line).toList());
    }

    // --- TestRunRecognizer ------------------------------------------------------------------------------

    @Test
    void aTestRunIsRecognizedByItsTaskNotByItsFlags() {
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.MAVEN, null));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.MAVEN, List.of()));
        assertTrue(TestRunRecognizer.isTestRun(BuildTool.MAVEN, List.of("-q", "integration-test")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.MAVEN, List.of("package")));

        assertTrue(TestRunRecognizer.isTestRun(BuildTool.GRADLE, List.of("integrationTest")));
        assertTrue(TestRunRecognizer.isTestRun(BuildTool.GRADLE, List.of("--info", "check")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.GRADLE, List.of("--tests", "-Dtest")), "flags only");
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.GRADLE, List.of("build")));

        assertTrue(TestRunRecognizer.isTestRun(BuildTool.NPM, List.of("run", "test:unit")));
        assertTrue(TestRunRecognizer.isTestRun(BuildTool.NPM, List.of("test")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.NPM, List.of("run", "build")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.NPM, List.of("test", "--watch")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.NPM, List.of("run")));

        assertTrue(TestRunRecognizer.isTestRun(BuildTool.CARGO, List.of("--quiet", "test")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.CARGO, List.of("--quiet", "--release")), "no task at all");
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.CARGO, List.of("build")));
        assertFalse(TestRunRecognizer.isTestRun(BuildTool.GO, List.of("vet", "test")));
    }

    @Test
    void everyToolHasADefaultTestTaskThatIsItselfRecognizedAsATestRun() {
        assertEquals(List.of("test"), TestRunRecognizer.defaultTestTask(BuildTool.MAVEN));
        assertEquals(List.of("test"), TestRunRecognizer.defaultTestTask(BuildTool.GRADLE));
        assertEquals(List.of("test"), TestRunRecognizer.defaultTestTask(BuildTool.CARGO));
        assertEquals(List.of("run", "test"), TestRunRecognizer.defaultTestTask(BuildTool.NPM));
        assertEquals(List.of("test", "./..."), TestRunRecognizer.defaultTestTask(BuildTool.GO));
        for (BuildTool tool : BuildTool.values()) {
            assertTrue(TestRunRecognizer.isTestRun(tool, TestRunRecognizer.defaultTestTask(tool)), tool.toString());
        }
    }

    @Test
    void goArgumentsWithoutATestTokenAreLeftAsTheyAre() {
        List<String> build = List.of("go", "build", "./...");

        assertSame(build, TestRunRecognizer.augmentArgv(BuildTool.GO, build));
        assertFalse(TestRunRecognizer.isFilteredRun(BuildTool.MAVEN, null));
        assertFalse(TestRunRecognizer.isFilteredRun(BuildTool.GO, List.of("test", "-run", "TestX")));
        assertEquals(
                List.of("test", "--tests", "demo.CalcTest"),
                TestRunRecognizer.singleTestTask(BuildTool.GRADLE, "demo.CalcTest", ""),
                "an empty method name means the whole class");
        assertEquals(List.of(), TestRunRecognizer.singleTestTask(BuildTool.CARGO, "demo.CalcTest", "adds"));
    }

    // --- JdkToolchain -----------------------------------------------------------------------------------

    @Test
    void aSelectedJdkGoesFirstOnThePathAndABlankOneChangesNothing(@TempDir Path home) throws Exception {
        String sep = java.io.File.pathSeparator;
        Path bin = java.nio.file.Files.createDirectories(home.resolve("bin"));

        assertEquals(Map.of(), JdkToolchain.environment(" ", "/usr/bin"));
        Map<String, String> env = JdkToolchain.environment(home.toString(), "/usr/bin");
        assertEquals(home.toString(), env.get("JAVA_HOME"));
        assertTrue(env.containsValue(bin + sep + "/usr/bin"), env.toString());
        assertTrue(JdkToolchain.environment(home.toString(), " ").containsValue(bin.toString()), "no inherited PATH");
        assertTrue(JdkToolchain.environment(home.toString(), null).containsValue(bin.toString()));

        assertEquals("javac", JdkToolchain.compilerForJavaExecutable(null));
        assertEquals("javac", JdkToolchain.compilerForJavaExecutable(" "));
        assertEquals("javac", JdkToolchain.compilerForJavaExecutable("java"), "a bare name has no folder to look in");
        assertEquals(
                bin,
                Path.of(JdkToolchain.compilerForJavaExecutable(
                                bin.resolve("java").toString()))
                        .getParent());
        assertEquals("", JdkToolchain.javaExecutable(null));
        assertEquals("/global", JdkToolchain.effectiveHome(" ", " /global "));
        assertEquals("/own", JdkToolchain.effectiveHome("/own", "/global"));
    }
}

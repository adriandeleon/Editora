package com.editora.editor;

import java.util.List;

import com.editora.text.ShellFiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure run-target scan behind the gutter Run glyphs — what {@code EditorBuffer} now evaluates off-thread. */
class RunScanTest {

    private static RunScan.Inputs inputs(String language, String name) {
        return new RunScan.Inputs(true, false, false, false, language, name, false, false);
    }

    @Test
    void nothingIsScannedWhenNoFeatureApplies() {
        RunScan.Inputs none = new RunScan.Inputs(false, false, false, false, "java", "A.java", false, false);
        assertFalse(none.needsText());
        RunScan.Result r = RunScan.scan(none, "");
        assertFalse(r.runnable());
        assertEquals(-1, r.line());
        assertTrue(r.httpLines().isEmpty() && r.makeTargets().isEmpty());
        assertTrue(r.testLines().isEmpty() && r.mainLines().isEmpty());
    }

    @Test
    void aPythonScriptRunsFromItsMainGuardElseItsFirstLine() {
        assertEquals(0, RunScan.scan(inputs("python", "a.py"), "print(1)\n").line());
        RunScan.Result guarded =
                RunScan.scan(inputs("python", "a.py"), "import x\n\nif __name__ == \"__main__\":\n    x.go()\n");
        assertTrue(guarded.runnable());
        assertEquals(2, guarded.line());
        assertEquals(2, RunScan.pythonRunLine("a\nb\n  if __name__ == '__main__':\n"));
    }

    /** Node.js: every script runs from the top; a declaration file holds no code; JSX/TSX need a bundler. */
    @Test
    void aNodeScriptRunsFromItsFirstLine() {
        for (String[] c : new String[][] {
            {"javascript", "app.js"}, {"javascript", "app.mjs"}, {"javascript", "app.cjs"},
            {"typescript", "main.ts"}, {"typescript", "main.mts"}, {"typescript", "main.cts"},
            {"javascript", "tool"}, {"javascript", "a.test.js"}
        }) {
            RunScan.Result r = RunScan.scan(inputs(c[0], c[1]), "console.log(1)\n");
            assertTrue(r.runnable(), c[1]);
            assertEquals(0, r.line(), c[1]);
        }
        assertFalse(RunScan.scan(inputs("typescript", "types.d.ts"), "export type A = 1;\n")
                .runnable());
        assertFalse(RunScan.scan(inputs("typescriptreact", "App.tsx"), "x\n").runnable());
        assertFalse(RunScan.scan(inputs("javascriptreact", "App.jsx"), "x\n").runnable());
        RunScan.Inputs off = new RunScan.Inputs(false, false, false, false, "javascript", "app.js", false, false);
        assertFalse(RunScan.scan(off, "x\n").runnable(), "the Run feature off: no ▶");
    }

    @Test
    void aClojureFileRunsFromItsMainOrTheTopButDataAndClojureScriptNeverRun() {
        RunScan.Result ns = RunScan.scan(
                inputs("clojure", "core.clj"), "(ns my-app.core)\n\n(defn -main [& args]\n  (println args))\n");
        assertTrue(ns.runnable());
        assertEquals(2, ns.line(), "the marker sits on (defn -main");
        RunScan.Result script = RunScan.scan(inputs("clojure", "tally.clj"), "(println (+ 1 2))\n");
        assertTrue(script.runnable());
        assertEquals(0, script.line());
        assertEquals(
                0, RunScan.scan(inputs("clojure", "tasks.bb"), "(println 1)\n").line());
        assertEquals(
                0,
                RunScan.scan(inputs("clojure", "deploy"), "#!/usr/bin/env bb\n(println 1)\n")
                        .line());
        for (String data : List.of("deps.edn", "bb.edn", "project.clj", "app.cljs", "config.edn")) {
            assertFalse(
                    RunScan.scan(inputs("clojure", data), "{:paths [\"src\"]}\n")
                            .runnable(),
                    data);
        }
    }

    @Test
    void aGroovyScriptRunsFromTheTopOrItsMainButABuildScriptNeverRuns() {
        RunScan.Result script = RunScan.scan(inputs("groovy", "report.groovy"), "println 'hi'\n");
        assertTrue(script.runnable());
        assertEquals(0, script.line());
        assertEquals(
                2,
                RunScan.scan(inputs("groovy", "App.groovy"), "class App {\n\n  static void main(String[] a) {}\n}\n")
                        .line());
        assertFalse(RunScan.scan(inputs("groovy", "build.gradle"), "plugins { id 'java' }\n")
                .runnable());
        assertFalse(
                RunScan.scan(inputs("groovy", "Jenkinsfile"), "pipeline { }\n").runnable());
    }

    @Test
    void aScalaFileRunsAtItsMainAScriptFromTheTopAndABuildNever() {
        RunScan.Result main = RunScan.scan(
                inputs("scala", "Main.scala"), "package demo\n\n@main def hello(): Unit =\n  println(1)\n");
        assertTrue(main.runnable());
        assertEquals(2, main.line());
        assertEquals(
                0, RunScan.scan(inputs("scala", "hello.sc"), "println(1)\n").line());
        assertFalse(RunScan.scan(inputs("scala", "Util.scala"), "object Util\n").runnable());
        assertFalse(RunScan.scan(inputs("scala", "build.sbt"), "lazy val root = project\n")
                .runnable());
    }

    @Test
    void aKotlinScriptRunsFromTheTopAProgramFromItsMainAndABuildScriptNever() {
        RunScan.Result script = RunScan.scan(inputs("kotlin", "tool.main.kts"), "println(\"hi\")\n");
        assertTrue(script.runnable());
        assertEquals(0, script.line());
        RunScan.Result program = RunScan.scan(
                inputs("kotlin", "Hello.kt"), "package demo\n\nfun greet() = 1\n\nfun main(args: Array<String>) {}\n");
        assertTrue(program.runnable());
        assertEquals(4, program.line());
        assertFalse(
                RunScan.scan(inputs("kotlin", "Util.kt"), "fun twice(x: Int) = 2 * x\n")
                        .runnable(),
                "a .kt without a top-level main is not a program");
        assertFalse(RunScan.scan(inputs("kotlin", "build.gradle.kts"), "plugins { kotlin(\"jvm\") }\n")
                .runnable());
        assertFalse(RunScan.scan(inputs("kotlin", "settings.gradle.kts"), "rootProject.name = \"x\"\n")
                .runnable());
    }

    @Test
    void aShellScriptIsRunnableWheneverRunIsOn() {
        // No LSP or Bash-server condition any more: like Python, only the Run feature gates it.
        RunScan.Result r = RunScan.scan(inputs("shell", "a.sh"), "echo hi\n");
        assertTrue(r.runnable());
        assertEquals(0, r.line(), "the whole script runs from the top");
        RunScan.Inputs off = new RunScan.Inputs(false, false, false, false, "shell", "a.sh", false, false);
        assertFalse(RunScan.scan(off, "echo hi\n").runnable());
    }

    @Test
    void aMakefileGetsOneGlyphPerTarget() {
        RunScan.Result r = RunScan.scan(inputs("makefile", "Makefile"), "all: build\n\t@echo hi\n\nbuild:\n\tcc x.c\n");
        assertTrue(r.runnable());
        assertEquals(-1, r.line(), "a Makefile uses the target map, not one entry line");
        assertEquals(List.of("all", "build"), List.copyOf(r.makeTargets().values()));
        assertEquals(List.of(0, 3), List.copyOf(r.makeTargets().keySet()));
        assertFalse(RunScan.scan(inputs("makefile", "Makefile"), "# nothing\n").runnable());
    }

    @Test
    void aCompactJavaSourceRunsFromItsMain() {
        RunScan.Result r = RunScan.scan(inputs("java", "Hello.java"), "// hi\nvoid main() {\n    IO.println(1);\n}\n");
        assertTrue(r.runnable());
        assertEquals(1, r.line());
        assertFalse(RunScan.scan(inputs("java", "Hello.java"), "class A {}\n").runnable());
        // An extensionless script needs the java shebang to count.
        assertFalse(RunScan.scan(inputs("java", "hello"), "void main() {}\n").runnable());
        RunScan.Inputs shebang = new RunScan.Inputs(true, false, false, false, "java", "hello", true, false);
        assertTrue(RunScan.scan(shebang, "#!/usr/bin/env -S java --source 25\nvoid main() {}\n")
                .runnable());
    }

    @Test
    void aClassicSingleFileProgramRunsFromItsFirstClassMain() {
        String hello = "public class Hello {\n    public static void main(String[] args) {\n    }\n}\n";
        RunScan.Result r = RunScan.scan(inputs("java", "Hello.java"), hello);
        assertTrue(r.runnable(), "java Hello.java runs it");
        assertEquals(1, r.line());
        assertTrue(r.classicJava());
        assertEquals(11, r.javaRelease(), "a public static main runs on JDK 11's source launcher");

        // Not public: only JDK 25's launcher takes it.
        RunScan.Result quiet =
                RunScan.scan(inputs("java", "Hello.java"), "class Hello {\n    static void main(String[] a) {}\n}\n");
        assertTrue(quiet.runnable());
        assertEquals(25, quiet.javaRelease());

        // A class of a Maven or Gradle project runs through the build (its project ▶), never as one file.
        RunScan.Inputs inBuild = new RunScan.Inputs(true, false, false, false, "java", "Hello.java", false, true);
        assertFalse(RunScan.scan(inBuild, hello).runnable());

        // The launcher runs the first class: a main in a later one, or none at all, is no program.
        assertFalse(RunScan.scan(
                        inputs("java", "Two.java"),
                        "class Util {}\nclass Two {\n    public static void main(String[] a) {}\n}\n")
                .runnable());
        assertFalse(RunScan.scan(inputs("java", "Lib.java"), "public class Lib {\n    int x;\n}\n")
                .runnable());

        // A compact source keeps its own kind and JDK.
        RunScan.Result compact = RunScan.scan(inputs("java", "C.java"), "void main() {}\n");
        assertFalse(compact.classicJava());
        assertEquals(25, compact.javaRelease());
    }

    @Test
    void testAndMainGlyphsAreAdditive() {
        String test = "import org.junit.jupiter.api.Test;\nclass ATest {\n    @Test\n    void works() {}\n}\n";
        RunScan.Inputs tests = new RunScan.Inputs(false, false, true, false, "java", "ATest.java", false, false);
        RunScan.Result r = RunScan.scan(tests, test);
        assertFalse(r.testLines().isEmpty());
        assertTrue(r.runnable(), "a file with tests is runnable even with the run feature off");

        String main = "class A {\n    public static void main(String[] args) {}\n}\n";
        RunScan.Inputs mains = new RunScan.Inputs(false, false, false, true, "java", "A.java", false, false);
        RunScan.Result m = RunScan.scan(mains, main);
        assertFalse(m.mainLines().isEmpty());
        assertFalse(m.runnable(), "a project main does not make the file runnable as one file");
    }

    @Test
    void aSourcedStartupFileHasNoRunGlyph() {
        // One stray click on .xinitrc's ▶ started a window manager; .zshrc was run by bash.
        for (String name : List.of(
                ".bashrc",
                ".bash_logout",
                ".profile",
                ".zshrc",
                ".zlogout",
                ".xinitrc",
                ".envrc",
                "work.bashrc",
                "profile")) {
            assertFalse(RunScan.scan(inputs("shell", name), "echo hi\n").runnable(), name);
            assertTrue(ShellFiles.isStartupFile("/home/u/" + name), name);
        }
        assertTrue(RunScan.scan(inputs("shell", "deploy.sh"), "echo hi\n").runnable());
        assertTrue(RunScan.scan(inputs("shell", "mvnw"), "echo hi\n").runnable(), "a wrapper script is run");
        assertFalse(ShellFiles.isStartupFile("install.sh"));
    }

    @Test
    void theMainGuardInAnySpellingButNotInsideAString() {
        // C14: a guard quoted in a docstring took the ▶; reversed or parenthesised guards fell back to line 1.
        assertEquals(
                6,
                RunScan.pythonRunLine(
                        "a.py",
                        "\"\"\"Usage:\nif __name__ == \"__main__\":\n\"\"\"\nimport sys\n\n\nif __name__ == \"__main__\":\n"));
        assertEquals(1, RunScan.pythonRunLine("a.py", "x = 1\nif '__main__' == __name__:\n"));
        assertEquals(1, RunScan.pythonRunLine("a.py", "x = 1\nif (__name__ == \"__main__\"):\n"));
        assertEquals(1, RunScan.pythonRunLine("a.py", "x = 1\nif __name__ == \"__main__\" and not DEBUG:\n"));
        assertEquals(
                2,
                RunScan.pythonRunLine("a.py", "x = 1\n# if __name__ == \"__main__\":\nif __name__ == '__main__':\n"));
    }

    @Test
    void stubsAndImportedFilesGetNoRunGlyph() {
        // A13: .pyi stubs, __init__.py and conftest.py all showed ▶ on line 1.
        assertFalse(RunScan.scan(inputs("python", "mod.pyi"), "def f(x: int) -> str: ...\n")
                .runnable());
        assertFalse(RunScan.scan(inputs("python", "__init__.py"), "from .a import b\n")
                .runnable());
        assertFalse(
                RunScan.scan(inputs("python", "conftest.py"), "import pytest\n").runnable());
        assertEquals(
                1,
                RunScan.scan(inputs("python", "__init__.py"), "x = 1\nif __name__ == \"__main__\":\n")
                        .line());
        assertEquals(
                0, RunScan.scan(inputs("python", "__main__.py"), "print(1)\n").line());
    }

    @Test
    void aTestFileGetsAPytestGlyphPerTestAndOneForTheFile() {
        // C10: ▶ on test_x.py ran it as a script — no test ran, exit code 0.
        String text =
                "import pytest\n\n\ndef test_add():\n    assert 1 == 2\n\n\nclass TestShop:\n    def test_buy(self):\n        pass\n";
        RunScan.Result r = RunScan.scan(inputs("python", "test_shop.py"), text);
        assertTrue(r.runnable());
        assertEquals(-1, r.line(), "no plain script ▶");
        assertEquals(List.of(0, 3, 7, 8), List.copyOf(r.testLines().keySet()));
        assertEquals("", r.testLines().get(0).className());
        assertEquals(null, r.testLines().get(0).methodName(), "line 1: the whole file");
        assertEquals("test_add", r.testLines().get(3).methodName());
        assertEquals("TestShop", r.testLines().get(7).className());
        assertEquals("test_buy", r.testLines().get(8).methodName());
        assertEquals(
                0, RunScan.scan(inputs("python", "test_empty.py"), "x = 1\n").line(), "no tests: a script");
    }

    /** A C, C++, Go or Rust file with a program's main is run by Run File, as Debug File debugs it. */
    @Test
    void aCompiledFileWithAMainIsRunnableAtIt() {
        RunScan.Result c = RunScan.scan(inputs("c", "tally.c"), "#include <stdio.h>\n\nint main(void) {\n}\n");
        assertTrue(c.runnable());
        assertEquals(2, c.line());
        assertFalse(RunScan.scan(inputs("c", "lib.c"), "int add(int a) { return a; }\n")
                .runnable());
        assertTrue(RunScan.scan(inputs("go", "a.go"), "package main\nfunc main() {}\n")
                .runnable());
        assertTrue(RunScan.scan(inputs("rust", "main.rs"), "fn main() {}\n").runnable());
        RunScan.Inputs off = new RunScan.Inputs(false, false, false, false, "c", "tally.c", false, false);
        assertFalse(RunScan.scan(off, "int main(void) {}\n").runnable(), "the Run feature off");
    }

    @Test
    void aCppTestFileHasAGlyphPerTestAndOneForTheWholeFile() {
        String gtest = "#include <gtest/gtest.h>\n\nTEST(Math, Adds) {\n}\nTEST(Math, Fails) {}\n";
        RunScan.Result r = RunScan.scan(inputs("cpp", "calc_test.cpp"), gtest);
        assertTrue(r.runnable(), "Run File runs the tests, though the file has no main");
        assertEquals(List.of(0, 2, 4), List.copyOf(r.testLines().keySet()));
        assertEquals("Math.Fails", r.testLines().get(4).methodName());
        assertEquals("GTEST", r.testLines().get(4).className());
        assertEquals(null, r.testLines().get(0).methodName(), "the whole file");
        RunScan.Result plain = RunScan.scan(inputs("cpp", "main.cpp"), "int main() {}\n");
        assertTrue(plain.testLines().isEmpty(), "a program: its main's ▶ only");
        assertEquals(0, plain.line());
    }

    @Test
    void aPowerShellScriptRunsFromTheTopAndAModuleDoesNot() {
        RunScan.Result script = RunScan.scan(inputs("powershell", "build.ps1"), "param($x)\nWrite-Output $x\n");
        assertTrue(script.runnable());
        assertEquals(0, script.line());
        assertFalse(RunScan.scan(inputs("powershell", "Tools.psm1"), "function A {}\n")
                .runnable());
    }
}

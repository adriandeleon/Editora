package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure run-target scan behind the gutter Run glyphs — what {@code EditorBuffer} now evaluates off-thread. */
class RunScanTest {

    private static RunScan.Inputs inputs(String language, String name) {
        return new RunScan.Inputs(true, false, false, false, language, name, false);
    }

    @Test
    void nothingIsScannedWhenNoFeatureApplies() {
        RunScan.Inputs none = new RunScan.Inputs(false, false, false, false, "java", "A.java", false);
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

    @Test
    void aShellScriptIsRunnableWheneverRunIsOn() {
        // No LSP or Bash-server condition any more: like Python, only the Run feature gates it.
        RunScan.Result r = RunScan.scan(inputs("shell", "a.sh"), "echo hi\n");
        assertTrue(r.runnable());
        assertEquals(0, r.line(), "the whole script runs from the top");
        RunScan.Inputs off = new RunScan.Inputs(false, false, false, false, "shell", "a.sh", false);
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
        RunScan.Inputs shebang = new RunScan.Inputs(true, false, false, false, "java", "hello", true);
        assertTrue(RunScan.scan(shebang, "#!/usr/bin/env -S java --source 25\nvoid main() {}\n")
                .runnable());
    }

    @Test
    void testAndMainGlyphsAreAdditive() {
        String test = "import org.junit.jupiter.api.Test;\nclass ATest {\n    @Test\n    void works() {}\n}\n";
        RunScan.Inputs tests = new RunScan.Inputs(false, false, true, false, "java", "ATest.java", false);
        RunScan.Result r = RunScan.scan(tests, test);
        assertFalse(r.testLines().isEmpty());
        assertTrue(r.runnable(), "a file with tests is runnable even with the run feature off");

        String main = "class A {\n    public static void main(String[] args) {}\n}\n";
        RunScan.Inputs mains = new RunScan.Inputs(false, false, false, true, "java", "A.java", false);
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
}

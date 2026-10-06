package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure run-target scan behind the gutter Run glyphs — what {@code EditorBuffer} now evaluates off-thread. */
class RunScanTest {

    private static RunScan.Inputs inputs(String language, String name) {
        return new RunScan.Inputs(true, false, false, false, language, name, true, false);
    }

    @Test
    void nothingIsScannedWhenNoFeatureApplies() {
        RunScan.Inputs none = new RunScan.Inputs(false, false, false, false, "java", "A.java", true, false);
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
    void aShellScriptIsRunnableOnlyWhenShellRunIsOn() {
        assertTrue(RunScan.scan(inputs("shell", "a.sh"), "echo hi\n").runnable());
        RunScan.Inputs off = new RunScan.Inputs(true, false, false, false, "shell", "a.sh", false, false);
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
        RunScan.Inputs shebang = new RunScan.Inputs(true, false, false, false, "java", "hello", true, true);
        assertTrue(RunScan.scan(shebang, "#!/usr/bin/env -S java --source 25\nvoid main() {}\n")
                .runnable());
    }

    @Test
    void testAndMainGlyphsAreAdditive() {
        String test = "import org.junit.jupiter.api.Test;\nclass ATest {\n    @Test\n    void works() {}\n}\n";
        RunScan.Inputs tests = new RunScan.Inputs(false, false, true, false, "java", "ATest.java", true, false);
        RunScan.Result r = RunScan.scan(tests, test);
        assertFalse(r.testLines().isEmpty());
        assertTrue(r.runnable(), "a file with tests is runnable even with the run feature off");

        String main = "class A {\n    public static void main(String[] args) {}\n}\n";
        RunScan.Inputs mains = new RunScan.Inputs(false, false, false, true, "java", "A.java", true, false);
        RunScan.Result m = RunScan.scan(mains, main);
        assertFalse(m.mainLines().isEmpty());
        assertFalse(m.runnable(), "a project main does not make the file runnable as one file");
    }
}

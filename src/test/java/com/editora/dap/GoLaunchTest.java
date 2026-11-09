package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Delve is asked to debug for a Go file, and to attach to. */
class GoLaunchTest {

    private static final String TESTS = """
            package calc

            import "testing"

            func helper() int { return 1 }

            func TestAdd(t *testing.T) {
            \tif add(1, 2) != 3 {
            \t\tt.Fatal("add")
            \t}
            }

            func BenchmarkAdd(b *testing.B) {
            \tfor range b.N {
            \t\tadd(1, 2)
            \t}
            }

            func (s *suite) TestMethod() {}
            """;

    @Test
    void theTestAtTheCaretIsTheTopLevelTestFunctionAroundIt() {
        assertEquals("TestAdd", GoLaunch.testAt(TESTS, 6), "on its declaration");
        assertEquals("TestAdd", GoLaunch.testAt(TESTS, 8), "in its body");
        assertEquals("TestAdd", GoLaunch.testAt(TESTS, 10), "on its closing brace");
        assertNull(GoLaunch.testAt(TESTS, 11), "between functions");
        assertNull(GoLaunch.testAt(TESTS, 4), "in a helper");
        assertNull(GoLaunch.testAt(TESTS, 0), "above every function");
        assertEquals("BenchmarkAdd", GoLaunch.testAt(TESTS, 14));
        assertNull(GoLaunch.testAt(TESTS, 18), "a method named Test… is no test function");
        assertNull(GoLaunch.testAt(TESTS, 99));
        assertNull(GoLaunch.testAt(TESTS, -1));
    }

    @Test
    void aTestRunsByNameAndABenchmarkWithoutTheTests() {
        assertEquals(List.of("-test.run", "^TestAdd$"), GoLaunch.testArgs("TestAdd"));
        assertEquals(List.of("-test.run", "^FuzzParse$"), GoLaunch.testArgs("FuzzParse"), "its seed corpus");
        assertEquals(List.of("-test.run", "^$", "-test.bench", "^BenchmarkAdd$"), GoLaunch.testArgs("BenchmarkAdd"));
    }

    @Test
    void aModulesFileDebugsItsPackageAndAnotherFileItself(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("mod/cmd/app"));
        Files.writeString(dir.resolve("mod/go.mod"), "module example.com/mod\n");
        Path main = Files.writeString(pkg.resolve("main.go"), "package main\n");
        assertEquals(dir.resolve("mod").toAbsolutePath(), GoLaunch.moduleRoot(main));
        Path loose = Files.writeString(dir.resolve("hello.go"), "package main\n");
        assertNull(GoLaunch.moduleRoot(loose));

        Map<String, Object> inModule = GoLaunch.launch(main, "package main\n", 0, true, List.of("-v"), Map.of(), false);
        assertEquals("debug", inModule.get("mode"));
        assertEquals(pkg.toAbsolutePath().toString(), inModule.get("program"), "the package, as go run .");
        assertEquals(pkg.toAbsolutePath().toString(), inModule.get("cwd"));
        assertEquals(List.of("-v"), inModule.get("args"));
        assertEquals("go", inModule.get("type"));
        String output = (String) inModule.get("output");
        assertTrue(Path.of(output).startsWith(Path.of(System.getProperty("java.io.tmpdir"))), output);
        assertFalse(output.contains("__debug_bin"), "Delve's build goes to the temporary folder: " + output);

        Map<String, Object> alone = GoLaunch.launch(loose, "package main\n", 0, false, List.of(), Map.of(), true);
        assertEquals(loose.toAbsolutePath().toString(), alone.get("program"), "the file, as go run hello.go");
        assertEquals(true, alone.get("stopOnEntry"));
    }

    @Test
    void aTestFileDebugsTheTestAtTheCaretOrItsPackagesTests(@TempDir Path dir) throws Exception {
        Path test = Files.writeString(dir.resolve("calc_test.go"), TESTS);
        assertTrue(GoLaunch.isTestFile(test));
        assertFalse(GoLaunch.isTestFile(dir.resolve("calc.go")));
        assertFalse(GoLaunch.isTestFile(null));

        Map<String, Object> one = GoLaunch.launch(test, TESTS, 8, true, List.of("-test.v"), Map.of(), false);
        assertEquals("test", one.get("mode"));
        assertEquals(dir.toAbsolutePath().toString(), one.get("program"), "the package's test binary");
        assertEquals(List.of("-test.run", "^TestAdd$", "-test.v"), one.get("args"));
        assertEquals("TestAdd", GoLaunch.sessionName(test, TESTS, 8));

        Map<String, Object> all = GoLaunch.launch(test, TESTS, 0, true, List.of(), Map.of(), false);
        assertEquals(List.of(), all.get("args"), "outside a test: every test of the package");
        assertEquals("calc_test.go", GoLaunch.sessionName(test, TESTS, 0));
    }

    @Test
    void anAttachIsByProcessIdOnThisMachine() {
        assertEquals(
                Map.of("type", "go", "request", "attach", "mode", "local", "processId", 4242L), GoLaunch.attach(4242));
    }
}

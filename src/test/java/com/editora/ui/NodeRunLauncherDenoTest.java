package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.run.DenoRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run File and Debug plan a file of a Deno project ({@code deno.json}) under Deno, from that project's folder. */
class NodeRunLauncherDenoTest {

    private static NodeRunLauncher.Ready plan(Path file, Path root, List<String> nodeArgs, String configured) {
        return NodeRunLauncher.planNow(new NodeRunLauncher.Spec(
                file, root, configured, "", List.of("x"), nodeArgs, false, Map.of("MODE", "dev"), null));
    }

    @Test
    void aProgramRunsUnderDenoFromItsProject(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Files.writeString(root.resolve("deno.jsonc"), "{}");
        Path main = Files.writeString(Files.createDirectory(root.resolve("src")).resolve("main.ts"), "let a = 1;\n");
        NodeRunLauncher.Ready ready = plan(main, root, List.of(), "");
        List<String> argv = ready.plan().argv();
        assertTrue(DenoRun.isDeno(argv.getFirst()), "deno: " + argv);
        assertEquals(List.of("run", "--allow-all", main.toString(), "x"), argv.subList(1, argv.size()));
        assertEquals(root, ready.dir());
        assertEquals(main.toString(), ready.target());
        assertEquals("deno.jsonc", ready.source());
        assertEquals("dev", ready.plan().env().get("MODE"));
    }

    @Test
    void aTestFileRunsUnderDenoTestFilteredToTheTestItsDebugNamed(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Files.writeString(root.resolve("deno.json"), "{}");
        Path test = Files.writeString(root.resolve("math_test.ts"), "Deno.test('adds', () => {});\n");
        List<String> argv = plan(
                        test, root, List.of("--test", "--test-name-pattern", "adds", DenoRun.FILTER_ARG + "adds"), "")
                .plan()
                .argv();
        assertEquals(
                List.of("test", "--allow-all", "--filter", "/^adds$/", test.toString(), "--", "x"),
                argv.subList(1, argv.size()));
        assertEquals("test", plan(test, root, List.of(), "").plan().argv().get(1), "Run File on a test file");
    }

    @Test
    void aShebangOrAConfigurationsInterpreterIsNotOverruled(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Files.writeString(root.resolve("deno.json"), "{}");
        Path script = Files.writeString(root.resolve("tool.js"), "#!/usr/bin/env node\nconsole.log(1);\n");
        assertFalse(
                DenoRun.isDeno(plan(script, root, List.of(), "").plan().argv().getFirst()));
        Path plain = Files.writeString(root.resolve("plain.js"), "console.log(1);\n");
        assertFalse(DenoRun.isDeno(
                plan(plain, root, List.of(), "node").plan().argv().getFirst()));
    }

    @Test
    void nodeNeverSeesADenoTestName(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Files.writeString(root.resolve("package.json"), "{}");
        Path test = Files.writeString(root.resolve("a.test.js"), "test('adds', () => {});\n");
        List<String> argv = plan(test, root, List.of("--test", DenoRun.FILTER_ARG + "adds"), "")
                .plan()
                .argv();
        assertFalse(argv.stream().anyMatch(a -> a.startsWith(DenoRun.FILTER_ARG)), argv.toString());
        assertTrue(argv.contains("--test"), argv.toString());
    }
}

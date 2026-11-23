package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.run.JsTestNames;
import com.editora.run.JsTestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run File and Debug plan a test file of a Jest or Vitest package under its runner, on the file. */
class NodeRunLauncherJsTestTest {

    private static NodeRunLauncher.Ready plan(Path file, Path root, List<String> nodeArgs) {
        return NodeRunLauncher.planNow(
                new NodeRunLauncher.Spec(file, root, "", "", List.of(), nodeArgs, false, Map.of(), null));
    }

    @Test
    void aJestTestFileRunsUnderJestNarrowedToTheCaretsTest(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Files.writeString(root.resolve("package.json"), "{\"devDependencies\": {\"jest\": \"30\"}}");
        Path jest =
                Files.createDirectories(root.resolve("node_modules/jest/bin")).resolve("jest.js");
        Files.writeString(jest, "");
        Path test = Files.writeString(root.resolve("math.test.tsx"), "test('adds', () => {});\n");
        String names = JsTestRunner.namesArg(new JsTestNames.Target(List.of("adds"), false));
        NodeRunLauncher.Ready ready = plan(test, root, List.of("--test", "--test-name-pattern", "adds", names));
        List<String> argv = ready.plan().argv();
        int at = argv.indexOf(jest.toString());
        assertTrue(at >= 1, "Node runs Jest's script: " + argv);
        assertEquals(List.of("--runInBand", "-t", "^adds$", test.toString()), argv.subList(at + 1, argv.size()));
        assertEquals(jest.toString(), ready.target(), "the program js-debug launches is Jest's script");
        assertEquals(root, ready.dir());
        assertFalse(NodeDebugConfigs.nodeRuns(ready, test), "a .tsx test runs: Jest transforms it");
        var program =
                NodeDebugConfigs.fromRunPlan(argv, ready.target(), ready.plan().env(), ready.dir());
        assertEquals(jest.toString(), program.program());
        assertEquals(List.of("--runInBand", "-t", "^adds$", test.toString()), program.args());
    }

    @Test
    void aTestFileOfAPackageWithoutARunnerStaysOnNodeTest(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Files.writeString(root.resolve("package.json"), "{}");
        Path test = Files.writeString(root.resolve("a.test.js"), "");
        List<String> argv = plan(test, root, List.of("--test")).plan().argv();
        assertTrue(argv.contains("--test") && argv.contains(test.toString()), argv.toString());
    }

    @Test
    void aRunnerThatIsNotInstalledIsNotUsed(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Files.writeString(root.resolve("package.json"), "{\"devDependencies\": {\"vitest\": \"5\"}}");
        Path test = Files.writeString(root.resolve("a.test.js"), "");
        assertTrue(plan(test, root, List.of()).plan().argv().contains(test.toString()));
        assertFalse(plan(test, root, List.of()).plan().argv().stream().anyMatch(a -> a.contains("vitest")));
    }
}

package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.build.BuildTool;
import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.run.PythonInterpreters;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import com.editora.test.TestStatus;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Node.js in a real window (JS review E109): Run File, a Package Scripts run and a test file's ▶, end to end with
 * the real {@code node} (and {@code npm}) of the machine. The Node.js setting points at a shim that marks its
 * children ({@code FAKE_NODE=1}) and then runs the real node, so a test can tell which Node.js a program ran on.
 * Skipped where node or npm is missing; CI's runners have both.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the shim is a POSIX shell script
class NodeRunWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!FxTestSupport.callOnFx(condition::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what + " never happened");
            FxTestSupport.drainFx();
            Thread.sleep(20);
        }
    }

    private static String realNode() {
        String node = PythonInterpreters.onAugmentedPath("node").orElse(null);
        assumeTrue(node != null, "node is not installed");
        return node;
    }

    /** A {@code bin/node} that exports {@code FAKE_NODE=1} and runs the real node: the Node.js setting's choice. */
    private static Path shim(Path dir, String realNode) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("chosen-node/bin"));
        Path node = bin.resolve("node");
        Files.writeString(node, "#!/bin/sh\nFAKE_NODE=1\nexport FAKE_NODE\nexec '" + realNode + "' \"$@\"\n");
        Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("rwxr-xr-x"));
        return node;
    }

    private static String console(MainController c) {
        CodeArea output = FxTestSupport.field(c.runCoordinator.panel(), "output");
        return output.getText();
    }

    private static EditorBuffer open(MainController c, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
        await(
                "the file to open",
                () -> c.activeBuffer() != null && file.equals(c.activeBuffer().getPath()));
        EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
        FxTestSupport.runOnFx(b::settleRunScan);
        return b;
    }

    @Test
    void runFileRunsTheScriptOnTheChosenNode() throws Exception {
        String real = realNode();
        Path cfg = Files.createTempDirectory("node-run-fx");
        Path node = shim(cfg, real);
        try (FxWindowFixture fx =
                FxWindowFixture.create(cfg, shared -> shared.getSettings().setNodePath(node.toString()))) {
            MainController c = fx.controller;
            Path root = fx.useProject();
            Files.writeString(root.resolve("package.json"), "{\"name\":\"demo\",\"version\":\"1.0.0\"}\n");
            Path script = Files.createDirectories(root.resolve("src")).resolve("where.js");
            Files.writeString(
                    script, "console.log('ran on ' + (process.env.FAKE_NODE === '1' ? 'chosen' : 'other'));\n");
            EditorBuffer b = open(c, script);
            assertTrue(FxTestSupport.callOnFx(b::isRunnable), "a .js file has ▶ and Run File");

            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.run"));
            await("the script's output", () -> console(c).contains("ran on "));
            assertTrue(console(c).contains("ran on chosen"), console(c));
        } finally {
            FxTestSupport.drainFx();
        }
    }

    /**
     * Settings → Node.js is "the Node.js for Run, Debug, npm scripts and code intelligence"; a Package Scripts run
     * used npm's own PATH node instead (JS review E101/C101). <b>Passes once C101 is merged</b> (the npm child's
     * PATH starts with the chosen Node.js's folder); on fix/js-code alone the {@code FAKE=1} assertion fails.
     */
    @Test
    void aPackageScriptRunsOnTheChosenNode() throws Exception {
        String real = realNode();
        assumeTrue(PythonInterpreters.onAugmentedPath("npm").isPresent(), "npm is not installed");
        Path cfg = Files.createTempDirectory("node-run-fx");
        Path node = shim(cfg, real);
        try (FxWindowFixture fx =
                FxWindowFixture.create(cfg, shared -> shared.getSettings().setNodePath(node.toString()))) {
            MainController c = fx.controller;
            Path root = fx.useProject();
            Files.writeString(root.resolve("package.json"), """
                    {
                      "name": "demo",
                      "version": "1.0.0",
                      "scripts": {
                        "where": "node -e \\"require('fs').writeFileSync('where.txt', 'FAKE=' + (process.env.FAKE_NODE || 'no') + '\\\\n' + process.env.PATH)\\""
                      }
                    }
                    """);
            fx.shared.getTrustStore().trust(root); // the user's own project: npm asks for trust before a script
            BuildCoordinator npm = FxTestSupport.callOnFx(() -> c.buildCoordinator(BuildTool.NPM));
            FxTestSupport.runOnFx(() -> npm.runTaskAt(root, List.of("run", "where"), List.of()));
            Path out = root.resolve("where.txt");
            await("the script to write where.txt", () -> Files.exists(out));
            List<String> lines = Files.readAllLines(out);
            assertEquals("FAKE=1", lines.get(0), "the script's node is the Node.js setting's");
            assertTrue(
                    lines.get(1).contains(node.getParent().toString()),
                    "the chosen Node.js's folder is on the script's PATH: " + lines.get(1));
        } finally {
            FxTestSupport.drainFx();
        }
    }

    @Test
    void aTestFilesPlayButtonFillsTestResults() throws Exception {
        realNode();
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            Path root = fx.useProject();
            Files.writeString(root.resolve("package.json"), "{\"name\":\"demo\",\"version\":\"1.0.0\"}\n");
            Path test = Files.createDirectories(root.resolve("test")).resolve("math.test.js");
            Files.writeString(test, """
                    const test = require('node:test');
                    const assert = require('node:assert');
                    test('adds', () => assert.strictEqual(1 + 1, 2));
                    test('subtracts wrongly', () => assert.strictEqual(2 - 1, 3));
                    """);
            open(c, test);
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.run"));

            TreeView<TestNode> tree = FxTestSupport.field(c.testRunCoordinator.panel(), "tree");
            await(
                    "both tests to finish",
                    () -> tests(tree.getRoot()).stream()
                                    .filter(n -> n.status().isTerminal())
                                    .count()
                            == 2);
            List<TestNode> tests = FxTestSupport.callOnFx(() -> tests(tree.getRoot()));
            TestNode adds = only(tests, "adds");
            TestNode wrong = only(tests, "subtracts wrongly");
            assertEquals(TestStatus.PASSED, adds.status());
            assertEquals(TestStatus.FAILED, wrong.status());
            String message = String.valueOf(wrong.failureMessage());
            assertTrue(message.contains("3"), "the failure says what was expected: " + message);
        }
    }

    private static TestNode only(List<TestNode> tests, String name) {
        List<TestNode> named = tests.stream()
                .filter(n -> name.equals(n.displayName()) || name.equals(n.methodName()))
                .toList();
        assertEquals(1, named.size(), "one row named " + name + " in " + tests);
        return named.get(0);
    }

    /** Every test row (not suites) under {@code item}. */
    private static List<TestNode> tests(TreeItem<TestNode> item) {
        List<TestNode> out = new ArrayList<>();
        if (item == null) {
            return out;
        }
        if (item.getValue() != null && item.getValue().kind() == TestNodeKind.TEST) {
            out.add(item.getValue());
        }
        for (TreeItem<TestNode> child : item.getChildren()) {
            out.addAll(tests(child));
        }
        return out;
    }
}

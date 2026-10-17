package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.test.JavaTestScanner;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What a Python test ▶, Run Test at Caret and Test Results' rerun select (decision 3). */
class PythonTestRunnerScopeTest {

    private static final Path ROOT = Path.of("/work/shop").toAbsolutePath();
    private static final Path FILE = ROOT.resolve("tests/test_cart.py");

    private static JavaTestScanner.TestTarget target(String cls, String method) {
        return new JavaTestScanner.TestTarget(3, cls, method, false);
    }

    @Test
    void pytestNodeIdsAreRelativeToTheProjectRoot() {
        assertEquals(
                List.of("tests/test_cart.py"),
                PythonTestRunner.scopeFor(FILE, "", null, ROOT).pytestIds());
        assertEquals(
                List.of("tests/test_cart.py"),
                PythonTestRunner.scopeFor(FILE, "", target("", null), ROOT).pytestIds());
        assertEquals(
                List.of("tests/test_cart.py::test_add"),
                PythonTestRunner.scopeFor(FILE, "", target("", "test_add"), ROOT)
                        .pytestIds());
        assertEquals(
                List.of("tests/test_cart.py::TestCart"),
                PythonTestRunner.scopeFor(FILE, "", target("TestCart", null), ROOT)
                        .pytestIds());
        assertEquals(
                List.of("tests/test_cart.py::TestCart::test_buy"),
                PythonTestRunner.scopeFor(FILE, "", target("TestCart", "test_buy"), ROOT)
                        .pytestIds());
        PythonTestRunner.Scope noProject = PythonTestRunner.scopeFor(FILE, "", target("", "test_add"), null);
        assertEquals(FILE.getParent(), noProject.rootDir());
        assertEquals(List.of("test_cart.py::test_add"), noProject.pytestIds());
    }

    @Test
    void unittestIdsOnlyForUnittestTests() {
        String unittest = "import unittest\nclass TestCart(unittest.TestCase):\n    def test_buy(self): pass\n";
        assertEquals(
                List.of("test_cart"),
                PythonTestRunner.scopeFor(FILE, unittest, null, ROOT).unittestIds());
        assertEquals(
                List.of("test_cart.TestCart.test_buy"),
                PythonTestRunner.scopeFor(FILE, unittest, target("TestCart", "test_buy"), ROOT)
                        .unittestIds());
        assertEquals(
                FILE.getParent(),
                PythonTestRunner.scopeFor(FILE, unittest, null, ROOT).unittestDir());
        assertNull(
                PythonTestRunner.scopeFor(FILE, "def test_add():\n    assert 1\n", null, ROOT)
                        .unittestIds(),
                "pytest-style tests need pytest");
    }

    @Test
    void rerunningFailedTestsSelectsThemByNodeId() {
        TestNode failed = new TestNode(TestNodeKind.TEST, "tests.test_cart.TestCart#test_buy", "test_buy");
        failed.setClassName("tests.test_cart.TestCart");
        failed.setMethodName("test_buy");
        failed.setSourceFileHint("tests/test_cart.py");
        PythonTestRunner.Scope all = PythonTestRunner.scopeFor(FILE, "", null, ROOT);
        assertEquals(
                List.of("tests/test_cart.py::TestCart::test_buy"),
                PythonTestRunner.narrowed(all, List.of(failed), true).pytestIds());
        assertEquals("tests.test_cart.TestCart.test_buy", PythonTestRunner.unittestId(failed));
    }
}

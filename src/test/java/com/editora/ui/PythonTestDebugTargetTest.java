package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.test.JavaTestScanner;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What Debug on a Python row of Test Results runs ({@link PythonTestRunner#debugTarget}) and which pytest test a
 * Python test glyph's Debug Test stands for ({@link DebugCoordinator#pytestTarget}). Both said "debugging this
 * test is not supported".
 */
class PythonTestDebugTargetTest {

    private static final Path ROOT = Path.of("/work/shop").toAbsolutePath();
    private static final Path FILE = ROOT.resolve("tests/test_cart.py");

    private static TestNode test(String cls, String method, String hint) {
        TestNode n = new TestNode(TestNodeKind.TEST, cls + "#" + method, method);
        n.setClassName(cls);
        n.setMethodName(method);
        n.setSourceFileHint(hint);
        return n;
    }

    @Test
    void aPytestRowDebugsItsNodeIdFromTheRunsFolder() {
        PythonTestRunner.Scope scope =
                new PythonTestRunner.Scope(ROOT, List.of(), FILE.getParent(), null, "shop", null);
        var t = PythonTestRunner.debugTarget(
                scope, test("tests.test_cart.TestCart", "test_add", "tests/test_cart.py"), true);
        assertEquals(List.of("tests/test_cart.py::TestCart::test_add"), t.pytestIds());
        assertEquals(List.of(), t.unittestIds());
        assertEquals(ROOT, t.cwd());
        assertEquals(FILE, t.anchor(), "the row's file picks the interpreter and names the session");
    }

    @Test
    void aUnittestRowDebugsItsDottedIdUnderUnittest() {
        PythonTestRunner.Scope scope =
                new PythonTestRunner.Scope(ROOT, List.of(), FILE.getParent(), List.of(), "cart", FILE);
        var t = PythonTestRunner.debugTarget(scope, test("test_cart.TestCart", "test_add", null), false);
        assertEquals(List.of(), t.pytestIds());
        assertEquals(List.of("test_cart.TestCart.test_add"), t.unittestIds());
        assertEquals(FILE.getParent(), t.cwd());
        assertEquals(FILE, t.anchor());
    }

    @Test
    void aRowNamingNothingCannotBeDebugged() {
        PythonTestRunner.Scope scope = new PythonTestRunner.Scope(ROOT, List.of(), ROOT, null, "shop", null);
        assertNull(PythonTestRunner.debugTarget(scope, test(null, "x", null), false));
        assertNull(PythonTestRunner.debugTarget(scope, test("c", "x", null), true));
    }

    private static final String SOURCE =
            "class TestCart:\n    def test_add(self):\n        assert 1\n\n\ndef test_free():\n    assert 1\n";

    @Test
    void aTestGlyphStandsForItsPytestTest() {
        var method =
                DebugCoordinator.pytestTarget(SOURCE, new JavaTestScanner.TestTarget(1, "TestCart", "test_add", false));
        assertEquals("TestCart::test_add", method.nodeSuffix());
        var cls = DebugCoordinator.pytestTarget(SOURCE, new JavaTestScanner.TestTarget(0, "TestCart", null, false));
        assertEquals("TestCart", cls.nodeSuffix());
        var free = DebugCoordinator.pytestTarget(SOURCE, new JavaTestScanner.TestTarget(5, "", "test_free", false));
        assertEquals("test_free", free.nodeSuffix());
        assertNull(
                DebugCoordinator.pytestTarget(SOURCE, new JavaTestScanner.TestTarget(0, "", null, false)), "the file");
    }
}

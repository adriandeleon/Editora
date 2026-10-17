package com.editora.ui;

import java.util.List;

import com.editora.test.PytestScanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link DebugCoordinator#unittestFallback}: what runs a test under {@code python -m unittest} when pytest is missing. */
class DebugPythonTestArgsTest {

    private static final String SOURCE = """
            import unittest


            class TestMath(unittest.TestCase):
                def test_add(self):
                    self.assertEqual(2, 1 + 1)


            class TestPlain:
                def test_x(self):
                    assert True


            def test_free():
                assert True
            """;

    private static PytestScanner.TestTarget at(int line) {
        return PytestScanner.atLine(SOURCE, line);
    }

    @Test
    void aTestCaseMethodOrClassIsNamedAsADottedPath() {
        assertEquals(
                List.of("tests.test_math.TestMath.test_add"),
                DebugCoordinator.unittestFallback(SOURCE, "tests/test_math.py", at(5)));
        assertEquals(
                List.of("test_math.TestMath"),
                DebugCoordinator.unittestFallback(
                        SOURCE, "test_math.py", new PytestScanner.TestTarget(3, 5, "TestMath", null)));
    }

    @Test
    void pytestStyleTestsHaveNoUnittestForm() {
        assertEquals(List.of(), DebugCoordinator.unittestFallback(SOURCE, "test_math.py", at(10)));
        assertEquals(List.of(), DebugCoordinator.unittestFallback(SOURCE, "test_math.py", at(14)));
        assertEquals(List.of(), DebugCoordinator.unittestFallback(SOURCE, "test_math.py", null));
    }
}

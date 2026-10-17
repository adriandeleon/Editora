package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PytestScannerTest {

    private static final String SOURCE = """
            import pytest
            '''
            def test_in_docstring():
                pass
            '''

            def helper():
                pass


            @pytest.mark.slow
            def test_one():
                assert helper() is None


            async def test_async():
                s = "def test_in_string(): pass"


            class TestThing:
                def setup_method(self):
                    pass

                def test_a(self):
                    def test_inner():  # nested: not collected on its own
                        pass
                    assert True

                @pytest.mark.parametrize("x", [1, 2])
                def test_b(self, x):
                    assert x


            class Helper:
                def test_not_collected(self):
                    pass


            class Login(unittest.TestCase):
                def test_ok(self):
                    self.assertTrue(True)
            """;

    @Test
    void scansWhatPytestCollects() {
        List<String> ids = PytestScanner.scan(SOURCE).stream()
                .map(PytestScanner.TestTarget::nodeSuffix)
                .toList();
        assertEquals(
                List.of(
                        "test_one",
                        "test_async",
                        "TestThing",
                        "TestThing::test_a",
                        "TestThing::test_b",
                        "Login",
                        "Login::test_ok"),
                ids);
    }

    @Test
    void linesAreTheDefLinesAfterDecorators() {
        var one = PytestScanner.scan(SOURCE).get(0);
        assertEquals("def test_one():", SOURCE.lines().toList().get(one.line()));
        assertEquals(one.line() + 1, one.endLine());
    }

    @Test
    void atLineFindsTheInnermostTest() {
        List<String> lines = SOURCE.lines().toList();
        int inA = lines.indexOf("        assert True");
        assertEquals("TestThing::test_a", PytestScanner.atLine(SOURCE, inA).nodeSuffix());
        int classLine = lines.indexOf("class TestThing:");
        assertEquals("TestThing", PytestScanner.atLine(SOURCE, classLine).nodeSuffix());
        int setup = lines.indexOf("    def setup_method(self):");
        assertEquals("TestThing", PytestScanner.atLine(SOURCE, setup).nodeSuffix());
        assertNull(PytestScanner.atLine(SOURCE, 0));
    }

    @Test
    void nodeIdUsesForwardSlashes() {
        var t = new PytestScanner.TestTarget(3, 4, "TestX", "test_y");
        assertEquals("tests/test_app.py::TestX::test_y", PytestScanner.nodeId("tests\\test_app.py", t));
        assertEquals("tests/test_app.py", PytestScanner.nodeId("tests/test_app.py", null));
    }

    @Test
    void testFileNamesFollowPytestDefaults() {
        assertTrue(PytestScanner.isTestFile("test_app.py"));
        assertTrue(PytestScanner.isTestFile("app_test.py"));
        assertFalse(PytestScanner.isTestFile("conftest.py"));
        assertFalse(PytestScanner.isTestFile("testing.py"));
        assertFalse(PytestScanner.isTestFile("test_app.pyi"));
    }

    @Test
    void blankKeepsLinesAndQuotes() {
        String s = "x = '''a\nb''' # c\ny = \"q\\\"z\"\n";
        String b = PytestScanner.blank(s);
        assertEquals(s.length(), b.length());
        assertEquals(s.lines().count(), b.lines().count());
        assertEquals("x = '''", b.substring(0, 7));
        assertTrue(b.contains("y = \""));
        assertFalse(b.contains("c"));
    }
}

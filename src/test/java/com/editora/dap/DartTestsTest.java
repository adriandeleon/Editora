package com.editora.dap;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DartTestsTest {

    private static final String FILE = """
            import 'package:test/test.dart';

            void main() {
              group('calc', () {
                test('adds', () {
                  var total = 1 + 2; // a ) in a comment
                  expect(total, 3);
                });
                test("it's \\"quoted\\" (and has parens)", () {
                  expect(')', isNotNull);
                });
              });
              testWidgets('a widget', (tester) async {
                await tester.pump();
              });
            }
            """;

    @Test
    void theTestAtTheCaretIsNamedWithItsGroups() {
        assertEquals(new DartTests.Target("calc adds", false), DartTests.at(FILE, 5));
        assertEquals(new DartTests.Target("calc adds", false), DartTests.at(FILE, 4), "its own line");
        assertEquals(
                new DartTests.Target("calc it's \"quoted\" (and has parens)", false),
                DartTests.at(FILE, 9),
                "a ) in a string or a comment does not end the call");
        assertEquals(new DartTests.Target("calc", true), DartTests.at(FILE, 11), "between the tests: the group");
        assertEquals(new DartTests.Target("a widget", false), DartTests.at(FILE, 13), "a widget test");
        assertNull(DartTests.at(FILE, 2), "in main, outside every test: the whole file");
        assertNull(DartTests.at("void main() {}\n", 0));
    }

    @Test
    void aTestIsMatchedWholeAndAGroupByItsStart() {
        assertEquals(List.of("--name", "^calc adds$"), DartTests.toolArgs(new DartTests.Target("calc adds", false)));
        assertEquals(List.of("--name", "^calc( |$)"), DartTests.toolArgs(new DartTests.Target("calc", true)));
        assertEquals("a \\(b\\)\\? \\$1\\.5", DartTests.regexLiteral("a (b)? $1.5"));
    }

    @Test
    void testFilesAreThoseDartTestFinds() {
        assertTrue(DartTests.isTestFile(Path.of("test", "calc_test.dart")));
        assertFalse(DartTests.isTestFile(Path.of("lib", "calc.dart")));
        assertFalse(DartTests.isTestFile(null));
    }

    @Test
    void anUnclosedCallSpansToTheEnd() {
        String open = "void main() {\n  test('half', () {\n    var x = '''\n  a ) b\n''';\n";
        assertEquals(new DartTests.Target("half", false), DartTests.at(open, 4));
        assertEquals(-1, DartTests.closing("(]", 0), "a bracket closes it: not this call's end");
        assertEquals(5, DartTests.closing("(r'\\')", 0), "a raw string's backslash escapes nothing");
    }
}

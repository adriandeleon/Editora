package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Python's defun for Narrow to Defun and the defun motions (review 2 A102). */
class PythonDefunTest {

    private static final String SRC = String.join(
            "\n",
            "MAX_SIZE = 10", // 0
            "",
            "",
            "@dataclass", // 3
            "class Point:",
            "    x: int",
            "",
            "    @property", // 7
            "    def norm(self):",
            "        return 1",
            "# a comment at column 0 inside the class",
            "    def later(self):", // 11
            "        pass",
            "",
            "",
            "def helper(a,", // 15
            "           b):",
            "    s = \"\"\"",
            "not code: def fake():",
            "\"\"\"",
            "    return a", // 20
            "",
            "x = 1",
            "");

    private static int lineStart(int line) {
        int pos = 0;
        for (int i = 0; i < line; i++) {
            pos = SRC.indexOf('\n', pos) + 1;
        }
        return pos;
    }

    private static String defunAt(int line) {
        int[] b = PythonDefun.bounds(SRC, lineStart(line) + 2);
        return b == null ? null : SRC.substring(b[0], b[1]);
    }

    @Test
    void onADecoratorTheDefunIsTheDecoratedClassWithItsDecorators() {
        String cls = defunAt(3);
        assertEquals(lineStart(3), SRC.indexOf(cls));
        assertEquals(SRC.substring(lineStart(3), lineStart(13)), cls, "the class ends after its last body line");
    }

    @Test
    void inAMethodTheDefunIsTheMethodWithItsDecorator() {
        assertEquals("    @property\n    def norm(self):\n        return 1\n", defunAt(9));
        assertEquals("    def later(self):\n        pass\n", defunAt(12));
    }

    @Test
    void aColumnZeroLineInsideAStringDoesNotEndTheFunction() {
        assertEquals(SRC.substring(lineStart(15), lineStart(21)), defunAt(18));
        assertEquals(SRC.substring(lineStart(15), lineStart(21)), defunAt(20));
    }

    @Test
    void outsideEveryDefinitionThereIsNone() {
        assertNull(defunAt(0));
        assertNull(defunAt(22));
    }

    @Test
    void theMotionsGoToTheStartAndEndOfTheDefinition() {
        int inNorm = lineStart(9) + 4;
        assertEquals(lineStart(7), PythonDefun.beginning(SRC, inNorm));
        // From a definition's start, the previous one's start.
        assertEquals(lineStart(3), PythonDefun.beginning(SRC, lineStart(7)));
        assertEquals(lineStart(10), PythonDefun.end(SRC, inNorm));
        // From module level, the end of the next definition.
        assertEquals(lineStart(21), PythonDefun.end(SRC, lineStart(14)));
        assertEquals(0, PythonDefun.beginning(SRC, 0), "nothing above: stays");
    }
}

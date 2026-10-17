package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B8: which lines a breakpoint is refused on — blank and comment-only ones. */
class CodeLessLinesTest {

    private static final String JAVA = String.join(
            "\n",
            "public class Main {", // 0
            "    // a comment", // 1
            "", // 2
            "    /* a block", // 3
            "       still the block */", // 4
            "    int x = 1; // trailing comment", // 5
            "    /* lead */ int y = 2;", // 6
            "    String s = \"/* not a comment\";", // 7
            "    int z = 3;", // 8
            "    String t = \"\"\"", // 9
            "        // inside a text block", // 10
            "        \"\"\";", // 11
            "     * a javadoc-style line", // 12
            "}"); // 13

    @Test
    void blankAndCommentOnlyJavaLinesHaveNoCode() {
        for (int line : new int[] {1, 2, 3, 4}) {
            assertTrue(CodeLessLines.isCodeLess(JAVA, line, "java"), "line " + line);
        }
    }

    @Test
    void linesWithCodeAreLeftAlone() {
        for (int line : new int[] {0, 5, 6, 7, 8, 9, 10, 11, 13}) {
            assertFalse(CodeLessLines.isCodeLess(JAVA, line, "java"), "line " + line);
        }
        // a string that looks like a comment opener does not open one
        assertFalse(CodeLessLines.isCodeLess(JAVA, 8, "java"));
    }

    @Test
    void pythonCommentsAndOtherLanguagesBlankLines() {
        String py = "x = 1\n# comment\n\nprint(x)  # trailing\n";
        assertFalse(CodeLessLines.isCodeLess(py, 0, "python"));
        assertTrue(CodeLessLines.isCodeLess(py, 1, "python"));
        assertTrue(CodeLessLines.isCodeLess(py, 2, "python"));
        assertFalse(CodeLessLines.isCodeLess(py, 3, "python"));
        assertTrue(CodeLessLines.isCodeLess("a\n  \nb", 1, "rust"));
        assertFalse(CodeLessLines.isCodeLess("// x", 0, "rust"), "unknown comment syntax: left to the adapter");
        assertFalse(CodeLessLines.isCodeLess("a", 5, "java"));
        assertFalse(CodeLessLines.isCodeLess(null, 0, "java"));
    }
}

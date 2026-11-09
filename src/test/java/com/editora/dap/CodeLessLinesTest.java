package com.editora.dap;

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
        assertTrue(CodeLessLines.isCodeLess("a\n  \nb", 1, "zig"));
        assertFalse(CodeLessLines.isCodeLess("// x", 0, "zig"), "unknown comment syntax: left to the adapter");
        assertFalse(CodeLessLines.isCodeLess("a", 5, "java"));
        assertFalse(CodeLessLines.isCodeLess(null, 0, "java"));
    }

    @Test
    void goCommentLinesHaveNoCode() {
        String go = "package main\n\n// Greet says hello.\nfunc greet() {\n\t/* a block\n\t   comment */\n"
                + "\tfmt.Println(`// not a comment`) // trailing\n}\n";
        assertTrue(CodeLessLines.isCodeLess(go, 2, "go"));
        assertTrue(CodeLessLines.isCodeLess(go, 4, "go"));
        assertTrue(CodeLessLines.isCodeLess(go, 5, "go"));
        assertFalse(CodeLessLines.isCodeLess(go, 3, "go"));
        assertFalse(CodeLessLines.isCodeLess(go, 6, "go"), "a raw string holding // is code");
    }

    @Test
    void rustCommentLinesHaveNoCode() {
        String rs = "/// Docs.\nfn main() {\n    // a comment\n    let x = 1; // trailing\n}\n";
        assertTrue(CodeLessLines.isCodeLess(rs, 0, "rust"));
        assertTrue(CodeLessLines.isCodeLess(rs, 2, "rust"));
        assertFalse(CodeLessLines.isCodeLess(rs, 1, "rust"));
        assertFalse(CodeLessLines.isCodeLess(rs, 3, "rust"));
    }

    @Test
    void cAndCppCommentLinesHaveNoCode() {
        String c = "/* a header */\nint main(void) {\n    // a comment\n    return 0;\n}\n";
        assertTrue(CodeLessLines.isCodeLess(c, 0, "c"));
        assertTrue(CodeLessLines.isCodeLess(c, 2, "cpp"));
        assertFalse(CodeLessLines.isCodeLess(c, 3, "c"));
    }
}

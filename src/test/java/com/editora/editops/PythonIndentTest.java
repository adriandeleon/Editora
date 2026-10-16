package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Python's Enter / clause / Backspace indentation (review findings A1, A2, A4). */
class PythonIndentTest {

    /** The indent Enter gives at the end of {@code text} (a Python buffer, four-space unit). */
    private static String enter(String text) {
        String insert =
                Indenter.enterEdit(text, text.length(), "python", 4, true, 4).insert();
        return insert.substring(1);
    }

    @Test
    void enterAfterAStatementThatEndsTheBlockStepsOut() {
        assertEquals("    ", enter("def f():\n    if x:\n        return 1"));
        assertEquals("", enter("for i in x:\n    break"));
        assertEquals("", enter("while x:\n    continue"));
        assertEquals("    ", enter("def f():\n    try:\n        pass"));
        assertEquals("", enter("def f():\n    raise ValueError(\n        'bad')"));
        // Words that only begin like one are ordinary statements.
        assertEquals("    ", enter("def f():\n    return_value = 1"));
        assertEquals("    ", enter("def f():\n    passwd = read()"));
    }

    @Test
    void enterMidLineAfterReturnKeepsTheIndent() {
        String text = "def f():\n    return x";
        int caret = text.indexOf(" x");
        assertEquals("\n    ", Indenter.enterEdit(text, caret, "python", 4).insert());
    }

    @Test
    void enterFollowsTheStatementNotTheContinuationLine() {
        assertEquals(
                "    ", enter("def g():\n    result = some_function(arg_one,\n                           arg_two)"));
        assertEquals("    ", enter("def g():\n    x = foo(\n        a,\n        b)"));
        assertEquals("    ", enter("def long_function_name(\n        var_one, var_two,\n        var_four):"));
        assertEquals("    ", enter("def f(\n    a: int,\n) -> None:"));
        assertEquals("", enter("def g():\n    pass\nx = [\n    1,\n]"));
    }

    @Test
    void enterInsideBracketsHangsOrAligns() {
        assertEquals("    ", enter("x = foo("));
        assertEquals("    ", enter("x = foo(\n    a,"));
        assertEquals("        ", enter("x = foo(a,"));
        assertEquals("     ", enter("d = {'a': 1,"));
        assertEquals("    ", enter("x = foo(  # why"));
    }

    @Test
    void aBackslashContinuesOneUnitIn() {
        assertEquals("    ", enter("total = 1 + \\"));
        assertEquals("", enter("total = 1 + \\\n    2"));
    }

    @Test
    void insideADocstringTheLineKeepsItsIndent() {
        assertEquals("    ", enter("def f():\n    \"\"\"Args:"));
        assertEquals("    ", enter("def f():\n    \"\"\"Doc.\n\n    (open paren"));
    }

    @Test
    void aColonInAStringOrCommentDoesNotOpenABlock() {
        assertEquals("", enter("s = \"if x:\""));
        assertEquals("", enter("x = 1  # note:"));
        assertEquals("    ", enter("class A:  # comment"));
    }

    @Test
    void clauseLinesAreRecognisedOnTheirColon() {
        assertTrue(PythonIndent.completesClause("    else:"));
        assertTrue(PythonIndent.completesClause("    elif x > 1:"));
        assertTrue(PythonIndent.completesClause("except:"));
        assertTrue(PythonIndent.completesClause("except* ValueError as e:"));
        assertTrue(PythonIndent.completesClause("except(OSError, ValueError):"));
        assertTrue(PythonIndent.completesClause("    finally:"));
        assertTrue(PythonIndent.completesClause("    case [a, b]:"));
        assertTrue(PythonIndent.completesClause("    else:\n"));
        assertFalse(PythonIndent.completesClause("    else"), "only the `:` (or Enter) completes it");
        assertFalse(PythonIndent.completesClause("    elsewhere:"));
        assertFalse(PythonIndent.completesClause("    elif:"));
        assertFalse(PythonIndent.completesClause("    x = {else:"));
        assertFalse(PythonIndent.completesClause("    elif d[1:"), "a slice colon inside brackets");
        assertFalse(PythonIndent.completesClause("    case = 1:"));
    }

    @Test
    void aClauseAlignsWithTheStatementItContinues() {
        String text = "def f():\n    if a:\n        for i in x:\n            g(i)\n            else:";
        assertEquals("        ", PythonIndent.clauseIndent(text, text.length(), 4, "            "));
        String except = "try:\n    for i in x:\n        g(i)\n        except OSError:";
        assertEquals("", PythonIndent.clauseIndent(except, except.length(), 4, "        "));
        // Already aligned: left alone.
        String aligned = "if a:\n    x = 1\nelse:";
        assertEquals("", PythonIndent.clauseIndent(aligned, aligned.length(), 4, ""));
        // Nothing it can continue: no change offered.
        String orphan = "x = 1\n    else:";
        assertNull(PythonIndent.clauseIndent(orphan, orphan.length(), 4, "    "));
    }

    @Test
    void backspaceStepsBackOneIndentStop() {
        assertEquals(4, PythonIndent.backspaceCount("        ", "    ", 4));
        assertEquals(2, PythonIndent.backspaceCount("      ", "    ", 4));
        assertEquals(1, PythonIndent.backspaceCount("\t\t", "\t", 4));
        assertEquals(0, PythonIndent.backspaceCount("    x", "    ", 4));
        assertEquals(0, PythonIndent.backspaceCount("", "    ", 4));
        // The language overload: Python and YAML step a level, a brace language keeps its documented join.
        assertEquals(4, Indenter.smartBackspaceCount("        ", "", true, "python", "    ", 4));
        assertEquals(2, Indenter.smartBackspaceCount("    ", "", true, "yaml", "  ", 2));
        assertEquals(9, Indenter.smartBackspaceCount("        ", "", true, "java", "    ", 4));
    }

    @Test
    void theScanTellsCodeFromStrings() {
        var lines = PythonScan.scan("x = '''a\n(b\n''' + f(\n  1)\ny = 2 \\\n  + 3\n# c\n\nz");
        assertEquals(9, lines.size());
        assertTrue(lines.get(0).logicalStart());
        assertTrue(lines.get(0).stringAtEnd());
        assertFalse(lines.get(1).logicalStart(), "inside the triple-quoted string");
        assertEquals(1, lines.get(2).depthAtEnd(), "the ( in the string does not count, the f( does");
        assertFalse(lines.get(3).logicalStart());
        assertTrue(lines.get(4).continued());
        assertFalse(lines.get(5).logicalStart());
        assertTrue(lines.get(6).commentOnly());
        assertTrue(lines.get(7).blank());
        assertTrue(lines.get(8).statement());
    }
}

package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Python's Enter / Tab / clause / Backspace indentation (review findings A1, A2, A4; review 2 A101, A110, D103, D106, D109). */
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

    @Test
    void hangingIndentsDoNotMakeAPythonFileEightSpaceIndented() {
        // PEP 8's hanging indent adds 8 for a def's arguments; three of those outvoted two real 4-space blocks.
        String src = String.join(
                "\n",
                "def long_function_name(",
                "        var_one, var_two,",
                "        var_three):",
                "    print(var_one)",
                "",
                "def other(",
                "        a, b):",
                "    if a:",
                "        return b",
                "",
                "x = some_call(",
                "        argument)",
                "");
        assertEquals("    ", Indenter.unitFor(src, 4, null, null, "python"));
        assertEquals("    ", Indenter.detectUnit(src, 4, "python"));
        // Enter after a block header in that file opens one 4-space level.
        String text = src + "class A:";
        assertEquals(
                "\n    ",
                Indenter.enterEdit(text, text.length(), "python", 4, null, null).insert());
    }

    /** The indent smart Tab gives the line at the end of {@code text} (caret at its start, four-space unit). */
    private static String tab(String text) {
        int ls = text.lastIndexOf('\n') + 1;
        Indenter.TabEdit e = Indenter.smartTab(text, ls, ls, "python", 4, false, true, 4);
        String after = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        return after.substring(ls, e.selStart());
    }

    @Test
    void theFirstCaseUnderAMatchStaysInsideIt() {
        // Review 2 A101: the `:` of the first `case` (nothing above it to continue) moved it out to the `match`.
        String typed = "def run(cmd):\n    match cmd:\n        case 1";
        assertEquals(
                "        ", Indenter.closerAlignIndent(Indenter.Style.PY, typed, typed.length(), 4, "        ", ':'));
        // Enter at the end of an existing, correct `case` line left it at the `match`'s column too.
        String existing = "def run(cmd):\n    match cmd:\n        case 1:";
        assertEquals(
                "        ",
                Indenter.closerAlignIndent(Indenter.Style.PY, existing, existing.length(), 4, "        ", '\n'));
        // A later `case` still lines up with the one before it.
        String second = "match cmd:\n    case 1:\n        pass\n        case 2";
        assertEquals(
                "    ", Indenter.closerAlignIndent(Indenter.Style.PY, second, second.length(), 4, "        ", ':'));
        // An `except` whose `try` is out of the scan's reach is left where it was typed, not sent to column 0.
        String longTry = "def f():\n    try:\n" + "        x = 1\n".repeat(900) + "        except ValueError";
        assertEquals(
                "        ",
                Indenter.closerAlignIndent(Indenter.Style.PY, longTry, longTry.length(), 4, "        ", ':'));
    }

    @Test
    void finallyGoesToTheTryNotToTheElseOfAnIfInItsBody() {
        // Review 2 D106.
        String inner = "try:\n    if a:\n        x()\n    else:\n        y()\n        finally:";
        assertEquals("", PythonIndent.clauseIndent(inner, inner.length(), 4, "        "));
        String level = "try:\n    if a:\n        x()\n    else:\n        y()\n    finally:";
        assertEquals("", PythonIndent.clauseIndent(level, level.length(), 4, "    "));
        String tryElse = "try:\n    a()\nexcept E:\n    b()\nelse:\n    c()\n    finally:";
        assertEquals("", PythonIndent.clauseIndent(tryElse, tryElse.length(), 4, "    "));
        String nested =
                "def f():\n    try:\n        a()\n    except E:\n        b()\n    else:\n        c()\n        finally:";
        assertEquals("    ", PythonIndent.clauseIndent(nested, nested.length(), 4, "        "));
    }

    @Test
    void sameQuoteNestingInAnFStringIsCode() {
        // Review 2 D109: `f"{d["k("]}"` — the `"` inside the field used to end the string, leaving `(` open.
        assertEquals("", enter("s = f\"{d[\"k(\"]}\""));
        assertEquals(
                0, PythonScan.scan("s = f'{x:{w}}' + t\"{a[\"(\"]}\"").get(0).depthAtEnd());
        assertEquals(0, PythonScan.scan("s = f\"{{(}}\"").get(0).depthAtEnd(), "doubled braces are text");
        assertEquals(1, PythonScan.scan("s = f\"{x}\" + g(").get(0).depthAtEnd());
        assertEquals(0, PythonScan.scan("elif\"(\"").get(0).depthAtEnd(), "not an f prefix: an ordinary string");
        // The Enter after a call with an f-string argument that nests the same quote.
        assertEquals("        ", enter("x = foo(f\"{d[\"k\"]}\","));
    }

    @Test
    void tabGivesWhatEnterWouldGive() {
        // Review 2 D103: Tab used the generic rule and read a continuation line as the block's indent.
        assertEquals("", tab("result = foo(a,\n             b)\n"));
        assertEquals("    ", tab("if (a and\n        b):\n"));
        assertEquals("    ", tab("def f():\n    if x:\n        return 1\n\n"));
        assertEquals("    ", tab("class A:\n"));
        assertEquals("    ", tab("x = foo(\n"));
        // A clause line goes to the statement it continues.
        assertEquals("", tab("if a:\n    x = 1\nelse:"));
        assertEquals("    ", tab("def f():\n    try:\n        g()\nexcept E:"));
    }

    @Test
    void enterBetweenTripleQuotesPutsTheClosingQuotesOnTheirOwnLine() {
        // Review 2 A110.
        String text = "def f():\n    \"\"\"\"\"\"";
        int caret = text.length() - 3;
        Indenter.EnterEdit e = Indenter.enterEdit(text, caret, "python", 4);
        assertEquals("\n    \n    ", e.insert());
        assertEquals(5, e.caretOffset());
        String single = "s = '''''';";
        assertEquals("\n\n", Indenter.enterEdit(single, 7, "python", 4).insert());
        // A closing triple quote followed by another string is not a split.
        assertFalse(PythonIndent.splitsTripleQuote("x = \"\"\"a\"\"\"", "\"\"\"b\"\"\""));
    }
}

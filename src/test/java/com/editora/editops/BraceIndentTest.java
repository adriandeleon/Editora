package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Case bodies, brace-less headers, open argument lists and call chains (Node.js review finding A12). */
class BraceIndentTest {

    /** The indent Enter gives the new line after the end of {@code text}'s last line. */
    private static String enter(String text, String language) {
        String insert = Indenter.enterEdit(text, text.length(), language, "  ").insert();
        return insert.substring(1);
    }

    /** The indent the last line takes once {@code typed} (already at its end) completes it. */
    private static String typed(String text, char typed) {
        int ls = text.lastIndexOf('\n') + 1;
        String current = Indenter.leadingWhitespace(text.substring(ls));
        assertTrue(Indenter.completesCloserKeyword(Indenter.Style.BRACES, text.substring(ls), "javascript"), text);
        return Indenter.closerAlignIndent(Indenter.Style.BRACES, text, text.length(), 2, current, typed, "  ");
    }

    @Test
    void aCaseBodyIsIndentedAndTheNextLabelStepsBack() {
        assertEquals("      ", enter("  switch (x) {\n    case 1:", "javascript"));
        assertEquals("      ", enter("  switch (x) {\n    default: // the rest", "javascript"));
        assertEquals("    ", typed("  switch (x) {\n    case 1:\n      foo();\n      break;\n      case 2:", ':'));
        assertEquals("    ", typed("  switch (x) {\n    case 1:\n      break;\n      default:", ':'));
        assertEquals("    ", typed("  switch (x) {\n  case 1:", ':'), "the first label: one unit inside switch");
        // An object key named default is not a label: nothing to align with, so it stays.
        assertEquals("    ", typed("const o = {\n    default:", ':'));
    }

    @Test
    void theStatementUnderABracelessHeaderIsIndentedAndTheNextLineIsNot() {
        assertEquals("    ", enter("  if (ok)", "javascript"));
        assertEquals("    ", enter("  else if (a && (b || c))", "javascript"));
        assertEquals("  ", enter("  if (a) foo(b)", "javascript"), "something after the condition: not a header");
        assertEquals("    ", enter("  else", "javascript"));
        assertEquals("  ", enter("  if (ok)\n    run();", "javascript"));
        assertEquals("  ", enter("  if (ok) run();", "javascript"), "a statement on the header's line is done");
    }

    @Test
    void anOpenArgumentListContinuesAndItsClosingLineReturns() {
        assertEquals("    ", enter("  foo(a,", "javascript"));
        assertEquals("  ", enter("  foo(a,\n    b);", "javascript"));
        assertEquals("  ", enter("  items.forEach((x) => {\n    use(x);\n  });", "javascript"));
        // A wrapped signature that opens its body: one unit inside the line it started on.
        assertEquals("    ", enter("  public void f(int a,\n          int b) {", "java"));
    }

    @Test
    void aLeadingDotContinuesTheStatementAndTheChainEndReturns() {
        assertEquals("    ", typed("  return fetch(url)\n  .", '.'));
        assertEquals("    ", typed("  return fetch(url)\n    .then((r) => r.json())\n.", '.'));
        assertEquals(
                "  ", enter("  return fetch(url)\n    .then((r) => r.json())\n    .then((d) => d);", "javascript"));
        // A spread at the start of a line in a literal is not a chain.
        assertFalse(Indenter.completesCloserKeyword(Indenter.Style.BRACES, "  ...", "javascript"));
        assertEquals("    ", typed("  const a = [\n    1,\n    .", '.'), "after a comma: left alone");
    }
}

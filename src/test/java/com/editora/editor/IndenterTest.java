package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.editora.editor.Indenter.Style;

/** Unit tests for the pure indentation engine (no toolkit). */
class IndenterTest {

    /** Enter at the end of {@code text}: returns the inserted string (newline + computed indent). */
    private static String enterAtEnd(String text, String lang) {
        return Indenter.enterEdit(text, text.length(), lang, 4).insert();
    }

    private static String enter(String text, int caret, String lang) {
        return Indenter.enterEdit(text, caret, lang, 4).insert();
    }

    @Test
    void styleMapping() {
        assertEquals(Style.BRACES, Indenter.styleFor("java"));
        assertEquals(Style.BRACES, Indenter.styleFor("powershell"));
        assertEquals(Style.PY, Indenter.styleFor("python"));
        assertEquals(Style.PY, Indenter.styleFor("yaml"));
        assertEquals(Style.SHELL, Indenter.styleFor("shell"));
        assertEquals(Style.RUBY, Indenter.styleFor("ruby"));
        assertEquals(Style.LUA, Indenter.styleFor("lua"));
        assertEquals(Style.BRACES, Indenter.styleFor("terraform"));
        assertEquals(Style.XML, Indenter.styleFor("html"));
        assertEquals(Style.PLAIN, Indenter.styleFor("ini"));
        assertEquals(Style.PLAIN, Indenter.styleFor(null));
    }

    @Test
    void luaIndentsAfterBlockOpeners() {
        // `then`/`do`/`function(...)` open a block → next line indents one level (tab, no indent precedent).
        assertEquals("\n\t", enterAtEnd("if x then", "lua"));
        assertEquals("\n\t", enterAtEnd("for i = 1, 10 do", "lua"));
        assertEquals("\n\t", enterAtEnd("local function f(a, b)", "lua"));
        assertEquals("\n\t", enterAtEnd("while running do", "lua"));
        assertEquals("\n\t", enterAtEnd("t = {", "lua"));
        // A space-indented opener: unit is inferred as spaces, and the block indents one more level.
        assertEquals("\n        ", enterAtEnd("    if y then", "lua"));
        // A non-opening statement just inherits the indent.
        assertEquals("\n    ", enterAtEnd("    x = x + 1", "lua"));
        // An inline `... end` on one line is net-zero, so no extra indent.
        assertEquals("\n", enterAtEnd("if x then return 1 end", "lua"));
    }

    @Test
    void luaClosersDeIndentViaKeyword() {
        assertTrue(Indenter.completesCloserKeyword(Style.LUA, "    end"));
        assertTrue(Indenter.completesCloserKeyword(Style.LUA, "  until"));
        assertTrue(Indenter.completesCloserKeyword(Style.LUA, "\telseif"));
        assertFalse(Indenter.completesCloserKeyword(Style.LUA, "    x = 1"));
    }

    @Test
    void inheritsIndentByDefault() {
        assertEquals("\n", enterAtEnd("foo();", "java"));
        assertEquals("\n    ", enterAtEnd("    foo();", "java"));   // 4-space file → inherit 4 spaces
        assertEquals("\n\t", enterAtEnd("\tfoo();", "java"));        // tab file → inherit tab
    }

    @Test
    void bracesOpenerAddsLevel() {
        assertEquals("\n\t", enterAtEnd("if (x) {", "java"));        // no indent yet → tab unit
        assertEquals("\n        ", enterAtEnd("    if (x) {", "java")); // 4-space → 8 spaces
        assertEquals("\n\t\t", enterAtEnd("\twhile (true) {", "java"));
    }

    @Test
    void bracesPairSplit() {
        // caret between { and }
        Indenter.EnterEdit e = Indenter.enterEdit("class A {}", 9, "java", 4);
        assertEquals("\n\t\n", e.insert());
        assertEquals(2, e.caretOffset()); // caret on the indented middle line
    }

    @Test
    void pythonColonOpens() {
        assertEquals("\n\t", enterAtEnd("def f():", "python"));
        assertEquals("\n    ", enterAtEnd("    x = 1", "python")); // inherit, no opener
        assertEquals("\n        ", enterAtEnd("    if x:", "python"));
    }

    @Test
    void shellOpenersAndInheritance() {
        assertEquals("\n\t", enterAtEnd("for x in y; do", "shell"));
        assertEquals("\n\t", enterAtEnd("if [ -f a ]; then", "shell"));
        assertEquals("\n", enterAtEnd("echo hi", "shell"));
    }

    @Test
    void rubyOpeners() {
        assertEquals("\n\t", enterAtEnd("def foo", "ruby"));
        assertEquals("\n\t", enterAtEnd("[1,2].each do |i|", "ruby"));
        assertEquals("\n\t", enterAtEnd("class Foo", "ruby"));
        assertEquals("\n", enterAtEnd("x = 1", "ruby"));
    }

    @Test
    void xmlOpenTagAndSplit() {
        assertEquals("\n\t", enterAtEnd("<div>", "xml"));
        assertEquals("\n", enterAtEnd("<br/>", "xml"));      // self-closing
        assertEquals("\n", enterAtEnd("</div>", "xml"));     // closing
        Indenter.EnterEdit e = Indenter.enterEdit("<a></a>", 3, "xml", 4); // caret between > and </a>
        assertEquals("\n\t\n", e.insert());
    }

    @Test
    void plainInheritsOnly() {
        assertEquals("\n    ", enterAtEnd("    note {", "ini")); // brace ignored for PLAIN
        assertEquals("\n  ", enterAtEnd("  - item", "markdown"));
    }

    @Test
    void trailingCommentDoesNotDefeatOpener() {
        assertEquals("\n\t", enterAtEnd("if (x) { // go", "java"));
        assertEquals("\n\t", enterAtEnd("def f(): # comment", "python"));
    }

    @Test
    void caretMidLineUsesCodeBeforeCaret() {
        // "a{|b" — before caret ends with '{' (opener), after = "b" (no closer) → indent, not split
        assertEquals("\n\t", enter("a{b", 2, "java"));
    }

    @Test
    void detectUnitFromDocument() {
        assertEquals("    ", Indenter.indentUnit("    ", 4));
        assertEquals("\t", Indenter.indentUnit("\t", 4));
        assertEquals("\t", Indenter.indentUnit("", 4)); // empty → tab default
    }

    @Test
    void closerAlignsToOpener() {
        String braces = "class A {\n\tfoo();\n\t"; // caret on the last (indented) line
        assertEquals("", Indenter.closerAlignIndent(braces, braces.length(), 4));
        String nested = "class A {\n\tdef m {\n\t\tx;\n\t\t"; // deepest line
        assertEquals("\t", Indenter.closerAlignIndent(nested, nested.length(), 4));
    }

    @Test
    void closerCharAndKeywordDetection() {
        assertTrue(Indenter.isCloserChar(Style.BRACES, '}'));
        assertTrue(Indenter.isCloserChar(Style.BRACES, ')'));
        assertFalse(Indenter.isCloserChar(Style.PY, '}'));
        assertFalse(Indenter.isCloserChar(Style.XML, '}'));

        assertTrue(Indenter.completesCloserKeyword(Style.SHELL, "  fi"));
        assertTrue(Indenter.completesCloserKeyword(Style.SHELL, "done"));
        assertTrue(Indenter.completesCloserKeyword(Style.RUBY, "    end"));
        assertTrue(Indenter.completesCloserKeyword(Style.RUBY, "  rescue"));
        assertFalse(Indenter.completesCloserKeyword(Style.RUBY, "  endpoint")); // not exact
        assertFalse(Indenter.completesCloserKeyword(Style.SHELL, "  fix"));
        assertFalse(Indenter.completesCloserKeyword(Style.BRACES, "  end")); // braces have no keywords
    }

    @Test
    void rubyMidKeywordsReopen() {
        assertEquals("\n\t", enterAtEnd("else", "ruby"));   // else body indents
        assertEquals("\n\t", enterAtEnd("rescue", "ruby"));
        assertEquals("\n", enterAtEnd("end", "ruby"));      // end does not open
    }

    @Test
    void smartBackspaceJoinsToPreviousLineOnABlankLine() {
        // Blank auto-indented line with a line above: delete the indent + the newline (back to where
        // Enter was pressed) → count is the leading whitespace length plus one.
        assertEquals(9, Indenter.smartBackspaceCount("        ", "", true)); // 8 spaces + newline
        assertEquals(5, Indenter.smartBackspaceCount("    ", "", true));
        assertEquals(3, Indenter.smartBackspaceCount("\t\t", "", true));     // two tabs + newline
        // Whitespace after the caret still counts the line as blank; only the indent before the caret
        // (plus the newline) is removed, so the count is 4 + 1.
        assertEquals(5, Indenter.smartBackspaceCount("    ", "  ", true));
    }

    @Test
    void smartBackspaceClearsIndentOnAContentLineOrFirstLine() {
        // Indented content line (text after the caret): clear just the indent, no join.
        assertEquals(8, Indenter.smartBackspaceCount("        ", "foo", true));
        assertEquals(4, Indenter.smartBackspaceCount("    ", "x = 1;", true));
        // No previous line (first line): can't join up, so clear the indent.
        assertEquals(4, Indenter.smartBackspaceCount("    ", "", false));
    }

    @Test
    void smartBackspaceNoOpOutsideLeadingWhitespace() {
        assertEquals(0, Indenter.smartBackspaceCount("", "", true));           // column 0
        assertEquals(0, Indenter.smartBackspaceCount("    foo", "", true));    // caret after code
        assertEquals(0, Indenter.smartBackspaceCount("foo", "", true));        // no leading whitespace
    }

    // --- smart Tab -------------------------------------------------------------------------------

    @Test
    void smartTabNullForPlainLanguage() {
        assertNull(Indenter.smartTab("hello", 0, 0, "plaintext", 4, false));
        assertNull(Indenter.smartTab("hello", 0, 0, "markdown", 4, false));
    }

    @Test
    void smartTabSnapsABlankLineToTheBlockIndent() {
        // First Tab on a blank line inside a brace block jumps to the body indent (one level in),
        // not just +1 space — the "smart" bit.
        String text = "void m() {\n\n}"; // line 2 (offset 11) is blank
        Indenter.TabEdit e = Indenter.smartTab(text, 11, 11, "java", 4, false);
        assertEquals(11, e.from());
        assertEquals(11, e.to());
        assertEquals("\t", e.replacement()); // one level (file has no spaces → tab unit)
    }

    @Test
    void smartTabIsNoOpOnceAtTheSuggestedLevel() {
        // The line already sits at the block's indent ("\t"); repeated Tab must NOT keep indenting.
        String text = "void m() {\n\tx\n}"; // line "\tx" starts at offset 11
        Indenter.TabEdit e = Indenter.smartTab(text, 11, 11, "java", 4, false);
        assertEquals(11, e.from());
        assertEquals(11, e.to());
        assertEquals("", e.replacement()); // no change
    }

    @Test
    void smartTabUsesTheFilesSpaceUnitWhenSnapping() {
        // Space-indented file: snapping a blank line uses spaces, never a raw \t.
        String text = "class C {\n    int x;\n\n}"; // blank line at offset 21
        Indenter.TabEdit e = Indenter.smartTab(text, 21, 21, "java", 4, false);
        assertEquals("    ", e.replacement()); // snaps to the sibling's 4-space indent
        assertEquals(21, e.from());
        assertEquals(21, e.to());
    }

    @Test
    void smartTabMidLineInsertsUnitAtCaret() {
        Indenter.TabEdit e = Indenter.smartTab("foo", 3, 3, "java", 4, false);
        assertEquals(3, e.from());
        assertEquals(3, e.to());
        assertEquals("\t", e.replacement());
        assertEquals(4, e.selStart());
    }

    @Test
    void smartTabShiftDedentsCurrentLine() {
        Indenter.TabEdit e = Indenter.smartTab("        x", 8, 8, "java", 4, true); // 8 spaces
        assertEquals(0, e.from());
        assertEquals(4, e.to()); // removes one 4-space unit
        assertEquals("", e.replacement());
        assertEquals(4, e.selStart());
    }

    @Test
    void smartTabBlockIndentsSelectedLinesAndDedents() {
        Indenter.TabEdit ind = Indenter.smartTab("a\nb\nc", 0, 3, "java", 4, false);
        assertEquals("\ta\n\tb", ind.replacement());
        assertEquals(0, ind.from());
        Indenter.TabEdit ded = Indenter.smartTab("\ta\n\tb\nc", 0, 5, "java", 4, true);
        assertEquals("a\nb", ded.replacement());
    }

    @Test
    void smartTabBlockSkipsBlankLines() {
        Indenter.TabEdit e = Indenter.smartTab("a\n\nb", 0, 4, "java", 4, false);
        assertEquals("\ta\n\n\tb", e.replacement()); // the empty middle line is left alone
    }
}

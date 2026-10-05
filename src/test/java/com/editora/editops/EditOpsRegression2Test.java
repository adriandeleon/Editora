package com.editora.editops;

import com.editora.editops.Indenter.Style;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression cases for the pure editing operations found in the third review round (findings E2-*). */
class EditOpsRegression2Test {

    // --- structural motion (E2-15) ------------------------------------------------------------------

    @Test
    void anApostropheInACommentDoesNotSwallowTheClosingBrace() {
        String t = "f() { // don't\n  a();\n}\nint g() { return 'x'; }\nint h() {}\n";
        assertEquals(t.indexOf("}\nint g") + 1, SexpNav.forward(t, 4));
        assertEquals(4, SexpNav.backward(t, t.indexOf("}\nint g") + 1));
        // A quote with no partner on its line is an ordinary character…
        String unterminated = "(a \" b)\n(c \" d)";
        assertEquals(7, SexpNav.forward(unterminated, 0));
        // …while a real string still hides the bracket inside it, prefixed or not.
        assertEquals(9, SexpNav.forward("(a ')' b) c", 0));
        assertEquals(10, SexpNav.forward("(a r')' b) c", 0));
        assertEquals(0, SexpNav.backward("(a '(' b)", 9));
    }

    @Test
    void aRustLifetimeIsNotAnOpenString() {
        String t = "fn f<'a>(x: &'a str) -> &'a str {\n    x\n}\nfn g() {}\n";
        assertEquals(t.indexOf(")") + 1, SexpNav.forward(t, t.indexOf('(')));
        assertEquals(t.indexOf("}\nfn g") + 1, SexpNav.forward(t, t.indexOf('{')));
    }

    @Test
    void killSexpRefusesAnUnbalancedBracketInsteadOfTakingTheRestOfTheBuffer() {
        String t = "x (a b\nmore text\n";
        assertEquals(t.length(), SexpNav.forward(t, 1), "motion still runs to the end");
        assertEquals(1, SexpNav.forwardBalanced(t, 1), "the span commands treat it as a no-op");
        assertEquals(7, SexpNav.forwardBalanced("x (a b) c", 1));
        assertEquals(3, SexpNav.forwardBalanced("foo bar", 0));
    }

    // --- fill (E2-9 … E2-14) ------------------------------------------------------------------------

    private static String fill(String text, int caret, int column, String lineComment, Filler.Mode mode) {
        Filler.Edit e = Filler.fillParagraph(text, caret, column, lineComment, mode, 4);
        return e == null ? text : text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    private static String fillRegion(String text, int start, int end, int column, String lc, Filler.Mode mode) {
        Filler.Edit e = Filler.fillRegion(text, start, end, column, lc, mode, 4);
        return e == null ? text : text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void fillingAListItemLeavesItsSiblingsAlone() {
        String dashes = "- one\n- two\n- three";
        assertEquals(dashes, fill(dashes, 3, 70, null, Filler.Mode.MARKDOWN));
        String numbered = "1. one\n2) two";
        assertEquals(numbered, fill(numbered, 3, 70, null, Filler.Mode.MARKDOWN));
        assertEquals(dashes, fill(dashes, 3, 70, null, Filler.Mode.TEXT), "plain text lists too");
        // The item itself still fills, hanging under its text and taking the plain lines below it.
        assertEquals(
                "- alpha beta\n  gamma delta\n- two",
                fill("- alpha beta gamma\ndelta\n- two", 3, 13, null, Filler.Mode.MARKDOWN));
        assertEquals(
                "  10. alpha beta\n      gamma\n", fill("  10. alpha beta gamma\n", 8, 16, null, Filler.Mode.MARKDOWN));
        // A paragraph that touches a list stops at it.
        assertEquals("some text here\n- one", fill("some text\nhere\n- one", 0, 70, null, Filler.Mode.MARKDOWN));
        assertEquals(dashes, fillRegion(dashes, 0, dashes.length(), 70, null, Filler.Mode.MARKDOWN));
    }

    @Test
    void markdownStructureIsNeverFilledOrMergedIntoAParagraph() {
        String heading = "# Heading\nSome para\ngraph text.";
        assertEquals("# Heading\nSome para graph text.", fill(heading, 14, 70, null, Filler.Mode.MARKDOWN));
        assertEquals(heading, fill(heading, 2, 70, null, Filler.Mode.MARKDOWN), "the heading itself is not text");
        String setext = "Title\n=====\nbody one\nbody two";
        assertEquals("Title\n=====\nbody one body two", fill(setext, setext.length(), 70, null, Filler.Mode.MARKDOWN));
        assertEquals(setext, fill(setext, 1, 70, null, Filler.Mode.MARKDOWN));
        String table = "| a | b |\n|---|---|\n| 1 | 2 |";
        assertEquals(table, fill(table, 3, 70, null, Filler.Mode.MARKDOWN));
        String fenced = "text\n```java\nint a = 1;\nint b = 2;\n```\nmore\nwords";
        assertEquals(fenced, fill(fenced, fenced.indexOf("int b"), 70, null, Filler.Mode.MARKDOWN));
        assertEquals(
                "text\n```java\nint a = 1;\nint b = 2;\n```\nmore words",
                fillRegion(fenced, 0, fenced.length(), 70, null, Filler.Mode.MARKDOWN));
        assertEquals(
                Filler.Mode.MARKDOWN, Filler.Mode.forLanguage("markdown"), "the fill commands read Markdown as such");
    }

    @Test
    void fillingInCodeOnlyTouchesComments() {
        String java = "int a = 1;\nint b = 2;\nreturn a + b;";
        assertNull(Filler.fillParagraph(java, 3, 70, "//", Filler.Mode.CODE, 4));
        assertEquals(java, fillRegion(java, 0, java.length(), 70, "//", Filler.Mode.CODE));
        String python = "x = 1  # comment\ny = 2";
        assertNull(Filler.fillParagraph(python, 10, 70, "#", Filler.Mode.CODE, 4));
        String doc = "def f():\n    \"\"\"Summary line.";
        assertNull(Filler.fillParagraph(doc, 2, 70, "#", Filler.Mode.CODE, 4));
        // Comments still fill: a run of line comments, and the text of a block comment with or without stars.
        assertEquals(
                "int a;\n// one two\n// three\nint b;",
                fill("int a;\n// one two three\nint b;", 10, 10, "//", Filler.Mode.CODE));
        assertEquals(
                "/**\n * Some long text that\n * should wrap here more\n * words\n */\nvoid f() {}",
                fill(
                        "/**\n * Some long text that should wrap here\n * more words\n */\nvoid f() {}",
                        8,
                        24,
                        "//",
                        Filler.Mode.CODE));
        assertEquals(
                "/*\n   one two three\n*/\nint a;\nint b;",
                fill("/*\n   one two\n   three\n*/\nint a;\nint b;", 6, 70, "//", Filler.Mode.CODE));
        // A line comment that mentions "/*" does not turn the code after it into comment text.
        String glob = "// see src/*.java\nint a;\nint b;";
        assertNull(Filler.fillParagraph(glob, glob.indexOf("int b"), 70, "//", Filler.Mode.CODE, 4));
        assertEquals(Filler.Mode.CODE, Filler.Mode.forLanguage("java"));
        assertEquals(Filler.Mode.CODE, Filler.Mode.forLanguage("python"));
        assertEquals(Filler.Mode.CODE, Filler.Mode.forLanguage("toml"));
        assertEquals(Filler.Mode.TEXT, Filler.Mode.forLanguage("plaintext"));
        assertEquals(Filler.Mode.TEXT, Filler.Mode.forLanguage("html"));
        assertEquals(Filler.Mode.TEXT, Filler.Mode.forLanguage("typst"));
    }

    @Test
    void fillRegionStopsBeforeTheLineASelectionMerelyEndsAt() {
        String two = "aaa bbb\nccc ddd";
        assertEquals(two, fillRegion(two, 0, 8, 20, null, Filler.Mode.TEXT));
        String four = "line one\nline two\nline three\nline four";
        assertEquals(
                "line one\nline two\nline three\nline four",
                fillRegion(four, four.indexOf("line two") + 4, four.indexOf("line three"), 70, null, Filler.Mode.TEXT));
        assertEquals("aaa bbb ccc ddd", fillRegion(two, 0, 9, 20, null, Filler.Mode.TEXT), "a touched line is filled");
    }

    @Test
    void aWordCommentTokenIsAWholeWordWhenFilling() {
        String bat = "REMOTE=1\nREMOTE=2";
        assertEquals("REMOTE=1 REMOTE=2", fill(bat, 3, 70, "REM", Filler.Mode.TEXT));
        assertNull(Filler.fillParagraph(bat, 3, 70, "REM", Filler.Mode.CODE, 4));
        assertEquals("REM one\nREM two", fill("REM one two", 5, 8, "REM", Filler.Mode.CODE));
        assertEquals("rem one\nrem two", fill("rem one two", 5, 8, "REM", Filler.Mode.CODE));
        assertEquals("", Filler.fillPrefix("REMOTE=1", "REM"));
    }

    @Test
    void fillMeasuresDisplayColumns() {
        String cjk = "日本語 日本語 日本語 日本語";
        assertEquals("日本語\n日本語\n日本語\n日本語", fill(cjk, 0, 10, null, Filler.Mode.TEXT));
        // A tab indent is 4 columns here, so only two words fit in 12.
        assertEquals("\t// aa bb\n\t// cc dd", fill("\t// aa bb cc dd", 5, 12, "//", Filler.Mode.CODE));
        assertEquals(6, Filler.width("\tab", 0, 4));
        assertEquals(8, Filler.width("a\tb\t", 0, 4));
    }

    @Test
    void aJavadocBlockTagStartsItsOwnParagraph() {
        String doc = "    /**\n     * Does a thing\n     * here.\n     * @param x the x\n     * @return y\n     */";
        String expected = "    /**\n     * Does a thing here.\n     * @param x the x\n     * @return y\n     */";
        assertEquals(expected, fill(doc, doc.indexOf("Does"), 40, "//", Filler.Mode.CODE));
        assertEquals(doc, fill(doc, doc.indexOf("@param") + 2, 70, "//", Filler.Mode.CODE));
        String wrapped = "/**\n * @param x the x that\n * goes on\n * @return y\n */";
        assertEquals(
                "/**\n * @param x the x that goes on\n * @return y\n */",
                fill(wrapped, wrapped.indexOf("goes"), 70, "//", Filler.Mode.CODE));
    }

    private static String autoFill(String line, int column) {
        AutoFill.Break b = AutoFill.computeProse(line, column, null);
        return b == null ? line : line.substring(0, b.at()) + b.insert() + line.substring(b.at() + b.removeLen());
    }

    @Test
    void autoFillWrapsAListItemUnderItsText() {
        assertEquals("* aaa bbb\n  ccc ddd eee fff", autoFill("* aaa bbb ccc ddd eee fff", 12));
        assertEquals("  - aaa bbb\n    ccc ddd", autoFill("  - aaa bbb ccc ddd", 12));
        assertEquals("12. aaa bbb\n    ccc ddd", autoFill("12. aaa bbb ccc ddd", 12));
        // Never broken between the marker and the item's first word.
        assertEquals("* " + "x".repeat(20) + "\n  yy", autoFill("* " + "x".repeat(20) + " yy", 12));
        // Anything else keeps its own prefix.
        assertEquals("> aaa bbb\n> ccc ddd", autoFill("> aaa bbb ccc ddd", 10));
        assertEquals("  aaa bbb\n  ccc ddd", autoFill("  aaa bbb ccc ddd", 10));
        assertEquals("**bold** aa\nbb", autoFill("**bold** aa bb", 12));
    }

    // --- comment toggle (E2-5 … E2-8) ---------------------------------------------------------------

    private static String toggle(String text, int selStart, int selEnd, String lang) {
        Commenter.Edit e = Commenter.toggle(text, selStart, selEnd, Commenter.styleFor(lang));
        return e == null ? null : text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void linesThatAreAllLineCommentedAreUncommentedWhateverTheSelection() {
        String text = "    // a\n\n    // b";
        assertEquals("    a\n\n    b", toggle(text, 0, text.length(), "java"));
        assertEquals("a\nb\n", toggle("-- a\n-- b\n", 0, 10, "sql"));
        // One uncommented line and the block form is used as before.
        assertEquals("/* // a\nb */", toggle("// a\nb", 0, 6, "java"));
    }

    @Test
    void textHoldingABlockCommentIsNotWrappedInAnother() {
        String java = "int a; /* x */\nint b;";
        String commented = "// int a; /* x */\n// int b;";
        assertEquals(commented, toggle(java, 0, java.length(), "java"));
        assertEquals(java, toggle(commented, 0, commented.length(), "java"), "and toggles back");
        // No line comment to fall back to: refuse rather than produce `/* … /* x */ … */`.
        assertNull(toggle("a { color: red; /* x */ }", 3, 3, "css"));
        assertNull(toggle("<p><!-- a --></p>\n<p>b</p>", 0, 26, "html"));
        // A lone block comment still unwraps.
        assertEquals("a {}", toggle("/* a {} */", 0, 0, "css"));
    }

    @Test
    void blockToggleOnBlankTextAddsNoWhitespace() {
        assertEquals("    /*  */", toggle("    ", 4, 4, "css"));
        Commenter.Edit e = Commenter.toggle("    ", 4, 4, Commenter.styleFor("css"));
        assertEquals(7, e.selStart(), "the caret sits inside the empty comment");
        assertEquals("\n\nx", toggle("\n\nx", 0, 2, "css"), "a blank selection is left as it is");
    }

    @Test
    void aBareCaretKeepsItsPlaceInABlockToggledLine() {
        String html = "<p>a</p>";
        Commenter.Edit wrap = Commenter.toggle(html, 3, 3, Commenter.styleFor("html"));
        assertEquals("<!-- <p>a</p> -->", wrap.replacement());
        assertEquals(8, wrap.selStart(), "still before the `a`");
        assertEquals(wrap.selStart(), wrap.selEnd());
        Commenter.Edit unwrap = Commenter.toggle("  <!-- <p>a</p> -->", 10, 10, Commenter.styleFor("html"));
        assertEquals("<p>a</p>", unwrap.replacement());
        assertEquals(2 + 3, unwrap.selStart());
        // In the opener or the closer: clamped onto the text, never left inside a delimiter.
        assertEquals(
                2,
                Commenter.toggle("  <!-- <p>a</p> -->", 4, 4, Commenter.styleFor("html"))
                        .selStart());
        assertEquals(
                10,
                Commenter.toggle("  <!-- <p>a</p> -->", 19, 19, Commenter.styleFor("html"))
                        .selStart());
        assertEquals(
                3,
                Commenter.toggle("color: red;", 0, 0, Commenter.styleFor("css")).selStart());
    }

    // --- indentation (E2-1, E2-2, E2-4, E2-20, E2-21, E2-22) ----------------------------------------

    @Test
    void aBlockCommentHeaderDoesNotMakeATabIndentedFileSpaceIndented() {
        String go = "/*\n * License\n */\npackage x\n\nfunc main() {\n\tfoo()\n}\n";
        assertEquals("\t", Indenter.detectUnit(go, 4));
        String c = go + "\tif (x) {";
        assertEquals("\n\t\t", Indenter.enterEdit(c, c.length(), "c", 4).insert());
        assertEquals("\t", Indenter.detectUnit("x = 1\n y = 2\n\tz()\n", 4), "one stray space is not an indent");
        assertEquals("    ", Indenter.detectUnit("/*\n * License\n */\nclass A {\n    int x;\n}\n", 4));
    }

    @Test
    void autoIndentUsesTheFilesOwnIndentWidth() {
        String two = "class A {\n  void f() {";
        assertEquals("\n    ", Indenter.enterEdit(two, two.length(), "java", 4).insert());
        assertEquals("  ", Indenter.detectUnit("a:\n  b:\n    c: 1\n  d:\n    e: 2\n", 4));
        // An aligned continuation line is outvoted by the real steps.
        assertEquals("    ", Indenter.detectUnit("f(a,\n  b);\nif (x) {\n    y();\n    if (z) {\n        w();\n", 2));
        assertEquals("    ", Indenter.detectUnit("class A {\n    int x;\n}\n", 2));
        assertEquals("  ", Indenter.detectUnit("    foo();", 2), "no step to measure: the tab size");
        // An EditorConfig override still wins.
        assertEquals(
                "\n     ",
                Indenter.enterEdit(two, two.length(), "java", 4, true, 3).insert());
    }

    private static String closer(Style style, String text, int tabSize, char typed) {
        int ls = text.lastIndexOf('\n') + 1;
        return Indenter.closerAlignIndent(style, text, text.length(), tabSize, text.substring(ls), typed);
    }

    @Test
    void aBracketCloserAlignsToItsOpenerNotToAWrappedStatement() {
        String wrapped = "    void f() {\n        return foo(a,\n                b);\n                ";
        assertEquals("    ", closer(Style.BRACES, wrapped, 4, '}'));
        String chain = "    void f() {\n        list.stream()\n            .map(x)\n            .toList();\n        ";
        assertEquals("    ", closer(Style.BRACES, chain, 4, '}'));
        // A header that wraps aligns to its first line; a lambda block to the line that opens it.
        String header = "    void f(int a,\n            int b) {\n        body();\n        ";
        assertEquals("    ", closer(Style.BRACES, header, 4, '}'));
        String lambda = "    foo\n        .bar(x -> {\n            body();\n            ";
        assertEquals("        ", closer(Style.BRACES, lambda, 4, '}'));
        // Only the typed kind is matched, and brackets in strings and comments do not count.
        String unfinished = "if (x) {\n    foo(\n    ";
        assertEquals("", closer(Style.BRACES, unfinished, 4, '}'));
        String noise = "if (x) {\n    s = \"}\"; // }\n    c = '{'; /* { */\n    ";
        assertEquals("", closer(Style.BRACES, noise, 4, '}'));
        assertEquals("  ", closer(Style.BRACES, "  call(\n      a,\n      b\n      ", 4, ')'));
        // Already aligned: unchanged, and never stepped out to the enclosing block.
        assertEquals("    ", closer(Style.BRACES, "class A {\n    void f() {\n        x();\n    ", 4, '}'));
        // No opener in sight: the indentation rule still answers.
        assertEquals("", closer(Style.BRACES, "foo();\n    ", 4, '}'));
    }

    @Test
    void aKeywordCloserSkipsAWrappedStatementAndIsNotFrozenBesideIt() {
        String shell = "if a; then\n    foo \\\n        bar\n        fi";
        assertEquals("", Indenter.closerAlignIndent(Style.SHELL, shell, shell.length(), 4, "        "));
        String frozen = "if a; then\n    foo \\\n        bar\n    fi";
        assertEquals("", Indenter.closerAlignIndent(Style.SHELL, frozen, frozen.length(), 4, "    "));
        String ruby = "def f\n  foo(a,\n      b)\n  end";
        assertEquals("", Indenter.closerAlignIndent(Style.RUBY, ruby, ruby.length(), 2, "  "));
        // A wrapped header is still the opener.
        String header = "f() {\n    if a \\\n        && b; then\n        c\n        fi";
        assertEquals("    ", Indenter.closerAlignIndent(Style.SHELL, header, header.length(), 4, "        "));
        String callback = "do\n    foo(function()\n        bar()\n        end";
        assertEquals("    ", Indenter.closerAlignIndent(Style.LUA, callback, callback.length(), 4, "        "));
        String lua = "local t = {}\nfunction t.f(a,\n        b)\n    x()\n    end";
        assertEquals("", Indenter.closerAlignIndent(Style.LUA, lua, lua.length(), 4, "    "));
    }

    private static String tab(String withCaret, String language) {
        int caret = withCaret.indexOf('|');
        String text = withCaret.replace("|", "");
        Indenter.TabEdit e = Indenter.smartTab(text, caret, caret, language, 4, false);
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void tabOnACloserLineDoesNotIndentItToBodyLevel() {
        assertEquals("if (x) {\n    foo();\n}", tab("if (x) {\n    foo();\n|}", "java"));
        assertEquals("if (x) {\n    foo();\n} else {", tab("if (x) {\n    foo();\n|} else {", "java"));
        assertEquals("if a; then\n    b\nfi", tab("if a; then\n    b\n|fi", "shell"));
        assertEquals("def f\n    x\nend", tab("def f\n    x\n|end", "ruby"));
        assertEquals("<a>\n    <b/>\n</a>", tab("<a>\n    <b/>\n|</a>", "xml"));
        assertEquals("if x:\n    y()\nelse:", tab("if x:\n    y()\n|else:", "python"));
        // A closer that is too shallow is still snapped up to its opener, and an empty block's stays put.
        assertEquals("    if (x) {\n        foo();\n    }", tab("    if (x) {\n        foo();\n|}", "java"));
        assertEquals("  def f\n  end", tab("  def f\n|end", "ruby"));
        // A body line is unaffected, also when it merely starts like a closer keyword.
        assertEquals("if a; then\n    b\n    finish", tab("if a; then\n    b\n|finish", "shell"));
    }

    private static String enter(String line, String language) {
        return Indenter.enterEdit(line, line.length(), language, 4).insert();
    }

    @Test
    void aTrailingBlockCommentOrLoneApostropheDoesNotHideTheOpener() {
        assertEquals("\n        ", enter("    if (x) { /* why */", "java"));
        assertEquals("\n    ", enter(".a { /* note */", "css"));
        assertEquals("\n    ", enter("if (x) { /* one */ /* two", "java"));
        assertEquals("\n    ", enter("fn f(x: &'a str) { // comment", "rust"));
        assertEquals("\n    ", enter("if (c == '{') { // it's", "java"));
        assertEquals("\n", enter("foo(); /* { */", "java"));
        assertEquals("\n", enter("x = '{'; // don't", "javascript"));
        assertEquals("\n    ", enter("f(/* a */ x, [", "java"), "an inline comment is not a trailing one");
    }

    @Test
    void blockOpenersAreNotGuessedFromALinesLastWord() {
        assertEquals("\n", enter("def foo = 42", "ruby"));
        assertEquals("\n", enter("def sq(x) = x * x", "ruby"));
        assertEquals("\n    ", enter("def foo=(v)", "ruby"));
        assertEquals("\n    ", enter("def foo(a = 1)", "ruby"));
        assertEquals("\n", enter("echo what to do", "shell"));
        assertEquals("\n", enter("echo now and then", "shell"));
        assertEquals("\n    ", enter("if x;then", "shell"));
        assertEquals("\n    ", enter("for i in 1 2 3;do", "shell"));
        assertEquals("\n    ", enter("cat f | while read l; do", "shell"));
        assertEquals("\n    ", enter("do", "shell"));
        assertEquals("\n    ", enter("case $x in", "shell"));
        assertEquals("\n", enter("title: Don't do this # note:", "yaml"));
        assertEquals("\n    ", enter("title: # it's nested", "yaml"));
    }

    // --- expand selection (E2-16) -------------------------------------------------------------------

    @Test
    void expandSelectionGrowsToTheDefinitionItIsIn() {
        String t = "def a():\n    x\n\ndef b():\n    y\n\ndef c():\n    z\n";
        int b = t.indexOf("def b");
        int bEnd = t.indexOf("    y") + 5;
        // From the paragraph `def b…` the next step is not "a and b", nor does it creep into `def c`.
        int[] next = SmartSelect.expand(t, b, bEnd);
        assertArrayEquals(new int[] {0, t.length()}, next);
        // From inside b's body the definition is b alone, without the blank line after it.
        int[] line = SmartSelect.expand(t, t.indexOf("    y"), bEnd);
        assertArrayEquals(new int[] {b, bEnd}, line);
        // Stepping out from the caret never passes through "a and b" or a slice of c.
        int[] sel = {t.indexOf("y"), t.indexOf("y")};
        int[] before = sel;
        while (sel[0] != 0 || sel[1] != t.length()) {
            before = sel;
            sel = SmartSelect.expand(t, sel[0], sel[1]);
        }
        assertArrayEquals(new int[] {b, bEnd}, before);
    }

    // --- case conversion (E2-17, E2-18) -------------------------------------------------------------

    @Test
    void caseConversionKeepsUnderscoreAffixes() {
        assertEquals("_private_var", StringCase.to(StringCase.Style.SNAKE, "_privateVar"));
        assertEquals("_privateVar", StringCase.to(StringCase.Style.CAMEL, "_private_var"));
        assertEquals("__init__", StringCase.to(StringCase.Style.CAMEL, "__init__"));
        assertEquals("__INIT__", StringCase.cycle("__init__"));
        assertEquals("__init__", StringCase.cycle("__INIT__"));
        assertEquals("foo_bar_", StringCase.cycle("fooBar_"));
        assertEquals("___", StringCase.to(StringCase.Style.KEBAB, "___"));
    }

    @Test
    void anAllCapsTokenWithDigitsConvertsBack() {
        assertEquals("I18N", StringCase.cycle("i18n"));
        assertEquals("i18n", StringCase.cycle("I18N"));
        assertEquals("BASE64URL", StringCase.cycle("base64url"));
        assertEquals("base64url", StringCase.cycle("BASE64URL"));
        // A capitalised word after a digit is still a new word.
        assertEquals("base64_url", StringCase.to(StringCase.Style.SNAKE, "base64Url"));
        assertEquals("v2_beta", StringCase.to(StringCase.Style.SNAKE, "V2Beta"));
        assertEquals("utf8_string", StringCase.to(StringCase.Style.SNAKE, "UTF8String"));
    }

    @Test
    void aMinusIsNotPartOfTheCaretTokenWhereItIsAnOperator() {
        assertArrayEquals(new int[] {4, 5}, StringCase.tokenAt("a = i-1;", 5, false));
        assertArrayEquals(new int[] {0, 5}, StringCase.tokenAt("count-1", 2, false));
        assertArrayEquals(new int[] {0, 8}, StringCase.tokenAt("my-class", 2, true), "kebab names keep theirs");
        assertTrue(StringCase.dashIsOperator("java"));
        assertTrue(StringCase.dashIsOperator("python"));
        assertFalse(StringCase.dashIsOperator("css"));
        assertFalse(StringCase.dashIsOperator("shell"));
        assertFalse(StringCase.dashIsOperator("markdown"));
        assertFalse(StringCase.dashIsOperator(null));
    }

    // --- move / duplicate a selected block (E2-19) --------------------------------------------------

    private static String apply(String text, LineOps.BlockEdit e) {
        String out = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        return out.substring(0, e.selStart()) + "[" + out.substring(e.selStart(), e.selEnd()) + "]"
                + out.substring(e.selEnd());
    }

    @Test
    void lineCommandsActOnEveryLineOfASelection() {
        String t = "a\nb1\nb2\nb3\nc";
        int s = t.indexOf("b1") + 1;
        int e = t.indexOf("b3") + 1;
        assertEquals("b[1\nb2\nb]3\na\nc", apply(t, LineOps.moveLinesUp(t, s, e)));
        assertEquals("a\nc\nb[1\nb2\nb]3", apply(t, LineOps.moveLinesDown(t, s, e)));
        assertEquals("a\nb1\nb2\nb3\nb[1\nb2\nb]3\nc", apply(t, LineOps.duplicateLines(t, s, e)));
        // Whole lines selected the usual way (the selection ends at the next line's start).
        int ws = t.indexOf("b1");
        int we = t.indexOf("c");
        assertEquals("[b1\nb2\nb3\n]a\nc", apply(t, LineOps.moveLinesUp(t, ws, we)));
        assertEquals("a\nc\n[b1\nb2\nb3]", apply(t, LineOps.moveLinesDown(t, ws, we)));
        assertEquals("a\nb1\nb2\nb3\n[b1\nb2\nb3\n]c", apply(t, LineOps.duplicateLines(t, ws, we)));
        assertNull(LineOps.moveLinesUp(t, 0, 3));
        assertNull(LineOps.moveLinesDown(t, t.indexOf("b3"), t.length()));
    }

    // --- tags and tabs (E2-23, E2-24) ---------------------------------------------------------------

    @Test
    void aComparisonInAnInlineScriptIsNotATag() {
        assertNull(TagAutoClose.closer("<script>items.filter(x => x<3).map(y =", true));
        assertNull(TagAutoClose.closer("<script>\nif (i<n) return x", true));
        assertNull(TagAutoClose.closer("<script>\nlet t = a<b", true), "anything in raw text is code");
        assertNull(TagAutoClose.closer("if (a<b) x", false));
        assertNull(TagAutoClose.closer("x<3", false));
        // The raw-text element's own tags, and markup after it, still close.
        assertEquals("</script>", TagAutoClose.closer("<script type=\"module\"", true));
        assertEquals("</p>", TagAutoClose.closer("<script>a<b</script>\n<p", true));
        assertEquals("</style>", TagAutoClose.closer("<SCRIPT>x</SCRIPT><style", true));
        assertEquals("</Foo.Bar>", TagAutoClose.closer("<Foo.Bar", false));
        assertEquals("</a:b>", TagAutoClose.closer("<a:b x=\"1\"", false));
    }

    @Test
    void tabifyLeavesStringContentsAlone() {
        assertEquals("\t\tx  \"a    b\"", TabConvert.tabify("        x  \"a    b\"", 4));
        assertEquals("ab\tc = 'x    y'\n\tz", TabConvert.tabify("ab  c = 'x    y'\n    z", 4), "per line");
    }
}

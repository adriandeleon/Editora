package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enter and a typed closer inside an HTML page's {@code <script>} and {@code <style>} follow braces (HTML review C10). */
class IndentWindowHtmlTest {

    private static String enter(String text, String language) {
        return IndentWindow.enterEdit(IndentWindow.of(text), text.length(), language, "  ")
                .insert();
    }

    @Test
    void enterAfterAnOpeningBraceInAScriptIndentsTheBody() {
        assertEquals("\n    ", enter("<body>\n<script>\n  function f() {", "html"));
        assertEquals("\n    ", enter("<style>\n  a {", "html"));
        // The body line keeps its indent, as in a .js file.
        assertEquals("\n    ", enter("<script>\n  function f() {\n    return 1;", "html"));
        assertEquals("\n    ", enter("<script>\n  function f() {", "html-template"));
    }

    @Test
    void theMarkupStillIndentsAsMarkup() {
        assertEquals("\n    ", enter("<div>\n  <script>", "html"), "the start tag's own line is markup");
        assertEquals("\n  ", enter("<div>\n  <p>a</p>", "html"));
        assertEquals("\n  ", enter("<style>a{}</style>\n  <p>a {", "html"), "after a closed style");
    }

    @Test
    void aClosingBraceTypedInAScriptStepsBackToItsOpener() {
        String text = "<div>\n  <script>\n    function f() {\n      return 1;\n      ";
        IndentWindow.Doc doc = IndentWindow.of(text);
        Indenter.Style style = IndentWindow.styleAt(doc, text.length(), "html", '}');
        assertEquals(Indenter.Style.BRACES, style);
        assertEquals("    ", IndentWindow.closerAlignIndent(style, doc, text.length(), 4, "      ", '}', "  ", "html"));
        assertEquals(Indenter.Style.XML, IndentWindow.styleAt(doc, text.length(), "html", 'x'), "not a closer");
        assertEquals(Indenter.Style.XML, IndentWindow.styleAt(IndentWindow.of("<p>"), 3, "html", '}'));
    }

    /** The text after Tab at the {@code |} of {@code marked}, with {@code |} where the caret ends. */
    private static String tab(String marked) {
        int caret = marked.indexOf('|');
        String text = marked.substring(0, caret) + marked.substring(caret + 1);
        Indenter.TabEdit e = IndentWindow.tabEdit(IndentWindow.of(text), caret, caret, "html", 4, false, "  ");
        String out = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        return out.substring(0, e.selStart()) + "|" + out.substring(e.selStart());
    }

    /** Enter at the {@code |} of {@code marked}: the text with the insert, {@code |} where the caret ends. */
    private static String enterAt(String marked) {
        int caret = marked.indexOf('|');
        String text = marked.substring(0, caret) + marked.substring(caret + 1);
        Indenter.EnterEdit e = IndentWindow.enterEdit(IndentWindow.of(text), caret, "html", "  ");
        String out = text.substring(0, caret) + e.insert() + text.substring(caret);
        int at = caret + (e.caretOffset() >= 0 ? e.caretOffset() : e.insert().length());
        return out.substring(0, at) + "|" + out.substring(at);
    }

    /** Type the tag (it auto-closes), then Enter: the end tag goes on a line of its own again (HTML review 2 C105). */
    @Test
    void enterBetweenAStyleOrScriptAndItsEndTagSplitsThem() {
        String div = enterAt("<head>\n  <div>|</div>");
        assertEquals(div.replace("div", "style"), enterAt("<head>\n  <style>|</style>"));
        assertEquals(div.replace("div", "script"), enterAt("<head>\n  <script>|</script>"));
        assertEquals("<head>\n  <style>\n    |\n  </style>", enterAt("<head>\n  <style>|</style>"));
        // Still the brace rules once inside the code.
        assertEquals("<style>\n  a {\n    |", enterAt("<style>\n  a {|"));
    }

    /** Tab inside a script or style follows the braces, as Enter does and as in a .js file (HTML review 2 C106). */
    @Test
    void tabInAScriptOrStyleFollowsTheBraces() {
        assertEquals(
                "<script>\n  if (a) {\n    |return 1;\n  }\n</script>",
                tab("<script>\n  if (a) {\n|return 1;\n  }\n</script>"));
        assertEquals(
                "<style>\n  a {\n    |color: red;\n  }\n</style>", tab("<style>\n  a {\n|color: red;\n  }\n</style>"));
        assertEquals(
                "<body>\n  <script>\n    if (a) {\n      |\n    }\n  </script>\n</body>",
                tab("<body>\n  <script>\n    if (a) {\n|\n    }\n  </script>\n</body>"));
        assertEquals(
                "<div>\n  <script>\n    function f() {\n      if (x) {\n        |y();",
                tab("<div>\n  <script>\n    function f() {\n      if (x) {\n|y();"));
        // The first code line and the end tag's line indent as markup.
        assertEquals("<div>\n  <script>\n    |go();", tab("<div>\n  <script>\n|go();"));
        assertEquals("<div>\n  <script>\n    go();\n  |</script>", tab("<div>\n  <script>\n    go();\n|</script>"));
    }

    /**
     * A line in a {@code <pre>} or {@code <textarea>} is content: Tab keeps its indent (the caret goes to the indent's
     * end) instead of re-indenting it as markup, inserts one unit on a blank line or mid-line, and never asks the
     * server (HTML review 2 B102).
     */
    @Test
    void tabInAVerbatimElementKeepsTheLineAndTheServerIsNotAsked() {
        assertEquals("<div>\n<pre>\n|x\n</pre>", tab("<div>\n<pre>\n|x\n</pre>"));
        assertEquals("<div>\n<pre>\n   |x\n</pre>", tab("<div>\n<pre>\n |  x\n</pre>"), "to the indent's end");
        assertEquals("<pre>\n  x\n|</pre>", tab("<pre>\n  x\n|</pre>"), "the end tag's line is content too");
        assertEquals("<pre>\n  |\n</pre>", tab("<pre>\n|\n</pre>"), "a blank line: one unit, as in plain text");
        assertEquals("<pre>\nx  |y\n</pre>", tab("<pre>\nx|y\n</pre>"), "mid-line: one unit");
        assertEquals("<textarea>\n|raw\n</textarea>", tab("<textarea>\n|raw\n</textarea>"));
        assertEquals(
                "<script type=\"text/x-template\">\n|<p>",
                tab("<script type=\"text/x-template\">\n|<p>"),
                "a template script: kept as typed");

        String page = "<body>\n  <pre>\n  x\n</pre>\n  <script>\n    f();\n  </script>\n<p>a</p>\n</body>";
        IndentWindow.Doc doc = IndentWindow.of(page);
        assertTrue(IndentWindow.serverReindents(doc, page.indexOf("<pre>"), "html"), "the start tag's line is markup");
        assertFalse(IndentWindow.serverReindents(doc, page.indexOf("x"), "html"));
        assertFalse(IndentWindow.serverReindents(doc, page.indexOf("</pre>"), "html"));
        assertFalse(IndentWindow.serverReindents(doc, page.indexOf("f();"), "html"));
        assertFalse(IndentWindow.serverReindents(doc, page.indexOf("</script>"), "html"));
        assertTrue(IndentWindow.serverReindents(doc, page.indexOf("<p>"), "html"));
        assertTrue(IndentWindow.serverReindents(doc, page.indexOf("<p>"), "html-template"));
        assertTrue(IndentWindow.serverReindents(IndentWindow.of(page), page.indexOf("x"), "xml"), "not HTML");
        assertTrue(IndentWindow.serverReindents(IndentWindow.of(""), 0, "html"));
    }

    /** Reviewer C's 42 KB page (rw/longscript/app.html): 300 handlers in one inline script, then {@code last}. */
    private static String longScript(String last) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html>\n<html>\n<body>\n  <div id=\"app\"></div>\n  <script>\n");
        for (int i = 0; i < 300; i++) {
            sb.append("    function handler")
                    .append(i)
                    .append("(event) {\n")
                    .append("      if (event.target.value.length > ")
                    .append(i)
                    .append(") {\n")
                    .append("        return event.target.value.slice(0, ")
                    .append(i)
                    .append(");\n")
                    .append("      }\n    }\n");
        }
        return sb.append(last).append("  </script>\n</body>\n</html>\n").toString();
    }

    /**
     * HTML review 3 C205: in a script longer than a back-scan (about 250 lines), and after a script string that holds
     * {@code '<style>…</style>'} or {@code '<pre>'}, Tab and Enter lost the brace rules — Tab used markup rules and
     * asked the server again, or kept the line as if in a {@code <pre>}.
     */
    @Test
    void longScriptsAndScriptsWithTagsInTheirStringsKeepTheBraceRules() {
        String page = longScript("    function last(a) {\n      if (a) {\n|return 1;\n      }\n    }\n");
        assertTrue(page.length() > 40_000);
        String tabbed = tab(page);
        assertTrue(tabbed.contains("      if (a) {\n        |return 1;\n"), "Tab: under the if, +1 unit");
        String text = page.replace("|", "");
        assertFalse(IndentWindow.serverReindents(IndentWindow.of(text), text.indexOf("return 1;"), "html"));
        String open = longScript("    function last(a) {|\n    }\n");
        int caret = open.indexOf('|');
        String plain = open.replace("|", "");
        assertEquals(
                "\n      ",
                IndentWindow.enterEdit(IndentWindow.of(plain), caret, "html", "  ")
                        .insert(),
                "Enter: +1 unit");

        assertEquals(
                "<div>\n<script>\n  s = '<style>a{}</style>';\n  if (a) {\n    |return 1;\n  }\n</script>\n</div>",
                tab("<div>\n<script>\n  s = '<style>a{}</style>';\n  if (a) {\n|return 1;\n  }\n</script>\n</div>"),
                "a style tag in a string is text");
        assertEquals(
                "<div>\n<script>\n  el.innerHTML = '<pre>';\n  if (a) {\n    |b();\n  }\n</script>\n</div>",
                tab("<div>\n<script>\n  el.innerHTML = '<pre>';\n  if (a) {\n|b();\n  }\n</script>\n</div>"),
                "so is a pre tag");
        String pre = "<div>\n<script>\n  el.innerHTML = '<pre>';\n  if (a) {\nb();\n  }\n</script>\n</div>";
        assertFalse(IndentWindow.serverReindents(IndentWindow.of(pre), pre.indexOf("b();"), "html"));
        assertEquals(
                "\n    ",
                enter("<script>\n  s = '<style>a{}</style>';\n  function f() {", "html"),
                "Enter after a style tag in a string");
        assertEquals(
                "<script>s = '<pre>';</script>\n<div>\n  |<p>x</p>\n</div>",
                tab("<script>s = '<pre>';</script>\n<div>\n|<p>x</p>\n</div>"),
                "after the script, the pre in its string opened nothing: markup");
        assertEquals("<style>a{}</style>\n<pre>\n|x\n</pre>", tab("<style>a{}</style>\n<pre>\n|x\n</pre>"));
    }

    @Test
    void verbatimBeforeReadsBackToTheLastStartOrEndTag() {
        assertEquals("pre", IndentWindow.verbatimBefore("<div><pre class=\"x\">\n<code>a</code>\n"));
        assertNull(IndentWindow.verbatimBefore("<pre>a</pre>\n"));
        assertNull(IndentWindow.verbatimBefore("<!-- <pre> -->\n"), "a mention in a comment");
        assertNull(IndentWindow.verbatimBefore("<preview>\n"), "another element");
        assertNull(IndentWindow.verbatimBefore("<pre\n"), "the start tag is not whole");
        assertNull(IndentWindow.verbatimBefore("<script src=x />\n"), "self-closed");
        assertNull(IndentWindow.verbatimBefore("a < b\n"));
        assertEquals("textarea", IndentWindow.verbatimBefore("<TEXTAREA>\n"));
    }
}

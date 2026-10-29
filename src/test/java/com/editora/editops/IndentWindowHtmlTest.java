package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

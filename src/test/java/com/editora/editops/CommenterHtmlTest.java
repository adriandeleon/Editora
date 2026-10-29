package com.editora.editops;

import com.editora.editops.Commenter.Edit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Toggle Comment by what the selection is in: HTML, an embedded script or style, or PHP (HTML review C3, C7). */
class CommenterHtmlTest {

    private static final String PAGE = "<p>hi</p>\n" // 0
            + "<style>\n" // 1
            + "    body { colr: red; }\n" // 2
            + "</style>\n" // 3
            + "<script>\n" // 4
            + "function greet(name) {\n" // 5
            + "    return mesage;\n" // 6
            + "}\n" // 7
            + "</script>\n" // 8
            + "<p>bye</p>\n"; // 9

    private static String apply(String text, int selStart, int selEnd, String lang) {
        Edit e = Commenter.toggle(text, selStart, selEnd, lang);
        if (e == null) {
            return text;
        }
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    private static String line(String text, int n) {
        return text.split("\n", -1)[n];
    }

    @Test
    void aScriptLineGetsAJavaScriptComment() {
        int at = PAGE.indexOf("return");
        String out = apply(PAGE, at, at, "html");
        assertEquals("    // return mesage;", line(out, 6));
        assertEquals(PAGE, apply(out, out.indexOf("return"), out.indexOf("return"), "html"), "round trip");
    }

    @Test
    void aStyleLineGetsACssComment() {
        int at = PAGE.indexOf("body");
        assertEquals("    /* body { colr: red; } */", line(apply(PAGE, at, at, "html"), 2));
    }

    @Test
    void severalScriptLinesAreCommentedAsScript() {
        int from = PAGE.indexOf("function");
        int to = PAGE.indexOf("}\n</script>") + 1;
        String out = apply(PAGE, from, to, "html");
        assertFalse(out.contains("<!--"), out);
        assertTrue(out.contains("/* function greet(name) {") || out.contains("// function greet(name) {"), out);
    }

    @Test
    void markupKeepsTheHtmlComment() {
        assertEquals("<!-- <p>hi</p> -->", line(apply(PAGE, 1, 1, "html"), 0));
        // The whole <script> element, tags included, is markup: an HTML comment takes it out of the page.
        int from = PAGE.indexOf("<script>");
        int to = PAGE.indexOf("</script>") + "</script>".length();
        String out = apply(PAGE, from, to, "html");
        assertTrue(out.contains("<!-- <script>"), out);
    }

    @Test
    void aSelectionFromTheMarkupIntoAScriptIsRefused() {
        int from = PAGE.indexOf("<p>hi");
        int to = PAGE.indexOf("return");
        assertNull(Commenter.toggle(PAGE, from, to, "html"));
        assertTrue(Commenter.mixesLanguages(PAGE, from, to, "html"));
        assertFalse(Commenter.mixesLanguages(PAGE, 0, 3, "html"));
    }

    @Test
    void anInlineScriptCommentsOnlyItsCode() {
        String html = "<script>track();</script>\n";
        int at = html.indexOf("track");
        Edit e = Commenter.toggle(html, at, at, "html");
        String out = html.substring(0, e.from()) + e.replacement() + html.substring(e.to());
        assertEquals("<script>/* track(); */</script>\n", out);
        assertEquals(at + 3, e.selStart(), "the caret stays on the same character");
    }

    @Test
    void phpCommentsItsHtmlAsHtmlAndItsCodeAsPhp() {
        String php = "<p>hello</p>\n<?php\necho 1;\n?>\n";
        assertEquals("<!-- <p>hello</p> -->", line(apply(php, 1, 1, "php"), 0));
        int at = php.indexOf("echo");
        assertEquals("// echo 1;", line(apply(php, at, at, "php"), 2));
    }

    @Test
    void templatesUseHtmlComments() {
        assertEquals("<!-- {{ name }} -->", apply("{{ name }}", 0, 0, "html-template"));
        assertEquals("<!-- <b>x</b> -->", apply("<b>x</b>", 0, 0, "handlebars"));
    }
}

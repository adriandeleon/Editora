package com.editora.text;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The embedded-language scan behind HTML comments, indentation and spell checking (HTML review C3, C10, C13). */
class HtmlRegionsTest {

    private static final String PAGE = "<p>x</p>\n" // 0
            + "<style>\n" // 9
            + "body { color: red; }\n"
            + "</style>\n"
            + "<script type=\"module\">\n"
            + "if (a<b) { x = '<span>'; }\n"
            + "</script>\n"
            + "<script type=\"application/ld+json\">{\"a\": 1}</script>\n"
            + "<script type=\"text/x-template\"><p>t</p></script>\n"
            + "<!-- <script> in a comment -->\n"
            + "<p>after</p>\n";

    @Test
    void theLanguageAtAnOffsetFollowsTheElementItIsIn() {
        assertEquals("html", HtmlRegions.languageAt(PAGE, PAGE.indexOf("x</p>"), "html"));
        assertEquals("css", HtmlRegions.languageAt(PAGE, PAGE.indexOf("color"), "html"));
        assertEquals("javascript", HtmlRegions.languageAt(PAGE, PAGE.indexOf("a<b"), "html"));
        assertEquals("javascript", HtmlRegions.languageAt(PAGE, PAGE.indexOf("<span>"), "html"));
        assertEquals("json", HtmlRegions.languageAt(PAGE, PAGE.indexOf("\"a\""), "html"));
        assertEquals("html", HtmlRegions.languageAt(PAGE, PAGE.indexOf("<p>t"), "html"), "a template script");
        assertEquals("html", HtmlRegions.languageAt(PAGE, PAGE.indexOf("in a comment"), "html"));
        assertEquals("html", HtmlRegions.languageAt(PAGE, PAGE.indexOf("after"), "html"));
        assertEquals("java", HtmlRegions.languageAt("int x;", 2, "java"), "not HTML: unchanged");
    }

    @Test
    void phpBlocksAreCodeAndTheRestIsHtml() {
        String php = "<p>hello</p>\n<?php\necho 1;\n?>\n<div><?= $x ?></div>\n<script>var a;</script>";
        assertEquals("html", HtmlRegions.languageAt(php, 3, "php"));
        assertEquals("php", HtmlRegions.languageAt(php, php.indexOf("echo"), "php"));
        assertEquals("php", HtmlRegions.languageAt(php, php.indexOf("$x"), "php"));
        assertEquals("javascript", HtmlRegions.languageAt(php, php.indexOf("var"), "php"));
        assertEquals("html", HtmlRegions.languageAt(php, php.indexOf("<div>"), "php"));
    }

    @Test
    void chosenElementsAndAttributesAreReported() {
        String html = "<p>Use <code>cnst</code> and <img alt=\"a lgo\" src=x.png> <a title='Clik'>x</a>"
                + " <pre><code>raw</code></pre></p>";
        List<HtmlRegions.Region> regions =
                HtmlRegions.scan(html, false, html.length(), Set.of("code", "pre"), Set.of("alt", "title"));
        assertTrue(contains(regions, html, HtmlRegions.Kind.ELEMENT, "cnst"));
        assertTrue(contains(regions, html, HtmlRegions.Kind.ELEMENT, "<code>raw</code>"));
        assertTrue(contains(regions, html, HtmlRegions.Kind.ATTRIBUTE, "a lgo"));
        assertTrue(contains(regions, html, HtmlRegions.Kind.ATTRIBUTE, "Clik"));
        assertFalse(contains(regions, html, HtmlRegions.Kind.ATTRIBUTE, "x.png"), "src is not asked for");
    }

    @Test
    void aTagMentionedInACommentOpensNothing() {
        String html = "<!-- drop the <pre> wrapper -->\n<p>Speling</p>";
        List<HtmlRegions.Region> regions = HtmlRegions.scan(html, false, html.length(), Set.of("pre"), Set.of());
        assertEquals(1, regions.size());
        assertEquals(HtmlRegions.Kind.COMMENT, regions.get(0).kind());
    }

    @Test
    void embeddedBeforeReadsBackToTheNearestScriptOrStyle() {
        String html = "<style>\na {\n</style>\n<script>\nfunction f() {\n";
        int[] start = new int[1];
        assertEquals("javascript", HtmlRegions.embeddedBefore(html, html.length(), start));
        assertEquals(html.indexOf("<script>") + "<script>".length(), start[0]);
        assertEquals("css", HtmlRegions.embeddedBefore(html, html.indexOf("</style>"), null));
        assertNull(HtmlRegions.embeddedBefore(html, html.indexOf("<script>"), null), "after a closed style");
        assertNull(HtmlRegions.embeddedBefore("<!-- <script> -->\nx", 19, null), "in a comment");
        assertNull(HtmlRegions.embeddedBefore("<script type=\"text/x-template\">\n<p>", 34, null));
    }

    @Test
    void theElementTableIsCaseInsensitive() {
        assertTrue(HtmlElements.isVoid("BR"));
        assertTrue(HtmlElements.isRawText("Script"));
        assertTrue(HtmlElements.isVerbatim("pre"));
        assertFalse(HtmlElements.isRawText("pre"), "a <pre> holds markup");
        assertTrue(HtmlElements.hasOptionalEnd("LI"));
        assertEquals(Set.of("li", "p"), HtmlElements.impliedEnds("li"));
        assertNull(HtmlElements.impliedEnds("span"));
        assertTrue(HtmlElements.isHtmlLanguage("html-template"));
        assertTrue(HtmlElements.isHtmlLanguage("handlebars"));
        assertFalse(HtmlElements.isHtmlLanguage("xml"));
    }

    private static boolean contains(List<HtmlRegions.Region> regions, String text, HtmlRegions.Kind kind, String body) {
        return regions.stream()
                .anyMatch(r ->
                        r.kind() == kind && text.substring(r.start(), r.end()).equals(body));
    }
}

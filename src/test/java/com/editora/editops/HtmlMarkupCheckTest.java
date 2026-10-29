package com.editora.editops;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Editora's own HTML markup check: unclosed elements, stray end tags, missing link targets (HTML review D5). */
class HtmlMarkupCheckTest {

    private static final Path PAGE = Path.of("/site/index.html").toAbsolutePath();

    private static List<String> kinds(String text) {
        return HtmlMarkupCheck.check(text, null, null, null).stream()
                .map(p -> p.kind() + " " + p.subject())
                .toList();
    }

    @Test
    void anUnclosedDivIsReportedOnItsNameAtTheEndTagThatClosesItsParent() {
        String text = "<main>\n  <div><p>unclosed div\n</main>";
        List<HtmlMarkupCheck.Problem> problems = HtmlMarkupCheck.check(text, null, null, null);
        assertEquals(1, problems.size(), problems.toString());
        HtmlMarkupCheck.Problem p = problems.get(0);
        assertEquals(HtmlMarkupCheck.Kind.UNCLOSED, p.kind());
        assertEquals("div", p.subject());
        assertEquals("div", text.substring(p.start(), p.end()));
    }

    @Test
    void legalHtmlIsClean() {
        String text = """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>a < b</title>
                <style>p > a { color: red }</style>
                <script>if (a < b && c > d) { document.write("<div>"); }</script>
                </head><body>
                <!-- <div> in a comment -->
                <ul><li>One<li>Two</li></ul>
                <table><tr><td>a<td>b</tr></table>
                <p>para<p>another
                <br><img src="x.png"><input type=text>
                <svg><path d="M0 0"/><circle r="1"></circle></svg>
                <textarea><b>not markup</textarea>
                <dl><dt>term<dd>def</dl>
                <select><option>a<option>b</select>
                if 1 < 2 then
                </body></html>
                """;
        assertEquals(List.of(), kinds(text));
    }

    @Test
    void aStrayEndTagAndANestingErrorAreReported() {
        assertEquals(List.of("STRAY_END span"), kinds("<div>text</span></div>"));
        assertEquals(List.of("UNCLOSED b"), kinds("<p><b>bold <i>it</i></p>"));
        assertEquals(List.of("UNCLOSED section"), kinds("<section><h1>t</h1>"));
        assertEquals(List.of("STRAY_END br"), kinds("<p>a</br>b</p>"));
        assertEquals(List.of("UNCLOSED div"), kinds("<DIV>x"));
        assertEquals(List.of(), kinds("<DIV>x</div>"), "HTML names are case-insensitive");
    }

    @Test
    void localLinksWhoseFileIsMissingAreReported() {
        Set<Path> there = Set.of(
                PAGE.getParent().resolve("css/site.css"),
                PAGE.getParent().resolve("pages/about.html"),
                PAGE.getParent().resolve("img/logo.svg"));
        String text = """
                <link rel="stylesheet" href="css/site.css">
                <a href="pages/about.html#team">Team</a>
                <a href="missing.html">Missing</a>
                <a href="https://example.com/">External</a>
                <a href="/about">a route</a>
                <a href="#top">top</a>
                <a href="mailto:a@b.c">mail</a>
                <form action="/submit.php"></form>
                <img src="img/logo.svg"><img src="img/hero.png?x=1">
                """;
        List<HtmlMarkupCheck.Problem> problems = HtmlMarkupCheck.check(text, PAGE, null, there::contains);
        assertEquals(
                List.of("missing.html", "img/hero.png?x=1"),
                problems.stream().map(HtmlMarkupCheck.Problem::subject).toList());
        assertTrue(problems.stream().allMatch(p -> p.kind() == HtmlMarkupCheck.Kind.MISSING_FILE));
        HtmlMarkupCheck.Problem first = problems.get(0);
        assertEquals("missing.html", text.substring(first.start(), first.end()));
        assertEquals(List.of(), HtmlMarkupCheck.check(text, null, null, there::contains), "untitled: no folder");
        assertEquals(List.of(), HtmlMarkupCheck.check(text, PAGE, null, null), "a remote page: links unchecked");
    }

    @Test
    void theReportIsBounded() {
        String text = "<div>".repeat(HtmlMarkupCheck.MAX_PROBLEMS + 50);
        assertEquals(
                HtmlMarkupCheck.MAX_PROBLEMS,
                HtmlMarkupCheck.check(text, null, null, null).size());
    }

    @Test
    void onlyALastSegmentWithAnExtensionNamesAFile() {
        assertTrue(HtmlMarkupCheck.namesAFile("a/b.html"));
        assertTrue(HtmlMarkupCheck.namesAFile("b.css?x=1#y"));
        assertTrue(!HtmlMarkupCheck.namesAFile("/about"));
        assertTrue(!HtmlMarkupCheck.namesAFile("docs/"));
        assertTrue(!HtmlMarkupCheck.namesAFile("v1.2/about"));
    }
}

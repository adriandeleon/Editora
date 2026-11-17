package com.editora.editops;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An HTML page's href/src links and where each one points (HTML review B6, C8, D4). */
class HtmlLinksTest {

    private static final Path PAGE = Path.of("/site/index.html").toAbsolutePath();
    private static final Path DIR = PAGE.getParent();

    private static final String FIXTURE = """
            <!doctype html>
            <html><head>
              <link rel="stylesheet" href="css/site.css">
              <style>a { color: red } /* <a href="not-a-link.html"> */</style>
            </head><body>
              <!-- <a href="commented.html">no</a> -->
              <a href="pages/about.html#team">Team</a>
              <a href='missing.html'>Missing</a>
              <img src=img/logo.svg alt=logo>
              <script src="js/app.js"></script>
              <script>var s = '<a href="in-script.html">';</script>
            </body></html>
            """;

    @Test
    void everyHrefAndSrcIsFoundButNotInCommentsStylesOrScripts() {
        List<String> values =
                HtmlLinks.all(FIXTURE).stream().map(HtmlLinks.Link::value).toList();
        assertEquals(
                List.of("css/site.css", "pages/about.html#team", "missing.html", "img/logo.svg", "js/app.js"), values);
    }

    @Test
    void theLinkAtTheCaretIsTheValueItIsIn() {
        int inTeam = FIXTURE.indexOf("about.html#team") + 3;
        HtmlLinks.Link link = HtmlLinks.at(FIXTURE, inTeam);
        assertNotNull(link);
        assertEquals("pages/about.html#team", link.value());
        assertEquals("a", link.element());
        assertEquals("href", link.attribute());
        // Both edges of the value count: a click just before the closing quote still follows it.
        assertNotNull(HtmlLinks.at(FIXTURE, FIXTURE.indexOf("pages/about")));
        assertNotNull(HtmlLinks.at(FIXTURE, FIXTURE.indexOf("#team\"") + 5));
        assertNull(HtmlLinks.at(FIXTURE, FIXTURE.indexOf(">Team<") + 2), "the link's text is not its value");
        assertNotNull(HtmlLinks.at(FIXTURE, FIXTURE.indexOf("img/logo") + 2), "an unquoted value");
        assertTrue(HtmlLinks.at(FIXTURE, FIXTURE.indexOf("css/site")).asset(), "a stylesheet is an asset");
    }

    @Test
    void relativeLinksResolveAgainstThePageAndKeepTheirFragment() {
        HtmlLinks.Target t = HtmlLinks.resolve(PAGE, null, "pages/about.html#team", null);
        assertEquals(HtmlLinks.Kind.FILE, t.kind());
        assertEquals(DIR.resolve("pages/about.html"), t.file());
        assertEquals("team", t.fragment());
        assertEquals(
                DIR.resolve("css/site.css"),
                HtmlLinks.resolve(PAGE, null, " css/site.css?v=3 ", null).file());
        assertEquals(
                DIR.getParent().resolve("x.html"),
                HtmlLinks.resolve(PAGE, null, "../x.html", null).file());
        assertEquals(
                DIR.resolve("my page.html"),
                HtmlLinks.resolve(PAGE, null, "my%20page.html", null).file());
        assertEquals(
                DIR.resolve("café.html"),
                HtmlLinks.resolve(PAGE, null, "caf%C3%A9.html", null).file());
    }

    @Test
    void aRootRelativeLinkStartsAtTheProjectAndABaseMovesTheFolder() {
        Path root = Path.of("/proj").toAbsolutePath();
        assertEquals(
                root.resolve("css/a.css"),
                HtmlLinks.resolve(PAGE, root, "/css/a.css", null).file());
        assertEquals(
                DIR.resolve("css/a.css"),
                HtmlLinks.resolve(PAGE, null, "/css/a.css", null).file());
        assertEquals(
                DIR.resolve("docs/a.html"),
                HtmlLinks.resolve(PAGE, null, "a.html", "docs/").file());
        HtmlLinks.Target web = HtmlLinks.resolve(PAGE, null, "a.html", "https://example.com/docs/");
        assertEquals(HtmlLinks.Kind.WEB, web.kind());
        assertEquals("https://example.com/docs/a.html", web.url());
        assertEquals("docs/", HtmlLinks.baseHref("<head><base href=\"docs/\"></head><body>"));
    }

    @Test
    void fragmentsWebAddressesAndTheRestAreToldApart() {
        HtmlLinks.Target fragment = HtmlLinks.resolve(PAGE, null, "#team", null);
        assertEquals(HtmlLinks.Kind.FRAGMENT, fragment.kind());
        assertEquals("team", fragment.fragment());
        assertEquals(
                HtmlLinks.Kind.WEB,
                HtmlLinks.resolve(PAGE, null, "https://example.com/", null).kind());
        assertEquals(
                "https://cdn.example/x.js",
                HtmlLinks.resolve(PAGE, null, "//cdn.example/x.js", null).url());
        for (String none : List.of("mailto:a@b.c", "javascript:void(0)", "tel:123", "data:,x", "", "  ")) {
            assertEquals(
                    HtmlLinks.Kind.NONE,
                    HtmlLinks.resolve(PAGE, null, none, null).kind(),
                    none);
        }
        for (String templated : List.of("{{ url }}", "<%= path %>", "${base}/a.html", "{% static 'a.css' %}")) {
            assertEquals(
                    HtmlLinks.Kind.NONE,
                    HtmlLinks.resolve(PAGE, null, templated, null).kind(),
                    templated);
        }
    }

    @Test
    void theServersEncodedFragmentIsSplitOff() {
        String uri = PAGE.getParent().resolve("pages/about.html").toUri() + "%23team";
        assertEquals("team", HtmlLinks.fragmentOf(uri));
        assertEquals(PAGE.getParent().resolve("pages/about.html").toUri().toString(), HtmlLinks.withoutFragment(uri));
        HtmlLinks.Target t = HtmlLinks.fromUri(uri);
        assertEquals(PAGE.getParent().resolve("pages/about.html"), t.file());
        assertEquals("team", t.fragment());
        assertEquals("", HtmlLinks.fragmentOf("file:///a/b.html"));
        assertEquals("x y", HtmlLinks.fragmentOf("file:///a/b.html#x%20y"));
        assertEquals(
                HtmlLinks.Kind.WEB, HtmlLinks.fromUri("https://example.com/").kind());
    }

    /** HTML review 2 B103: the server encodes a link's query into the file name; it is cut off, as a browser would. */
    @Test
    void theServersEncodedQueryIsCutOff() {
        Path css = PAGE.getParent().resolve("css/site.css");
        String uri = css.toUri() + "%3Fv=3";
        assertEquals(css.toUri().toString(), HtmlLinks.withoutQuery(uri));
        assertEquals(css.toUri().toString(), HtmlLinks.withoutQuery(css.toUri() + "%3fv=3"), "either case");
        assertEquals("file:///a/b.html", HtmlLinks.withoutQuery("file:///a/b.html?x=1%3Fy"));
        assertEquals("file:///a/b.html", HtmlLinks.withoutQuery("file:///a/b.html"));
        HtmlLinks.Target t =
                HtmlLinks.fromUri(PAGE.getParent().resolve("about.html").toUri() + "%3Fref=nav%23team");
        assertEquals(PAGE.getParent().resolve("about.html"), t.file());
        assertEquals("team", t.fragment());
    }

    @Test
    void theAnchorIsTheElementWithThatIdOrAnAnchorsName() {
        String about = "<h1>About</h1>\n<h2 id=\"team\">Team</h2>\n<a name=\"old\"></a><p name=\"no\">";
        assertEquals(about.indexOf("<h2"), HtmlLinks.anchorOffset(about, "team"));
        assertEquals(about.indexOf("<a name"), HtmlLinks.anchorOffset(about, "old"));
        assertEquals(-1, HtmlLinks.anchorOffset(about, "no"), "only an <a> is named by its name");
        assertEquals(-1, HtmlLinks.anchorOffset(about, "missing"));
        assertEquals(-1, HtmlLinks.anchorOffset(about, ""));
    }

    @Test
    void decodingKeepsAPlusAndAMalformedEscape() {
        assertEquals("a+b", HtmlLinks.decode("a+b"));
        assertEquals("100%", HtmlLinks.decode("100%"));
        assertEquals("%zz", HtmlLinks.decode("%zz"));
    }

    @Test
    void aSchemeStartsWithALetterAndHoldsOnlyLettersDigitsPlusMinusAndDots() {
        assertEquals("web+cal-x.y9", HtmlLinks.scheme("Web+cal-x.y9:z"), "lower-cased");
        assertNull(HtmlLinks.scheme("nocolon"));
        assertNull(HtmlLinks.scheme("C:\\temp"), "a drive letter");
        assertNull(HtmlLinks.scheme("1ab:x"), "a digit first");
        assertNull(HtmlLinks.scheme("[ab:x"));
        assertNull(HtmlLinks.scheme("{ab:x"));
        assertNull(HtmlLinks.scheme("ab_c:x"), "an underscore");
    }

    @Test
    void theLinkAtAnOffsetIsFoundPastEarlierOnesAndNoneAfterTheLast() {
        String text = "<a href=\"one.html\">x</a> <a href=\"two.html\">y</a> tail";
        HtmlLinks.Link two = HtmlLinks.at(text, text.indexOf("two") + 1);
        assertNotNull(two);
        assertEquals("two.html", two.value());
        assertNull(HtmlLinks.at(text, text.indexOf("tail")), "after every link");
    }

    @Test
    void theBaseHrefIsTheBaseElementsHrefBeforeTheBody() {
        assertEquals("/b/", HtmlLinks.baseHref("<base data=\"x\" href=\" /b/ \">"), "its other link attribute skipped");
        assertNull(HtmlLinks.baseHref("<body background=\"bg.png\"><base href=\"/b/\">"), "not after the body");
    }

    @Test
    void schemesBasesAndOddValuesResolveAsABrowserWould() {
        assertEquals(
                new HtmlLinks.Target(HtmlLinks.Kind.WEB, null, "", "https://x.org/a"),
                HtmlLinks.resolve(PAGE, null, "https://x.org/a", null));
        HtmlLinks.Target file = HtmlLinks.resolve(PAGE, null, "file:///tmp/a.html#top", null);
        assertEquals(HtmlLinks.Kind.FILE, file.kind());
        assertEquals(Path.of("/tmp/a.html"), file.file());
        assertEquals("top", file.fragment());
        assertEquals(
                HtmlLinks.Kind.NONE,
                HtmlLinks.resolve(Path.of("/"), null, "a.html", null).kind(),
                "no folder");

        assertEquals(
                DIR.resolve("a.html"),
                HtmlLinks.resolve(PAGE, null, "a.html", "").file(),
                "an empty base");
        assertEquals(
                DIR.resolve("a.html"),
                HtmlLinks.resolve(PAGE, null, "a.html", "{{ base }}").file(),
                "templated");
        assertEquals(
                "https://cdn.example.com/lib/a.js",
                HtmlLinks.resolve(PAGE, null, "a.js", "//cdn.example.com/lib/").url(),
                "a scheme-relative base");
        assertEquals(
                HtmlLinks.Kind.NONE,
                HtmlLinks.resolve(PAGE, null, "a.js", "ftp://host/dir/").kind());
        assertEquals(
                DIR.resolve("a.html"),
                HtmlLinks.resolve(PAGE, null, "a.html", "sub").file(),
                "a file base");
        assertEquals(
                DIR.resolve("sub/a.html"),
                HtmlLinks.resolve(PAGE, null, "a.html", "sub/").file(),
                "a folder");
        assertEquals(
                DIR.resolve("a.html"),
                HtmlLinks.resolve(PAGE, null, "a.html", "bad\0base").file(),
                "a base that is no path is ignored");

        HtmlLinks.Target self = HtmlLinks.resolve(PAGE, null, "?page=2", null);
        assertEquals(DIR, self.file(), "a query alone: the page's own folder");
        assertEquals(
                DIR.resolve("css/x.css"),
                HtmlLinks.resolve(PAGE, null, "/css/x.css", null).file(),
                "no root");
    }

    @Test
    void aServerUriIsReadForItsSchemeFragmentAndQuery() {
        assertEquals(HtmlLinks.Kind.WEB, HtmlLinks.fromUri("https://x.org/a").kind());
        assertEquals(HtmlLinks.Kind.NONE, HtmlLinks.fromUri("relative/a.html").kind(), "not an absolute URI");
        assertEquals("file:///a.html", HtmlLinks.withoutFragment("file:///a.html%23team"));
        assertEquals("a", HtmlLinks.withoutQuery("a?x%3Fy"), "the plain ? first");
        assertEquals("a", HtmlLinks.withoutQuery("a%3Fy?x"), "the encoded one first");
        assertTrue(HtmlLinks.templated("<?php echo $u ?>"));
    }

    @Test
    void percentEscapesAreDecodedAsUtf8AndAMalformedOneIsKept() {
        assertEquals("%4z", HtmlLinks.decode("%4z"));
        assertEquals("a\u00e9", HtmlLinks.decode("a%C3%A9"), "an escape at the end");
        assertEquals("\u00e9b", HtmlLinks.decode("%C3%A9b"));
    }
}

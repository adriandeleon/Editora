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
}

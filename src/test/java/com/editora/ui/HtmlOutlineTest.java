package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Structure window's HTML outline without the language server (HTML review C14). */
class HtmlOutlineTest {

    private static final String PAGE = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <title>Demo</title>
            </head>
            <BODY>
              <header id="hero" class="card wide">
                <h1>Hello</h1>
                <a id="top" href="#">top</a>
                <span>plain</span>
              </header>
              <ul class="nav">
                <li>one
                <li>two
              </ul>
              <script>
                if (a<b) { x = '<div>'; }
              </script>
            </body>
            </html>
            """;

    private static List<String> flat(List<StructureNode> nodes) {
        List<String> out = new ArrayList<>();
        flat(nodes, "", out);
        return out;
    }

    private static void flat(List<StructureNode> nodes, String indent, List<String> out) {
        for (StructureNode n : nodes) {
            out.add(indent + n.label() + "@" + n.line());
            flat(n.children(), indent + "  ", out);
        }
    }

    @Test
    void elementsAreLabelledAsTheServerLabelsThem() {
        assertEquals(
                List.of(
                        "html@1",
                        "  head@2",
                        "  body@6",
                        "    header#hero.card.wide@7",
                        "      h1@8",
                        "      a#top@9",
                        "    ul.nav@12",
                        "    script@16"),
                flat(HtmlOutline.build(PAGE, 1000)));
    }

    @Test
    void omittedEndTagsCloseTheirElement() {
        String html = "<ul>\n<li>one\nmore\n<li>two\n</ul>";
        List<StructureNode> ul = HtmlOutline.build(html, 1000);
        assertEquals(List.of("ul@0", "  li@1"), flat(ul), "the second li is one line: not listed");
        assertEquals(2, ul.get(0).children().get(0).region().endLine());
    }

    @Test
    void theLabelIsCut() {
        String classes = "x ".repeat(60);
        String label = HtmlOutline.label("div", "<div class=\"" + classes + "\">");
        assertEquals(2 * XmlOutline.MAX_VALUE_CHARS, label.length());
    }
}

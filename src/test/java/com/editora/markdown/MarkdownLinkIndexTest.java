package com.editora.markdown;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.editora.markdown.MarkdownLinkRewrite.Kind;
import com.editora.markdown.MarkdownLinkRewrite.Link;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownLinkIndexTest {

    private static final Path ROOT = Path.of("/p").toAbsolutePath();

    private static Path p(String rel) {
        return ROOT.resolve(rel);
    }

    private static MarkdownLinkIndex index() {
        MarkdownLinkIndex index = new MarkdownLinkIndex(ROOT);
        index.setFiles(List.of(p("README.md"), p("docs/guide.md"), p("docs/setup.md"), p("img/pic.png")));
        index.put(
                p("README.md"),
                "# Read me\n\nSee [the guide](docs/guide.md#start) and [[setup]].\n\n![p](img/pic.png)\n");
        index.put(
                p("docs/guide.md"),
                "Back to [[README|home]], [setup](setup.md), <img src=\"../img/pic.png\">, ![[pic.png]]\n\n"
                        + "[web](https://example.org) [top](#top) [[#here]] [[nowhere]]\n\n[ref]: ./setup.md\n");
        index.put(p("docs/setup.md"), "no links\n");
        return index;
    }

    @Test
    void scanningFindsEveryKindOfLinkWithItsLine() {
        List<Link> links = MarkdownLinkRewrite.links(
                "[a](x.md)\n\n[[note#part|label]] ![[pic.png]]\n\n<a href='y.md'>y</a> <img src=\"z.png\" alt=\"<\">\n\n"
                        + "<div>\n<img src=\"in block.png\">\n</div>\n\n[r]: w.md\n\n`[[code]]` \\[[escaped]] [[ ]]\n\n"
                        + "```\n[[fenced]] <img src=\"f.png\">\n```\n<!-- <img src=\"c.png\"> -->\n");
        assertEquals(
                List.of(
                        "INLINE:x.md@0",
                        "WIKI:note#part@2",
                        "WIKI_EMBED:pic.png@2",
                        "HTML:y.md@4",
                        "HTML:z.png@4",
                        "HTML:in block.png@7",
                        "DEFINITION:w.md@10"),
                links.stream()
                        .map(l -> l.kind() + ":" + l.destination() + "@" + l.line())
                        .toList());
        Link wiki = links.get(1);
        assertEquals("note", wiki.path());
        assertEquals("part", wiki.fragment());
        assertTrue(wiki.isWiki());
        assertEquals(Kind.HTML, links.get(3).kind());
        assertFalse(MarkdownLinkRewrite.links("[[note]]", false).stream().anyMatch(Link::isWiki), "wiki links off");
    }

    @Test
    void incomingLinksAreGroupedByTargetWithTheirSourceLine() {
        MarkdownLinkIndex index = index();
        List<MarkdownLinkIndex.Backlink> toSetup = index.incoming(p("docs/setup.md"));
        assertEquals(
                List.of("README.md:2:WIKI", "docs/guide.md:0:INLINE", "docs/guide.md:4:DEFINITION"),
                toSetup.stream()
                        .map(b -> ROOT.relativize(b.source()).toString().replace('\\', '/') + ":" + b.line() + ":"
                                + b.link().kind())
                        .toList());
        assertEquals(
                "See [the guide](docs/guide.md#start) and [[setup]].",
                toSetup.get(0).lineText());
        assertEquals(3, index.incoming(p("img/pic.png")).size(), "inline, HTML and a wiki embed");
        assertEquals(1, index.incoming(p("README.md")).size());
        assertEquals(List.of(), index.incoming(p("nowhere.md")));
        assertEquals(
                Set.of(p("README.md"), p("docs/guide.md")), index.sourcesOf(p("img")), "a folder: anything under it");
    }

    @Test
    void anchorsWebAddressesAndUnresolvedNamesHaveNoTarget() {
        MarkdownLinkIndex index = index();
        for (Link link : index.outgoing(p("docs/guide.md"))) {
            Path target = index.target(p("docs/guide.md"), link);
            switch (link.destination()) {
                case "https://example.org", "#top", "#here", "nowhere" -> assertNull(target, link.destination());
                default -> assertTrue(target != null, link.destination());
            }
        }
    }

    @Test
    void theIndexFollowsEditsDeletesAndRenames() {
        MarkdownLinkIndex index = index();
        index.put(p("README.md"), "now only [[guide]]\n");
        assertEquals(
                1,
                index.incoming(p("docs/setup.md")).stream()
                        .map(MarkdownLinkIndex.Backlink::source)
                        .distinct()
                        .count());
        assertEquals(
                List.of(p("README.md")),
                index.incoming(p("docs/guide.md")).stream()
                        .map(MarkdownLinkIndex.Backlink::source)
                        .toList());

        index.renamed(p("docs"), p("manual"));
        assertTrue(index.contains(p("manual/guide.md")) && !index.contains(p("docs/guide.md")));
        assertEquals(
                List.of(p("README.md")),
                index.incoming(p("manual/guide.md")).stream()
                        .map(MarkdownLinkIndex.Backlink::source)
                        .toList(),
                "the wiki name still resolves after the move");
        assertEquals(
                List.of(p("manual/guide.md"), p("manual/guide.md")),
                index.incoming(p("manual/setup.md")).stream()
                        .map(MarkdownLinkIndex.Backlink::source)
                        .toList(),
                "a moved document's relative links still point at its neighbours");

        index.remove(p("manual"));
        assertEquals(1, index.documentCount());
        assertEquals(List.of(), index.incoming(p("manual/setup.md")));
        assertNull(index.target(p("README.md"), index.outgoing(p("README.md")).get(0)), "its target is gone");

        index.setWikiLinks(false);
        index.put(p("README.md"), "now only [[guide]]\n");
        assertEquals(List.of(), index.outgoing(p("README.md")));
    }
}

package com.editora.markdown;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownLinkRewriteTest {

    private static final Path ROOT = Path.of("/p").toAbsolutePath();

    private static Path p(String rel) {
        return ROOT.resolve(rel);
    }

    private static MarkdownLinkRewrite.Result renamed(String text, String doc, String from, String to) {
        return MarkdownLinkRewrite.rewrite(text, p(doc), p(doc), p(from), p(to));
    }

    @Test
    void findsInlineImageAngleAndDefinitionDestinations() {
        String text = "See [a](one.md) and ![i](img/x.png \"t\") and [s](<my file.md>).\n\n[ref]: docs/two.md 'T'\n";
        List<MarkdownLinkRewrite.Link> links = MarkdownLinkRewrite.links(text);
        assertEquals(
                List.of("one.md", "img/x.png", "my file.md", "docs/two.md"),
                links.stream().map(MarkdownLinkRewrite.Link::destination).toList());
        assertTrue(links.get(2).angle());
        assertEquals(
                "<my file.md>",
                text.substring(links.get(2).start(), links.get(2).end()));
        assertEquals("one.md", text.substring(links.get(0).start(), links.get(0).end()));
    }

    @Test
    void codeFrontMatterAndNonLinksAreNotLinks() {
        String text = "---\nlink: [a](front.md)\n---\n\n```\n[a](fenced.md)\n```\n\n    [a](indented.md)\n\n"
                + "`[a](span.md)` \\[a](escaped.md) [a](two words.md) [^n]: foot.md\n";
        assertEquals(List.of(), MarkdownLinkRewrite.links(text));
    }

    @Test
    void renameRewritesOnlyLinksToTheRenamedFile() {
        String text = "[a](old.md) [b](other.md) [c](./old.md#part) ![i](old.md?raw=1)\n[r]: old.md\n";
        MarkdownLinkRewrite.Result r = renamed(text, "index.md", "old.md", "new.md");
        assertEquals("[a](new.md) [b](other.md) [c](./new.md#part) ![i](new.md?raw=1)\n[r]: new.md\n", r.text());
        assertEquals(4, r.count());
    }

    @Test
    void moveToAnotherFolderRecomputesTheRelativePath() {
        MarkdownLinkRewrite.Result r =
                renamed("[a](../img/pic.png)", "docs/guide/a.md", "docs/img/pic.png", "assets/pic.png");
        assertEquals("[a](../../assets/pic.png)", r.text());
        assertEquals(1, r.count());
    }

    @Test
    void folderRenameRewritesEveryLinkUnderIt() {
        String text = "[a](img/one.png) [b](img/sub/two.png) [c](img/) [d](imgs/three.png)";
        MarkdownLinkRewrite.Result r = renamed(text, "a.md", "img", "media");
        assertEquals("[a](media/one.png) [b](media/sub/two.png) [c](media/) [d](imgs/three.png)", r.text());
        assertEquals(3, r.count());
    }

    @Test
    void movedDocumentKeepsPointingAtTheSameFiles() {
        String text = "[up](../README.md) [peer](b.md) [web](https://example.org/a.md) [top](#top) [abs](/etc/x)";
        MarkdownLinkRewrite.Result r = MarkdownLinkRewrite.rewrite(
                text, p("docs/a.md"), p("docs/deep/a.md"), p("docs/a.md"), p("docs/deep/a.md"));
        assertEquals(
                "[up](../../README.md) [peer](../b.md) [web](https://example.org/a.md) [top](#top) [abs](/etc/x)",
                r.text());
        assertEquals(2, r.count());
    }

    @Test
    void documentMovedWithItsFolderKeepsLinksInsideTheFolder() {
        String text = "[peer](b.md) [out](../c.md)";
        MarkdownLinkRewrite.Result r =
                MarkdownLinkRewrite.rewrite(text, p("old/a.md"), p("deep/new/a.md"), p("old"), p("deep/new"));
        assertEquals("[peer](b.md) [out](../../c.md)", r.text());
        assertEquals(1, r.count());
    }

    @Test
    void spacesStayEncodedOrInAngleBrackets() {
        MarkdownLinkRewrite.Result r =
                renamed("[a](my%20notes.md) [b](<my notes.md>)", "a.md", "my notes.md", "sub dir/new notes.md");
        assertEquals("[a](sub%20dir/new%20notes.md) [b](<sub dir/new notes.md>)", r.text());
        assertEquals(2, r.count());
    }

    @Test
    void untouchedDocumentIsReturnedAsIs() {
        String text = "[a](keep.md) [m](mailto:a@b.c) [w](C:/x.md)\r\n";
        assertSame(text, renamed(text, "a.md", "old.md", "new.md").text());
        assertEquals(0, renamed(text, "a.md", "old.md", "new.md").count());
    }

    @Test
    void crlfDocumentsKeepTheirLineEndings() {
        MarkdownLinkRewrite.Result r = renamed("[a](old.md)\r\n[r]: old.md\r\n", "a.md", "old.md", "new.md");
        assertEquals("[a](new.md)\r\n[r]: new.md\r\n", r.text());
    }

    @Test
    void rewriteIsIdempotentAndReversible() {
        String text = "[a](x/old.md#h) ![i](<x/old.md>)";
        String once = renamed(text, "a.md", "x/old.md", "y/new.md").text();
        assertEquals(once, renamed(once, "a.md", "x/old.md", "y/new.md").text());
        assertEquals(text, renamed(once, "a.md", "y/new.md", "x/old.md").text());
    }

    @Test
    void relativeTestRejectsSchemesAndAnchors() {
        assertTrue(MarkdownLinkRewrite.isRelative("a/b.md"));
        assertTrue(MarkdownLinkRewrite.isRelative("../a b.md"));
        assertFalse(MarkdownLinkRewrite.isRelative("https://x"));
        assertFalse(MarkdownLinkRewrite.isRelative("#a"));
        assertFalse(MarkdownLinkRewrite.isRelative("/a"));
        assertEquals("a%20(1).md", MarkdownLinkRewrite.encode("a (1).md"));
        assertEquals("a%29.md", MarkdownLinkRewrite.encode("a).md"));
        assertEquals("é b", MarkdownLinkRewrite.decode("%C3%A9%20b"));
    }

    @Test
    void htmlSrcAndHrefAreRewrittenLikeLinks() {
        String text =
                "<img src=\"img/old pic.png\" width=\"10\"> <a href='old.md#x'>x</a> <a href=\"https://e.org/old.md\">w</a>\n\n"
                        + "<div>\n<img src=\"old.md\">\n</div>\n";
        MarkdownLinkRewrite.Result r = renamed(text, "a.md", "old.md", "sub/new.md");
        assertEquals(
                "<img src=\"img/old pic.png\" width=\"10\"> <a href='sub/new.md#x'>x</a> <a href=\"https://e.org/old.md\">w</a>\n\n"
                        + "<div>\n<img src=\"sub/new.md\">\n</div>\n",
                r.text());
        assertEquals(2, r.count());
        assertEquals(
                "<img src=\"media/old%20pic.png\">",
                renamed("<img src=\"img/old%20pic.png\">", "a.md", "img", "media")
                        .text());
    }

    @Test
    void wikiLinksFollowARenamedNoteAndIgnoreAMereMove() {
        WikiTargets before =
                new WikiTargets(ROOT, List.of(p("a.md"), p("notes/old.md"), p("notes/other.md"), p("img/pic.png")));
        String text = "[[old]] [[old#part|label]] [[OLD.md]] ![[pic.png]] [[other]] [[notes/old]] [[missing]]";

        MarkdownLinkRewrite.Result renamed =
                MarkdownLinkRewrite.rewrite(text, p("a.md"), p("a.md"), p("notes/old.md"), p("notes/new.md"), before);
        assertEquals(
                "[[new]] [[new#part|label]] [[new.md]] ![[pic.png]] [[other]] [[notes/new]] [[missing]]",
                renamed.text());
        assertEquals(4, renamed.count());

        MarkdownLinkRewrite.Result moved =
                MarkdownLinkRewrite.rewrite(text, p("a.md"), p("a.md"), p("notes/old.md"), p("archive/old.md"), before);
        assertEquals(
                "[[old]] [[old#part|label]] [[OLD.md]] ![[pic.png]] [[other]] [[archive/old]] [[missing]]",
                moved.text(),
                "a bare name still names the note; only the link written with a folder changes");
        assertEquals(1, moved.count());

        MarkdownLinkRewrite.Result picture =
                MarkdownLinkRewrite.rewrite(text, p("a.md"), p("a.md"), p("img/pic.png"), p("img/photo.png"), before);
        assertTrue(picture.text().contains("![[photo.png]]"), picture.text());

        // Without the notes, wiki links are left exactly as they are.
        assertSame(text, renamed(text, "a.md", "notes/old.md", "notes/new.md").text());
    }

    @Test
    void aMovedDocumentKeepsItsFolderRelativeWikiLinks() {
        WikiTargets before = new WikiTargets(ROOT, List.of(p("docs/a.md"), p("docs/sub/b.md"), p("c.md")));
        MarkdownLinkRewrite.Result r = MarkdownLinkRewrite.rewrite(
                "[[sub/b]] [[c]]", p("docs/a.md"), p("deep/er/a.md"), p("docs/a.md"), p("deep/er/a.md"), before);
        assertEquals("[[../../docs/sub/b]] [[c]]", r.text());
    }

    @Test
    void theWikiLinkUnderTheCaretIsFoundAnywhereInsideItsBrackets() {
        String text = "intro\nsee [[note#part|label]] and [[other]]\n\n```\n[[code]]\n```\n";
        int open = text.indexOf("[[note");
        for (int caret : new int[] {open, open + 3, text.indexOf("label"), text.indexOf("]] and") + 2}) {
            assertEquals("note#part", MarkdownLinkRewrite.wikiAt(text, caret).destination(), "caret " + caret);
        }
        assertEquals(
                "other", MarkdownLinkRewrite.wikiAt(text, text.indexOf("other")).destination());
        assertEquals(null, MarkdownLinkRewrite.wikiAt(text, text.indexOf(" and ") + 3));
        assertEquals(null, MarkdownLinkRewrite.wikiAt(text, text.indexOf("code")));
        assertEquals(null, MarkdownLinkRewrite.wikiAt(text, 2));
        assertEquals("[[note#part]]", MarkdownLinkAt.destination(text, open + 3));
    }
}

package com.editora.markdown;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link MarkdownLinkAt}: what the caret is on, for the less common ways a link is written. */
class MarkdownLinkAtShapesTest {

    /** The destination at the {@code |} in {@code marked}. */
    private static String at(String marked) {
        int caret = marked.indexOf('|');
        return MarkdownLinkAt.destination(marked.substring(0, caret) + marked.substring(caret + 1), caret);
    }

    @Test
    void inlineLinksWithTitlesAndBrackets() {
        assertEquals(null, at("see [a](u 'unclosed title|"));
        assertEquals("u", at("see [a](u (t)|)"));
        assertEquals(null, at("see [a]( |)"));
        assertEquals("q.md", at("[`]`](q.md|)"), "a bracket in a code span does not close the text");
        assertEquals(null, at("\\[a](b|)"));
        assertEquals(null, at("[a](b|"));
        assertEquals("d", at("[a [b] c](d|)"));
        assertEquals("x y", at("[a](<x y>|)"));
        assertEquals(null, at("[a](b) |"));
    }

    @Test
    void referenceLinksAndDefinitions() {
        assertEquals("a b", at("[x][r|]\n\n[r]: <a b>\n"));
        assertEquals(null, at("[x][r|]\n\n[r]: <>\n"));
        assertEquals(null, at("[x][r|]\n\n[r]:\n"));
        assertEquals(null, at("[x][^n|]\n\n[^n]: foot\n"), "a footnote is no link");
        assertEquals("e.md", at("[x][|]\n\n[x]: e.md\n"), "a collapsed reference uses the text");
        assertEquals(null, at("[r|]\n\n```\n[r]: code.md\n```\n"), "a definition in code defines nothing");
        assertEquals("x.md", at("   [r]: x.md|"));
        assertEquals(null, at("    [r]: x.md|"), "four spaces is code");
        assertEquals(null, at("[^r]: x.md|"));
    }

    @Test
    void autolinksAndBareUrls() {
        assertEquals("mailto:a@b.co", at("mail <a@b.co|>"));
        assertEquals(null, at("<a b|>"));
        assertEquals(null, at("<|>"));
        assertEquals(null, at("<span|>"), "an HTML tag");
        assertEquals("http://Www.x.io", at("Www.x.io|"));
        assertEquals(null, at("www.x.io/p.|"), "the trailing dot is not part of it");
        assertEquals(null, at("xhttp://h.io|"), "inside a word");
        assertEquals(null, at("go https://h.io/a), |now"));
        assertEquals(null, at("`https://h|.io`"));
        assertEquals(null, at("text|"));
    }

    @Test
    void definitionsInOrder() {
        assertEquals(List.of(), MarkdownLinkAt.definitions(null));
        assertEquals(
                List.of(new MarkdownLinkAt.Definition("a", "x", 0), new MarkdownLinkAt.Definition("d", "q r", 6)),
                MarkdownLinkAt.definitions("[a]: x\n[^f]: y\n[b]:\n```\n[c]: z\n```\n  [ d ]: <q r>\n[e]x: n\n"));
    }
}

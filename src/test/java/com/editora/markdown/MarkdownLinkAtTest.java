package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which link the caret is on, for the forms the old two-regex scan got wrong or did not know. */
class MarkdownLinkAtTest {

    /** The link at the first occurrence of {@code needle} (one character in). */
    private static String at(String text, String needle) {
        int i = text.indexOf(needle);
        assertEquals(true, i >= 0, "needle '" + needle + "' is in the text");
        return MarkdownLinkAt.destination(text, i + 1);
    }

    @Test
    void anInlineLinkOpensFromItsTextAndFromItsTarget() {
        String text = "see [the guide](docs/guide.md \"Guide\") end";
        assertEquals("docs/guide.md", at(text, "the guide"));
        assertEquals("docs/guide.md", at(text, "docs/"));
        assertEquals("docs/guide.md", at(text, "Guide\""));
        assertNull(at(text, "see"));
        assertNull(at(text, "end"));
    }

    @Test
    void parenthesesInsideTheTargetStayInIt() {
        String text = "[w](https://en.wikipedia.org/wiki/A_(b)) and [x](a\\)b.md)";
        assertEquals("https://en.wikipedia.org/wiki/A_(b)", at(text, "wikipedia"));
        assertEquals("a)b.md", at(text, "a\\)"));
    }

    @Test
    void anAngleBracketTargetKeepsItsSpaces() {
        String text = "![shot](<assets/my shot.png>) and [a](<my file.md> 'title')";
        assertEquals("assets/my shot.png", at(text, "my shot"));
        assertEquals("my file.md", at(text, "my file"));
    }

    @Test
    void anImageInsideALinkOpensTheImageAndTheRestOpensTheLink() {
        String text = "[![badge](img/badge.svg)](https://ci.example/build) tail";
        assertEquals("img/badge.svg", at(text, "badge.svg"));
        assertEquals("img/badge.svg", at(text, "badge]"));
        assertEquals("https://ci.example/build", at(text, "ci.example"));
        assertNull(at(text, "tail"));
    }

    @Test
    void referenceLinksResolveToTheirDefinition() {
        String text = "Full [text][Ref One], collapsed [ref one][] and shortcut [REF  one].\n"
                + "Unknown [nope][missing] and a task - [x] done.\n\n"
                + "[ref one]: <docs/ref one.md> \"Title\"\n"
                + "[other]: https://other.example\n"
                + "[^note]: a footnote, not a link\n";
        assertEquals("docs/ref one.md", at(text, "text]"));
        assertEquals("docs/ref one.md", at(text, "Ref One"));
        assertEquals("docs/ref one.md", at(text, "ref one][]"));
        assertEquals("docs/ref one.md", at(text, "REF  one"));
        assertNull(at(text, "nope"));
        assertNull(at(text, "x] done"));
        // the definition line itself, on its label and on its target
        assertEquals("docs/ref one.md", at(text, "ref one]:"));
        assertEquals("https://other.example", at(text, "other]:"));
        assertEquals("https://other.example", at(text, "other.example"));
        assertNull(at(text, "note]"), "a footnote definition is not a link");
    }

    @Test
    void aDefinitionInsideAFenceIsNotADefinition() {
        String text = "[a][r]\n\n```\n[r]: https://in.fence\n```\n";
        assertNull(at(text, "a]"));
    }

    @Test
    void autolinksAndMailAddresses() {
        String text = "<https://auto.example/x> <me@example.org> <div class=\"x\"> <b>";
        assertEquals("https://auto.example/x", at(text, "auto.example"));
        assertEquals("mailto:me@example.org", at(text, "me@"));
        assertNull(at(text, "div"));
        assertNull(at(text, "b>"));
    }

    @Test
    void aBareUrlLosesTheSentencePunctuationAfterIt() {
        assertEquals("http://x.com/a", at("go to http://x.com/a. Then", "x.com"));
        assertEquals("http://x.com/a", at("go to http://x.com/a, then", "x.com"));
        assertEquals("https://x.com/a", at("(see https://x.com/a)", "x.com"));
        assertEquals("https://x.com/a_(b)", at("see https://x.com/a_(b) now", "x.com"));
        assertEquals("https://x.com/a?b=1", at("is it https://x.com/a?b=1?", "x.com"));
        assertEquals("https://x.com", at("<a href=\"https://x.com\">", "x.com"));
        assertNull(MarkdownLinkAt.destination("go https://y/z now", 0));
    }

    @Test
    void aWwwAddressIsALink() {
        assertEquals("http://www.example.com/docs", at("visit www.example.com/docs.", "example"));
        assertNull(at("awww.example.com", "example"), "not at a word boundary");
    }

    @Test
    void nothingInACodeSpanOrAFenceIsALink() {
        String text = "Write `[a](http://in.span)` or ``<https://in.span2>`` like so:\n\n"
                + "```md\n[b](http://in.fence) https://bare.fence\n```\n\n"
                + "~~~\nhttps://in.tildes\n~~~\n\nafter https://after.example\n";
        assertNull(at(text, "in.span)"));
        assertNull(at(text, "in.span2"));
        assertNull(at(text, "in.fence"));
        assertNull(at(text, "bare.fence"));
        assertNull(at(text, "in.tildes"));
        assertEquals("https://after.example", at(text, "after.example"));
    }

    @Test
    void aCodeSpanInALinksTextIsStillPartOfTheLink() {
        String text = "[`Path.of`](api/path.md) and [a `]` b](c.md)";
        assertEquals("api/path.md", at(text, "Path.of"));
        assertEquals("c.md", at(text, "a `"));
    }

    @Test
    void linkTextMayWrapButALinkDoesNotCrossABlankLine() {
        assertEquals("t.md", at("[first\nsecond](t.md)", "first"));
        assertNull(at("[first\n\nsecond](t.md)", "first"));
    }

    @Test
    void outOfRangeAndEmptyInputs() {
        assertNull(MarkdownLinkAt.destination(null, 0));
        assertNull(MarkdownLinkAt.destination("", 0));
        assertNull(MarkdownLinkAt.destination("[a](b)", -1));
        assertNull(MarkdownLinkAt.destination("[a](b)", 99));
        assertNull(MarkdownLinkAt.destination("[a]()", 1));
        assertNull(MarkdownLinkAt.destination("[a](b", 1));
        assertEquals("b", MarkdownLinkAt.destination("[a](b)", 6), "touching the end still counts");
    }
}

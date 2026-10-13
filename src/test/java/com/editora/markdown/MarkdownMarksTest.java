package com.editora.markdown;

import java.util.List;

import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Highlight, superscript and subscript — and, as much, what they must leave alone: comparisons, powers in
 * prose, and every strikethrough that rendered as one before.
 */
class MarkdownMarksTest {

    /** The parser the preview and the exports use for these marks: two-tilde strikethrough + the marks. */
    private static final List<org.commonmark.Extension> EXT = List.of(
            StrikethroughExtension.builder().requireTwoTildes(true).build(),
            MarkdownMarks.extension(),
            MarkdownMath.extension(),
            TablesExtension.create(),
            FootnotesExtension.builder().inlineFootnotes(true).build());

    private static final Parser PARSER = Parser.builder().extensions(EXT).build();
    private static final HtmlRenderer HTML =
            HtmlRenderer.builder().extensions(EXT).build();

    /** What documents rendered as before this extension existed: GFM strikethrough, one tilde allowed. */
    private static final List<org.commonmark.Extension> BEFORE = List.of(StrikethroughExtension.create());

    private static String html(String md) {
        return HTML.render(PARSER.parse(md)).strip();
    }

    private static String before(String md) {
        return HtmlRenderer.builder()
                .extensions(BEFORE)
                .build()
                .render(Parser.builder().extensions(BEFORE).build().parse(md))
                .strip();
    }

    @Test
    void highlight() {
        assertEquals("<p>This is <mark>important</mark> text.</p>", html("This is ==important== text."));
        assertEquals(
                "<p><mark>two words</mark> and <mark>with <strong>bold</strong></mark></p>",
                html("==two words== and ==with **bold**=="));
        assertEquals("<p>(<mark>x</mark>)</p>", html("(==x==)"));
    }

    @Test
    void equalsSignsThatAreNotHighlight() {
        assertEquals("<p>if a == b and c == d</p>", html("if a == b and c == d"));
        assertEquals("<p>a==b and c==d</p>", html("a==b and c==d"), "inside words: a comparison");
        assertEquals("<p>x = y, z=1, =start and end=</p>", html("x = y, z=1, =start and end="));
        assertEquals("<p>===not=== and ====neither====</p>", html("===not=== and ====neither===="));
        assertEquals("<h1>Title</h1>", html("Title\n=====\n"), "a setext underline is still a heading");
        assertEquals("<p><code>==code==</code></p>", html("`==code==`"));
        assertEquals("<p>== spaced ==</p>", html("== spaced =="));
    }

    @Test
    void superscriptIsOneRunWithoutSpaces() {
        assertEquals("<p>x<sup>2</sup> and 10<sup>th</sup></p>", html("x^2^ and 10^th^"));
        assertEquals("<p>2^10 and 3^5</p>", html("2^10 and 3^5"), "powers in prose, with a space between the carets");
        assertEquals("<p>^ alone, a^b, ^^, ^^x^^</p>", html("^ alone, a^b, ^^, ^^x^^"));
        assertEquals("<p>a^<strong>b</strong>^</p>", html("a^**b**^"), "only plain text is raised");
        assertEquals("<p><code>x^2^</code></p>", html("`x^2^`"));
    }

    @Test
    void subscriptIsATildePairAttachedToAWord() {
        assertEquals("<p>H<sub>2</sub>O and x<sub>i</sub>, f(x)<sub>n</sub></p>", html("H~2~O and x~i~, f(x)~n~"));
        assertEquals("<p>CO<sub>2</sub> is not CO<sup>2</sup></p>", html("CO~2~ is not CO^2^"));
    }

    @Test
    void everyStrikethroughThatWasOneStillIs() {
        for (String md : List.of(
                "~~gone~~",
                "~gone~",
                "This ~is gone~ now.",
                "~~two words~~ and ~one word~",
                "a ~~b~~ c",
                "(~x~)",
                "**~bold strike~**",
                "~a~~ and ~~b~",
                "~~~not~~~",
                "~ spaced ~ and ~~ spaced ~~",
                "~/home and ~/bin",
                "approx. ~5 or ~10",
                "user~name~tag is ~struck~",
                "- ~~item~~\n- ~item~\n")) {
            if (md.startsWith("user~name~tag")) {
                continue; // the one deliberate change, pinned below
            }
            assertEquals(before(md), html(md), "unchanged: " + md);
        }
        assertEquals("<p>This <del>is gone</del> now.</p>", html("This ~is gone~ now."));
        assertEquals("<p><del>gone</del></p>", html("~gone~"), "a pair standing alone: GitHub's strikethrough");
        // The deliberate change: a single-tilde pair glued to the word before it, around one unspaced run.
        assertEquals("<p>user<del>name</del>tag</p>", before("user~name~tag"));
        assertEquals("<p>user<sub>name</sub>tag</p>", html("user~name~tag"));
        assertEquals("<p>H<del>2 O</del>x</p>", html("H~2 O~x"), "with a space inside it is still strikethrough");
    }

    @Test
    void theOtherSyntaxAroundThemIsUntouched() {
        org.junit.jupiter.api.Assertions.assertTrue(
                html("text^[an inline note]").contains("class=\"footnote-ref\""),
                "an inline footnote is not a superscript");
        assertEquals(
                1,
                PARSER.parse("so $x^2 + y^2$ and $a_i~b$ hold")
                                        .getFirstChild()
                                        .getFirstChild()
                                        .getNext()
                                instanceof MarkdownMath.Inline
                        ? 1
                        : 0);
        assertEquals(
                "<td>==a==</td>".replace("==a==", "<mark>a</mark>"),
                html("| h |\n|---|\n| ==a== |\n")
                        .lines()
                        .filter(l -> l.contains("mark"))
                        .findFirst()
                        .orElse("")
                        .strip());
    }

    @Test
    void plainTextKeepsTheWords() {
        String text = TextContentRenderer.builder().extensions(EXT).build().render(PARSER.parse("==hi== x^2^ H~2~O"));
        assertEquals("hi x2 H2O", text.strip());
    }
}

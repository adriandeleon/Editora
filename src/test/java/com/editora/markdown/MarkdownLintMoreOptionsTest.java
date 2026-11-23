package com.editora.markdown;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The style options of the rules {@link MarkdownLintMore} checks, each value of each one. */
class MarkdownLintMoreOptionsTest {

    private static final MarkdownLintOptions DEFAULTS = MarkdownLintOptions.DEFAULT;

    private static List<String> of(String code, String md) {
        return of(code, md, DEFAULTS);
    }

    private static List<String> of(String code, String md, MarkdownLintOptions options) {
        return MarkdownLint.lint(md, Set.of(), options).stream()
                .filter(d -> d.code().equals(code))
                .map(d -> d.line() + ":" + d.column() + "+" + d.length())
                .toList();
    }

    private static MarkdownLintOptions with(String code, String option, Object value) {
        return DEFAULTS.with(code, option, value);
    }

    @Test
    void lineLengthOfHeadingsAndFrontMatter() {
        String heading = "# " + "word ".repeat(20) + "\n";
        assertEquals(List.of("1:81+22"), of("MD013", heading));
        assertEquals(List.of(), of("MD013", heading, with("MD013", "headings", false)));
        assertEquals(List.of(), of("MD013", "---\ntitle: " + "word ".repeat(20) + "\n---\n\n# T\n"));
        assertEquals(List.of("1:2+4"), of("MD013", "abc d\n", with("MD013", "line_length", 1)));
        assertEquals(List.of(), of("MD013", "[ref]: https://x.io/" + "a".repeat(70) + " \"title words\"\n"));
    }

    @Test
    void setextFamilyHeadingStyles() {
        MarkdownLintOptions closed = with("MD003", "style", "setext_with_atx_closed");
        assertEquals(List.of(), of("MD003", "A\n===\n\nB\n---\n\n### C ###\n", closed));
        assertEquals(List.of("7:1+5"), of("MD003", "A\n===\n\nB\n---\n\n### C\n", closed));
        MarkdownLintOptions atx = with("MD003", "style", "setext_with_atx");
        assertEquals(List.of(), of("MD003", "A\n===\n\n### C\n", atx));
        assertEquals(List.of("1:1+3"), of("MD003", "# A\n", atx));
        MarkdownLintOptions setext = with("MD003", "style", "setext");
        assertEquals(List.of(), of("MD003", "### C ###\n\n#### D\n", setext), "either ATX form is fine below level 2");
        assertEquals(List.of("1:1+3"), of("MD003", "# A\n", with("MD003", "style", "atx_closed")));
        assertEquals(List.of(), of("MD003", "# A \\#\n", with("MD003", "style", "atx")), "an escaped # is text");
    }

    @Test
    void emptyHeadingsAreNeverDuplicates() {
        assertEquals(List.of(), of("MD024", "#\n\n#\n"));
        assertEquals(List.of("3:1+7"), of("MD024", "# `a` b\n\n# `a` b\n"));
    }

    @Test
    void orderedListStyles() {
        MarkdownLintOptions zero = with("MD029", "style", "zero");
        assertEquals(List.of(), of("MD029", "0. a\n0. b\n", zero));
        assertEquals(List.of("2:1+2"), of("MD029", "0. a\n1. b\n", zero));
        MarkdownLintOptions ordered = with("MD029", "style", "ordered");
        assertEquals(List.of(), of("MD029", "1. a\n2. b\n3. c\n", ordered));
        assertEquals(List.of("2:1+2", "3:1+2"), of("MD029", "1. a\n1. b\n1. c\n", ordered));
        assertEquals(List.of("3:1+2"), of("MD029", "3) a\n4) b\n9) c\n"));
    }

    @Test
    void bulletStyles() {
        assertEquals(List.of("1:1+1"), of("MD004", "* a\n", with("MD004", "style", "dash")));
        assertEquals(List.of(), of("MD004", "+ a\n+ b\n", with("MD004", "style", "plus")));
        assertEquals(List.of("1:1+1"), of("MD004", "- a\n", with("MD004", "style", "plus")));
    }

    @Test
    void markerSpacingForOrderedAndMultiParagraphItems() {
        assertEquals(List.of(), of("MD030", "1.  a\n", with("MD030", "ol_single", 2)));
        assertEquals(List.of("1:3+1"), of("MD030", "1. a\n", with("MD030", "ol_single", 2)));
        assertEquals(List.of("1:2+1"), of("MD030", "- a\n\n  b\n", with("MD030", "ul_multi", 3)));
        assertEquals(List.of(), of("MD030", "-\ta\n"), "a tab after the marker is not counted");
    }

    @Test
    void tablePipeStyles() {
        String both = "| a | b |\n|---|---|\n";
        assertEquals(List.of("1:1+9", "2:1+9"), of("MD055", both, with("MD055", "style", "leading_only")));
        assertEquals(List.of(), of("MD055", "| a | b\n|---|---\n", with("MD055", "style", "leading_only")));
        assertEquals(List.of(), of("MD055", "a | b |\n---|---|\n", with("MD055", "style", "trailing_only")));
        assertEquals(List.of(), of("MD055", "a | b\n---|---\n", with("MD055", "style", "no_leading_or_trailing")));
        assertEquals(List.of(), of("MD055", "| a | b \\|\n|---|---\n", with("MD055", "style", "leading_only")));
    }

    @Test
    void codeBlockAndFenceStyles() {
        assertEquals(List.of("1:1+8"), of("MD046", "    code\n", with("MD046", "style", "fenced")));
        assertEquals(List.of("1:1+3"), of("MD046", "```\nx\n```\n", with("MD046", "style", "indented")));
        assertEquals(List.of("1:1+3"), of("MD048", "~~~\nx\n~~~\n", with("MD048", "style", "backtick")));
        assertEquals(List.of(), of("MD048", "~~~\nx\n~~~\n\n~~~\ny\n~~~\n"));
    }

    @Test
    void emphasisStyles() {
        assertEquals(List.of("1:1+3"), of("MD049", "*a* _b_\n", with("MD049", "style", "underscore")));
        assertEquals(List.of("1:5+3"), of("MD049", "*a* _b_\n", with("MD049", "style", "asterisk")));
        assertEquals(List.of("1:1+5"), of("MD050", "__a__ **b**\n", with("MD050", "style", "asterisk")));
        assertEquals(List.of("1:7+5"), of("MD050", "__a__ **b**\n", with("MD050", "style", "underscore")));
    }

    @Test
    void spacesInsideLinkTextAtEitherEnd() {
        assertEquals(List.of("1:1+11"), of("MD039", "[ text](/u)\n"));
        assertEquals(List.of("1:1+11"), of("MD039", "[text ](/u)\n"));
        assertEquals(List.of(), of("MD039", "[`code`](/u) [**b** ](/u2)\n"), "only plain text at an end counts");
    }

    @Test
    void imagesHiddenFromAssistiveTechnologyNeedNoAltText() {
        assertEquals(List.of(), of("MD045", "<img src=\"a.png\" aria-hidden=\"true\">\n"));
        assertEquals(List.of("1:1+13"), of("MD045", "<IMG src=\"a\">\n"));
        assertEquals(List.of(), of("MD045", "<div>\n<img alt=\"\" src=\"a\">\n</div>\n"));
    }

    @Test
    void codeSpansGluedToWordsOrPaddedForBackticks() {
        assertEquals(List.of(), of("MD038", "a`` `x` ``b\n"), "padding around a backtick is required");
        assertEquals(List.of(), of("MD038", "x` y `z\n"), "glued to words: not a mistake to fix");
        assertEquals(List.of("1:1+7"), of("MD038", "`` `x``\n"));
        assertEquals(List.of(), of("MD038", "`  `\n"), "only spaces");
    }

    @Test
    void reversedLinksAfterWordsOrBracketsAreNotReversed() {
        assertEquals(List.of(), of("MD011", "f(x)[i] and a\\(b)[c] and ](d)[e]\n"));
        assertEquals(List.of("1:1+11"), of("MD011", "(text)[url] at the start\n"));
    }

    @Test
    void anchorsAreHeadingSlugsNumberedWhenRepeatedAndHtmlIds() {
        assertEquals(
                Set.of("a-b", "a-b-1", "x", "y", "z"),
                MarkdownLintMore.anchorsOf(
                        "# A *b*\n\n# A *b*\n\n#\n\ntext <a id=\"x\" name='y'> <span>\n<div id=\"z\">"));
        assertEquals(Set.of(), MarkdownLintMore.anchorsOf("plain <b> only"));
    }
}

package com.editora.markdown;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownSourceStylesTest {

    private static List<String> styled(String text) {
        return styled(text, 0, Integer.MAX_VALUE - 1);
    }

    private static List<String> styled(String text, int first, int last) {
        return MarkdownSourceStyles.ranges(text, first, last).stream()
                .map(r -> r.style() + ":" + text.substring(r.start(), r.end()))
                .toList();
    }

    @Test
    void theTextLinesOfASetextHeadingAreHeadingsAndOnlyBecauseOfTheUnderline() {
        assertEquals(List.of("heading:Title"), styled("Title\n=====\n\nbody\n"));
        assertEquals(List.of("heading:Sub *part*"), styled("intro\n\n  Sub *part*  \n---\n"));
        assertEquals(List.of("heading:Two lines", "heading:of title"), styled("Two lines\nof title\n===\n"));
        assertEquals(List.of(), styled("Title\n\n=====\n"), "a blank line between: not a heading");
        assertEquals(List.of(), styled("Title\nmore\n"), "no underline, no heading");
        assertEquals(List.of(), styled("# Atx\n\ntext\n"), "an ATX heading is the grammar's own");
        assertEquals(List.of(), styled("- item\n---\n"), "a rule under a list item is a rule");
    }

    @Test
    void taskBoxesReadDifferentlyOpenAndDone() {
        assertEquals(
                List.of("md-marker:[ ]", "comment:[x]", "comment:[X]", "md-marker:[ ]", "md-marker:[ ]"),
                styled("- [ ] open\n- [x] done\n  * [X] nested\n1. [ ] numbered\n> - [ ] quoted\n"));
        assertEquals(List.of(), styled("[ ] not a list item\n- [y] not a box\n- [ ]no space\ntext - [ ] mid-line\n"));
    }

    @Test
    void bareUrlsAreLinksWithoutTheSentencesPunctuation() {
        assertEquals(
                List.of("link:https://example.org/a_b?q=1", "link:http://e.org/x(1)"),
                styled("See https://example.org/a_b?q=1. Or (http://e.org/x(1)), fine.\n"));
        assertEquals(
                List.of(),
                styled("[t](https://in.link/x) <https://auto.link/x> `https://in.code/x` nothttps://glued.x/y\n"),
                "a link target, an autolink and code are the grammar's own");
        assertEquals(List.of("comment:[x]", "link:https://e.org/done"), styled("- [x] see https://e.org/done\n"));
    }

    @Test
    void codeFrontMatterAndHtmlAreNeverStyled() {
        String text =
                "---\ntitle: https://front.matter/x\n---\n\n```\nTitle\n=====\n- [ ] box https://in.fence/x\n```\n\n"
                        + "    - [x] indented https://code.x/y\n\n<div>\n- [ ] https://in.html/x\n</div>\n\n<!--\nTitle\n===\n-->\n";
        assertEquals(List.of(), styled(text));
    }

    @Test
    void onlyTheAskedLinesAreStyledAndTheLineAfterIsStillRead() {
        String text = "- [ ] zero\nTitle\n=====\n- [x] three https://e.org/three\n";
        assertEquals(List.of("heading:Title"), styled(text, 1, 1), "its underline is outside the window");
        assertEquals(List.of("comment:[x]", "link:https://e.org/three"), styled(text, 3, 3));
        assertEquals(List.of(), styled(text, 2, 2));
        assertEquals(List.of(), styled("", 0, 5));
        assertEquals(List.of(), styled("x\n", 3, 1));
        assertEquals(11, MarkdownSourceStyles.ranges(text, 1, 1).get(0).start(), "document offsets");
    }
}

package com.editora.markdown;

import com.editora.markdown.MarkdownCompletionContext.Context;
import com.editora.markdown.MarkdownCompletionContext.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarkdownCompletionContextTest {

    /** The context with the caret at the end of {@code before}. */
    private static Context end(String before) {
        return MarkdownCompletionContext.at(before, before.length());
    }

    @Test
    void aLinkTargetIsAPathUntilItHasAHash() {
        assertEquals(new Context(Kind.PATH, 11, "", "", false), end("see [text]("));
        assertEquals(new Context(Kind.PATH, 11, "docs/gu", "", false), end("see [text](docs/gu"));
        assertEquals(new Context(Kind.PATH, 7, "img/sh", "", false), end("![alt](img/sh"));
        assertEquals(new Context(Kind.PATH, 5, "my fi", "", true), end("[a](<my fi"));
        assertEquals(new Context(Kind.ANCHOR, 5, "", "", false), end("[a](#"));
        assertEquals(new Context(Kind.ANCHOR, 5, "inst", "", false), end("[a](#inst"));
        assertEquals(new Context(Kind.ANCHOR, 13, "se", "guide.md", false), end("[a](guide.md#se"));
    }

    @Test
    void aFinishedTargetAWebAddressAndATitleOfferNothing() {
        assertNull(end("[a](guide.md) and more"));
        assertNull(end("[a](guide.md \"Ti"));
        assertNull(end("[a](https://exam"));
        assertNull(end("[a](mailto:me"));
        assertNull(end("[a](//host/x"));
        assertNull(end("plain prose (with parens"));
        assertNull(end("[a](guide.md more"));
    }

    @Test
    void onlyTheCaretsOwnTargetCounts() {
        String line = "[a](one.md) and [b](tw";
        assertEquals(new Context(Kind.PATH, 20, "tw", "", false), end(line));
        // The caret inside the first, finished link: its target ended at the ")" — nothing to complete.
        assertEquals(new Context(Kind.PATH, 4, "on", "", false), MarkdownCompletionContext.at(line, 6));
    }

    @Test
    void referenceLabels() {
        assertEquals(new Context(Kind.REFERENCE, 7, "", "", false), end("[text]["));
        assertEquals(new Context(Kind.REFERENCE, 7, "la", "", false), end("[text][la"));
        assertEquals(new Context(Kind.REFERENCE, 5, "lab", "", false), end("see [lab"));
        assertNull(end("see ["), "a bare bracket may be the start of anything");
        assertNull(end("- [ "), "a task box being typed");
        assertNull(end("- [x"));
        assertNull(end("a footnote[^no"));
        assertNull(end("escaped \\[lab"));
        assertNull(end("[done] and"));
    }

    @Test
    void fenceLanguages() {
        assertEquals(new Context(Kind.FENCE_LANGUAGE, 3, "", "", false), end("```"));
        assertEquals(new Context(Kind.FENCE_LANGUAGE, 3, "jav", "", false), end("```jav"));
        assertEquals(new Context(Kind.FENCE_LANGUAGE, 6, "py", "", false), end("  ~~~~py"));
        assertNull(end("```java title"));
        assertNull(end("text ```ja"));
        assertNull(end("    ```ja"), "four spaces: indented code");
    }

    @Test
    void emojiShortcodes() {
        assertEquals(new Context(Kind.EMOJI, 5, "smi", "", false), end("nice :smi"));
        assertEquals(new Context(Kind.EMOJI, 0, "tada", "", false), end(":tada"));
        assertNull(end("nice :s"), "one letter is not enough to go on");
        assertNull(end("at 10:30"));
        assertNull(end("key:value"));
    }

    @Test
    void nothingInsideACodeSpanOrOutOfRange() {
        String line = "use `[a](pa` here";
        assertNull(MarkdownCompletionContext.at(line, line.indexOf("pa") + 2));
        assertNull(MarkdownCompletionContext.at(null, 0));
        assertNull(MarkdownCompletionContext.at("abc", 9));
        assertNull(MarkdownCompletionContext.at("abc", -1));
        assertNull(end("ordinary prose"));
    }
}

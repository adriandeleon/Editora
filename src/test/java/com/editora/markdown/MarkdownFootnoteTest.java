package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkdownFootnoteTest {

    private static String applied(String marked) {
        int caret = marked.indexOf('‸');
        String text = marked.replace("‸", "");
        MarkdownEdit e = MarkdownFootnote.insert(text, caret);
        StringBuilder out = new StringBuilder(text.substring(0, e.from()) + e.replacement() + text.substring(e.to()));
        return out.insert(e.selStart(), '‸').toString();
    }

    @Test
    void theReferenceGoesAtTheCaretAndTheDefinitionAtTheEnd() {
        assertEquals("A claim[^1] here.\n\n[^1]: ‸", applied("A claim‸ here.\n"));
        assertEquals("[^1]\n\n[^1]: ‸", applied("‸"));
    }

    @Test
    void theNextFreeNumberIsUsed() {
        assertEquals(
                "One[^1] two[^4].\n\n[^1]: first\n[^3]: third\n[^4]: ‸",
                applied("One[^1] two‸.\n\n[^1]: first\n[^3]: third\n"));
        assertEquals(1, MarkdownFootnote.nextNumber("see [^note] and [1]"));
        assertEquals(8, MarkdownFootnote.nextNumber("[^7]: x"));
    }

    @Test
    void aDefinitionFollowsThePreviousOneWithoutABlankLine() {
        assertEquals("a[^2]\n\n[^1]: x\n[^2]: ‸", applied("a‸\n\n[^1]: x"));
        assertEquals("a[^1]\n\ntext\n\n[^1]: ‸", applied("a‸\n\ntext\n\n\n"));
    }
}

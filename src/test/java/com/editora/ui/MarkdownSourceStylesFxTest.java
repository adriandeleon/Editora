package com.editora.ui;

import java.util.Collection;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Markdown decoration in a real buffer: it follows an edit to the line that makes a line a heading. */
@Tag("fx")
class MarkdownSourceStylesFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Collection<String> styleAt(EditorBuffer b, int offset) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getStyleOfChar(offset));
    }

    /** Waits until the character at {@code offset} has (or has lost) {@code style}: highlighting is asynchronous. */
    private static void await(EditorBuffer b, int offset, String style, boolean present, String what) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (styleAt(b, offset).contains(style) != present) {
            assertTrue(System.nanoTime() < deadline, what + ": style at " + offset + " is " + styleAt(b, offset));
            Thread.sleep(25);
        }
    }

    @Test
    void anUnderlineTypedOrRemovedRestylesTheLineAboveAndBoxesAndUrlsAreStyled() throws Exception {
        String text =
                "intro paragraph\n\nTitle line\n\n- [ ] open https://example.org/page\n- [x] done\n\n```\nCode title\n=====\n```\n";
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("markdown");
            buffer.getNode();
            buffer.setContent(text);
            return buffer;
        });
        int title = text.indexOf("Title line") + 2;
        int open = text.indexOf("[ ]") + 1;
        int done = text.indexOf("[x]") + 1;
        int url = text.indexOf("https://") + 10;
        await(b, open, "md-marker", true, "an open box is a marker");
        await(b, done, "comment", true, "a ticked box is muted");
        await(b, url, "link", true, "a bare URL is a link");
        assertFalse(styleAt(b, title).contains("heading"), "no underline yet");
        assertFalse(styleAt(b, text.indexOf("Code title") + 2).contains("heading"), "never in a code block");

        // Typing the underline — an edit on the line BELOW — makes the line above a heading.
        int underlineAt = text.indexOf("Title line") + "Title line".length() + 1;
        FxTestSupport.runOnFx(() -> b.getArea().insertText(underlineAt, "===\n"));
        await(b, title, "heading", true, "the text line of a setext heading");
        await(b, text.indexOf("intro") + 2, "heading", false, "the paragraph above is not part of it");

        // Breaking the underline takes the heading style off the line above again.
        FxTestSupport.runOnFx(() -> b.getArea().replaceText(underlineAt, underlineAt + 3, "=x="));
        await(b, title, "heading", false, "no longer underlined");

        FxTestSupport.runOnFx(() -> b.getArea().replaceText(underlineAt, underlineAt + 3, "---"));
        await(b, title, "heading", true, "a dash underline: a level-2 heading");

        // Ticking the box changes how it reads.
        int box = FxTestSupport.callOnFx(() -> b.getArea().getText().indexOf("[ ]"));
        FxTestSupport.runOnFx(() -> b.getArea().replaceText(box + 1, box + 2, "x"));
        await(b, box + 1, "comment", true, "ticked");
    }
}

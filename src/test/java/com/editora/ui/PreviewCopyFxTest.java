package com.editora.ui;

import javafx.scene.input.Clipboard;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-FX coverage of the preview copy. The fragment's own rules are unit-tested in
 * {@code MarkdownClipboardHtmlTest}; what needs a real clipboard is that <em>both</em> flavors go on
 * together for Markdown (a rich-text target and a plain-text target must each get the right thing from one
 * copy) and that a non-Markdown preview still copies its source with no HTML flavor attached.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewCopyFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer(String language, String text) {
        EditorBuffer b = new EditorBuffer();
        b.setLanguageOverride(language);
        b.setContent(text);
        b.getNode();
        return b;
    }

    @Test
    void markdownCopiesRichTextAndPlainTextTogether() throws Exception {
        String[] flavors = FxTestSupport.callOnFx(() -> {
            buffer("markdown", "# Title\n\nSome **bold** text.\n").copyPreviewToClipboard();
            Clipboard cb = Clipboard.getSystemClipboard();
            return new String[] {cb.hasHtml() ? cb.getHtml() : null, cb.hasString() ? cb.getString() : null};
        });
        assertTrue(flavors[0] != null && flavors[0].contains("<h1"), "the text/html flavor carries the render");
        assertTrue(flavors[0].contains("<strong>bold</strong>"), "bold survives as markup, not as asterisks");
        assertTrue(flavors[1] != null && flavors[1].contains("Title"), "plain text is still there for a code target");
        assertFalse(flavors[1].contains("**"), "the plain flavor is markup-stripped, as it always was");
    }

    @Test
    void aNonMarkdownPreviewCopiesItsSourceWithNoHtmlFlavor() throws Exception {
        String[] flavors = FxTestSupport.callOnFx(() -> {
            // Put something else on the clipboard first, so a stale HTML flavor would be visible.
            buffer("markdown", "# Prior\n").copyPreviewToClipboard();
            buffer("mermaid", "graph TD;\n  A-->B;\n").copyPreviewToClipboard();
            Clipboard cb = Clipboard.getSystemClipboard();
            return new String[] {cb.hasHtml() ? cb.getHtml() : null, cb.hasString() ? cb.getString() : null};
        });
        assertEquals(null, flavors[0], "a diagram has no rendered HTML to offer");
        assertTrue(flavors[1] != null && flavors[1].contains("graph TD"), "a diagram copies its source");
    }

    @Test
    void copyAsHtmlPutsTheMarkupOnAsPlainText() throws Exception {
        String[] result = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = buffer("markdown", "# Title\n");
            boolean ok = b.copyPreviewHtmlSource();
            Clipboard cb = Clipboard.getSystemClipboard();
            return new String[] {String.valueOf(ok), cb.hasString() ? cb.getString() : null};
        });
        assertEquals("true", result[0], "reports success for a Markdown buffer");
        assertTrue(result[1] != null && result[1].contains("<h1"), "the markup itself is the plain-text payload");
    }

    @Test
    void copyAsHtmlRefusesANonMarkdownBuffer() throws Exception {
        boolean ok = FxTestSupport.callOnFx(
                () -> buffer("mermaid", "graph TD;\n  A-->B;\n").copyPreviewHtmlSource());
        assertFalse(ok, "the caller reports 'Markdown only' rather than copying something meaningless");
    }
}

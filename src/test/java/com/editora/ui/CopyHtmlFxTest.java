package com.editora.ui;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-FX coverage of the copy-with-highlighting wiring. The HTML-generation rules are unit-tested in
 * {@code CodeHtmlTest}; here we check the buffer's {@code hasHighlighting} gate (grammar present vs not) and
 * that the system clipboard accepts an HTML flavor alongside plain text. We deliberately do <em>not</em>
 * re-tokenize on the FX thread — the copy path reads {@code area.getStyleSpans(...)}, and tokenizing on the
 * FX thread would race the background highlighters against the shared tm4e grammar.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CopyHtmlFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aGrammarBufferReportsHighlighting() throws Exception {
        boolean highlighted = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("class C {}");
            b.getNode();
            return b.hasHighlighting();
        });
        assertTrue(highlighted, "a java buffer is syntax-highlighted");
    }

    @Test
    void aPlainBufferReportsNoHighlighting() throws Exception {
        boolean highlighted = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("just plain text with no grammar");
            b.getNode();
            return b.hasHighlighting();
        });
        assertFalse(highlighted, "no grammar → plain-text copy only");
    }

    @Test
    void theSystemClipboardAcceptsAnHtmlFlavor() throws Exception {
        boolean hasHtml = FxTestSupport.callOnFx(() -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString("return;");
            cc.putHtml("<pre><span style=\"color:#cf222e\">return</span>;</pre>");
            Clipboard.getSystemClipboard().setContent(cc);
            return Clipboard.getSystemClipboard().hasHtml();
        });
        assertTrue(hasHtml, "the clipboard carries the text/html flavor alongside text/plain");
    }
}

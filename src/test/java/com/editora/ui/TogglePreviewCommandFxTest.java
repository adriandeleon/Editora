package com.editora.ui;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TogglePreviewCommandFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer addActive(String content, String language) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            if (language != null) {
                b.setLanguageOverride(language);
            }
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    @Test
    void togglePreviewFlipsPreviewableBufferEditorPreview() throws Exception {
        EditorBuffer b = addActive("# Hello\n", "markdown");
        assertTrue(FxTestSupport.callOnFx(b::hasPreview), "markdown buffer is previewable");
        assertSame(MarkdownViewMode.EDITOR, FxTestSupport.callOnFx(b::getMarkdownViewMode));

        FxTestSupport.runOnFx(() -> registry.run("view.togglePreview"));
        assertSame(MarkdownViewMode.PREVIEW, FxTestSupport.callOnFx(b::getMarkdownViewMode), "EDITOR -> PREVIEW");

        FxTestSupport.runOnFx(() -> registry.run("view.togglePreview"));
        assertSame(MarkdownViewMode.EDITOR, FxTestSupport.callOnFx(b::getMarkdownViewMode), "PREVIEW -> EDITOR");
    }

    @Test
    void toggleSplitPreviewFlipsPreviewableBufferEditorSplit() throws Exception {
        EditorBuffer b = addActive("# Hello\n", "markdown");
        FxTestSupport.runOnFx(() -> registry.run("view.toggleSplitPreview"));
        assertSame(MarkdownViewMode.SPLIT, FxTestSupport.callOnFx(b::getMarkdownViewMode), "EDITOR -> SPLIT");

        FxTestSupport.runOnFx(() -> registry.run("view.toggleSplitPreview"));
        assertSame(MarkdownViewMode.EDITOR, FxTestSupport.callOnFx(b::getMarkdownViewMode), "SPLIT -> EDITOR");
    }

    @Test
    void theTwoCommandsFlipBetweenSplitAndPreview() throws Exception {
        EditorBuffer b = addActive("# Hi\n", "markdown");
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(MarkdownViewMode.SPLIT));

        // Full-preview toggle from SPLIT jumps straight to PREVIEW (not back to EDITOR).
        FxTestSupport.runOnFx(() -> registry.run("view.togglePreview"));
        assertSame(MarkdownViewMode.PREVIEW, FxTestSupport.callOnFx(b::getMarkdownViewMode), "SPLIT -> PREVIEW");

        // Split toggle from PREVIEW jumps to SPLIT.
        FxTestSupport.runOnFx(() -> registry.run("view.toggleSplitPreview"));
        assertSame(MarkdownViewMode.SPLIT, FxTestSupport.callOnFx(b::getMarkdownViewMode), "PREVIEW -> SPLIT");
    }

    @Test
    void bothCommandsNoOpOnNonPreviewableBuffer() throws Exception {
        EditorBuffer b = addActive("plain text\n", null);
        assertFalse(FxTestSupport.callOnFx(b::hasPreview), "plain buffer is not previewable");
        FxTestSupport.runOnFx(() -> registry.run("view.togglePreview"));
        FxTestSupport.runOnFx(() -> registry.run("view.toggleSplitPreview"));
        assertSame(MarkdownViewMode.EDITOR, FxTestSupport.callOnFx(b::getMarkdownViewMode), "stays EDITOR, no crash");
    }
}

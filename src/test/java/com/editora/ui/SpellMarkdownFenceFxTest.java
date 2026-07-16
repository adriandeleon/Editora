package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a Markdown buffer's spell overlay actually knows it's Markdown, so ``` fenced code blocks are
 * skipped. {@code setMarkdown} was only ever called from {@code installOverlays()} — which runs in the
 * EditorBuffer constructor, before any path/language exists — so the flag was permanently false and the
 * fenced-code skip (and its unit tests) were dead code in production: opening a README squiggled `sudo`,
 * `cd`, `xzf` inside its ```bash block.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpellMarkdownFenceFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static boolean overlayThinksItsMarkdown(EditorBuffer b) {
        Object overlay = FxTestSupport.field(b, "spellOverlay");
        return FxTestSupport.<Boolean>field(overlay, "markdown");
    }

    @Test
    void aMarkdownBufferTellsItsSpellOverlaySoFencesAreSkipped() throws Exception {
        boolean markdown = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("text\n```bash\nsudo cd xzf\n```\n");
            b.setPath(Path.of("README.md")); // → applyLanguage("markdown", …)
            return overlayThinksItsMarkdown(b);
        });
        assertTrue(markdown, "a .md buffer's spell overlay must be in markdown mode (fenced code skipped)");
    }

    @Test
    void aNonMarkdownBufferIsNotInMarkdownMode() throws Exception {
        boolean markdown = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("Main.java"));
            return overlayThinksItsMarkdown(b);
        });
        assertFalse(markdown, "a code buffer is not in markdown mode");
    }
}

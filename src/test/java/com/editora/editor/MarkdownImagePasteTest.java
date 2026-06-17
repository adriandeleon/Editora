package com.editora.editor;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the pure Markdown image-paste helpers. */
class MarkdownImagePasteTest {

    @Test
    void uniqueFileNameAvoidsCollisions() {
        Set<String> taken = Set.of("pasted-image.png", "pasted-image-1.png");
        assertEquals("pasted-image-2.png", MarkdownImagePaste.uniqueFileName(taken::contains, "pasted-image", "png"));
        assertEquals("fresh.png", MarkdownImagePaste.uniqueFileName(taken::contains, "fresh", "png"));
    }

    @Test
    void relativePathIsForwardSlashed() {
        Path base = Path.of("/home/u/docs");
        Path target = Path.of("/home/u/docs/assets/pasted-image.png");
        assertEquals("assets/pasted-image.png", MarkdownImagePaste.relativePath(base, target));
    }

    @Test
    void snippetFormatsMarkdownImage() {
        assertEquals("![](assets/x.png)", MarkdownImagePaste.snippet("assets/x.png", ""));
        assertEquals("![logo](assets/x.png)", MarkdownImagePaste.snippet("assets/x.png", "logo"));
    }
}

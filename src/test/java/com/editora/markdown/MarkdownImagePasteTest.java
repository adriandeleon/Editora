package com.editora.markdown;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void imageUrlFromDragPrefersUrlThenHtmlThenString() {
        assertEquals(
                "https://ex.com/a.png",
                MarkdownImagePaste.imageUrlFromDrag("https://ex.com/a.png", "<img src=\"https://ex.com/b.png\">", "x"));
        assertEquals(
                "https://ex.com/b.png",
                MarkdownImagePaste.imageUrlFromDrag(null, "<p><img alt='c' src=\"https://ex.com/b.png\"/></p>", null));
        assertEquals("https://ex.com/c.jpg", MarkdownImagePaste.imageUrlFromDrag(null, null, "https://ex.com/c.jpg"));
        assertEquals(
                "data:image/png;base64,AAAA",
                MarkdownImagePaste.imageUrlFromDrag("data:image/png;base64,AAAA", null, null));
        assertNull(MarkdownImagePaste.imageUrlFromDrag(null, null, "just some dragged text"));
        assertNull(MarkdownImagePaste.imageUrlFromDrag(null, null, null));
    }

    @Test
    void extensionForUrlReadsPathOrDataMimeElseFallback() {
        assertEquals("png", MarkdownImagePaste.extensionForUrl("https://ex.com/a.png", "bin"));
        assertEquals("jpg", MarkdownImagePaste.extensionForUrl("https://ex.com/a.JPEG", "bin"));
        assertEquals("gif", MarkdownImagePaste.extensionForUrl("https://ex.com/a.gif?w=100#x", "bin"));
        assertEquals("svg", MarkdownImagePaste.extensionForUrl("data:image/svg+xml;base64,zzz", "bin"));
        assertEquals("png", MarkdownImagePaste.extensionForUrl("data:image/png;base64,zzz", "bin"));
        assertEquals("png", MarkdownImagePaste.extensionForUrl("https://ex.com/cdn/12345", "png"));
        assertEquals("png", MarkdownImagePaste.extensionForUrl(null, "png"));
    }
}

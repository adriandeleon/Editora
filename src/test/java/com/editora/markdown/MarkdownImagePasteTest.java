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
        assertEquals(
                "![screen_shot_1](assets/screen_shot_1.png)",
                MarkdownImagePaste.snippet("assets/screen_shot_1.png", "screen_shot_1"));
    }

    /** Parses {@code snippet} as Markdown and returns {@code [alt, destination]} of the image it produced. */
    private static String[] parsedImage(String snippet) {
        org.commonmark.node.Node para =
                com.editora.editor.MarkdownRenderer.parseToDocument(snippet).getFirstChild();
        org.commonmark.node.Node first = para == null ? null : para.getFirstChild();
        if (!(first instanceof org.commonmark.node.Image img) || first.getNext() != null) {
            return null; // not (only) an image: the snippet is broken Markdown
        }
        StringBuilder alt = new StringBuilder();
        for (org.commonmark.node.Node c = img.getFirstChild(); c != null; c = c.getNext()) {
            alt.append(((org.commonmark.node.Text) c).getLiteral());
        }
        return new String[] {alt.toString(), img.getDestination()};
    }

    @Test
    void snippetForAPathWithSpacesOrParenthesesIsStillAnImage() {
        assertEquals(
                "![Screenshot 2026-10-01 at 10.15.32](<assets/Screenshot 2026-10-01 at 10.15.32.png>)",
                MarkdownImagePaste.snippet(
                        "assets/Screenshot 2026-10-01 at 10.15.32.png", "Screenshot 2026-10-01 at 10.15.32"));
        assertEquals(
                "![image (1)](<assets/image (1).png>)",
                MarkdownImagePaste.snippet("assets/image (1).png", "image (1)"));
        assertEquals("![a](<assets/a(.png>)", MarkdownImagePaste.snippet("assets/a(.png", "a"));
        assertEquals("![x](<assets/a\\<b\\>.png>)", MarkdownImagePaste.snippet("assets/a<b>.png", "x"));
        assertEquals(
                "![plot \\[final\\]](assets/plot.png)", MarkdownImagePaste.snippet("assets/plot.png", "plot [final]"));

        // Every awkward name round-trips through the real Markdown parser to the same alt + path, and the
        // path is what the preview / PDF / office writers resolve against the document's folder.
        String[][] cases = {
            {"assets/Screenshot 2026-10-01 at 10.15.32.png", "Screenshot 2026-10-01 at 10.15.32"},
            {"assets/image (1).png", "image (1)"},
            {"assets/a(.png", "a("},
            {"assets/tab\there.png", "tab"},
            {"assets/a<b>.png", "a<b>"},
            {"assets/plot.png", "plot [final] ]["},
            {"assets/back\\slash name.png", "back\\slash"},
            {"assets/plain.png", ""},
            {"assets/x.png", "_draft_ *v2* `code` R&D &amp; ~~old~~ screen_shot_1"},
        };
        for (String[] c : cases) {
            String snippet = MarkdownImagePaste.snippet(c[0], c[1]);
            String[] parsed = parsedImage(snippet);
            org.junit.jupiter.api.Assertions.assertNotNull(parsed, "not an image: " + snippet);
            assertEquals(c[1], parsed[0], snippet);
            assertEquals(c[0], parsed[1], snippet);
        }
    }

    @Test
    void theOldUnescapedFormWasNotAnImage() {
        // Documents why snippet() must not emit this: CommonMark does not parse it as an image.
        assertNull(parsedImage("![shot](assets/Screenshot 2026-10-01 at 10.15.32.png)"));
        assertNull(parsedImage("![a](assets/a(.png)"));
    }

    @Test
    void previewResolvesTheAngleBracketFormToTheRealFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path assets = java.nio.file.Files.createDirectories(dir.resolve("assets"));
        Path file = java.nio.file.Files.createFile(assets.resolve("image (1) copy.png"));
        String[] parsed =
                parsedImage(MarkdownImagePaste.snippet(MarkdownImagePaste.relativePath(dir, file), "image (1) copy"));
        // The preview's own URL resolution (private; other batches own the class, so it is probed, not changed).
        java.lang.reflect.Method resolve =
                com.editora.editor.MarkdownRenderer.class.getDeclaredMethod("resolveUrl", String.class, Path.class);
        resolve.setAccessible(true);
        String url = (String) resolve.invoke(null, parsed[1], dir);
        assertEquals(file.toUri().toString(), url);
        assertEquals(file, Path.of(java.net.URI.create(url)));
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

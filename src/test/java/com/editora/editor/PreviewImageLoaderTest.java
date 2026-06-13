package com.editora.editor;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure SVG content sniff used by the preview image loader. */
class PreviewImageLoaderTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void detectsPlainSvg() {
        assertTrue(PreviewImageLoader.looksLikeSvg(b("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")));
    }

    @Test
    void detectsSvgAfterXmlProlog() {
        assertTrue(PreviewImageLoader.looksLikeSvg(
                b("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- a badge -->\n<svg width=\"100\"/>")));
        assertTrue(PreviewImageLoader.looksLikeSvg(b("<SVG />"))); // case-insensitive
    }

    @Test
    void rejectsRasterAndOtherContent() {
        assertFalse(PreviewImageLoader.looksLikeSvg(new byte[] {(byte) 0x89, 'P', 'N', 'G'})); // PNG magic
        assertFalse(PreviewImageLoader.looksLikeSvg(b("<html><body>not svg</body></html>")));
        assertFalse(PreviewImageLoader.looksLikeSvg(b("just some text")));
        assertFalse(PreviewImageLoader.looksLikeSvg(new byte[0]));
    }
}

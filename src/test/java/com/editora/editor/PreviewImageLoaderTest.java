package com.editora.editor;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure SVG content sniff + JSVG rasterization used by the preview image loader. */
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

    @Test
    void rasterizesValidSvgToPng() {
        byte[] png = PreviewImageLoader.svgToPng(b("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\""
                + " height=\"20\"><rect width=\"20\" height=\"20\" fill=\"red\"/></svg>"));
        assertTrue(png != null && png.length > 8, "expected non-empty PNG bytes");
        // PNG signature: 0x89 'P' 'N' 'G'
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
    }

    @Test
    void svgToPngReturnsNullOnGarbage() {
        assertNull(PreviewImageLoader.svgToPng(b("this is not an svg at all")));
    }
}

package com.editora.pdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a Markdown image whose bytes are SVG (e.g. a shields.io/GitHub badge served as
 * {@code image/svg+xml}) must not abort the PDF export. PDFBox can't decode SVG, so it's rasterized to
 * PNG via {@link com.editora.editor.PreviewImageLoader#svgToPng}; if that ever fails the writer falls
 * back to alt text instead of throwing (it used to crash with IllegalArgumentException).
 */
class MarkdownPdfWriterSvgTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"80\" height=\"20\">"
            + "<rect width=\"80\" height=\"20\" fill=\"green\"/></svg>";

    @Test
    void svgRasterizesToPng() {
        byte[] png = com.editora.editor.PreviewImageLoader.svgToPng(SVG.getBytes(StandardCharsets.UTF_8));
        assertNotNull(png, "SVG should rasterize to PNG");
        assertTrue(png.length > 8 && (png[1] & 0xFF) == 'P' && (png[2] & 0xFF) == 'N', "PNG signature");
    }

    @Test
    void svgImageDoesNotAbortExport() throws Exception {
        String dataUri =
                "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(SVG.getBytes(StandardCharsets.UTF_8));
        String md = "# Title\n\n![badge](" + dataUri + ")\n\nbody text\n";
        Path out = Files.createTempFile("svgpdf", ".pdf");
        assertDoesNotThrow(() -> MarkdownPdfWriter.write(md, null, "letter", null, out));
        assertTrue(Files.size(out) > 0, "PDF produced");
    }
}

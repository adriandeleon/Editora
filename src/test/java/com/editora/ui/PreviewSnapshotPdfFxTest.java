package com.editora.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

import com.editora.editor.EditorBuffer;
import com.editora.pdf.ImagePdfWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (headless-FX) coverage of the "snapshot the preview → PDF" export for the image/tree previews
 * (JSON/YAML/TOML tree, XML tree, Markwhen timeline): each buffer's {@link EditorBuffer#snapshotPreviewChunks(String)}
 * produces real PNG images, which {@link ImagePdfWriter} turns into a valid multi-page PDF.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewSnapshotPdfFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static List<byte[]> chunksFor(String lang, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride(lang);
            b.setContent(text);
            b.setStructuredPreviewEnabled(true); // no-op for markwhen; enables the tree previews
            b.getNode();
            return b.snapshotPreviewChunks(null); // null UA → inherit the harness theme (color isn't asserted)
        });
    }

    private void assertExportsToPdf(String lang, String text, Path out) throws Exception {
        List<byte[]> chunks = chunksFor(lang, text);
        assertNotNull(chunks, lang + " should produce snapshot chunks");
        assertTrue(!chunks.isEmpty(), lang + " chunks should be non-empty");
        for (byte[] png : chunks) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            assertNotNull(img, lang + " chunk should be a decodable PNG");
            assertTrue(img.getWidth() > 0 && img.getHeight() > 0, lang + " chunk should have pixels");
        }
        ImagePdfWriter.write(
                chunks.stream()
                        .map(png -> {
                            try {
                                return ImageIO.read(new ByteArrayInputStream(png));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .toList(),
                "letter",
                out);
        assertTrue(Files.size(out) > 0, lang + " PDF should be written");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertTrue(doc.getNumberOfPages() >= 1, lang + " PDF should have pages");
        }
    }

    @Test
    void forcedLightThemeSnapshotStillRenders() throws Exception {
        // A valid Primer Light user-agent stylesheet URL + a snapshot that still produces pixels (the
        // forced-light export path; colour correctness is visual and not asserted here).
        String lightUa = Themes.lightUserAgentStylesheet();
        assertNotNull(lightUa);
        List<byte[]> chunks = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("json");
            b.setContent("{\"a\":1,\"b\":\"two\"}");
            b.setStructuredPreviewEnabled(true);
            b.getNode();
            return b.snapshotPreviewChunks(lightUa);
        });
        assertNotNull(chunks);
        assertTrue(!chunks.isEmpty() && chunks.get(0).length > 0);
    }

    @Test
    void jsonYamlXmlAndMarkwhenPreviewsSnapshotToPdf(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        assertExportsToPdf("json", "{\"name\":\"editora\",\"nested\":{\"a\":1,\"b\":[1,2,3]}}", dir.resolve("j.pdf"));
        assertExportsToPdf("yaml", "name: editora\nnested:\n  a: 1\n  b:\n    - 1\n    - 2\n", dir.resolve("y.pdf"));
        assertExportsToPdf("xml", "<root attr=\"x\"><child>text</child><!-- c --></root>", dir.resolve("x.pdf"));
        assertExportsToPdf("markwhen", "title: Demo\n2020: start\n2021 / 2022: middle\n", dir.resolve("m.pdf"));
    }
}

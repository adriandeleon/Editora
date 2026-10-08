package com.editora.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

import com.editora.editor.EditorBuffer;
import com.editora.editor.PreviewSnapshots;
import com.editora.pdf.ImagePaging;
import com.editora.pdf.ImagePdfWriter;
import com.editora.pdf.PageImage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (headless-FX) coverage of the "snapshot the preview → PDF" export for the image/tree previews
 * (JSON/YAML/TOML tree, XML tree, Markwhen timeline): each buffer's {@link EditorBuffer#snapshotPreviewChunks}
 * produces real PNG images, which {@link ImagePdfWriter} turns into a valid multi-page PDF.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewSnapshotPdfFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Runs the (multi-pulse) snapshot of {@code b} and waits for its result off the FX thread. */
    static PreviewSnapshots.Result snapshot(EditorBuffer b, String lightUa) throws Exception {
        java.util.concurrent.CompletableFuture<java.util.Optional<PreviewSnapshots.Result>> done =
                new java.util.concurrent.CompletableFuture<>();
        FxTestSupport.runOnFx(() ->
                b.snapshotPreviewChunks(lightUa, (at, of) -> {}, r -> done.complete(java.util.Optional.ofNullable(r))));
        return done.get(120, java.util.concurrent.TimeUnit.SECONDS).orElse(null);
    }

    static EditorBuffer buffer(String lang, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride(lang);
            b.setContent(text);
            b.setStructuredPreviewEnabled(true); // no-op for markwhen; enables the tree previews
            b.getNode();
            return b;
        });
    }

    private static List<byte[]> chunksFor(String lang, String text) throws Exception {
        // null UA → inherit the harness theme (color isn't asserted)
        PreviewSnapshots.Result r = snapshot(buffer(lang, text), null);
        return r == null ? null : r.images().stream().map(PageImage::png).toList();
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
        PreviewSnapshots.Result r = snapshot(buffer("json", "{\"a\":1,\"b\":\"two\"}"), lightUa);
        assertNotNull(r);
        List<byte[]> chunks = r.images().stream().map(PageImage::png).toList();
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

    private static boolean blank(BufferedImage img, int row) {
        int first = img.getRGB(0, row);
        for (int x = 1; x < img.getWidth(); x++) {
            if (img.getRGB(x, row) != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * A tree longer than a page: the snapshot is at least 2× (it used to be 1×, 72 dpi on paper), its chunks
     * continue on the same page, and every page break lies between two rows — it used to fall wherever the
     * page ended, through a line of text.
     */
    @Test
    void aTreeSnapshotBreaksPagesBetweenRowsAndIsAtLeastTwiceAsDense(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        StringBuilder yaml = new StringBuilder();
        for (int i = 0; i < 260; i++) {
            yaml.append("key").append(i).append(": value number ").append(i).append('\n');
        }
        PreviewSnapshots.Result r = snapshot(buffer("yaml", yaml.toString()), Themes.lightUserAgentStylesheet());
        assertNotNull(r);
        assertEquals(261, r.totalRows(), "the root and one row per key");
        assertEquals(261, r.shownRows());
        assertEquals(3, r.images().size(), "100 rows a chunk");
        List<ImagePaging.Source> sources =
                r.images().stream().map(PageImage::source).toList();
        List<BufferedImage> decoded = new java.util.ArrayList<>();
        for (PageImage image : r.images()) {
            decoded.add(ImageIO.read(new ByteArrayInputStream(image.png())));
        }
        for (int i = 0; i < sources.size(); i++) {
            assertTrue(sources.get(i).pixelScale() >= 2, "snapshot density");
            assertEquals(decoded.get(i).getHeight(), sources.get(i).pixelHeight());
            assertEquals(i > 0, sources.get(i).continues());
        }
        assertEquals(99, sources.get(0).safeCuts().length, "a boundary between each two of the 100 rows");

        List<ImagePaging.Page> pages = ImagePaging.layout(sources, 540, 720, true, ImagePaging.BlankRows.NONE);
        assertTrue(pages.size() >= 4, "261 rows do not fit three pages: " + pages.size());
        int breaks = 0;
        for (ImagePaging.Page page : pages) {
            for (ImagePaging.Slice s : page.slices()) {
                assertTrue(s.srcWidth() / s.width() >= 2, "at least 2 px per point");
                int cut = s.srcY() + s.srcHeight();
                if (cut == sources.get(s.image()).pixelHeight()) {
                    continue; // the end of a chunk is the end of a row
                }
                breaks++;
                assertTrue(
                        java.util.Arrays.stream(sources.get(s.image()).safeCuts())
                                .anyMatch(c -> c == cut),
                        "a page break at pixel row " + cut + " is not a row boundary");
                assertTrue(
                        blank(decoded.get(s.image()), cut - 1) && blank(decoded.get(s.image()), cut),
                        "ink at the page break, pixel row " + cut);
            }
        }
        assertTrue(breaks >= 2, "page breaks inside chunks were exercised: " + breaks);
        long slices = pages.stream().mapToInt(p -> p.slices().size()).sum();
        assertTrue(slices > pages.size(), "a chunk continues on the page the one before it ended on");

        Path out = dir.resolve("tree.pdf");
        ImagePdfWriter.writePng(r.images(), "letter", out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(pages.size(), doc.getNumberOfPages());
        }
    }

    private static final String OPENAPI = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Pets\",\"version\":\"1\"},"
            + "\"paths\":{\"/pets\":{\"get\":{\"summary\":\"List pets\","
            + "\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

    /** Print/PDF of an OpenAPI document give what the preview shows: the docs by default, the tree after a switch. */
    @Test
    void anOpenApiDocumentSnapshotsItsDocsViewUntilTheUserSwitchesToTheTree() throws Exception {
        EditorBuffer b = buffer("json", OPENAPI);
        PreviewSnapshots.Result docs = snapshot(b, null);
        assertNotNull(docs);
        assertEquals(0, docs.totalRows(), "the docs are one rendered node, not tree rows");
        assertEquals(1, docs.images().size());
        assertEquals(
                PreviewSnapshots.DOCS_WIDTH,
                docs.images().get(0).source().logicalWidth(),
                1.0,
                "laid out at the docs width");

        FxTestSupport.runOnFx(b::toggleStructuredView);
        PreviewSnapshots.Result tree = snapshot(b, null);
        assertNotNull(tree);
        assertTrue(tree.totalRows() > 10, "the tree view was chosen: " + tree.totalRows());
        assertTrue(tree.images().get(0).source().logicalWidth() < PreviewSnapshots.DOCS_WIDTH);

        // A JSON file that is not OpenAPI has no docs view to show.
        assertTrue(snapshot(buffer("json", "{\"a\":1}"), null).totalRows() > 0);
    }

    /** The row cap is reported, and the output itself ends with a line saying so. */
    @Test
    void aTreeOverTheRowCapReportsHowManyRowsItLeftOut() throws Exception {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 4200; i++) {
            json.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":").append(i);
        }
        PreviewSnapshots.Result r = snapshot(buffer("json", json.append('}').toString()), null);
        assertNotNull(r);
        assertTrue(r.truncated());
        assertEquals(4000, r.shownRows());
        assertEquals(4201, r.totalRows());
        assertEquals(40, r.images().size());
        int[] last = r.images().get(39).source().safeCuts();
        int[] before = r.images().get(38).source().safeCuts();
        assertEquals(before.length + 1, last.length, "one more row on the last chunk: the truncation line");
        assertFalse(snapshot(buffer("json", "{\"a\":1}"), null).truncated());
    }
}

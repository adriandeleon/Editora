package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.pdf.HiDpiImage;
import com.editora.pdf.ImagePaging;
import com.editora.pdf.PageImage;
import com.editora.pdf.PdfExportService;
import com.editora.print.PrintService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The image pipeline print and PDF share, where it meets the toolkit: printed image pages (density, no
 * enlarging, tiling), the Project Map's density, the truncation notice of a capped tree, and the diagram
 * print's temp file. No printer is involved: pages are built for a printable area given in points.
 */
@Tag("fx")
class RasterPrintFxTest {
    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final double W = 576; // Letter, quarter-inch margins
    private static final double H = 756;

    private static ImageView only(Node page) {
        return (ImageView) ((Pane) page).getChildren().get(0);
    }

    @Test
    void aDenseImagePrintsAtItsLogicalSizeWithAllItsPixels() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Image img = new WritableImage(800, 400); // a 2× snapshot of a 400×200 node
            PrintService.Pages pages =
                    PrintService.imagePages(List.of(new ImagePaging.Source(800, 400, 2, null, false)), i -> img, W, H);
            assertEquals(1, pages.count());
            ImageView iv = only(pages.get(0));
            assertEquals(400, iv.getFitWidth(), 1e-6, "one logical pixel per point, not one image pixel");
            assertEquals(200, iv.getFitHeight(), 1e-6);
            assertEquals(800, iv.getViewport().getWidth(), 1e-6, "every pixel is kept: 144 dpi");
        });
    }

    @Test
    void aWideImageIsNeverRotatedForPrintButTiledWhenItWouldBecomeAStrip() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Image map = new WritableImage(6000, 2000);
            PrintService.Pages pages =
                    PrintService.imagePages(List.of(ImagePaging.Source.plain(6000, 2000)), i -> map, W, H);
            // Half size is 3000×1000 pt on 576×756: 6 columns, 2 bands. It used to be one 576×192 pt strip.
            assertEquals(12, pages.count());
            ImageView second = only(pages.get(1));
            assertEquals(1152, second.getViewport().getMinX(), 1e-6, "the next column to the right");
            assertEquals(576, second.getFitWidth(), 1e-6);

            // The Project Map's own snapshot says how dense it is, so a small map at 2× is not mistaken for a
            // huge one: 1600 px at 2× is 800 logical pixels — fitted to the width, on one page.
            Image small = new HiDpiImage(1600, 1200, 2);
            assertEquals(2, HiDpiImage.scaleOf(small));
            assertEquals(1, HiDpiImage.scaleOf(map));
            pages = PrintService.imagePages(
                    List.of(new ImagePaging.Source(1600, 1200, HiDpiImage.scaleOf(small), null, false)),
                    i -> small,
                    W,
                    H);
            assertEquals(1, pages.count());
            assertEquals(576, only(pages.get(0)).getFitWidth(), 1e-6);
        });
    }

    @Test
    void aStandaloneDiagramIsShrunkToThePageButNeverEnlarged() throws Exception {
        FxTestSupport.runOnFx(() -> {
            // mmdc renders at 2×: a 300×200 px PNG is a 150×100 diagram. It used to be blown up to 576 pt.
            ImageView small = (ImageView) ((StackPane) PrintService.imagePage(new WritableImage(300, 200), 2, W, H))
                    .getChildren()
                    .get(0);
            assertEquals(150, small.getFitWidth(), 1e-6);
            assertEquals(100, small.getFitHeight(), 1e-6);
            ImageView big = (ImageView) ((StackPane) PrintService.imagePage(new WritableImage(2304, 800), 2, W, H))
                    .getChildren()
                    .get(0);
            assertEquals(576, big.getFitWidth(), 1e-6, "a large one still shrinks to fit");
            assertEquals(200, big.getFitHeight(), 1e-6);
        });
    }

    @Test
    void anSvgIsRasterizedByTheServiceAtPrintDensity() throws Exception {
        byte[] svg =
                "<svg xmlns='http://www.w3.org/2000/svg' width='240' height='160'><rect width='240' height='160'/></svg>"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path out = temp.resolve("pic.pdf");
        PdfExportService service = new PdfExportService();
        try {
            CompletableFuture<PdfExportService.Result> done = new CompletableFuture<>();
            service.exportSvg(svg, "no image", "letter", out, done::complete);
            assertTrue(done.get(30, TimeUnit.SECONDS).ok());
            try (PDDocument doc = Loader.loadPDF(out.toFile())) {
                var res = doc.getPage(0).getResources();
                var image = (org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject)
                        res.getXObject(res.getXObjectNames().iterator().next());
                assertEquals(960, image.getWidth(), "4× the SVG's 240 px: it used to be 480 px drawn 480 pt wide");
            }
            done = new CompletableFuture<>();
            service.exportSvg("not an svg".getBytes(), "no image", "letter", out, done::complete);
            PdfExportService.Result failed = done.get(30, TimeUnit.SECONDS);
            assertFalse(failed.ok());
            assertEquals("no image", failed.message());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void pngPagesOfUnknownDensityAreDecodedOnlyWhenAPageIsAskedFor() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(200, 300, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        PrintService service = new PrintService();
        try {
            CompletableFuture<PrintService.Prepared> done = new CompletableFuture<>();
            service.preparePageImages(
                    List.of(PageImage.of(bytes.toByteArray()), PageImage.of(bytes.toByteArray(), 2)), done::complete);
            PrintService.Prepared prepared = done.get(30, TimeUnit.SECONDS);
            assertTrue(prepared.ok());
            done = new CompletableFuture<>();
            service.prepareImages(List.of(), done::complete);
            assertFalse(done.get(30, TimeUnit.SECONDS).ok(), "nothing to print is an error, not a blank page");
        } finally {
            service.shutdown();
        }
    }

    private static final class Host extends CoordinatorHostStub {
        private final Settings settings = new Settings();
        private EditorBuffer active;
        private final List<String> statuses = new ArrayList<>();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }
    }

    /** A tree over the row cap: the export says how much it left out instead of a plain "exported". */
    @Test
    void exportingATreeOverTheRowCapSaysSoInTheStatusBar() throws Exception {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 4100; i++) {
            json.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":").append(i);
        }
        json.append('}');
        Path output = temp.resolve("big.pdf");
        Host host = new Host();
        ExportCoordinator[] exports = new ExportCoordinator[1];
        try {
            FxTestSupport.runOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setDisplayName("big.json");
                b.setContent(json.toString());
                b.setStructuredPreviewEnabled(true);
                b.getNode();
                host.active = b;
                exports[0] = new ExportCoordinator(
                        host, null, null, null, path -> fail("PDF export opens nothing"), chooser -> output.toFile());
                exports[0].exportPreviewPdf();
                assertEquals(
                        List.of(tr("status.pdf.exporting")),
                        host.statuses,
                        "the command returns before the tree is snapshotted: the FX thread is not held for it");
            });
            String expected = tr("status.pdf.exportedTruncated", 4000, 4101);
            for (int i = 0; i < 1200 && !FxTestSupport.callOnFx(() -> host.statuses.contains(expected)); i++) {
                Thread.sleep(50);
            }
            List<String> statuses = FxTestSupport.callOnFx(() -> List.copyOf(host.statuses));
            assertEquals(expected, statuses.get(statuses.size() - 1), statuses.toString());
            assertTrue(statuses.contains(tr("status.preview.snapshotting", 50)), "progress while snapshotting");
            assertTrue(statuses.contains(tr("status.pdf.exported", output.toString())), "after the plain report");
            assertTrue(Files.size(output) > 0);
            try (PDDocument doc = Loader.loadPDF(output.toFile())) {
                assertTrue(doc.getNumberOfPages() > 50, "4,000 rows: " + doc.getNumberOfPages());
            }
        } finally {
            FxTestSupport.runOnFx(() -> exports[0].shutdown());
        }
    }

    /** The DOT/PlantUML print renders to a temp PNG: it is removed whether the render worked or not. */
    @Test
    void theDiagramPrintTempFileIsDeletedOnEveryPath() throws Exception {
        Path rendered = Files.write(temp.resolve("ok.png"), new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {1, 2, 3}, ExportCoordinator.takeRenderedPng(rendered, true));
        assertFalse(Files.exists(rendered), "after a successful read");

        Path failed = Files.write(temp.resolve("failed.png"), new byte[0]); // createTempFile left it empty
        assertNull(ExportCoordinator.takeRenderedPng(failed, false));
        assertFalse(Files.exists(failed), "after a failed render — this one used to stay behind");

        Path unreadable = Files.createDirectory(temp.resolve("dir.png")); // reading it throws
        assertThrows(java.io.IOException.class, () -> ExportCoordinator.takeRenderedPng(unreadable, true));
        assertFalse(Files.exists(unreadable), "after a failed read — so did this one");
    }
}

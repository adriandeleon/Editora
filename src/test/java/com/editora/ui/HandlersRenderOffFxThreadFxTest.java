package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.Pane;

import com.editora.editor.EditorBuffer;
import com.editora.editor.markdown.MarkdownRenderer;
import com.editora.editor.markdown.MathImages;
import com.editora.editor.markdown.PreviewMenu;
import com.editora.pdf.ImagePaging;
import com.editora.pdf.PageImage;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import com.editora.print.PrintService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.2.0), the rendering the third audit found on the FX thread: each test drives on the FX thread
 * what used to parse or decode there — the preview's Copy HTML, a LaTeX formula in a rendered document (a cache miss,
 * a warmed formula, a formula that does not parse) and a print preview's page images — and listens to
 * {@link BlockingSites}: every named place is heard off the FX thread, none on it.
 *
 * <p>What it cannot see: work at a place that names no site (it checks the named places, not every loop); the
 * actual printer (its {@code printPage} runs after {@link PrintService.Pages#prepare}, which is driven here).
 */
@Tag("fx")
class HandlersRenderOffFxThreadFxTest {

    private record Heard(String site, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() {
        BlockingSites.listenForTest(null);
        OffFx.runnerForTest(null);
        MathImages.configure(false, false);
    }

    private void listen() {
        heard.clear();
        BlockingSites.listenForTest((site, thread) -> heard.add(new Heard(site, Platform.isFxApplicationThread())));
    }

    private void assertNothingOnFx() throws Exception {
        FxTestSupport.drainFx();
        BlockingSites.listenForTest(null);
        List<String> onFx = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx()) {
                onFx.add(h.site());
            }
        }
        assertEquals(List.of(), onFx, "rendered on the FX thread; heard: " + heard);
    }

    private void awaitHeard(String site) throws Exception {
        SettingsRig.awaitFx(
                site + " heard off FX",
                () -> heard.stream().anyMatch(h -> h.site().equals(site) && !h.fx()));
    }

    @Test
    void copyHtmlRendersTheMarkupOffTheFxThread() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("markdown");
            b.setContent("# Copied title\n\nSome **bold** text.\n");
            b.getNode();
            Clipboard.getSystemClipboard().clear();
            return b;
        });
        try {
            listen();
            assertTrue(FxTestSupport.callOnFx(buffer::copyPreviewHtmlSource));
            SettingsRig.awaitFx("the markup on the clipboard", () -> {
                Clipboard cb = Clipboard.getSystemClipboard();
                return cb.hasString() && cb.getString().contains("<h1");
            });
            awaitHeard(PreviewMenu.COPY_HTML_SITE);
            assertNothingOnFx();
        } finally {
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    @Test
    void aFormulaIsRasterizedOffTheFxThreadAndPutInWhenItLands() throws Exception {
        MathImages.configure(true, false);
        listen();
        Node doc = FxTestSupport.callOnFx(() -> MarkdownRenderer.renderDocument(
                MarkdownRenderer.parseForRender("Inline $a^{" + System.nanoTime() + "}$ here.\n", true), null));
        ImageView formula = FxTestSupport.callOnFx(() -> (ImageView) doc.lookup(".md-math-inline"));
        assertNotNull(formula, "the formula's place is there at once");
        SettingsRig.awaitFx("the formula drawn", () -> formula.getImage() != null);
        awaitHeard(MathImages.SITE);
        assertNothingOnFx();
    }

    @Test
    void aFormulaWarmedWithItsParseIsDrawnAtOnceOnTheFxThread() throws Exception {
        MathImages.configure(true, false);
        String md = "Inline $b_{" + System.nanoTime() + "}$ here.\n";
        // The preview, a popup and the agent panel parse off the FX thread and warm the formulas there.
        org.commonmark.node.Node ast = MarkdownRenderer.parseForRender(md, true);
        listen();
        MarkdownRenderer.warmMath(ast, null);
        ImageView formula = FxTestSupport.callOnFx(
                () -> (ImageView) MarkdownRenderer.renderDocument(ast, null).lookup(".md-math-inline"));
        assertNotNull(formula.getImage(), "found in the cache: drawn at once");
        assertTrue(heard.stream().anyMatch(h -> h.site().equals(MathImages.SITE) && !h.fx()));
        assertNothingOnFx();
    }

    @Test
    void aFormulaThatDoesNotParseShowsItsSourceWhenTheAnswerLands() throws Exception {
        MathImages.configure(true, false);
        listen();
        Node doc = FxTestSupport.callOnFx(() -> MarkdownRenderer.renderDocument(
                MarkdownRenderer.parseForRender("Bad $\\nosuchcommand " + System.nanoTime() + "$ here.\n", true),
                null));
        SettingsRig.awaitFx("the source shown in its place", () -> doc.lookup(".md-math-error") instanceof Label);
        assertNull(doc.lookup(".md-math-inline"), "the empty image was replaced");
        assertNothingOnFx();
    }

    @Test
    void printPagesDecodeTheirImagesOffTheFxThread() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(200, 300, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        List<PageImage> images = List.of(PageImage.of(bytes.toByteArray()), PageImage.of(bytes.toByteArray()));
        // The prepare thread's part: each image read once for its blank rows.
        PrintService.Decoder decoder = PrintService.Decoder.of(images);
        List<ImagePaging.Source> sources =
                images.stream().map(PageImage::source).toList();
        listen();
        PrintService.Pages pages = FxTestSupport.callOnFx(() -> PrintService.decodedPages(sources, decoder, 576, 756));
        Pane page = FxTestSupport.callOnFx(() -> (Pane) pages.get(0));
        ImageView slice = (ImageView) page.getChildren().get(0);
        SettingsRig.awaitFx("the page's image decoded", () -> slice.getImage() != null);
        awaitHeard(PrintService.DECODE_SITE);

        // Printing a page waits for its images: prepared, the page is built from what is at hand.
        boolean[] ready = {false};
        FxTestSupport.runOnFx(() -> pages.prepare(pages.count() - 1, () -> ready[0] = true));
        SettingsRig.awaitFx("the last page prepared", () -> ready[0]);
        Pane last = FxTestSupport.callOnFx(() -> (Pane) pages.get(pages.count() - 1));
        assertNotNull(((ImageView) last.getChildren().get(0)).getImage(), "printed with its pixels");
        assertNothingOnFx();
    }
}

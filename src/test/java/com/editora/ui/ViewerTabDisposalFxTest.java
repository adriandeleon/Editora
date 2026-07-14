package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The image / hex / PDF viewer panes were released only through {@code Tab.setOnClosed} — and JavaFX fires
 * that from exactly one place: {@code TabPaneBehavior.closeTab()}, i.e. a click on the tab's ✕. Every one of
 * the app's own close paths (Ctrl-W, Close All / Others / Left / Right, and window close) removes the tab
 * <b>programmatically</b>, which doesn't fire it.
 *
 * <p>So closing a PDF with Ctrl-W leaked its {@code pdf-render} thread — parked forever, a GC root — and left
 * the document's file handle open for the life of the process. Closing the same tab by clicking the ✕ leaked
 * nothing, which is precisely why it survived manual testing.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ViewerTabDisposalFxTest {

    private FxWindowFixture fx;

    @TempDir
    Path tmp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static Set<String> threadNamesContaining(String fragment) {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(n -> n.contains(fragment))
                .collect(Collectors.toSet());
    }

    /** A minimal one-page PDF, hand-built so the test needs no fixture file. */
    private Path writePdf() throws Exception {
        Path pdf = tmp.resolve("doc.pdf");
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void closingAPdfTabProgrammaticallyReleasesItsThread() throws Exception {
        Path pdf = writePdf();
        assertTrue(Files.exists(pdf));

        int before = threadNamesContaining("pdf-render").size();

        Tab tab = FxTestSupport.callOnFx(() -> (Tab)
                FxTestSupport.call(fx.controller, "openPdfTab", new Class<?>[] {Path.class, boolean.class}, pdf, true));
        assertTrue(tab.getUserData() instanceof PdfViewerPane, "a PDF opens in the PDF viewer");
        assertTrue(threadNamesContaining("pdf-render").size() > before, "the pane started its render thread");

        // Close it the way the app does — a programmatic remove (Ctrl-W / Close All / window close), NOT a
        // click on the ✕. This is the path that leaked.
        TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
        FxTestSupport.runOnFx(() -> tabs.getTabs().remove(tab));
        FxTestSupport.runOnFx(() -> {});

        // The executor is shut down asynchronously; give the thread a moment to actually exit.
        for (int i = 0; i < 50 && threadNamesContaining("pdf-render").size() > before; i++) {
            Thread.sleep(20);
        }
        assertEquals(
                before,
                threadNamesContaining("pdf-render").size(),
                "the pdf-render thread must be gone — it used to park forever, once per closed PDF tab");
    }

    @Test
    void closingAnImageTabProgrammaticallyDisposesThePane() throws Exception {
        // A decoded Image pins a Prism texture; the texture pool has a hard ceiling, and exhausting it is what
        // produced the black-window bug. So an image tab must release it on close, not wait for GC.
        Path png = tmp.resolve("pic.png");
        Files.write(png, pngBytes());

        Tab tab = FxTestSupport.callOnFx(() -> (Tab) FxTestSupport.call(
                fx.controller, "openImageTab", new Class<?>[] {Path.class, boolean.class}, png, true));
        ImageViewerPane pane = (ImageViewerPane) tab.getUserData();

        TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
        FxTestSupport.runOnFx(() -> tabs.getTabs().remove(tab));
        FxTestSupport.runOnFx(() -> {});

        assertTrue(
                FxTestSupport.<javafx.scene.image.ImageView>field(pane, "imageView")
                                .getImage()
                        == null,
                "the decoded image (and its GPU texture) is released on a programmatic close");
    }

    /** The smallest valid PNG: a 1x1 transparent pixel. */
    private static byte[] pngBytes() {
        return java.util.Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");
    }
}

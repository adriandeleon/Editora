package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.command.Command;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Print and Export to PDF as a real window offers them on each kind of tab, for a selection, and for the
 * Project Map. Destinations come from a fake chooser and printer jobs are {@link PrintPreviewFxTest.FakeJob}s:
 * no dialog opens and no {@code PrinterJob} is created.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrintEntryPointsFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** The palette's live enabled-predicate verdict for each of {@code ids} (as {@code PaletteContextFxTest}). */
    private static Map<String, Boolean> verdicts(MainController controller, String... ids) throws Exception {
        CommandPalette palette = FxTestSupport.field(controller, "palette");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(palette, "filter", new Class<?>[] {String.class}, ""));
        List<Command> items = FxTestSupport.field(palette, "items");
        Predicate<Command> enabled = FxTestSupport.field(palette, "enabledSnapshot");
        return FxTestSupport.callOnFx(() -> {
            Map<String, Boolean> out = new java.util.LinkedHashMap<>();
            for (String id : ids) {
                items.stream().filter(c -> c.id().equals(id)).findFirst().ifPresent(c -> out.put(id, enabled.test(c)));
            }
            return out;
        });
    }

    /** What the palette row (and the File menu item's tooltip) says about a grayed command. */
    private static String reason(MainController controller, String id) throws Exception {
        CommandPalette palette = FxTestSupport.field(controller, "palette");
        java.util.function.Function<Command, String> reason = FxTestSupport.field(palette, "disabledReason");
        return FxTestSupport.callOnFx(() -> reason.apply(Command.of(id, () -> {})));
    }

    private static FxWindowFixture open(Path dir, Path file) throws Exception {
        Path config = Files.createDirectories(dir.resolve("config-" + file.getFileName()));
        return FxWindowFixture.create(
                config, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), w -> {});
    }

    private static ExportCoordinator exports(MainController controller) {
        return FxTestSupport.field(controller, "exports");
    }

    private static Object tabContent(MainController controller) throws Exception {
        return FxTestSupport.callOnFx(() -> exports(controller).activeTabContent.get());
    }

    private static void run(MainController controller, String id) throws Exception {
        com.editora.command.CommandRegistry registry = FxTestSupport.field(controller, "registry");
        FxTestSupport.runOnFx(() -> registry.get(id).orElseThrow().run());
    }

    /** Whether {@code message} was reported in the status bar (the open of the file reports there too). */
    private static boolean reported(MainController controller, String message) throws Exception {
        FxTestSupport.drainFx();
        StatusBar bar = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.callOnFx(() -> FxTestSupport.<MessageLog>field(bar, "messageLog").entries().stream()
                .anyMatch(e -> message.equals(e.text())));
    }

    private static Path png(Path dir, String name) throws Exception {
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Path file = dir.resolve(name);
        javax.imageio.ImageIO.write(bi, "png", file.toFile());
        return file;
    }

    private static Path pdf(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(file.toFile());
        }
        return file;
    }

    private static void awaitFile(Path file) throws Exception {
        for (int i = 0; i < 300 && !Files.exists(file); i++) {
            Thread.sleep(50);
        }
        FxTestSupport.drainFx();
        assertTrue(Files.exists(file), "the export should have written " + file);
    }

    private static List<Stage> previews() {
        List<Stage> out = new ArrayList<>();
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w instanceof Stage s
                    && s.isShowing()
                    && tr("print.preview.title").equals(s.getTitle())) {
                out.add(s);
            }
        }
        return out;
    }

    /** Replaces the printer with a fake, runs {@code id}, and reports whether a Print Preview opened. */
    private static boolean printOpensAPreview(MainController controller, String id, boolean expected) throws Exception {
        ExportCoordinator exports = exports(controller);
        int[] jobs = {0};
        FxTestSupport.runOnFx(() -> {
            exports.printJobs = () -> {
                jobs[0]++;
                try {
                    return new PrintPreviewFxTest.FakeJob(PrintPreviewFxTest.letter());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            exports.printReporter = r -> {};
        });
        run(controller, id);
        boolean opened = false;
        for (int i = 0; i < (expected ? 200 : 10) && !opened; i++) {
            Thread.sleep(50);
            opened = FxTestSupport.callOnFx(() -> !previews().isEmpty());
        }
        FxTestSupport.runOnFx(() -> previews().forEach(Stage::close));
        FxTestSupport.drainFx();
        assertEquals(opened ? 1 : 0, jobs[0], "a printer job is made exactly when there is something to print");
        return opened;
    }

    /**
     * A PDF viewer and a hex viewer have no text: Print and Export to PDF are grayed there with a reason
     * that fits — they used to be lit and answer "No file open" with a file plainly open — and say the same
     * when reached by a key chord.
     */
    @Test
    void pdfAndHexTabsHaveNothingToPrintAndSaySo(@TempDir Path dir) throws Exception {
        Path binary = Files.write(dir.resolve("blob.bin"), new byte[] {0, 1, 2, 3, 0, (byte) 0xff, 0, 7, 0, 0, 9});
        record Case(Path file, Class<?> viewer) {}
        for (Case c :
                List.of(new Case(pdf(dir, "doc.pdf"), PdfViewerPane.class), new Case(binary, HexViewerPane.class))) {
            FxWindowFixture fx = open(dir, c.file());
            try {
                if (c.viewer() == HexViewerPane.class) {
                    run(fx.controller, "view.openAsHex");
                    for (int i = 0; i < 100 && !(tabContent(fx.controller) instanceof HexViewerPane); i++) {
                        Thread.sleep(50);
                    }
                }
                assertInstanceOf(c.viewer(), tabContent(fx.controller), "the test must be looking at a viewer tab");
                Map<String, Boolean> v = verdicts(
                        fx.controller,
                        "editor.print",
                        "editor.exportPdf",
                        "editor.printSelection",
                        "editor.exportSelectionPdf");
                for (Map.Entry<String, Boolean> e : v.entrySet()) {
                    assertFalse(e.getValue(), e.getKey() + " on " + c.file().getFileName());
                    assertEquals(
                            tr("palette.disabled.needsPrintableTab"), reason(fx.controller, e.getKey()), e.getKey());
                }
                assertEquals(4, v.size(), "all four commands are registered");
                Path out = dir.resolve("never-" + c.file().getFileName() + ".pdf");
                FxTestSupport.runOnFx(() -> exports(fx.controller).chooseDestination = chooser -> out.toFile());
                run(fx.controller, "editor.exportPdf");
                assertTrue(reported(fx.controller, tr("status.pdf.noText")), "status.pdf.noText");
                assertFalse(printOpensAPreview(fx.controller, "editor.print", false));
                assertTrue(reported(fx.controller, tr("status.print.noText")), "status.print.noText");
                assertFalse(Files.exists(out));
            } finally {
                fx.dispose();
            }
        }
    }

    /** An image tab has no text but a picture: it prints and exports as an image page. */
    @Test
    void anImageTabPrintsAndExportsItsPicture(@TempDir Path dir) throws Exception {
        FxWindowFixture fx = open(dir, png(dir, "photo.png"));
        try {
            ImageViewerPane pane = assertInstanceOf(ImageViewerPane.class, tabContent(fx.controller));
            pane.loadedForTest().get(30, TimeUnit.SECONDS);
            FxTestSupport.drainFx();
            Map<String, Boolean> v =
                    verdicts(fx.controller, "editor.print", "editor.exportPdf", "editor.printSelection");
            assertTrue(v.get("editor.print"), "an image can be printed");
            assertTrue(v.get("editor.exportPdf"), "an image can be exported");
            assertFalse(v.get("editor.printSelection"), "an image has no text selection");

            Path out = dir.resolve("photo-out.pdf");
            javafx.stage.FileChooser[] asked = new javafx.stage.FileChooser[1];
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setPdfPageSize("a4");
                exports(fx.controller).chooseDestination = chooser -> {
                    asked[0] = chooser;
                    return out.toFile();
                };
            });
            run(fx.controller, "editor.exportPdf");
            awaitFile(out);
            assertEquals("photo.pdf", asked[0].getInitialFileName(), "named after the image");
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(out.toFile())) {
                assertEquals(1, doc.getNumberOfPages());
                org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(0);
                assertEquals(595, Math.round(page.getMediaBox().getWidth()), "the configured PDF page size");
                assertTrue(page.getResources().getXObjectNames().iterator().hasNext(), "the picture is on the page");
            }
            assertTrue(
                    printOpensAPreview(fx.controller, "editor.print", true),
                    "Print… opens the preview for the picture");
        } finally {
            fx.dispose();
        }
    }

    /**
     * Print Selection… and Export Selection to PDF… are lit only while text is selected, and put out the
     * selected lines — whole lines — numbered as they are in the file.
     */
    @Test
    void theSelectionCommandsNeedASelectionAndKeepTheFilesLineNumbers(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("words.txt"), "alpha\nbravo\ncharlie\ndelta\necho\n");
        FxWindowFixture fx = open(dir, file);
        try {
            EditorBuffer b = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[0]));
            assertNotNull(b);
            Map<String, Boolean> none = verdicts(fx.controller, "editor.printSelection", "editor.exportSelectionPdf");
            assertFalse(none.get("editor.printSelection"), "nothing is selected yet");
            assertFalse(none.get("editor.exportSelectionPdf"));
            assertEquals(tr("palette.disabled.needsSelection"), reason(fx.controller, "editor.printSelection"));
            run(fx.controller, "editor.exportSelectionPdf");
            assertTrue(reported(fx.controller, tr("status.print.noSelection")), "status.print.noSelection");

            // From inside "charlie" to inside "delta": lines 3 and 4.
            String text = Files.readString(file);
            FxTestSupport.runOnFx(() -> b.getArea().selectRange(text.indexOf("arlie"), text.indexOf("lta")));
            Map<String, Boolean> some = verdicts(fx.controller, "editor.printSelection", "editor.exportSelectionPdf");
            assertTrue(some.get("editor.printSelection"));
            assertTrue(some.get("editor.exportSelectionPdf"));

            Path out = dir.resolve("selection.pdf");
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setPdfLineNumbers(true);
                exports(fx.controller).chooseDestination = chooser -> out.toFile();
            });
            run(fx.controller, "editor.exportSelectionPdf");
            awaitFile(out);
            String pdfText;
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(out.toFile())) {
                pdfText = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            }
            List<String> lines =
                    pdfText.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            assertEquals("3 charlie", lines.get(0), "the first exported line keeps its number in the file: " + pdfText);
            assertEquals("4 delta", lines.get(1), pdfText);
            for (String left : new String[] {"alpha", "bravo", "echo"}) {
                assertFalse(pdfText.contains(left), left + " was not selected: " + pdfText);
            }
            assertTrue(printOpensAPreview(fx.controller, "editor.printSelection", true));

            // The editor's right-click menu offers the same four, the selection pair only with a selection.
            List<String> withSelection =
                    FxTestSupport.callOnFx(() -> exports(fx.controller).editorMenuItems(b, List.of()).stream()
                            .map(javafx.scene.control.MenuItem::getText)
                            .toList());
            assertEquals(
                    List.of(
                            tr("menu.print"),
                            tr("menu.exportPdf"),
                            tr("menu.printSelection"),
                            tr("menu.exportSelectionPdf")),
                    withSelection);
            List<String> without = FxTestSupport.callOnFx(() -> {
                b.getArea().deselect();
                return exports(fx.controller).editorMenuItems(b, List.of()).stream()
                        .map(javafx.scene.control.MenuItem::getText)
                        .toList();
            });
            assertEquals(List.of(tr("menu.print"), tr("menu.exportPdf")), without);
        } finally {
            fx.dispose();
        }
    }

    /** The Project Map's Print… and PDF… buttons are commands, lit only while the map has something to show. */
    @Test
    void theProjectMapCommandsAreRegisteredAndNeedTheMap(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Files.writeString(project.resolve("a.txt"), "a");
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            Map<String, Boolean> tree = verdicts(fx.controller, "projectMap.print", "projectMap.exportPdf");
            assertEquals(2, tree.size(), "both are in the palette");
            assertFalse(tree.get("projectMap.print"), "the Project tool window shows the tree");
            assertFalse(tree.get("projectMap.exportPdf"));
            assertEquals(tr("palette.disabled.needsProjectMap"), reason(fx.controller, "projectMap.print"));
            run(fx.controller, "projectMap.exportPdf");
            assertTrue(reported(fx.controller, tr("status.projectMap.notShowing")), "status.projectMap.notShowing");

            ProjectPanel panel = FxTestSupport.field(fx.controller, "projectPanel");
            FxTestSupport.runOnFx(() -> {
                panel.setRoot(project);
                FxTestSupport.<javafx.scene.control.ToggleButton>field(panel, "mapModeButton")
                        .fire();
            });
            if (!FxTestSupport.callOnFx(() -> panel.getScene() != null)) {
                run(fx.controller, "tool.project"); // the Project tool window was closed in this layout
            }
            for (int i = 0; i < 200 && !FxTestSupport.callOnFx(panel::isMapOutputAvailable); i++) {
                Thread.sleep(50);
            }
            assertTrue(FxTestSupport.callOnFx(panel::isMapOutputAvailable), "the map is on screen with a column");
            Map<String, Boolean> map = verdicts(fx.controller, "projectMap.print", "projectMap.exportPdf");
            assertTrue(map.get("projectMap.print"), "the map is showing");
            assertTrue(map.get("projectMap.exportPdf"));

            Path out = dir.resolve("map.pdf");
            FxTestSupport.runOnFx(() -> exports(fx.controller).chooseDestination = chooser -> out.toFile());
            run(fx.controller, "projectMap.exportPdf");
            for (int i = 0; i < 300 && !Files.exists(out); i++) {
                Thread.sleep(50);
            }
            FxTestSupport.drainFx();
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            List<String> said =
                    FxTestSupport.callOnFx(() -> FxTestSupport.<MessageLog>field(bar, "messageLog").entries().stream()
                            .map(e -> e.text())
                            .toList());
            assertTrue(Files.exists(out), "the export should have written " + out + "; the status bar said " + said);
        } finally {
            fx.dispose();
        }
    }

    /**
     * While a JSON file's tree preview is on screen and shows a parse error, the two commands that put the
     * preview on a page are grayed — from what the preview last rendered, without parsing anything. In
     * editor mode nothing has been rendered, so they stay lit and the command refuses when run.
     */
    @Test
    void aPreviewShowingAParseErrorCannotBeExported(@TempDir Path dir) throws Exception {
        record Case(String name, String text, boolean exportable) {}
        for (Case c : List.of(
                new Case("broken.json", "{\"a\": [1,\n", false), new Case("fine.json", "{\"a\": [1]}\n", true))) {
            FxWindowFixture fx = open(dir, Files.writeString(dir.resolve(c.name()), c.text()));
            try {
                EditorBuffer b = FxTestSupport.callOnFx(
                        () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[0]));
                assertTrue(verdicts(fx.controller, "preview.print").get("preview.print"), "editor mode: lit");
                FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW));
                for (int i = 0; i < 100 && FxTestSupport.callOnFx(b::previewShowsError) == c.exportable(); i++) {
                    Thread.sleep(50); // the tree (or the error) is rendered off-thread
                }
                Thread.sleep(300);
                FxTestSupport.drainFx();
                Map<String, Boolean> v = verdicts(fx.controller, "preview.print", "preview.exportPdf", "preview.copy");
                assertEquals(c.exportable(), v.get("preview.print"), c.name());
                assertEquals(c.exportable(), v.get("preview.exportPdf"), c.name());
                assertTrue(v.get("preview.copy"), "the rest of the family only needs a preview");
                if (!c.exportable()) {
                    assertEquals(tr("palette.disabled.needsExportablePreview"), reason(fx.controller, "preview.print"));
                }
            } finally {
                fx.dispose();
            }
        }
    }
}

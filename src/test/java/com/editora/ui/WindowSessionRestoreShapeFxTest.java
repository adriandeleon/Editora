package com.editora.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a restored session puts back besides the text files: the split layout and which group each file was
 * in, pinned tabs, image and PDF viewers — and what the command-line start-up forms do when there is no
 * session to restore around.
 */
@Tag("fx")
class WindowSessionRestoreShapeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private static String entry(Path path, boolean pinned, int group) {
        return "{\"path\":\"" + json(path) + "\",\"pinned\":" + pinned + ",\"group\":" + group + "}";
    }

    private static Path tabPath(WindowRig w, Tab tab) {
        return (Path) FxTestSupport.call(w.controller, "tabPath", new Class<?>[] {Tab.class}, tab);
    }

    @Test
    void aSplitSessionComesBackWithItsGroupsPinsAndViewers(@TempDir Path dir) throws Exception {
        Path cfg = Files.createDirectories(dir.resolve("config"));
        Path work = Files.createDirectories(dir.resolve("work"));
        Path a = Files.writeString(work.resolve("a.txt"), "alpha\n");
        Path b = Files.writeString(work.resolve("b.txt"), "beta\n");
        Path c = Files.writeString(work.resolve("c.txt"), "gamma\n");
        Path picture = work.resolve("picture.png");
        javax.imageio.ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", picture.toFile());
        Path doc = work.resolve("doc.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(doc.toFile());
        }
        Path deleted = work.resolve("deleted-since.txt");
        Files.writeString(
                cfg.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":["
                        + String.join(
                                ",",
                                entry(a, true, 0),
                                entry(picture, true, 0),
                                entry(c, false, 0),
                                entry(deleted, false, 1),
                                entry(doc, true, 1),
                                entry(b, false, 1))
                        + "],\"activeFile\":\"" + json(b) + "\","
                        + "\"editorLayout\":{\"orientation\":\"HORIZONTAL\",\"children\":"
                        + "[{\"selected\":2},{\"selected\":0}]}}");

        try (WindowRig w = new WindowRig(FxWindowFixture.create(cfg, false, false, false, List.of(), ctl -> {}))) {
            w.await("the session's files to load", () -> {
                for (Path file : List.of(a, b, c)) {
                    Tab tab = w.tabFor(file);
                    if (tab == null || !(tab.getUserData() instanceof EditorBuffer buffer) || buffer.isLoading()) {
                        return false;
                    }
                }
                return true;
            });
            w.async.awaitFx();
            FxTestSupport.runOnFx(() -> {
                EditorArea area = w.field("editorArea");
                List<Tab> tabs = area.tabs();
                assertEquals(
                        List.of(a, picture, c, doc, b),
                        tabs.stream().map(t -> tabPath(w, t)).toList(),
                        "tab order, without the file that no longer exists");
                assertEquals(2, area.groupCount(), "the split is back");
                assertEquals(
                        List.of(0, 0, 0, 1, 1),
                        tabs.stream().map(area::groupIndexOf).toList(),
                        "each file in the group it was saved in");

                Set<Tab> pinned = w.controller.pinned;
                assertTrue(pinned.contains(tabs.get(0)), "a pinned text file");
                assertTrue(pinned.contains(tabs.get(1)), "a pinned image");
                assertTrue(pinned.contains(tabs.get(3)), "a pinned PDF");
                assertFalse(pinned.contains(tabs.get(2)) || pinned.contains(tabs.get(4)));

                assertTrue(tabs.get(1).getUserData() instanceof ImageViewerPane, "an image restores as an image");
                assertTrue(tabs.get(3).getUserData() instanceof PdfViewerPane, "a PDF restores as a PDF");
                assertEquals("alpha\n", ((EditorBuffer) tabs.get(0).getUserData()).getContent());
                assertEquals("gamma\n", ((EditorBuffer) tabs.get(2).getUserData()).getContent());

                assertEquals(b, w.active().getPath(), "the session's active file is the active tab");
                assertEquals(1, area.groupIndexOf(area.selectedTab()));
            });
        }
    }

    @Test
    void newFileStartsABlankOrNamedBufferInsteadOfTheWelcomePage() throws Exception {
        try (WindowRig w = new WindowRig()) {
            WindowSessionCoordinator sessions = w.field("sessions");

            FxTestSupport.runOnFx(() -> sessions.applyStartupTargets(null, ""));
            assertEquals(tr("status.newBuffer"), w.status());
            FxTestSupport.runOnFx(() -> {
                assertNull(w.active().getPath());
                assertNull(w.active().getDisplayName(), "--new-file with no name is a plain untitled buffer");
            });

            FxTestSupport.runOnFx(() -> sessions.applyStartupTargets(List.of(), "notes.md"));
            assertEquals(tr("status.newFile", "notes.md"), w.status());
            FxTestSupport.runOnFx(() -> {
                assertEquals("notes.md", w.active().getDisplayName(), "the name is kept for the first save");
                assertNull(w.active().getPath(), "nothing is on disk yet");
            });
        }
    }

    @Test
    void aDiffStartupWithAnUnreadableSideOrMismatchedKindsSaysSoAndFallsBackToWelcome(@TempDir Path dir)
            throws Exception {
        Path file = Files.writeString(dir.resolve("left.txt"), "text\n");
        Path folder = Files.createDirectory(dir.resolve("folder"));
        Path missing = dir.resolve("missing.txt");

        assertTrue(WindowSessionCoordinator.readableDiffPath(file));
        assertTrue(WindowSessionCoordinator.readableDiffPath(folder));
        assertFalse(WindowSessionCoordinator.readableDiffPath(missing));
        assertFalse(WindowSessionCoordinator.readableDiffPath(null));

        Path cfg1 = Files.createDirectories(dir.resolve("config1"));
        try (WindowRig w = new WindowRig(FxWindowFixture.createDiff(cfg1, file, missing, ctl -> {}))) {
            w.await("the refusal", () -> messages(w).contains(tr("status.diff.unreadable", missing)));
            EditorArea area = w.field("editorArea");
            w.await("the Welcome page", () -> area.size() == 1);
            assertNull(FxTestSupport.callOnFx(w::active), "no comparison was opened");
        }

        Path cfg2 = Files.createDirectories(dir.resolve("config2"));
        try (WindowRig w = new WindowRig(FxWindowFixture.createDiff(cfg2, file, folder, ctl -> {}))) {
            w.await("the refusal", () -> messages(w).contains(tr("status.diff.pathTypeMismatch")));
        }
    }

    /** Every status message of the window so far. FX thread. */
    private static List<String> messages(WindowRig w) {
        StatusBar status = w.field("statusBar");
        return FxTestSupport.<MessageLog>field(status, "messageLog").entries().stream()
                .map(e -> e.text())
                .toList();
    }
}

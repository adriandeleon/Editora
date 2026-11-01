package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The canvas group's pure parts: the whole-folder budget (position 15), the SVG writer and the PDF's text (16). */
class ProjectMapCanvasExploreTest {

    @TempDir
    Path temp;

    private static ProjectMapModel.Request request(Path root, Set<Path> open, boolean whole) {
        return new ProjectMapModel.Request(
                root,
                open,
                false,
                Map.of(),
                Map.of(),
                List.of(),
                ProjectMapModel.Labels.PLAIN,
                () -> false,
                Map.of(),
                false,
                List.of(),
                whole);
    }

    private static Path folder(Path parent, String name, int files) throws Exception {
        Path folder = Files.createDirectory(parent.resolve(name));
        for (int i = 0; i < files; i++) {
            Files.writeString(folder.resolve(String.format("f%05d.txt", i)), "");
        }
        return folder;
    }

    private static long rowsIn(ProjectMapModel.Snapshot snapshot, Path folder) {
        return snapshot.entries().stream()
                .filter(entry -> folder.equals(entry.parent()) && !entry.isPlaceholder())
                .count();
    }

    @Test
    void wholeFoldersAreListedWholeAndChargedOnlyTheRowsNearTheViewport() throws Exception {
        Path root = temp.toRealPath();
        Path one = folder(root, "one", 2_000);
        Path two = folder(root, "two", 2_000);
        Path three = folder(root, "three", 2_000);
        ProjectMapModel.Snapshot whole = ProjectMapModel.load(request(root, Set.of(root, one, two, three), true));
        assertEquals(2_000, rowsIn(whole, one));
        assertEquals(2_000, rowsIn(whole, two));
        assertEquals(2_000, rowsIn(whole, three));
        assertFalse(whole.capped(), "three long folders cost 900 of the 1,200 rows");
        assertTrue(whole.entries().stream().noneMatch(ProjectMapModel.Entry::isMore));

        ProjectMapModel.Snapshot cut = ProjectMapModel.load(request(root, Set.of(root, one, two, three), false));
        assertEquals(ProjectMapModel.DIRECTORY_CHUNK, rowsIn(cut, one), "a remote root keeps its row limit");
        assertTrue(cut.entries().stream().anyMatch(ProjectMapModel.Entry::isMore));
    }

    @Test
    void aWholeFolderTheBudgetCutShortKeepsItsFirstRowsWithNoGap() throws Exception {
        Path root = temp.toRealPath();
        List<Path> folders = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            folders.add(folder(root, "d" + i, 400));
        }
        Set<Path> open = new java.util.HashSet<>(folders);
        open.add(root);
        ProjectMapModel.Snapshot snapshot = ProjectMapModel.load(request(root, open, true));
        assertTrue(snapshot.capped(), "five folders would cost 1,500");
        long total = 0;
        for (Path folder : folders) {
            long rows = rowsIn(snapshot, folder);
            total += Math.min(rows, ProjectMapModel.NEAR_VIEWPORT_ROWS);
            if (rows > 0 && rows < 400) {
                // The rows it has are its first ones, and "+N more" stands for the rest.
                assertTrue(rows <= ProjectMapModel.NEAR_VIEWPORT_ROWS, folder + " " + rows);
                assertTrue(
                        snapshot.entries().stream().anyMatch(entry -> entry.isMore() && folder.equals(entry.parent())));
                assertTrue(snapshot.entries().stream()
                        .anyMatch(entry -> entry.path().equals(folder.resolve("f00000.txt"))));
            }
        }
        assertTrue(total <= ProjectMapModel.MAX_VISIBLE_ITEMS, "charged " + total);
    }

    @Test
    void aWholeFolderStopsAtFiftyThousandRows() {
        ProjectMapModel.Request whole = request(Path.of("/r"), Set.of(), true);
        assertEquals(ProjectMapModel.WHOLE_FOLDER_LIMIT, whole.limitOf(Path.of("/r/a")));
        ProjectMapModel.Request cut = request(Path.of("/r"), Set.of(), false);
        assertEquals(ProjectMapModel.DIRECTORY_CHUNK, cut.limitOf(Path.of("/r/a")));
    }

    @Test
    void theSvgEscapesNamesAndWritesPlainCoordinates() {
        assertEquals("a &amp; &lt;b&gt; &quot;c&quot; &apos;d&apos;", ProjectMapSvg.escape("a & <b> \"c\" 'd'"));
        assertEquals("x", ProjectMapSvg.escape("x\u0001"), "characters XML 1.0 forbids are dropped");
        assertEquals("12", ProjectMapSvg.n(12.0));
        assertEquals("12.35", ProjectMapSvg.n(12.346));
        assertEquals("-0.5", ProjectMapSvg.n(-0.5).replace("-0.50", "-0.5"));
        ProjectMapSvg.Palette palette = new ProjectMapSvg.Palette(
                "#fff", "#eee", "#ddd", "#ede", "#fff", "#ccc", "#111", "#666", "#06c", "#06c", "#666", "#85d");
        String svg = ProjectMapSvg.write(
                new double[] {0, 0, 200, 100},
                List.of(new ProjectMapSvg.Column("src", "2", false, 10, 10, 180, 80)),
                List.of(new ProjectMapSvg.Row(
                        "Main.java", "Main.java", false, false, false, true, false, 20, 30, 160, 32, 51, 50.5)),
                List.of(new ProjectMapSvg.Link(0, 10, 20, 46, false)),
                palette,
                "Project Map of x");
        assertTrue(svg.startsWith("<?xml"));
        assertTrue(svg.contains(">Main.java</text>"));
        assertTrue(svg.contains("<title>Main.java</title>"));
        assertTrue(svg.contains("viewBox=\"0 0 200 100\""));
        assertTrue(svg.contains("<path d=\"M0 10 C10 10 10 46 20 46\"/>"));
    }

    @Test
    void thePdfPagesCarryInvisibleTextAViewerFinds() throws Exception {
        java.awt.image.BufferedImage page =
                new java.awt.image.BufferedImage(400, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Path pdf = temp.resolve("map.pdf");
        com.editora.pdf.ImagePdfWriter.writePages(
                List.of(page),
                0.5,
                "A4",
                false,
                List.of(List.of(
                        new com.editora.pdf.ImagePdfWriter.TextBox("kubelet.go", 20, 40, 120, 24),
                        new com.editora.pdf.ImagePdfWriter.TextBox("Größe ünd Ωmega", 20, 80, 160, 24))),
                pdf);
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            assertEquals(1, document.getNumberOfPages());
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertTrue(text.contains("kubelet.go"), text);
            assertTrue(text.contains("Größe ünd Ωmega"), text);
        }
        // Without text the pages are what they always were.
        Path plain = temp.resolve("plain.pdf");
        com.editora.pdf.ImagePdfWriter.writePages(List.of(page), 0.5, "A4", false, plain);
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(plain.toFile())) {
            assertEquals(
                    "",
                    new org.apache.pdfbox.text.PDFTextStripper()
                            .getText(document)
                            .strip());
        }
    }
}

package com.editora.ui;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.editora.pdf.ImagePdfWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure (no toolkit) coverage of the Project Map's output page plan, its PDF writer and preview decoding. */
class ProjectMapOutputPlanTest {

    private static final double PAGE_WIDTH = 540; // US Letter less the half-inch margins, in points
    private static final double PAGE_HEIGHT = 720;

    @TempDir
    Path temp;

    @Test
    void aMapThatFitsThePageIsOnePageAtFullSize() {
        ProjectMapOutputPlan.Box content = new ProjectMapOutputPlan.Box(0, 0, 400, 500);
        ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(content, List.of(content), PAGE_WIDTH, PAGE_HEIGHT);

        assertEquals(1, plan.pages().size());
        assertFalse(plan.tiled());
        assertFalse(plan.scaledDown(), "nothing was shrunk, so there is nothing to tell the user");
        assertFalse(plan.detailReduced());
        assertEquals(1.0, plan.paperScale(), 1e-9);
        assertEquals(ProjectMapOutputPlan.PREFERRED_RENDER_SCALE, plan.renderScale(), 1e-9);
    }

    @Test
    void aSlightlyLargerMapIsShrunkOntoOnePageWhileLabelsStayLegible() {
        ProjectMapOutputPlan.Box content = new ProjectMapOutputPlan.Box(0, 0, 720, 600);
        ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(content, List.of(content), PAGE_WIDTH, PAGE_HEIGHT);

        assertEquals(1, plan.pages().size());
        assertTrue(plan.scaledDown());
        assertEquals(0.75, plan.paperScale(), 1e-9);
        assertTrue(plan.paperScale() * ProjectMapOutputPlan.LABEL_SIZE >= ProjectMapOutputPlan.MIN_LABEL_POINTS);
    }

    @Test
    void aLargeMapIsTiledAtTheMinimumLegibleScaleInsteadOfShrinkingWithoutBound() {
        // A 1,200-row column: 1,200 × 41 px tall. Fitting it on one page would print 12 px labels at 2 pt.
        ProjectMapOutputPlan.Box content = new ProjectMapOutputPlan.Box(0, 0, 300, 49_200);
        List<ProjectMapOutputPlan.Box> rows = new ArrayList<>();
        for (int row = 0; row < 1_200; row++) {
            rows.add(new ProjectMapOutputPlan.Box(10, row * 41.0, 280, 32));
        }
        ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(content, rows, PAGE_WIDTH, PAGE_HEIGHT);

        assertTrue(plan.tiled());
        assertEquals(ProjectMapOutputPlan.MIN_PAPER_SCALE, plan.paperScale(), 1e-9);
        assertEquals(1, plan.columns());
        assertTrue(plan.rows() >= 40, () -> "49,200 px at 7 pt labels needs about forty pages: " + plan.rows());
        assertEquals(plan.rows(), plan.pages().size());
        for (ProjectMapOutputPlan.Cell page : plan.pages()) {
            assertTrue(page.height() * plan.paperScale() <= PAGE_HEIGHT + 0.01, "a page never overflows the paper");
            double cut = page.y() + page.height();
            assertTrue(
                    rows.stream().noneMatch(row -> row.y() < cut && cut < row.maxY()),
                    () -> "a page must end between two rows, not through one: " + cut);
        }
    }

    @Test
    void pageCutsFallInTheGapBetweenColumnsAndEmptyPagesAreLeftOut() {
        // Two column cards far apart, with nothing between them or under the first.
        ProjectMapOutputPlan.Box left = new ProjectMapOutputPlan.Box(0, 0, 700, 400);
        ProjectMapOutputPlan.Box right = new ProjectMapOutputPlan.Box(800, 1_600, 700, 400);
        ProjectMapOutputPlan.Box content = new ProjectMapOutputPlan.Box(0, 0, 1_500, 2_000);
        ProjectMapOutputPlan.Plan plan =
                ProjectMapOutputPlan.plan(content, List.of(left, right), PAGE_WIDTH, PAGE_HEIGHT);

        assertTrue(plan.tiled());
        assertEquals(2, plan.columns());
        double firstCut = plan.pages().getFirst().x() + plan.pages().getFirst().width();
        assertTrue(firstCut > left.maxX() && firstCut < right.x(), () -> "cut through a card at " + firstCut);
        assertTrue(plan.pages().size() < plan.columns() * plan.rows(), "pages with nothing on them are not printed");
        assertTrue(plan.pages().stream().anyMatch(page -> page.column() == 0 && page.row() == 0));
        assertTrue(plan.pages().stream().noneMatch(page -> page.column() == 1 && page.row() == 0));
    }

    @Test
    void aMapPastThePixelBudgetSaysSoInsteadOfShrinkingSilently() {
        ProjectMapOutputPlan.Box content = new ProjectMapOutputPlan.Box(0, 0, 9_000, 9_000);
        ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(content, List.of(content), PAGE_WIDTH, PAGE_HEIGHT);

        assertTrue(plan.tiled());
        assertTrue(plan.detailReduced(), "81 Mpx of map cannot be rendered legibly inside a 12 Mpx budget");
        double pixels = plan.pages().stream()
                .mapToDouble(page -> page.width() * page.height() * plan.renderScale() * plan.renderScale())
                .sum();
        assertTrue(pixels <= ProjectMapOutputPlan.MAX_TOTAL_PIXELS * 1.001, "the budget is still a hard limit");
    }

    @Test
    void cutsKeepToThePageExtentWhenNoGapIsInReach() {
        double[] cuts = ProjectMapOutputPlan.cuts(0, 2_500, 1_000, List.of(new double[] {-10, 3_000}));
        assertArrayEquals(new double[] {0, 1_000, 2_000, 2_500}, cuts, 1e-9);
    }

    @Test
    void pagedPdfHasOneLandscapePagePerImage() throws Exception {
        Path out = temp.resolve("map.pdf");
        BufferedImage page = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB);
        ImagePdfWriter.writePages(List.of(page, page, page), 0.5, "letter", true, out);

        try (PDDocument document = Loader.loadPDF(out.toFile())) {
            assertEquals(3, document.getNumberOfPages(), "the caller's page cuts are kept, not re-sliced");
            PDRectangle box = document.getPage(0).getMediaBox();
            assertTrue(box.getWidth() > box.getHeight(), "a map wider than tall goes on a landscape page");
        }
        double[] printable = ImagePdfWriter.printableSize("letter", true);
        assertEquals(720, printable[0], 1e-6);
        assertEquals(540, printable[1], 1e-6);
    }

    @Test
    void previewDecodesLikeTheEditorInsteadOfAsLenientUtf8() {
        String text = "café naïve über";
        assertEquals(
                text,
                ProjectMapPreview.decode(text.getBytes(StandardCharsets.ISO_8859_1), false, null),
                "bytes that are not UTF-8 are read with a single-byte charset, never as U+FFFD");
        assertEquals(
                "¤",
                ProjectMapPreview.decode(new byte[] {(byte) 0xA4}, false, "latin1"),
                "the file's .editorconfig charset is used");
        assertEquals(
                "one\ntwo\nthree",
                ProjectMapPreview.decode("one\r\ntwo\rthree".getBytes(StandardCharsets.UTF_8), false, null),
                "line endings take the editor's form, which the highlighter's offsets assume");
    }

    @Test
    void aReadCapNeverSplitsACharacter() {
        byte[] utf8 = "ab€d".getBytes(StandardCharsets.UTF_8); // € is three bytes
        for (int cut = 3; cut <= 4; cut++) {
            byte[] capped = Arrays.copyOf(utf8, cut);
            assertEquals(
                    "ab",
                    ProjectMapPreview.decode(capped, true, null),
                    "a sequence cut by the byte cap is dropped, not decoded as mojibake");
        }
        assertEquals("ab€", ProjectMapPreview.decode(Arrays.copyOf(utf8, 5), true, null));

        byte[] utf16 = "﻿a😀".getBytes(StandardCharsets.UTF_16LE); // BOM, a, a surrogate pair
        assertEquals("a", ProjectMapPreview.decode(Arrays.copyOf(utf16, 6), true, null));
        assertEquals("a", ProjectMapPreview.decode(Arrays.copyOf(utf16, 5), true, null));

        String pair = "x".repeat(ProjectMapPreview.MAX_PREVIEW_CHARS - 1) + "😀";
        String capped = ProjectMapPreview.capText(pair, false);
        assertEquals(ProjectMapPreview.MAX_PREVIEW_CHARS - 1, capped.length());
        assertFalse(Character.isHighSurrogate(capped.charAt(capped.length() - 1)));
        assertEquals("ok", ProjectMapPreview.capText("ok\uD83D", true), "a cut made by the caller is repaired too");
    }
}

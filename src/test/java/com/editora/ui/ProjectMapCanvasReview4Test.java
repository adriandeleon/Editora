package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map review 4, canvas group, the pure parts: output columns cut to a chunk and "+N more" (D1), the page cap (D1)
 * and the total cap on whole folders' free rows (D3).
 */
class ProjectMapCanvasReview4Test {

    @TempDir
    Path temp;

    private static ProjectMapModel.Entry file(Path folder, int depth, String name) {
        return new ProjectMapModel.Entry(folder.resolve(name), folder, depth, false);
    }

    @Test
    void outputCutsALongFolderToItsFirstChunkKeepsTheRowsColumnsHangFromAndCountsTheRest() {
        Path root = Path.of("/r").toAbsolutePath(); // C:\r on Windows, as the model makes it
        Path big = root.resolve("big");
        List<ProjectMapModel.Entry> entries = new ArrayList<>();
        entries.add(new ProjectMapModel.Entry(root, null, 0, true));
        entries.add(new ProjectMapModel.Entry(big, root, 1, true));
        entries.add(file(root, 1, "README.md"));
        for (int i = 0; i < 1_000; i++) {
            entries.add(file(big, 2, String.format("f%04d.txt", i)));
        }
        // A file past the chunk with its structure open: its row stays, so the structure column keeps its place.
        Path open = big.resolve("f0700.txt");
        ProjectMapModel.Symbol symbol = new ProjectMapModel.Symbol(
                open, "run()", "method", 1, 1, 1, new com.editora.text.FoldRegions.Region(1, 1), false);
        entries.add(ProjectMapModel.Entry.symbol(
                open.resolve(ProjectMapStructure.SEGMENT_PREFIX + "run~0"), open, 3, symbol));
        List<ProjectMapModel.Column> columns = ProjectMapModel.columnsById(entries, Map.of(), Map.of());

        List<ProjectMapModel.Column> output =
                ProjectMapModel.canvasOutputColumns(columns, ProjectMapModel.Labels.PLAIN);
        assertEquals(columns.size(), output.size());
        ProjectMapModel.Column long_ = output.stream()
                .filter(column -> big.equals(column.parent()))
                .findFirst()
                .orElseThrow();
        assertEquals(ProjectMapModel.DIRECTORY_CHUNK + 1, long_.shownEntries(), "300 rows and the open file");
        assertTrue(long_.entries().stream().anyMatch(entry -> entry.path().equals(open)));
        ProjectMapModel.Entry more = long_.entries().getLast();
        assertTrue(more.isMore());
        assertEquals(
                1_000 - ProjectMapModel.DIRECTORY_CHUNK - 1, more.placeholder().remaining());
        assertEquals(1_000, long_.totalEntries(), "the header still counts the whole folder");
        assertEquals("301/1000", long_.countLabel());
        // Short columns are the same objects.
        for (int i = 0; i < columns.size(); i++) {
            if (!big.equals(columns.get(i).parent())) {
                assertSame(columns.get(i), output.get(i));
            }
        }
    }

    @Test
    void anExistingPlusNMoreIsFoldedIntoTheOneTheOutputAdds() {
        Path root = Path.of("/r").toAbsolutePath(); // C:\r on Windows, as the model makes it
        List<ProjectMapModel.Entry> entries = new ArrayList<>();
        entries.add(new ProjectMapModel.Entry(root, null, 0, true));
        for (int i = 0; i < 500; i++) {
            entries.add(file(root, 1, String.format("f%04d.txt", i)));
        }
        entries.add(ProjectMapModel.Entry.placeholder(
                root, 1, new ProjectMapModel.Placeholder(ProjectMapModel.PlaceholderKind.MORE, 2_000, "+2000 more")));
        List<ProjectMapModel.Column> output = ProjectMapModel.canvasOutputColumns(
                ProjectMapModel.columnsById(entries, Map.of(), Map.of()), ProjectMapModel.Labels.PLAIN);
        ProjectMapModel.Column first = output.stream()
                .filter(column -> root.equals(column.parent()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                1,
                first.entries().stream().filter(ProjectMapModel.Entry::isMore).count());
        assertEquals(2_200, first.entries().getLast().placeholder().remaining());
        assertEquals("+2200 more…", first.entries().getLast().name());
    }

    @Test
    void aMapThatNeedsMoreThanFortyPagesIsShrunkToFortyAndSaysSo() {
        // A column of 30,000 rows: hundreds of pages at readable labels.
        ProjectMapOutputPlan.Box content = new ProjectMapOutputPlan.Box(0, 0, 400, 30_000 * 41.0);
        List<ProjectMapOutputPlan.Box> obstacles = new ArrayList<>();
        obstacles.add(content);
        for (int i = 0; i < 2_000; i++) {
            obstacles.add(new ProjectMapOutputPlan.Box(10, 40 + i * 41.0 * 15, 380, 32));
        }
        long started = System.nanoTime();
        ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(content, obstacles, 540, 720);
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(
                plan.pages().size() <= ProjectMapOutputPlan.MAX_PAGES,
                "pages: " + plan.pages().size());
        assertTrue(plan.pages().size() > ProjectMapOutputPlan.MAX_PAGES / 2, "the cap is used, not overshot");
        assertTrue(plan.pageCapped());
        assertTrue(millis < 5_000, "planned in " + millis + " ms");
        assertEquals(
                tr("status.projectMap.outputCapped", plan.pages().size()),
                ExportCoordinator.projectMapOutputNote(plan));

        // A wide and tall map with nothing to cut along: the even grid still stops at the cap.
        ProjectMapOutputPlan.Plan grid =
                ProjectMapOutputPlan.plan(new ProjectMapOutputPlan.Box(0, 0, 90_000, 90_000), List.of(), 540, 720);
        assertTrue(
                grid.pages().size() <= ProjectMapOutputPlan.MAX_PAGES,
                "pages: " + grid.pages().size());
        assertTrue(grid.pageCapped());

        // What fits stays as it was.
        ProjectMapOutputPlan.Plan small =
                ProjectMapOutputPlan.plan(new ProjectMapOutputPlan.Box(0, 0, 400, 300), List.of(), 540, 720);
        assertEquals(1, small.pages().size());
        assertFalse(small.pageCapped());
    }

    @Test
    void wholeFoldersListAtMostSixtyThousandFreeRowsBetweenThem() throws Exception {
        Path root = temp.toRealPath();
        Path fifty = Files.createDirectory(root.resolve("fifty"));
        for (int i = 0; i < 50_000; i++) {
            Files.createFile(fifty.resolve(String.format("g%05d.txt", i)));
        }
        Path twelve = Files.createDirectory(root.resolve("twelve"));
        for (int i = 0; i < 12_000; i++) {
            Files.createFile(twelve.resolve(String.format("h%05d.txt", i)));
        }
        ProjectMapModel.Snapshot snapshot = ProjectMapModel.load(new ProjectMapModel.Request(
                root,
                Set.of(root, fifty, twelve),
                false,
                Map.of(),
                Map.of(),
                List.of(),
                ProjectMapModel.Labels.PLAIN,
                () -> false,
                Map.of(),
                false,
                List.of(),
                true));
        long inFifty = snapshot.entries().stream()
                .filter(entry -> fifty.equals(entry.parent()) && !entry.isPlaceholder())
                .count();
        long inTwelve = snapshot.entries().stream()
                .filter(entry -> twelve.equals(entry.parent()) && !entry.isPlaceholder())
                .count();
        assertEquals(50_000, inFifty, "the first long folder is whole");
        long real = snapshot.entries().stream()
                .filter(entry -> !entry.isPlaceholder())
                .count();
        assertTrue(
                real <= ProjectMapModel.MAX_FREE_ROWS + ProjectMapModel.MAX_VISIBLE_ITEMS,
                "free rows plus the overall limit: " + real);
        assertTrue(inTwelve < 12_000, "the second stops where the free rows ran out: " + inTwelve);
        assertTrue(snapshot.entries().stream().anyMatch(entry -> entry.isMore() && twelve.equals(entry.parent())));
        assertTrue(snapshot.capped());
    }
}

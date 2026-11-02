package com.editora.ui;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MapLoader} driven step by step: both executors are queues the test runs by hand, so a superseded load, a
 * load landing after a newer request, and a rename while a load lists happen in a known order (Map refactor stage 2).
 */
class MapLoaderTest {

    @TempDir
    Path temp;

    /** Runs nothing until asked: {@link #runAll} runs what is queued, in order. */
    private static final class StepExecutor implements Executor {
        final Deque<Runnable> queued = new ArrayDeque<>();
        boolean shutDown;

        @Override
        public void execute(Runnable command) {
            if (shutDown) {
                throw new RejectedExecutionException("shut down");
            }
            queued.add(command);
        }

        int runAll() {
            int ran = 0;
            while (!queued.isEmpty()) {
                queued.poll().run();
                ran++;
            }
            return ran;
        }
    }

    /** The view's side: what the loader asked and what landed. */
    private static final class FakeHost implements MapLoader.Host {
        boolean active = true;
        boolean searching;
        boolean compactChains;
        int started;
        int finished;
        int cleared;
        final List<ProjectMapModel.Snapshot> landed = new ArrayList<>();
        final List<Boolean> expansionChanges = new ArrayList<>();

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public boolean searching() {
            return searching;
        }

        @Override
        public boolean showHidden() {
            return false;
        }

        @Override
        public Map<Path, Boolean> hiddenOverrides() {
            return Map.of();
        }

        @Override
        public Map<Path, String> columnFilterQueries() {
            return Map.of();
        }

        @Override
        public boolean compactChains() {
            return compactChains;
        }

        @Override
        public @Nullable Path selectedColumn() {
            return null;
        }

        @Override
        public void loadStarted() {
            started++;
        }

        @Override
        public void loadFinished() {
            finished++;
        }

        @Override
        public void cleared() {
            cleared++;
        }

        @Override
        public void loaded(
                Path requestedRoot, ProjectMapModel.Snapshot loaded, Set<Path> loadedPaths, boolean expansionChanged) {
            landed.add(loaded);
            expansionChanges.add(expansionChanged);
        }
    }

    private final FakeHost host = new FakeHost();
    private final StepExecutor background = new StepExecutor();
    private final StepExecutor ui = new StepExecutor();

    private MapLoader loader() {
        return new MapLoader(host, background, ui, new ListingService(0, null));
    }

    private Path root() throws IOException {
        return Files.createDirectory(temp.resolve("project")).toRealPath();
    }

    private static Set<Path> paths(ProjectMapModel.Snapshot snapshot) {
        Set<Path> paths = new HashSet<>();
        snapshot.entries().forEach(entry -> paths.add(entry.path()));
        return paths;
    }

    @Test
    void closeOthersKeepsTheWayToTheRowAndReportsTheTopOfEachClosedBranch() throws IOException {
        Path root = root();
        Path a = root.resolve("a");
        Path ax = a.resolve("x");
        Path b = root.resolve("b");
        Path by = b.resolve("y");
        Path byz = by.resolve("z");
        MapLoader loader = loader();
        loader.setRoot(root);
        loader.expanded.addAll(Set.of(a, ax, b, by, byz));

        // Close Other Branches on folder b keeps its own column; everything under it is off the way.
        assertEquals(List.of(a, by), loader.canvasCloseOthers(b, true), "the topmost folder of each closed branch");
        assertEquals(Set.of(root, b), loader.expandedDirectories());

        // Collapse All on a file keeps its folders and the first column.
        loader.expanded.addAll(Set.of(a, ax, by, byz));
        assertEquals(List.of(a), loader.canvasCloseOthers(byz.resolve("f.txt"), false));
        assertEquals(Set.of(root, b, by, byz), loader.expandedDirectories());
        // Collapse All on a folder row closes the folder's own column too; on nothing, all but the first.
        assertEquals(List.of(byz), loader.canvasCloseOthers(byz, false));
        assertEquals(List.of(b), loader.canvasCloseOthers(null, false));
        assertEquals(Set.of(root), loader.expandedDirectories());
        assertEquals(List.of(), loader.canvasCloseOthers(null, false), "nothing more to close");

        // During a search the search's own view closes, and the manual set it hands back is left alone.
        loader.expanded.addAll(Set.of(a, b));
        host.searching = true;
        loader.setSearchMatches(
                List.of(ax.resolve("m.txt"), by.resolve("n.txt")),
                List.of(ax.resolve("m.txt"), by.resolve("n.txt")),
                null,
                true);
        loader.canvasCloseOthers(ax.resolve("m.txt"), false);
        assertEquals(Set.of(root, a, ax), loader.expandedDirectories());
        host.searching = false;
        assertEquals(Set.of(root, a, b), loader.expandedDirectories(), "the manual set comes back after the search");
    }

    @Test
    void loadsQueuedBehindANewerOneAreSkippedAndOnlyTheNewestLands() throws IOException {
        Path root = root();
        Files.writeString(root.resolve("a.txt"), "a");
        MapLoader loader = loader();
        loader.setRoot(root);
        for (int click = 0; click < 10; click++) {
            loader.reload();
        }
        assertEquals(10, host.started);
        assertEquals(10, background.runAll(), "every request was queued");
        assertEquals(1, loader.loadsStartedForTest.get(), "ten requests, one listing");
        assertEquals(1, ui.runAll());
        assertEquals(1, host.landed.size());
        assertTrue(paths(loader.snapshot()).contains(root.resolve("a.txt")));
        assertEquals(1, host.finished);
    }

    @Test
    void aLoadThatFinishesAfterANewerRequestIsDroppedWhenItLands() throws IOException {
        Path root = root();
        Path folder = Files.createDirectory(root.resolve("src"));
        Files.writeString(folder.resolve("Old.java"), "class Old {}");
        MapLoader loader = loader();
        loader.setRoot(root);
        loader.reload();
        background.runAll(); // listed: its answer is on its way to the UI thread
        loader.toggle(folder);
        loader.reload(); // a newer request before the first one landed
        ui.runAll();
        assertTrue(host.landed.isEmpty(), "the older answer does not land over the newer request");
        background.runAll();
        ui.runAll();
        assertEquals(1, host.landed.size());
        assertTrue(paths(loader.snapshot()).contains(folder.resolve("Old.java")), "the newer request's rows");
    }

    @Test
    void aRenameWhileALoadIsListingKeepsTheRenamedFolderOpenAndListsTheNewTree() throws IOException {
        Path root = root();
        Path docs = Files.createDirectory(root.resolve("docs"));
        Files.writeString(docs.resolve("page.md"), "# Page");
        Path moved = root.resolve("docs-moved");
        MapLoader loader = loader();
        loader.setRoot(root);
        loader.toggle(docs);
        loader.reload();
        background.runAll(); // the old tree is listed; its snapshot waits on the UI queue
        Files.move(docs, moved);
        loader.pathRenamed(docs, moved);
        assertTrue(loader.expanded.contains(moved) && !loader.expanded.contains(docs), "remapped at once");
        ui.runAll(); // the stale snapshot (no docs-moved in it) would have pruned the folder
        assertTrue(host.landed.isEmpty());
        assertTrue(loader.expanded.contains(moved), "the stale load did not close the moved folder");
        background.runAll();
        ui.runAll();
        assertEquals(1, host.landed.size());
        assertTrue(paths(loader.snapshot()).contains(moved.resolve("page.md")), "the new tree is listed");
        assertEquals(List.of(false), host.expansionChanges, "nothing was pruned");
    }

    @Test
    void aRenameWhileTheWindowIsHiddenDropsTheLoadInFlightWithoutListing() throws IOException {
        Path root = root();
        Path docs = Files.createDirectory(root.resolve("docs"));
        MapLoader loader = loader();
        loader.setRoot(root);
        loader.toggle(docs);
        loader.reload();
        background.runAll();
        host.active = false;
        long before = loader.generation.get();
        int finishedBefore = host.finished;
        loader.pathRenamed(docs, root.resolve("renamed"));
        assertEquals(before + 1, loader.generation.get());
        assertEquals(finishedBefore + 1, host.finished, "the view stops waiting for the load");
        assertTrue(background.queued.isEmpty(), "nothing is listed while hidden");
        ui.runAll();
        assertTrue(host.landed.isEmpty());
        loader.reload();
        assertTrue(background.queued.isEmpty(), "a hidden window lists nothing");
    }

    @Test
    void aFolderThatIsGoneLeavesTheManualSetWhenTheLoadLands() throws IOException {
        Path root = root();
        Path gone = Files.createDirectory(root.resolve("gone"));
        Files.writeString(root.resolve("keep.txt"), "k");
        MapLoader loader = loader();
        loader.setRoot(root);
        loader.toggle(gone);
        Files.delete(gone);
        loader.reload();
        background.runAll();
        ui.runAll();
        assertFalse(loader.expanded.contains(gone));
        assertEquals(List.of(true), host.expansionChanges);
    }

    @Test
    void duringASearchTheMatchesFoldersOpenOnTopAndClearingItGivesTheManualSetBack() throws IOException {
        Path root = root();
        Path deep = Files.createDirectories(root.resolve("a/b"));
        Path match = Files.writeString(deep.resolve("Match.java"), "class Match {}");
        MapLoader loader = loader();
        loader.setRoot(root);
        host.searching = true;
        assertTrue(loader.setSearchMatches(List.of(match), List.of(match), null, true));
        assertTrue(loader.expandedDirectories().containsAll(Set.of(root.resolve("a"), deep)));
        loader.toggle(root.resolve("a")); // closed by hand during the search: the branch goes
        assertFalse(loader.expandedDirectories().contains(deep));
        assertEquals(Set.of(root), loader.expanded, "the manual set is untouched");
        loader.reload();
        background.runAll();
        ui.runAll();
        loader.clearSearch();
        host.searching = false;
        assertEquals(Set.of(root), loader.expandedDirectories());
    }

    @Test
    void returningToARootBringsBackWhatWasOpenThereAndTheSelection() throws IOException {
        Path first = root();
        Path src = Files.createDirectory(first.resolve("src"));
        Path other = Files.createDirectory(temp.resolve("other")).toRealPath();
        MapLoader loader = loader();
        loader.setRoot(first);
        loader.toggle(src);
        loader.rememberRoot(src);
        assertNull(loader.setRoot(other));
        assertEquals(Set.of(other), loader.expanded);
        loader.rememberRoot(null);
        assertEquals(src, loader.setRoot(first));
        assertTrue(loader.expanded.containsAll(Set.of(first, src)));
    }

    @Test
    void aLoadThatCannotBeQueuedIsFinishedAtOnce() throws IOException {
        Path root = root();
        MapLoader loader = loader();
        loader.setRoot(root);
        background.shutDown = true;
        loader.reload();
        assertEquals(1, host.started);
        assertEquals(1, host.finished);
        loader.dispose();
        loader.reload();
        assertEquals(1, host.started, "nothing is asked after dispose");
    }

    @Test
    void theListingCacheAgesARemoteFolderByTheLoadersClock() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(
                URI.create("jar:" + temp.resolve("remote.zip").toUri()), Map.of("create", "true"))) {
            Path root = Files.createDirectories(zip.getPath("/project"));
            Files.writeString(root.resolve("a.txt"), "a");
            long[] now = {5_000_000};
            MapLoader loader = new MapLoader(host, background, ui, () -> now[0]);
            try {
                loader.setRoot(root);
                loader.reload();
                background.runAll();
                ui.runAll();
                int first = loader.listings.listedForTest.get();
                loader.reload();
                background.runAll();
                ui.runAll();
                assertEquals(first, loader.listings.listedForTest.get(), "reused while young");
                now[0] += ListingService.REMOTE_TTL_MILLIS + 1;
                loader.reload();
                background.runAll();
                ui.runAll();
                assertEquals(first + 1, loader.listings.listedForTest.get(), "listed again once old by that clock");
                assertEquals(3, host.landed.size());
            } finally {
                loader.dispose();
            }
        }
    }

    @Test
    void anEmptyRootClearsTheMapWithoutListing() {
        MapLoader loader = loader();
        loader.setRoot(null);
        loader.reload();
        assertEquals(1, host.cleared);
        assertTrue(background.queued.isEmpty());
        assertTrue(loader.snapshot().entries().isEmpty());
    }

    @Test
    void moreRowsThatTheOverallLimitDidNotGiveUndoTheRaise() throws IOException {
        Path root = root();
        Files.writeString(root.resolve("only.txt"), "x");
        MapLoader loader = loader();
        loader.setRoot(root);
        loader.reload();
        background.runAll();
        ui.runAll();
        assertTrue(loader.loadMore(root));
        assertEquals(root, loader.lastRaised());
        loader.reload();
        background.runAll();
        ui.runAll();
        assertNull(loader.finishLoadMore(loader.snapshot()), "no row arrived: the raise is undone");
        assertNull(loader.finishLoadMore(loader.snapshot()), "and it is answered once");
    }
}

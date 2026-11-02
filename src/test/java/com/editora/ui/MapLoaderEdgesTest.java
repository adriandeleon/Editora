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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MapLoader} at its edges, step by step with queue executors: the roots it remembers (local ones, the last
 * eight, a selection only inside its root), reveal pins past their cap, "+N more" that brings rows, a column filter
 * on a cut folder, the folders a load pays first, and the search's folders — a match stepped to past the opened
 * ones, a folder match, and what clearing the query keeps.
 */
class MapLoaderEdgesTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private static final class StepExecutor implements Executor {
        final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runAll() {
            while (!queued.isEmpty()) {
                queued.poll().run();
            }
        }
    }

    private static final class FakeHost implements MapLoader.Host {
        boolean searching;

        @Nullable
        Path selectedColumn;

        final List<ProjectMapModel.Snapshot> landed = new ArrayList<>();

        @Override
        public boolean active() {
            return true;
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
            return false;
        }

        @Override
        public @Nullable Path selectedColumn() {
            return selectedColumn;
        }

        @Override
        public void loadStarted() {}

        @Override
        public void loadFinished() {}

        @Override
        public void cleared() {}

        @Override
        public void loaded(
                Path requestedRoot, ProjectMapModel.Snapshot loaded, Set<Path> loadedPaths, boolean expansionChanged) {
            landed.add(loaded);
        }
    }

    private final FakeHost host = new FakeHost();
    private final StepExecutor background = new StepExecutor();
    private final StepExecutor ui = new StepExecutor();

    private MapLoader loader() {
        MapLoader loader = new MapLoader(host, background, ui, new ListingService(0, null));
        loader.canvasWholeFolders = folder -> false; // "+N more", as on a remote root
        return loader;
    }

    private void load(MapLoader loader) {
        loader.reload();
        background.runAll();
        ui.runAll();
    }

    private Path root(String name) throws IOException {
        return Files.createDirectory(temp.resolve(name)).toRealPath();
    }

    private static boolean shows(MapLoader loader, Path path) {
        return loader.snapshot().entries().stream()
                .anyMatch(entry -> entry.path().equals(path));
    }

    @Test
    void theLastEightLocalRootsAreRememberedWithASelectionOnlyInsideThem() throws Exception {
        MapLoader loader = loader();
        assertNull(loader.setRoot(null));
        loader.rememberRoot(null); // no root: nothing to remember
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < MapLoader.MAX_REMEMBERED_ROOTS + 1; i++) {
            Path root = root("r" + i);
            roots.add(root);
            loader.setRoot(root);
            Path open = Files.createDirectory(root.resolve("open"));
            loader.toggle(open);
            loader.rememberRoot(open.resolve("file.txt"));
        }
        assertNull(loader.setRoot(roots.getFirst()), "the oldest of nine is forgotten");
        assertEquals(Set.of(roots.getFirst()), loader.expanded);
        assertEquals(roots.get(1).resolve("open/file.txt"), loader.setRoot(roots.get(1)));
        assertTrue(loader.expanded.contains(roots.get(1).resolve("open")));

        Path elsewhere = root("elsewhere");
        loader.rememberRoot(elsewhere.resolve("not-inside.txt"));
        loader.setRoot(elsewhere);
        assertNull(loader.setRoot(roots.get(1)), "a selection outside its root is not brought back");
        assertTrue(loader.expanded.contains(roots.get(1).resolve("open")), "its folders are");

        try (FileSystem zip = FileSystems.newFileSystem(
                URI.create("jar:" + temp.resolve("remote.zip").toUri()), Map.of("create", "true"))) {
            Path remote = Files.createDirectories(zip.getPath("/project"));
            loader.setRoot(remote);
            loader.rememberRoot(remote.resolve("a"));
            loader.setRoot(elsewhere);
            assertNull(loader.setRoot(remote), "a remote root is never remembered");
        }
    }

    @Test
    void closeOthersWithNoRootOrARowElsewhereKeepsOnlyTheFirstColumn() throws Exception {
        MapLoader loader = loader();
        assertEquals(List.of(), loader.canvasCloseOthers(null, false), "no root: nothing to close");
        Path root = root("project");
        Path a = root.resolve("a");
        loader.setRoot(root);
        loader.expanded.add(a);
        assertEquals(List.of(a), loader.canvasCloseOthers(temp.resolve("outside/x.txt"), true));
        assertEquals(Set.of(root), loader.expandedDirectories());
        loader.expanded.add(a);
        assertFalse(loader.onlyAncestorsOpen(root.resolve("b")), "a is off the way to b");
        assertTrue(loader.onlyAncestorsOpen(a.resolve("deep")));
    }

    @Test
    void revealPinsAreCappedAndTheWayToATargetOpensInTheSearchOrTheManualSet() throws Exception {
        Path root = root("project");
        Path a = root.resolve("a");
        MapLoader loader = loader();
        loader.setRoot(root);
        assertTrue(loader.pin(a.resolve("first")));
        assertFalse(loader.pin(a.resolve("first")), "pinned already");
        for (int i = 0; i < MapLoader.MAX_REVEAL_PINS; i++) {
            loader.pin(a.resolve("p" + i));
        }
        assertTrue(loader.pin(a.resolve("first")), "the oldest pin went past the cap, so it pins again");

        loader.openTo(Set.of(a), a.resolve("x.txt"));
        assertTrue(loader.expanded.contains(a));
        Path b = root.resolve("b");
        host.searching = true;
        loader.openTo(Set.of(b), b.resolve("y.txt"));
        assertFalse(loader.expanded.contains(b), "during a search the search's view opens it");
        assertTrue(loader.expandedDirectories().contains(b));
        assertTrue(loader.hasSearchFolders());
        assertFalse(loader.keepLoadedFoldersOpen(), "not during a search");
        loader.clearSearchEdits();
        assertFalse(loader.hasSearchFolders());
    }

    @Test
    void moreRowsThatArriveAreReportedAndAColumnFilterOnACutFolderListsItAgain() throws Exception {
        Path root = root("project");
        Path big = Files.createDirectory(root.resolve("big"));
        for (int i = 0; i < ProjectMapModel.DIRECTORY_CHUNK + 40; i++) {
            Files.writeString(big.resolve(String.format("f%04d.txt", i)), "");
        }
        Path small = Files.createDirectory(root.resolve("small"));
        Files.writeString(small.resolve("one.txt"), "");
        MapLoader loader = loader();
        loader.setRoot(root);
        assertFalse(loader.loadMore(null));
        loader.toggle(big);
        loader.toggle(small);
        load(loader);
        assertFalse(shows(loader, big.resolve("f0339.txt")), "cut at the chunk");

        long generation = loader.generation.get();
        loader.columnFilterChanged(null);
        loader.columnFilterChanged(root.resolve("unknown"));
        loader.columnFilterChanged(small);
        assertEquals(generation, loader.generation.get(), "a whole folder is filtered where it is");
        loader.columnFilterChanged(big);
        assertEquals(generation + 1, loader.generation.get(), "a cut folder is listed again for its filter");
        background.runAll();
        ui.runAll();

        assertTrue(loader.loadMore(big));
        load(loader);
        MapLoader.Raised raised = loader.finishLoadMore(loader.snapshot());
        assertNotNull(raised, "rows arrived");
        assertEquals(big, raised.directory());
        assertEquals(ProjectMapModel.DIRECTORY_CHUNK, raised.previousLoaded());
        assertTrue(shows(loader, big.resolve("f0339.txt")));

        // The folder raised last is paid first, after the selected column; once its raise is gone it is forgotten.
        host.selectedColumn = big;
        load(loader);
        assertEquals(big, loader.lastRaised(), "the selected column is the raised one: paid once");
        host.selectedColumn = small;
        load(loader);
        assertEquals(big, loader.lastRaised());
        loader.setRoot(root("other"));
        load(loader);
        assertNull(loader.lastRaised(), "its column went with the root");

        assertTrue(loader.loadMore(root("fresh")), "a folder not loaded yet still takes a raise");
    }

    @Test
    void placeholderRowsSayWhatTheyStandFor() {
        assertEquals(
                tr("project.map.row.more", 5), MapLoader.placeholderLabel(ProjectMapModel.PlaceholderKind.MORE, 5));
        assertEquals(
                tr("project.map.row.emptyFolder"),
                MapLoader.placeholderLabel(ProjectMapModel.PlaceholderKind.EMPTY, 0));
        assertEquals(
                tr("project.map.row.unreadableFolder"),
                MapLoader.placeholderLabel(ProjectMapModel.PlaceholderKind.UNREADABLE, 0));
        assertEquals(
                tr("project.map.row.overLimit", ProjectMapModel.MAX_VISIBLE_ITEMS),
                MapLoader.placeholderLabel(ProjectMapModel.PlaceholderKind.OVER_LIMIT, 0));
        assertEquals(
                tr("project.map.row.notResponding"),
                MapLoader.placeholderLabel(ProjectMapModel.PlaceholderKind.NOT_RESPONDING, 0));
        assertEquals(
                tr("project.map.row.moreNotMatching", 7),
                MapLoader.searchPlaceholderLabel(ProjectMapModel.PlaceholderKind.MORE, 7),
                "in a search, the rows behind it do not match");
        assertEquals(
                tr("project.map.row.emptyFolder"),
                MapLoader.searchPlaceholderLabel(ProjectMapModel.PlaceholderKind.EMPTY, 0));
    }

    @Test
    void aSearchStepsPastItsOpenedMatchesAndClearingItKeepsThePlace() throws Exception {
        Path root = root("project");
        Path a = Files.createDirectory(root.resolve("a"));
        Path b = Files.createDirectory(root.resolve("b"));
        Path c = Files.createDirectory(root.resolve("c"));
        Path m1 = Files.writeString(a.resolve("m1.txt"), "");
        Path m2 = Files.writeString(b.resolve("m2.txt"), "");
        Path m3 = Files.writeString(c.resolve("m3.txt"), "");
        Path loose = Files.writeString(root.resolve("m0.txt"), "");
        Path d = Files.createDirectory(root.resolve("d"));
        Files.writeString(d.resolve("other.txt"), "");
        MapLoader loader = loader();
        loader.setRoot(root);
        host.searching = true;
        assertFalse(loader.hasSearchFolders());
        assertTrue(loader.setSearchMatches(List.of(m1, m2, m3, loose), List.of(m1), null, true));
        assertFalse(loader.setSearchMatches(List.of(m1, m2, m3, loose), List.of(m1), null, false), "the same");
        assertTrue(loader.setSearchMatches(List.of(m1, m2, m3, loose), List.of(m1), a, false), "a new focus only");
        assertEquals(a, loader.searchFocusFolder());
        assertEquals(List.of(m1), loader.openedMatches());

        assertTrue(loader.showMatch(m3, false), "past the opened ones: its own folders open");
        assertFalse(loader.showMatch(m3, false), "already stepped to");
        assertTrue(loader.showMatch(b, true), "a folder match is focused");
        assertTrue(loader.showMatch(m1, false), "back among the opened ones");
        assertTrue(loader.showMatch(m3, false));
        assertFalse(
                loader.setSearchMatches(List.of(m1, m2, m3, loose), List.of(m1), b, false),
                "the match stepped to stays while it is still a match");
        loader.toggle(d); // the user opens d by hand during the search
        load(loader);
        assertTrue(shows(loader, m3), "the match stepped to is listed");
        assertTrue(shows(loader, loose), "a match in an open folder is pinned, so no \"+N more\" hides it");

        assertFalse(
                loader.setSearchMatches(List.of(m1, m2), List.of(m1), b, false),
                "m3 is no longer a match: the folders to open are the same, the step past them is dropped");
        assertTrue(loader.showMatch(m3, false), "so stepping to it again is a change");
        loader.setSearchMatches(List.of(m1, m2), List.of(m1), b, false);
        loader.toggle(a); // closed by hand during the search
        assertTrue(loader.keepSearchPlace(root, b), "the folder match the user is on stays open");
        assertTrue(loader.expanded.contains(b));
        assertTrue(loader.expanded.contains(d), "and what was opened by hand");
        assertFalse(loader.expanded.contains(a), "but not what was closed by hand");
        assertTrue(loader.keepSearchPlace(root, m2), "a match is pinned past its folder's row limit");
        assertFalse(loader.keepSearchPlace(root, m2), "once");
        assertFalse(loader.keepSearchPlace(root, temp.resolve("x")), "a place outside the root keeps nothing");
        loader.clearSearch();
        host.searching = false;
        assertFalse(loader.hasSearchFolders());
        assertNull(loader.searchFocusFolder());
    }

    @Test
    void foldersAChainOpenedStayOpenWhenChainsGoOff() throws Exception {
        Path root = root("project");
        Path x = Files.createDirectory(root.resolve("x"));
        Files.writeString(x.resolve("f.txt"), "");
        MapLoader loader = loader();
        loader.setRoot(root);
        loader.toggle(x);
        load(loader);
        loader.expanded.remove(x); // listed by the last load, no longer in the manual set (as a chain opened it)
        assertTrue(loader.keepLoadedFoldersOpen());
        assertTrue(loader.expanded.contains(x));
        assertFalse(loader.keepLoadedFoldersOpen(), "every loaded folder is open now");
    }
}

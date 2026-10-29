package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.ui.ProjectMapModel.Entry;
import com.editora.ui.ProjectMapModel.Listings;
import com.editora.ui.ProjectMapModel.PlaceholderKind;
import com.editora.ui.ProjectMapModel.Request;
import com.editora.ui.ProjectMapModel.Snapshot;
import com.editora.ui.ProjectMapModel.TypeFilter;
import com.editora.ui.ProjectPanel.FsChange;
import com.editora.ui.ProjectPanel.FsKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checks for the Project Map review's data fixes (ordering, buckets, listing, search ranking). */
class ProjectMapDataReviewTest {

    @TempDir
    Path temp;

    // --- E7: natural order, shared by the tree and the Map ------------------------------------------

    @Test
    void numberedNamesSortByTheirNumbersNotTheirCharacters() {
        List<String> names = new ArrayList<>(List.of(
                "note-11.txt",
                "note-100.txt",
                "note-2.txt",
                "note-1.txt",
                "note-10.txt",
                "Note-3.txt",
                "note-109.txt"));
        names.sort(ProjectPathOrder.NATURAL);
        assertEquals(
                List.of(
                        "note-1.txt",
                        "note-2.txt",
                        "Note-3.txt",
                        "note-10.txt",
                        "note-11.txt",
                        "note-100.txt",
                        "note-109.txt"),
                names);
    }

    @Test
    void leadingZerosAndCaseOnlyDifferencesStillGiveOneStableOrder() {
        List<Path> sorted = ProjectPathOrder.sorted(
                List.of(
                        Path.of("a1"),
                        Path.of("a01"),
                        Path.of("README"),
                        Path.of("readme"),
                        Path.of("0010-decision.md"),
                        Path.of("0002-decision.md"),
                        Path.of("v1.10"),
                        Path.of("v1.9")),
                p -> false);
        assertEquals(
                List.of(
                        Path.of("0002-decision.md"),
                        Path.of("0010-decision.md"),
                        Path.of("a01"),
                        Path.of("a1"),
                        Path.of("README"),
                        Path.of("readme"),
                        Path.of("v1.9"),
                        Path.of("v1.10")),
                sorted);
        // Folders still come first, numbered folders in order among themselves.
        assertEquals(
                List.of(Path.of("step2"), Path.of("step10"), Path.of("file1")),
                ProjectPathOrder.sorted(
                        List.of(Path.of("file1"), Path.of("step10"), Path.of("step2")),
                        p -> p.toString().startsWith("step")));
    }

    // --- A14: Type filter buckets ------------------------------------------------------------------

    @Test
    void plainTextDocumentsAreMarkupAndBuildFilesConfig() {
        for (String document : List.of(
                "notes.txt", "README", "LICENSE", "CHANGELOG", "NOTICE", "AUTHORS", "readme.txt", "LICENSE.txt")) {
            assertEquals(TypeFilter.MARKUP, ProjectMapModel.classify(document), document);
        }
        for (String config : List.of(
                "go.mod",
                "go.sum",
                "Gemfile",
                "Rakefile",
                "Vagrantfile",
                "Procfile",
                "CODEOWNERS",
                "mvnw",
                "gradlew",
                "init.gradle",
                "publish.gradle.kts",
                "Info.plist",
                "CMakeLists.txt",
                "requirements.txt")) {
            assertEquals(TypeFilter.CONFIG, ProjectMapModel.classify(config), config);
        }
        assertEquals(TypeFilter.SOURCE, ProjectMapModel.classify("tasks.rb"));
        assertEquals(TypeFilter.OTHER, ProjectMapModel.classify("photo.png"));
    }

    // --- A3: a folder the row limit starves stays open ------------------------------------------------

    @Test
    void aFolderTheOverallLimitStarvesStaysOpenWithAStubAndComesBackWhenRoomIsFreed() throws Exception {
        Path root = root();
        List<Path> big = new ArrayList<>();
        for (String name : List.of("a", "b", "c", "d")) {
            big.add(folderWithFiles(root, name, 300));
        }
        Path late = folderWithFiles(root, "e", 3);
        Path deeper = Files.createDirectory(late.resolve("sub"));
        Files.createFile(deeper.resolve("leaf.txt"));
        Set<Path> expanded = new HashSet<>(big);
        expanded.addAll(List.of(root, late, deeper));

        Snapshot starved = ProjectMapModel.load(new Request(root, expanded, false));
        assertTrue(starved.skipped().contains(late));
        List<Entry> stub = childrenOf(starved, late);
        assertEquals(1, stub.size());
        assertEquals(PlaceholderKind.OVER_LIMIT, stub.getFirst().placeholder().kind());
        assertEquals(4, stub.getFirst().placeholder().remaining(), "it says how much it holds");
        Set<Path> kept = ProjectMapModel.pruneExpansion(root, expanded, starved);
        assertTrue(kept.contains(late), "the starved folder stays open");
        assertTrue(kept.contains(deeper), "and so does the folder the user opened inside it");

        kept.remove(big.get(0)); // the user closes a column, as the status line advises
        Snapshot freed = ProjectMapModel.load(new Request(root, kept, false));
        assertTrue(
                childrenOf(freed, late).stream().anyMatch(entry -> entry.path().equals(deeper)));
        assertTrue(childrenOf(freed, deeper).stream()
                .anyMatch(entry -> entry.path().equals(deeper.resolve("leaf.txt"))));
    }

    // --- B8: a column filter searches the whole folder ------------------------------------------------

    @Test
    void aColumnFilterOnALongFolderLoadsTheMatchesBeyondTheRowLimit() throws Exception {
        Path root = root();
        Path huge = Files.createDirectory(root.resolve("huge"));
        for (int index = 0; index < 700; index++) {
            Files.createFile(huge.resolve(String.format("item-%04d.txt", index)));
        }
        Path wanted = huge.resolve("item-0650.txt");
        Set<Path> expanded = Set.of(root, huge);

        Snapshot plain = ProjectMapModel.load(new Request(root, expanded, false));
        assertFalse(
                childrenOf(plain, huge).stream().anyMatch(entry -> entry.path().equals(wanted)));

        Snapshot filtered = ProjectMapModel.load(new Request(
                root,
                expanded,
                false,
                Map.of(),
                Map.of(),
                List.of(),
                ProjectMapModel.Labels.PLAIN,
                () -> false,
                Map.of(huge, " 0650 ")));
        assertTrue(childrenOf(filtered, huge).stream()
                .anyMatch(entry -> entry.path().equals(wanted)));
        ProjectMapModel.DirectoryFacts facts = filtered.directories().get(huge);
        assertEquals(700, facts.total());
        assertEquals(ProjectMapModel.DIRECTORY_CHUNK + 1, facts.loaded(), "the first chunk plus the one match");

        ProjectMapModel.Column column = ProjectMapModel.columnsById(
                        filtered.entries(), Map.of(new ProjectMapModel.ColumnId(2, huge), "0650"), Map.of())
                .stream()
                .filter(candidate -> huge.equals(candidate.parent()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of(wanted),
                column.entries().stream()
                        .filter(entry -> !entry.isPlaceholder())
                        .map(Entry::path)
                        .toList());
        assertEquals("1/700", column.countLabel(), "one match out of the whole folder, all of which was searched");
    }

    // --- A10: listings are reused while watched, and one that hangs does not hold the loader --------

    @Test
    void aWatchedFolderIsListedOnceUntilItsWatcherSaysItChanged() throws Exception {
        Path root = root();
        Path watched = folderWithFiles(root, "watched", 3);
        Path unwatched = folderWithFiles(root, "unwatched", 3);
        Set<Path> expanded = Set.of(root, watched, unwatched);
        try (Listings listings = new Listings(5_000, null)) {
            listings.setWatched(Set.of(root, watched));
            ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(3, listings.listedForTest.get(), "every folder is listed the first time");
            assertTrue(listings.cached(watched, false));
            assertFalse(listings.cached(unwatched, false), "a folder no watcher follows is never reused");

            ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(4, listings.listedForTest.get(), "the next load lists only the unwatched folder");

            Path added = Files.createFile(watched.resolve("added.txt"));
            listings.invalidate(List.of(watched)); // what the Project watcher's event does
            Snapshot after = ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(6, listings.listedForTest.get(), "the changed folder and the unwatched one");
            assertTrue(childrenOf(after, watched).stream()
                    .anyMatch(entry -> entry.path().equals(added)));

            listings.setWatched(Set.of(root)); // the folder stops being watched: its listing is dropped
            assertFalse(listings.cached(watched, false));
            listings.invalidateAll();
            assertFalse(listings.cached(root, false));
        }
    }

    @Test
    void aCachedListingThatLacksAnOpenFolderIsListedAgainBeforeTheWatcherCatchesUp() throws Exception {
        Path root = root();
        folderWithFiles(root, "old", 2);
        try (Listings listings = new Listings(5_000, null)) {
            listings.setWatched(Set.of(root));
            ProjectMapModel.load(new Request(root, Set.of(root), false), listings);
            assertTrue(listings.cached(root, false));

            // Made outside Editora and opened (a reveal) before the watcher's event reaches the cache.
            Path fresh = folderWithFiles(root, "fresh", 1);
            Snapshot after = ProjectMapModel.load(new Request(root, Set.of(root, fresh), false), listings);
            assertTrue(
                    childrenOf(after, root).stream()
                            .anyMatch(entry -> entry.path().equals(fresh)),
                    "the stale cached root listing was replaced");
            assertFalse(childrenOf(after, fresh).isEmpty(), "and the new folder was listed, so it stays open");
        }
    }

    @Test
    void aFolderThatDoesNotAnswerIsMarkedNotRespondingAndTheLoadGoesOn() throws Exception {
        Path root = root();
        Path slow = folderWithFiles(root, "slow", 2);
        Path fine = folderWithFiles(root, "fine", 2);
        Path inside = Files.createDirectory(slow.resolve("inside"));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        Set<Path> expanded = Set.of(root, slow, fine, inside);
        try (Listings listings = new Listings(300, directory -> {
            if (directory.equals(slow)) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        })) {
            listings.setOnRecovered(recovered::countDown);
            long started = System.nanoTime();
            Snapshot stalled = ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertNotNull(stalled);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000);
            assertEquals(
                    PlaceholderKind.NOT_RESPONDING,
                    childrenOf(stalled, slow).getFirst().placeholder().kind());
            assertEquals(2, childrenOf(stalled, fine).size(), "the other folders still load");
            assertTrue(
                    ProjectMapModel.pruneExpansion(root, expanded, stalled).contains(inside),
                    "nothing below a folder that did not answer is forgotten");

            long again = System.nanoTime();
            Snapshot still = ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertTrue(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - again) < 250,
                    "a folder already known to hang is not waited for again");
            assertEquals(
                    PlaceholderKind.NOT_RESPONDING,
                    childrenOf(still, slow).getFirst().placeholder().kind());

            release.countDown();
            assertTrue(recovered.await(5, TimeUnit.SECONDS), "the view hears that the folder answered");
            Snapshot back = ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertTrue(childrenOf(back, slow).stream()
                    .anyMatch(entry -> entry.path().equals(inside)));
        }
    }

    // --- A5 / A6: search ranks the name typed first and says when it kept only the best -------------

    @Test
    void theExactNameRanksFirstThenPrefixesThenWholeWords() {
        assertEquals(ProjectSearchRank.EXACT, ProjectSearchRank.tier("Settings.java", "settings"));
        assertEquals(ProjectSearchRank.PREFIX_WORDS, ProjectSearchRank.tier("SettingsPane.java", "settings"));
        assertEquals(ProjectSearchRank.PREFIX, ProjectSearchRank.tier("SettingSwitch.java", "settings"));
        assertEquals(ProjectSearchRank.PREFIX, ProjectSearchRank.tier("PanelsTestSupport.java", "panel"));
        assertEquals(ProjectSearchRank.WORDS, ProjectSearchRank.tier("ProjectPanel.java", "panel"));
        assertEquals(ProjectSearchRank.WORD_START, ProjectSearchRank.tier("DoctorPaneLayoutFxTest.java", "panel"));
        assertEquals(ProjectSearchRank.WORDS, ProjectSearchRank.tier("project-map.md", "map"));
        assertEquals(ProjectSearchRank.SUBSTRING, ProjectSearchRank.tier("bitmap.png", "map"));
        assertEquals(ProjectSearchRank.FUZZY, ProjectSearchRank.tier("ProjectMapView.java", "pmv"));
        assertEquals(ProjectSearchRank.FUZZY, ProjectSearchRank.tier("ProjectMapView.java", "map view"));
    }

    @Test
    void aSearchSelectsTheFileItNamesAheadOfLooserMatches() throws Exception {
        Path root = root();
        Path settings = touch(root, "src/main/java/com/editora/config/Settings.java");
        touch(root, "src/main/java/com/editora/ui/SettingSwitch.java");
        touch(root, "src/main/java/com/editora/ui/SettingsPane.java");
        Path panel = touch(root, "src/main/java/com/editora/ui/ProjectPanel.java");
        touch(root, "src/test/java/com/editora/ui/DoctorPaneLayoutFxTest.java");
        touch(root, "src/test/java/com/editora/ui/PanelsTestSupport.java");
        Path view = touch(root, "src/main/java/com/editora/ui/ProjectMapView.java");
        touch(root, "src/test/java/com/editora/ui/ProjectMapViewFxTest.java");
        touch(root, "src/main/java/com/editora/ui/ReviewPane.java");
        var none = com.editora.search.GitignoreFilter.NONE;

        assertEquals(
                settings, ProjectPanel.search(root, "Settings", false, none).getFirst());
        List<Path> settingsHits = ProjectPanel.search(root, "Settings", false, none);
        assertTrue(
                settingsHits.indexOf(root.resolve("src/main/java/com/editora/ui/SettingsPane.java"))
                        < settingsHits.indexOf(root.resolve("src/main/java/com/editora/ui/SettingSwitch.java")),
                "whole words before a prefix that runs into the next word: " + settingsHits);
        assertEquals(panel, ProjectPanel.search(root, "panel", false, none).getFirst());
        assertEquals(view, ProjectPanel.search(root, "mapview", false, none).getFirst());
        List<Path> views = ProjectPanel.search(root, "view", false, none);
        assertTrue(
                views.indexOf(view) < views.indexOf(root.resolve("src/main/java/com/editora/ui/ReviewPane.java")),
                "a whole-word match outranks a word that merely contains the query: " + views);
    }

    @Test
    void aBroadQueryStillFindsTheDeepFileItNamesAndSaysItKeptOnlyTheBest() throws Exception {
        Path root = root();
        for (int index = 0; index < 350; index++) {
            Files.createFile(root.resolve("latest-" + index + ".txt"));
        }
        Path deep = touch(root, "a/b/c/d/Test.java");
        var none = com.editora.search.GitignoreFilter.NONE;

        ProjectPanel.SearchResult found = ProjectPanel.searchRanked(root, "test", false, none, () -> false);

        assertEquals(351, found.total());
        assertEquals(300, found.matches().size());
        assertTrue(found.truncated());
        assertEquals(deep, found.matches().getFirst(), "found although 350 shallow names matched before it");
    }

    // --- A2: the echo of an in-app change is dropped, an outside change in the same moment is not ---

    @Test
    void onlyTheEventsOfAnInAppOperationAreTakenForItsEcho() {
        Path root = Path.of("/project").toAbsolutePath();
        Path renamedFrom = root.resolve("docs/old.txt");
        Path renamedTo = root.resolve("docs/new.txt");
        Path external = root.resolve("docs/aaa-external.txt");
        ProjectWatchChanges changes = new ProjectWatchChanges();
        changes.add(
                root.resolve("docs"),
                List.of(
                        new FsChange(renamedFrom, FsKind.DELETED),
                        new FsChange(renamedTo, FsKind.CREATED),
                        new FsChange(external, FsKind.CREATED)),
                false);

        ProjectWatchChanges.Plan plan =
                changes.drain(path -> false, path -> path.equals(renamedFrom) || path.equals(renamedTo));

        assertEquals(List.of(new FsChange(external, FsKind.CREATED)), plan.external());
        assertEquals(Set.of(root.resolve("docs")), plan.relistDirs(), "the outside file's folder is listed again");
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private Path root() throws IOException {
        return Files.createDirectories(temp.resolve("project")).toRealPath();
    }

    private static Path folderWithFiles(Path root, String name, int count) throws IOException {
        Path folder = Files.createDirectory(root.resolve(name));
        for (int index = 0; index < count; index++) {
            Files.createFile(folder.resolve(String.format("file%04d.txt", index)));
        }
        return folder;
    }

    private static Path touch(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "x");
    }

    private static List<Entry> childrenOf(Snapshot snapshot, Path parent) {
        return snapshot.entries().stream()
                .filter(entry -> parent.equals(entry.parent()))
                .toList();
    }
}

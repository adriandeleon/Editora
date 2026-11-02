package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.search.ProjectFileList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project index (Map refactor stage 2): one file list per root, ranked the Map's way, kept current from what
 * the panel reports, answering a stale list at once or after listing it again as each caller asks.
 */
class ProjectFileIndexTest {

    @TempDir
    Path temp;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Path> relisted = new ArrayList<>();
    private ProjectFileList.Settings settings = new ProjectFileList.Settings(true, false);
    private final ProjectFileIndex index =
            new ProjectFileIndex(executor, Runnable::run, () -> settings, () -> false, root -> {
                synchronized (relisted) {
                    relisted.add(root);
                }
            });

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    /** Waits for everything queued on the index's thread so far. */
    private void drain() throws Exception {
        executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private Path project() throws IOException {
        Path root = Files.createDirectory(temp.resolve("project")).toRealPath();
        Files.createDirectories(root.resolve("src/main/java/app"));
        Files.createDirectories(root.resolve("src/test/java/app"));
        Files.createDirectories(root.resolve("vendor/lib"));
        Files.writeString(root.resolve("src/test/java/app/Widget.java"), "class WidgetTest {}");
        Files.writeString(root.resolve("src/main/java/app/Widget.java"), "class Widget {}");
        Files.writeString(root.resolve("vendor/lib/Widget.java"), "class Widget {}");
        return root;
    }

    private static List<String> rels(ProjectMapSearchResult result) {
        return result.matches().stream()
                .map(ProjectMapSearchResult.Match::relativePath)
                .toList();
    }

    @Test
    void filesAreRankedAsTheMapRanksThemAndAFilesSearchLeavesFoldersOut() throws IOException {
        Path root = project();
        ProjectMapSearchResult files =
                index.search(root, "app", ProjectIndex.Kinds.FILES, 50, ProjectIndex.Freshness.AS_LISTED, () -> false);
        assertNotNull(files);
        assertTrue(files.matches().stream().allMatch(m -> m.kind() == ProjectMapSearchResult.Kind.FILE));
        ProjectMapSearchResult all = index.search(
                root, "app", ProjectIndex.Kinds.FILES_AND_FOLDERS, 50, ProjectIndex.Freshness.AS_LISTED, () -> false);
        assertNotNull(all);
        assertEquals(ProjectMapSearchResult.Kind.FOLDER, all.first().kind(), "the Map's search finds the folders");

        ProjectMapSearchResult widget = index.search(
                root, "Widget.java", ProjectIndex.Kinds.FILES, 50, ProjectIndex.Freshness.AS_LISTED, () -> false);
        assertNotNull(widget);
        assertEquals(
                List.of("src/main/java/app/Widget.java", "vendor/lib/Widget.java", "src/test/java/app/Widget.java"),
                rels(widget),
                "the project's source, then vendored source, then tests (ProjectMapSearch's order)");
        assertEquals(rels(widget), rels(index.searchListed(root, "Widget.java", ProjectIndex.Kinds.FILES, 50)));
    }

    @Test
    void nothingIsListedOnTheFxSideAndWhenListedRunsOnceTheListIsThere() throws Exception {
        Path root = project();
        assertTrue(
                index.searchListed(root, "Widget", ProjectIndex.Kinds.FILES, 10).isEmpty(), "not listed yet");
        AtomicInteger ran = new AtomicInteger();
        index.whenListed(root, ran::incrementAndGet);
        drain();
        assertEquals(1, ran.get());
        assertEquals(
                3,
                index.searchListed(root, "Widget", ProjectIndex.Kinds.FILES, 10)
                        .matches()
                        .size());
        index.whenListed(root, ran::incrementAndGet);
        assertEquals(2, ran.get(), "at once when listed");
    }

    @Test
    void theListFollowsWhatThePanelReports() throws Exception {
        Path root = project();
        assertNotNull(index.snapshot(root));
        Path added = Files.writeString(root.resolve("src/main/java/app/Gadget.java"), "class Gadget {}");
        index.changed(List.of(added));
        drain();
        assertEquals(
                List.of("src/main/java/app/Gadget.java"),
                rels(index.searchListed(root, "Gadget", ProjectIndex.Kinds.FILES, 10)));
        Files.delete(added);
        index.changed(List.of(added));
        drain();
        assertTrue(
                index.searchListed(root, "Gadget", ProjectIndex.Kinds.FILES, 10).isEmpty());
    }

    @Test
    void aStaleListAnswersAtOnceForTheMapAndIsListedFirstForTheTree() throws Exception {
        Path root = project();
        assertNotNull(index.snapshot(root));
        Path unseen = Files.writeString(root.resolve("Unseen.md"), "x"); // no event reports it
        index.markStale();
        ProjectMapSearchResult asListed = index.search(
                root, "Unseen", ProjectIndex.Kinds.FILES, 10, ProjectIndex.Freshness.AS_LISTED, () -> false);
        assertNotNull(asListed);
        assertTrue(asListed.isEmpty(), "the Map answers from the list as it was");
        drain(); // and it is listed again in the background
        synchronized (relisted) {
            assertEquals(List.of(root), relisted, "the owner hears the list changed");
        }
        assertFalse(index.held(root).stale());
        assertEquals(
                1,
                index.searchListed(root, "Unseen", ProjectIndex.Kinds.FILES, 10)
                        .matches()
                        .size());

        Files.delete(unseen);
        index.markStale();
        ProjectMapSearchResult current =
                index.search(root, "Unseen", ProjectIndex.Kinds.FILES, 10, ProjectIndex.Freshness.CURRENT, () -> false);
        assertNotNull(current);
        assertTrue(current.isEmpty(), "the tree's filter waits for the fresh list");
    }

    @Test
    void otherSettingsListAgainAndOnlyTwoRootsAreKept() throws Exception {
        Path root = project();
        Files.writeString(root.resolve(".hidden.txt"), "x");
        assertTrue(
                index.searchListed(root, "hidden", ProjectIndex.Kinds.FILES, 10).isEmpty());
        assertNotNull(index.snapshot(root));
        assertTrue(
                index.searchListed(root, "hidden", ProjectIndex.Kinds.FILES, 10).isEmpty());
        settings = new ProjectFileList.Settings(true, true); // "show hidden files" turned on
        assertNull(index.held(root), "a list for other settings is not answered from");
        assertNotNull(index.snapshot(root));
        assertEquals(
                1,
                index.searchListed(root, "hidden", ProjectIndex.Kinds.FILES, 10)
                        .matches()
                        .size());

        Path second = Files.createDirectory(temp.resolve("second"));
        Path third = Files.createDirectory(temp.resolve("third"));
        assertNotNull(index.snapshot(second));
        assertNotNull(index.snapshot(third));
        assertNull(index.held(root), "the least recently used root's list went");
        assertNotNull(index.held(second));
        assertNotNull(index.held(third));
    }

    @Test
    void aCancelledSearchAnswersNothing() throws IOException {
        Path root = project();
        assertNull(index.search(
                root, "Widget", ProjectIndex.Kinds.FILES, 10, ProjectIndex.Freshness.AS_LISTED, () -> true));
    }
}

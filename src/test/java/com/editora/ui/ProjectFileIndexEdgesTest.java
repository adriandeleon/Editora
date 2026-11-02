package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * The project index ({@link ProjectFileIndex}) at its edges: a cancelled or closed search answers nothing, a list
 * held under other settings is not the list of now, a stale list that relists the same files tells no one, a search
 * of the held list with nothing found is still an answer, changes with nothing held or nothing changed queue no
 * work, and a closed index's thread gets none.
 */
class ProjectFileIndexEdgesTest {

    @TempDir
    Path temp;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Path> relisted = new ArrayList<>();
    private ProjectFileList.Settings settings = new ProjectFileList.Settings(true, false);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ProjectFileIndex index =
            new ProjectFileIndex(executor, Runnable::run, () -> settings, closed::get, root -> {
                synchronized (relisted) {
                    relisted.add(root);
                }
            });

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    private void drain() throws Exception {
        executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private Path project() throws IOException {
        Path root = Files.createDirectory(temp.resolve("project")).toRealPath();
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Widget.java"), "class Widget {}");
        Files.writeString(root.resolve(".hidden.txt"), "h");
        return root;
    }

    @Test
    void aCancelledSearchAnswersNothingAndASnapshotIsTheListOfNow() throws Exception {
        Path root = project();
        assertNull(
                index.search(
                        root, "widget", ProjectIndex.Kinds.FILES, 10, ProjectIndex.Freshness.AS_LISTED, () -> true),
                "cancelled: no answer");
        ProjectFileList.Snapshot snapshot = index.snapshot(root);
        assertNotNull(snapshot);
        assertTrue(
                snapshot.files().contains("src/Widget.java"), snapshot.files().toString());
        assertFalse(snapshot.files().contains(".hidden.txt"), "hidden files left out, as the settings say");
        assertNotNull(index.heldForTest(root));
        assertNull(index.heldForTest(temp.resolve("other")), "nothing held for another root");
    }

    @Test
    void aListHeldUnderOtherSettingsIsNotTheListOfNow() throws Exception {
        Path root = project();
        assertNotNull(index.list(root));
        settings = new ProjectFileList.Settings(true, true);
        assertNull(index.held(root), "the list held was made without hidden files");
        assertNotNull(index.heldForTest(root), "it is still held, until a list for these settings replaces it");
        ProjectMapSearchResult listed = index.searchListed(root, "hidden", ProjectIndex.Kinds.FILES, 10);
        assertTrue(listed.matches().isEmpty(), "no list for these settings: nothing, and nothing listed");
        assertNull(index.held(root));
        ProjectFileList.Snapshot now = index.snapshot(root);
        assertTrue(now.files().contains(".hidden.txt"), "listed for the settings of now");
    }

    @Test
    void aStaleListThatRelistsTheSameFilesTellsNoOneAndASearchOfItWithNoMatchIsStillAnAnswer() throws Exception {
        Path root = project();
        assertNotNull(index.list(root));
        index.markStale();
        ProjectMapSearchResult current =
                index.search(root, "widget", ProjectIndex.Kinds.FILES, 10, ProjectIndex.Freshness.CURRENT, () -> false);
        assertNotNull(current);
        assertEquals(1, current.matches().size());
        drain();
        synchronized (relisted) {
            assertTrue(relisted.isEmpty(), "listed again, nothing changed: no one is told");
        }
        assertFalse(index.held(root).stale());

        ProjectMapSearchResult none = index.searchListed(root, "zzzz-nothing", ProjectIndex.Kinds.FILES, 10);
        assertNotNull(none);
        assertTrue(none.matches().isEmpty());

        index.markStale();
        Files.writeString(root.resolve("src/Gadget.java"), "class Gadget {}");
        ProjectMapSearchResult answeredAsListed = index.searchListed(root, "gadget", ProjectIndex.Kinds.FILES, 10);
        assertTrue(answeredAsListed.matches().isEmpty(), "answered from the list as it was");
        drain();
        synchronized (relisted) {
            assertEquals(List.of(root), relisted, "listed again later, and changed: told");
        }
        assertEquals(
                1,
                index.searchListed(root, "gadget", ProjectIndex.Kinds.FILES, 10)
                        .matches()
                        .size());
    }

    @Test
    void changesWithNothingHeldOrNothingChangedQueueNoWorkAndAClosedIndexQueuesNone() throws Exception {
        Path root = project();
        AtomicInteger ran = new AtomicInteger();
        index.changed(List.of(root.resolve("src/Widget.java"))); // nothing held: nothing to do
        assertNotNull(index.list(root));
        index.changed(List.of());
        drain();
        index.whenListed(root, ran::incrementAndGet);
        assertEquals(1, ran.get(), "held already: at once");

        closed.set(true);
        index.markStale();
        index.searchListed(root, "widget", ProjectIndex.Kinds.FILES, 10); // stale, but closed: no relist queued
        index.changed(List.of(root.resolve("src/Widget.java")));
        drain();
        synchronized (relisted) {
            assertTrue(relisted.isEmpty());
        }
        assertNull(index.list(temp.resolve("never-listed")), "closed: no new list is made");

        executor.shutdownNow();
        closed.set(false);
        index.changed(List.of(root.resolve("src/Widget.java"))); // the thread is gone: dropped quietly
        Path other = Files.createDirectory(temp.resolve("other"));
        index.whenListed(other, ran::incrementAndGet);
        assertEquals(2, ran.get(), "a thread that is gone still answers the caller");
    }
}

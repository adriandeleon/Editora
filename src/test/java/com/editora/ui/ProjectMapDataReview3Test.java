package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.ui.ProjectMapModel.Entry;
import com.editora.ui.ProjectMapModel.Labels;
import com.editora.ui.ProjectMapModel.PlaceholderKind;
import com.editora.ui.ProjectMapModel.Request;
import com.editora.ui.ProjectMapModel.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Pure checks for the data fixes of Project Map review 3 (listings, regain, row budget, order, sizes, focus). */
class ProjectMapDataReview3Test {

    @TempDir
    Path temp;

    @AfterEach
    void forgetFocus() {
        StartupFocus.resetStageFocusForTest();
    }

    // --- D1: a folder that does not answer gets one listing, through any number of invalidations --------

    @Test
    void aHungFolderIsNotRespondingAtOnceAfterEveryInvalidationAndHoldsOneThread() throws Exception {
        Path root = root();
        Path slow = folderWithFiles(root, "slow", 2);
        folderWithFiles(root, "fine", 2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger slowListings = new AtomicInteger();
        Set<Path> expanded = Set.of(root, slow, root.resolve("fine"));
        try (ListingService listings = new ListingService(300, directory -> {
            if (directory.equals(slow)) {
                slowListings.incrementAndGet();
                awaitQuietly(release);
            }
        })) {
            listings.setOnRecovered(recovered::countDown);
            listings.setWatched(Set.of(root, slow, root.resolve("fine")));
            for (int load = 0; load < 4; load++) {
                Snapshot snapshot = ProjectMapModel.load(new Request(root, expanded, false), listings);
                assertNotNull(snapshot);
                assertEquals(
                        PlaceholderKind.NOT_RESPONDING,
                        childrenOf(snapshot, slow).getFirst().placeholder().kind(),
                        "load " + load);
                listings.invalidateAll(); // a focus regain or an in-app change, as before every load in the field
            }
            assertEquals(1, slowListings.get(), "one listing of the hung folder, whatever was invalidated");
            assertEquals(1, listings.waitedOutForTest.get(), "and one wait for it: the later loads knew at once");
            assertEquals(1, listings.runningForTest(), "one blocked thread, not one per invalidation");

            release.countDown();
            assertTrue(recovered.await(5, TimeUnit.SECONDS), "the view hears that the folder answered");
            Snapshot back = ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(2, childrenOf(back, slow).size(), "listed afresh: the answer predates the invalidations");
            assertEquals(2, slowListings.get());
        }
    }

    @Test
    void aLoadAfterAnInvalidationJoinsTheRunningListingAndListsAgainWhenItEnds() throws Exception {
        Path root = root();
        Path folder = folderWithFiles(root, "folder", 2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger listingsOfFolder = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        Set<Path> expanded = Set.of(root, folder);
        ExecutorService loads = Executors.newFixedThreadPool(2);
        try (ListingService listings = new ListingService(5_000, directory -> {
            if (directory.equals(folder)) {
                mostAtOnce.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                if (listingsOfFolder.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    awaitQuietly(releaseFirst);
                }
                concurrent.decrementAndGet();
            }
        })) {
            Future<Snapshot> before =
                    loads.submit(() -> ProjectMapModel.load(new Request(root, expanded, false), listings));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            // The listing is under way when a file arrives and the folder is invalidated (the watcher's event).
            Path added = Files.writeString(folder.resolve("added.txt"), "new");
            listings.invalidate(List.of(folder));
            Future<Snapshot> after =
                    loads.submit(() -> ProjectMapModel.load(new Request(root, expanded, false), listings));
            Thread.sleep(50); // let the second load reach the running listing (it may not; it must not list beside it)
            releaseFirst.countDown();
            assertNotNull(before.get(10, TimeUnit.SECONDS));
            Snapshot fresh = after.get(10, TimeUnit.SECONDS);
            assertTrue(
                    childrenOf(fresh, folder).stream()
                            .anyMatch(entry -> entry.path().equals(added)),
                    "the load after the invalidation never takes the earlier listing's answer");
            assertEquals(1, mostAtOnce.get(), "never two listings of one folder at once");
            assertEquals(2, listingsOfFolder.get(), "listed again once the earlier listing had ended");
        } finally {
            loads.shutdownNow();
        }
    }

    // --- D3: a real focus regain checks each cached folder's modification time instead of listing it ----

    @Test
    void aRegainKeepsTheWatchedFoldersThatDidNotChangeAndListsTheOneThatDid() throws Exception {
        Path root = root();
        Path quiet = folderWithFiles(root, "quiet", 3);
        Path busy = folderWithFiles(root, "busy", 3);
        FileTime longAgo = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1));
        for (Path folder : List.of(root, quiet, busy)) {
            Files.setLastModifiedTime(folder, longAgo); // settled: the listing comes well after the last change
        }
        Set<Path> expanded = Set.of(root, quiet, busy);
        try (ListingService listings = new ListingService(5_000, null)) {
            listings.setWatched(expanded);
            ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(3, listings.listedForTest.get());

            listings.revalidateAll(); // a regain: nothing changed meanwhile
            ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(3, listings.listedForTest.get(), "nothing is listed again");
            assertEquals(3, listings.confirmedForTest.get(), "each folder answered one attribute read");

            ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(3, listings.confirmedForTest.get(), "checked once per regain, then cached as before");

            Path added = Files.writeString(busy.resolve("from-a-terminal.txt"), "x"); // its watcher event missed
            listings.revalidateAll();
            Snapshot after = ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(4, listings.listedForTest.get(), "only the folder whose time moved is listed");
            assertTrue(childrenOf(after, busy).stream()
                    .anyMatch(entry -> entry.path().equals(added)));

            listings.invalidateAll(); // an in-app change still lists every folder
            ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(7, listings.listedForTest.get());
        }
    }

    @Test
    void aListingTakenInTheSameMomentAsTheFolderChangedIsNotTrustedOnARegain() throws Exception {
        Path root = root();
        Path fresh = folderWithFiles(root, "fresh", 2); // its time is now: a change in this tick could hide
        Set<Path> expanded = Set.of(root, fresh);
        Files.setLastModifiedTime(root, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        try (ListingService listings = new ListingService(5_000, null)) {
            listings.setWatched(expanded);
            ProjectMapModel.load(new Request(root, expanded, false), listings);
            listings.revalidateAll();
            ProjectMapModel.load(new Request(root, expanded, false), listings);
            assertEquals(3, listings.listedForTest.get(), "the unsettled folder is listed again, the root is not");
            assertEquals(1, listings.confirmedForTest.get());
        }
    }

    @Test
    void focusMovingBetweenEditorasWindowsIsNotARegainButAReturnThroughTheMapIs() {
        StartupFocus.resetStageFocusForTest();
        long[] now = {1_000_000_000L};
        StartupFocus.clock = () -> now[0];
        StartupFocus focus = new StartupFocus();
        focus.repositoryStateComputed();
        assertEquals(StartupFocus.Action.NONE, focus.onFocusGained(), "the show-time focus");

        StartupFocus.noteStageFocusLost(); // a file opened from the Map: the Map window gave focus up
        now[0] += TimeUnit.MILLISECONDS.toNanos(20);
        StartupFocus.noteStageFocusGained(); // the Map window, opened a moment before
        StartupFocus.noteStageFocusLost();
        now[0] += TimeUnit.MILLISECONDS.toNanos(20);
        assertEquals(StartupFocus.Action.NONE, focus.onFocusGained(), "the editor window raised from the Map");

        StartupFocus.noteStageFocusLost(); // to another program
        now[0] += TimeUnit.SECONDS.toNanos(30);
        assertEquals(StartupFocus.Action.REGAIN, focus.onFocusGained(), "back from another program");

        StartupFocus.noteStageFocusLost(); // to another program again…
        now[0] += TimeUnit.SECONDS.toNanos(30);
        StartupFocus.noteStageFocusGained(); // …and back through the Map window
        StartupFocus.noteStageFocusLost();
        now[0] += TimeUnit.MILLISECONDS.toNanos(20);
        assertEquals(StartupFocus.Action.REGAIN, focus.onFocusGained(), "the editor still refreshes, once");

        StartupFocus.noteStageFocusLost();
        now[0] += TimeUnit.MILLISECONDS.toNanos(20);
        assertEquals(StartupFocus.Action.NONE, focus.onFocusGained(), "and not again for the next move");
    }

    // --- A13: in a big folder of files only the rows that are shown have their attributes read ----------

    @Test
    void aBigFolderOfFilesReadsTheAttributesOfTheRowsItShowsOnly() throws Exception {
        Path root = root();
        Path corpus = folderWithFiles(root, "corpus", 700);
        String store = Files.getFileStore(corpus).type();
        assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux")
                        && Set.of("ext4", "tmpfs", "xfs").contains(store),
                "a file system that counts subfolders in a folder's link count");
        Set<Path> expanded = Set.of(root, corpus);
        try (ListingService listings = new ListingService(5_000, null)) {
            listings.setWatched(expanded);
            Snapshot snapshot = ProjectMapModel.load(new Request(root, expanded, false), listings);
            List<Entry> rows = childrenOf(snapshot, corpus).stream()
                    .filter(row -> !row.isPlaceholder())
                    .toList();
            assertEquals(ProjectMapModel.DIRECTORY_CHUNK, rows.size());
            assertTrue(rows.stream().allMatch(row -> row.size() >= 0), "a shown row has its size");
            assertEquals(
                    700 - ProjectMapModel.DIRECTORY_CHUNK,
                    listings.unreadForTest(corpus, false),
                    "the rows past the limit were never read");
        }
    }

    @Test
    void aBigFolderWithOneSubfolderIsReadInFullAndKeepsItsFolderFirst() throws Exception {
        Path root = root();
        Path corpus = folderWithFiles(root, "corpus", 400);
        Path sub = Files.createDirectory(corpus.resolve("zz-sub"));
        Set<Path> expanded = Set.of(root, corpus);
        try (ListingService listings = new ListingService(5_000, null)) {
            listings.setWatched(expanded);
            Snapshot snapshot = ProjectMapModel.load(new Request(root, expanded, false), listings);
            List<Entry> rows = childrenOf(snapshot, corpus);
            assertEquals(sub, rows.getFirst().path(), "the folder sorts first though its name sorts last");
            assertEquals(0, listings.unreadForTest(corpus, false));
        }
    }

    // --- A10: the folder being read keeps its rows when the overall limit is reached --------------------

    @Test
    void thePriorityFolderIsPaidBeforeTheShallowerOnes() throws Exception {
        Path root = root();
        List<Path> shallow = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            shallow.add(folderWithFiles(root, "s" + index, 300));
        }
        Path deep = Files.createDirectories(root.resolve("x/y"));
        Path big = folderWithFiles(deep, "big", 1_000);
        Set<Path> expanded = new java.util.HashSet<>(shallow);
        expanded.addAll(List.of(root, root.resolve("x"), deep, big));
        Map<Path, Integer> limits = new HashMap<>(Map.of(big, 900));

        Snapshot plain = ProjectMapModel.load(request(root, expanded, limits, List.of()));
        assertTrue(plain.capped());
        ProjectMapModel.DirectoryFacts left = plain.directories().get(big);
        assertTrue(left == null || left.loaded() < 900, "breadth-first, the deep folder gets what is left, if any");

        Snapshot first = ProjectMapModel.load(request(root, expanded, limits, List.of(big)));
        assertTrue(first.capped());
        assertEquals(900, first.directories().get(big).loaded(), "the folder being read keeps every raised row");
        assertTrue(
                childrenOf(first, deep).stream().anyMatch(entry -> entry.path().equals(big)));
        assertTrue(first.entries().size() <= ProjectMapModel.MAX_VISIBLE_ITEMS + 16, "still within the limit");
    }

    // --- A15: hash-named folders read in plain order --------------------------------------------------

    @Test
    void aFolderOfHashesSortsInPlainOrderAndOtherFoldersKeepNaturalOrder() {
        List<String> hashes = new ArrayList<>(List.of(
                "7d02d42f0c76ca82", "07c96f6b389dc56c", "7cd8c920ebe214f7", "07d2eb30c88dc1bf", "7c781cff7187cb9b"));
        hashes.sort(ProjectPathOrder.forFolder(hashes, name -> false, name -> name));
        assertEquals(
                List.of(
                        "07c96f6b389dc56c",
                        "07d2eb30c88dc1bf",
                        "7c781cff7187cb9b",
                        "7cd8c920ebe214f7",
                        "7d02d42f0c76ca82"),
                hashes);

        List<String> mixed = new ArrayList<>(List.of("note-10", "07c96f6b389dc56c", "note-2"));
        mixed.sort(ProjectPathOrder.forFolder(mixed, name -> false, name -> name));
        assertEquals(List.of("07c96f6b389dc56c", "note-2", "note-10"), mixed, "one name that is not a hash");

        List<String> numbers = new ArrayList<>(List.of("100000000", "99999999", "123456789"));
        numbers.sort(ProjectPathOrder.forFolder(numbers, name -> false, name -> name));
        assertEquals(List.of("99999999", "100000000", "123456789"), numbers, "plain numbers keep natural order");

        List<Path> tree = ProjectPathOrder.sorted(
                List.of(Path.of("7c781cff.bin"), Path.of("07c96f6b.bin"), Path.of("7d02d42f.bin")), path -> false);
        assertEquals(Path.of("07c96f6b.bin"), tree.getFirst(), "the Project tree takes the same order");
    }

    // --- D12: sizes are written as the interface's locale writes numbers ---------------------------

    @Test
    void sizesUseTheLocalesDecimalSeparator() throws Exception {
        Class<?> surface = Class.forName("com.editora.ui.ProjectMapView$MapSurface");
        java.lang.reflect.Method format = surface.getDeclaredMethod("formatSize", long.class, Locale.class);
        format.setAccessible(true);
        assertEquals("1,5 KiB", format.invoke(null, 1536L, Locale.GERMANY));
        assertEquals("1.5 KiB", format.invoke(null, 1536L, Locale.US));
        assertEquals("2,0 MiB", format.invoke(null, 2L << 20, Locale.FRANCE));
        assertEquals("512 B", format.invoke(null, 512L, Locale.GERMANY));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static Request request(Path root, Set<Path> expanded, Map<Path, Integer> limits, List<Path> priority) {
        return new Request(
                root,
                expanded,
                false,
                Map.of(),
                limits,
                List.of(),
                Labels.PLAIN,
                () -> false,
                Map.of(),
                false,
                priority);
    }

    private Path root() throws Exception {
        return Files.createDirectory(temp.resolve("root")).toRealPath();
    }

    private static Path folderWithFiles(Path parent, String name, int count) throws Exception {
        Path folder = Files.createDirectory(parent.resolve(name));
        for (int index = 0; index < count; index++) {
            Files.writeString(folder.resolve(String.format("f%04d.txt", index)), "x");
        }
        return folder;
    }

    private static List<Entry> childrenOf(Snapshot snapshot, Path parent) {
        return snapshot.entries().stream()
                .filter(entry -> parent.equals(entry.parent()))
                .toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

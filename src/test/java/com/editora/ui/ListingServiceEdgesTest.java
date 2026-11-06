package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Map's folder listings ({@link ListingService}) at their edges: a child that vanished before its attributes
 * were read, the names a listing leaves out and the links it follows, null folders handed to the watched set and to
 * invalidation, a listing that hangs (reported once, never listed twice, heard when it recovers), a wait given up
 * when its load is superseded, a listing invalidated while it ran, and a regain that confirms a settled listing by
 * its folder's time alone.
 */
class ListingServiceEdgesTest {

    @TempDir
    Path temp;

    @Test
    void aChildReadOnlyByNameThatVanishedReadsAsGoneAndARootIsNamedByItself() throws Exception {
        Path gone = temp.resolve("gone.txt");
        ListingService.Item item = ListingService.Item.unread(gone);
        assertFalse(item.attributesRead(), "not read yet");
        assertEquals(-1, item.size(), "gone: no size");
        assertEquals(-1, item.modifiedMillis());
        assertFalse(item.directory());
        assertFalse(item.symbolicLink());
        assertTrue(item.attributesRead(), "read once, and kept");
        assertEquals("gone.txt", item.name());
        Path root = temp.getRoot();
        assertEquals(root.toString(), ListingService.Item.unread(root).name(), "a path with no file name");

        Path present = Files.writeString(temp.resolve("here.txt"), "four");
        ListingService.Item read = ListingService.Item.unread(present);
        assertEquals(4, read.size());
        assertTrue(read.modifiedMillis() > 0);
        assertNull(ListingService.stat(gone));
    }

    @Test
    void aListingLeavesOutHiddenAndSaveTemporaryNamesAndFollowsALinkToAFolder() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("folder"));
        Files.writeString(folder.resolve(".hidden"), "h");
        Files.createDirectory(folder.resolve(".git"));
        Files.writeString(folder.resolve("page.md.editora-tmp"), "t");
        Files.writeString(folder.resolve(".editora-typst-123"), "t");
        Files.writeString(folder.resolve("visible.txt"), "v");
        Path target = Files.createDirectory(temp.resolve("target"));
        Path link = Files.createSymbolicLink(folder.resolve("link"), target);
        ListingService listings = new ListingService(0, null);

        List<String> names = names(listings.list(folder, false, () -> false));
        assertEquals(List.of("link", "visible.txt"), names, "the link sorts as the folder it leads to");
        assertEquals(
                List.of("link", ".hidden", "visible.txt"),
                names(listings.list(folder, true, () -> false)),
                "hidden names shown when asked; a save's temporary sibling and the repository's .git never");
        ListingService.Item linked = ListingService.stat(link);
        assertNotNull(linked);
        assertTrue(linked.directory(), "a link to a folder is a folder");
        assertTrue(linked.symbolicLink());
        assertTrue(linked.sortsAsDirectory());

        ListingService.Listed missing = listings.list(temp.resolve("absent"), false, () -> false);
        assertNull(missing.items(), "a folder that cannot be read");
        assertFalse(missing.timedOut());
    }

    @Test
    void nullsInTheWatchedSetOrAnInvalidationAreSkippedAndOnlyWatchedFoldersAreCached() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("watched"));
        Files.writeString(folder.resolve("a.txt"), "a");
        try (ListingService listings = new ListingService(0, null)) {
            listings.setWatched(null);
            listings.setOnRecovered(null);
            assertEquals(0, listings.runningForTest(), "no listing thread yet");
            assertEquals(-1, listings.unreadForTest(folder, false), "nothing cached");
            listings.list(folder, false, () -> false);
            assertFalse(listings.cached(folder, false), "an unwatched local folder is not cached");

            Set<Path> watched = new HashSet<>();
            watched.add(null);
            watched.add(folder);
            listings.setWatched(watched);
            listings.list(folder, false, () -> false);
            assertTrue(listings.cached(folder, false));
            assertEquals(0, listings.unreadForTest(folder, false), "every child of a small folder was read");
            assertTrue(listings.list(folder, false, () -> false).cached(), "reused");
            assertFalse(listings.cached(folder, true), "per show-hidden setting");

            List<Path> changed = new ArrayList<>();
            changed.add(null);
            changed.add(folder);
            listings.invalidate(changed);
            assertFalse(listings.cached(folder, false), "invalidated");
            assertFalse(listings.list(folder, false, () -> false).cached());

            listings.setWatched(Set.of());
            assertFalse(listings.cached(folder, false), "unwatched: dropped");
        }
    }

    @Test
    void aHungListingIsReportedOnceIsNeverListedTwiceAndIsHeardWhenItRecovers() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("slow"));
        Files.writeString(folder.resolve("a.txt"), "a");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger listings = new AtomicInteger();
        try (ListingService service = new ListingService(60, dir -> {
            listings.incrementAndGet();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })) {
            service.setOnRecovered(recovered::countDown);
            ListingService.Listed first = service.list(folder, false, () -> false);
            assertTrue(first.timedOut(), "not responding");
            assertEquals(1, service.waitedOutForTest.get());
            ListingService.Listed again = service.list(folder, false, () -> false);
            assertTrue(again.timedOut());
            assertEquals(1, service.waitedOutForTest.get(), "a folder still hung is reported at once, not waited on");
            service.invalidate(List.of(folder));
            assertTrue(service.list(folder, false, () -> false).timedOut(), "through an invalidation too");
            assertEquals(1, listings.get(), "and it never gets a second listing");
            assertEquals(1, service.runningForTest());

            release.countDown();
            assertTrue(recovered.await(10, TimeUnit.SECONDS), "its answer at last is heard");
            ListingService.Listed after = service.list(folder, false, () -> false);
            assertNotNull(after.items());
            assertEquals(List.of("a.txt"), names(after));
        }
    }

    @Test
    void aWaitIsGivenUpWhenItsLoadIsSupersededAndTheListingIsPickedUpNextTime() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("busy"));
        Files.writeString(folder.resolve("b.txt"), "b");
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean superseded = new AtomicBoolean();
        try (ListingService service = new ListingService(10_000, dir -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })) {
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    return;
                }
                superseded.set(true);
            });
            canceller.start();
            ListingService.Listed cancelled = service.list(folder, false, superseded::get);
            canceller.join();
            assertTrue(cancelled.cancelled(), "a newer load came: this one stops waiting");
            assertNull(cancelled.items());
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    return;
                }
                release.countDown();
            });
            releaser.start();
            ListingService.Listed next = service.list(folder, false, () -> false); // joins it while it still runs
            releaser.join();
            assertEquals(List.of("b.txt"), names(next));
            assertEquals(1, service.listedForTest.get(), "the running listing's answer, not a second listing");
        }
    }

    @Test
    void aLoadJoiningAListingBegunBeforeAnInvalidationListsAgainOnceItFinishes() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("changing"));
        Files.writeString(folder.resolve("old.txt"), "o");
        AtomicInteger listed = new AtomicInteger();
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ListingService service = new ListingService(10_000, dir -> {
            if (listed.incrementAndGet() == 1) {
                inFirst.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        })) {
            service.setWatched(Set.of(folder));
            List<ListingService.Listed> early = new java.util.concurrent.CopyOnWriteArrayList<>();
            Thread first = new Thread(() -> early.add(service.list(folder, false, () -> false)));
            first.start();
            assertTrue(inFirst.await(10, TimeUnit.SECONDS), "the first listing is under way");
            Files.writeString(folder.resolve("new.txt"), "n");
            service.invalidate(List.of(folder)); // the watcher's event, while that listing runs
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    return;
                }
                release.countDown();
            });
            releaser.start();
            ListingService.Listed joined = service.list(folder, false, () -> false); // joins the running one
            first.join();
            releaser.join();
            assertEquals(2, listed.get(), "the joining load listed again once the earlier listing finished");
            assertEquals(List.of("new.txt", "old.txt"), names(joined));
            assertEquals(1, early.size());
        }
    }

    @Test
    void aRegainConfirmsASettledListingByItsFoldersTimeAndAClosedServiceListsNothing() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("settled"));
        Files.writeString(folder.resolve("c.txt"), "c");
        long[] now = {System.currentTimeMillis() + 60_000}; // long after the folder last changed
        ListingService service = new ListingService(1_000, null, () -> now[0]);
        try {
            service.setWatched(Set.of(folder));
            service.list(folder, false, () -> false);
            assertEquals(1, service.listedForTest.get());
            service.revalidateAll();
            ListingService.Listed confirmed = service.list(folder, false, () -> false);
            assertTrue(confirmed.cached(), "nothing moved: the listing answered again");
            assertEquals(1, service.confirmedForTest.get());
            assertEquals(1, service.listedForTest.get(), "without listing");
        } finally {
            service.close();
        }
        ListingService.Listed closed = service.list(folder, false, () -> false);
        assertTrue(closed.cancelled(), "a closed service starts no listing");
        service.close(); // twice: nothing more
    }

    private static List<String> names(ListingService.Listed listed) {
        assertNotNull(listed.items());
        return listed.items().stream().map(ListingService.Item::name).toList();
    }
}

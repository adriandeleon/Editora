package com.editora.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which recent entries are still worth offering. Pure — the filesystem and the local/remote decision are
 * both injected, so none of this touches a disk or a network.
 */
class RecentFilesShowableTest {

    private static final Path GONE = Path.of("/tmp/deleted.txt");
    private static final Path HERE = Path.of("/tmp/present.txt");
    private static final Path REMOTE = Path.of("/remote/onahost.txt");

    private static final Predicate<Path> LOCAL = p -> !p.equals(REMOTE);
    private static final Predicate<Path> EXISTS = Set.of(HERE)::contains;

    @Test
    void keepsFilesThatAreStillThere() {
        assertEquals(List.of(HERE), RecentFiles.showable(List.of(HERE), LOCAL, EXISTS));
    }

    @Test
    void dropsFilesThatAreGone() {
        assertEquals(List.of(HERE), RecentFiles.showable(List.of(GONE, HERE), LOCAL, EXISTS));
    }

    @Test
    void preservesOrder() {
        assertEquals(List.of(HERE, REMOTE), RecentFiles.showable(List.of(HERE, GONE, REMOTE), LOCAL, EXISTS));
    }

    /**
     * A remote entry is kept without asking. Asking is a network round trip per entry, on the FX thread,
     * every time the menu is built — and it answers "gone" for a host that is merely asleep.
     */
    @Test
    void neverChecksRemoteEntries() {
        Predicate<Path> refuses = p -> {
            throw new AssertionError("existence was checked for a remote path: " + p);
        };
        assertEquals(List.of(REMOTE), RecentFiles.showable(List.of(REMOTE), LOCAL, refuses));
    }

    @Test
    void everythingGoneYieldsAnEmptyList() {
        assertTrue(RecentFiles.showable(List.of(GONE), LOCAL, EXISTS).isEmpty());
        assertTrue(RecentFiles.showable(List.of(), LOCAL, EXISTS).isEmpty());
    }

    /** Filtering is a view: the caller's list is never modified, so nothing is pruned from the store. */
    @Test
    void theInputListIsNotMutated() {
        List<Path> entries = new java.util.ArrayList<>(List.of(GONE, HERE));
        RecentFiles.showable(entries, LOCAL, EXISTS);
        assertEquals(List.of(GONE, HERE), entries, "a temporarily-unreachable file must not be pruned");
    }
}

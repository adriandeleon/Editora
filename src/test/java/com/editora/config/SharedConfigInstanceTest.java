package com.editora.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SharedConfig}'s view of "am I the only Editora on this config dir?" — the one answer both the
 * second-instance warning and the local-history GC gate are derived from.
 *
 * <p>The other process is played by {@link InstanceLockTestHooks}, which takes the locks a real one would.
 */
class SharedConfigInstanceTest {

    @Test
    void aConfigThatWasNeverClaimedIsItsOwnSoleUser(@TempDir Path dir) {
        SharedConfig shared = new SharedConfig(dir, false);
        try {
            assertTrue(shared.isPrimaryInstance(), "tests and embedders that never claim behave as before");
            assertTrue(shared.mayCollectHistoryBlobs());
        } finally {
            shared.shutdown();
        }
    }

    @Test
    void theFirstProcessIsThePrimaryAndMayCollect(@TempDir Path dir) {
        SharedConfig shared = new SharedConfig(dir, false);
        try {
            assertTrue(shared.claimInstance());
            assertTrue(shared.claimInstance(), "idempotent");
            assertTrue(shared.isPrimaryInstance());
            assertTrue(shared.mayCollectHistoryBlobs());
        } finally {
            shared.shutdown();
        }
    }

    @Test
    void aSecondProcessIsNotThePrimaryAndNeverCollects(@TempDir Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        try (var first = InstanceLockTestHooks.otherProcessIsPrimary(dir)) {
            assertFalse(shared.claimInstance());
            assertFalse(shared.isPrimaryInstance());
            assertFalse(shared.mayCollectHistoryBlobs(), "it would delete the first editor's revision bodies");

            first.close(); // the first editor quits
            assertFalse(shared.isPrimaryInstance(), "no promotion: its stores were loaded while another wrote");
            assertFalse(shared.mayCollectHistoryBlobs());
        } finally {
            shared.shutdown();
        }
    }

    @Test
    void thePrimaryHoldsOffCollectingWhileAnotherProcessIsRunning(@TempDir Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        try {
            assertTrue(shared.claimInstance());
            try (var second = InstanceLockTestHooks.otherProcessIsSecondary(dir)) {
                assertTrue(shared.isPrimaryInstance(), "still the primary");
                assertFalse(
                        shared.mayCollectHistoryBlobs(),
                        "the second editor's new revisions are not in this process's index");
            }
            assertTrue(shared.mayCollectHistoryBlobs(), "once it has exited, collection resumes");
        } finally {
            shared.shutdown();
        }
    }

    @Test
    void shuttingDownReleasesTheClaimSoTheNextLaunchIsThePrimary(@TempDir Path dir) {
        SharedConfig first = new SharedConfig(dir, false);
        assertTrue(first.claimInstance());
        first.shutdown();

        SharedConfig next = new SharedConfig(dir, false);
        try {
            assertTrue(next.claimInstance());
        } finally {
            next.shutdown();
        }
    }
}

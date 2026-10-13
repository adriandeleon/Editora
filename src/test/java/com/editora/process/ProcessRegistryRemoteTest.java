package com.editora.process;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A child on another machine has no {@link ProcessHandle}; the registry must stop it through its target. */
class ProcessRegistryRemoteTest {

    @Test
    void aRemoteProcessHasNoLocalIdentity() {
        FakeRemoteProcess p = new FakeRemoteProcess(true);
        assertThrows(UnsupportedOperationException.class, p::pid);
        assertThrows(UnsupportedOperationException.class, p::toHandle);
        p.exit(0);
    }

    @Test
    void trackingAndUntrackingNeverAskForAPid() {
        FakeRemoteProcess p = new FakeRemoteProcess(true);
        ProcessRegistry.track(p);
        ProcessRegistry.expectExit(p);
        ProcessRegistry.untrack(p);
        p.exit(0);
    }

    @Test
    void aPoliteKillIsEnoughForAProcessThatListens() throws Exception {
        FakeRemoteProcess p = new FakeRemoteProcess(true);
        ProcessRegistry.track(p);

        ProcessRegistry.killTree(p);

        assertTrue(p.waitFor(1, TimeUnit.SECONDS));
        Thread.sleep(ProcessRegistry.GRACE_MS + 300);
        assertEquals(List.of(false), p.signals, "already gone at the grace period: no second signal");
    }

    @Test
    void aProcessThatIgnoresThePoliteKillIsForcedAfterTheGracePeriod() throws Exception {
        FakeRemoteProcess p = new FakeRemoteProcess(false);
        ProcessRegistry.track(p);

        ProcessRegistry.killTree(p);

        assertEquals(List.of(false), p.signals);
        assertFalse(p.waitFor(200, TimeUnit.MILLISECONDS), "killTree does not block or force at once");
        assertTrue(p.waitFor(ProcessRegistry.GRACE_MS + 2000, TimeUnit.MILLISECONDS));
        assertEquals(List.of(false, true), p.signals);
    }

    @Test
    void destroyGoesThroughTheTargetToo() {
        FakeRemoteProcess p = new FakeRemoteProcess(false);
        p.destroy();
        p.destroyForcibly();
        assertEquals(List.of(false, true), p.signals);
    }
}

package com.editora.ipc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-instance handoff: the wire format, and the claim/forward/stale decisions end to end over a real
 * loopback socket (no GUI — {@link SingleInstance} deliberately knows nothing about windows).
 */
class SingleInstanceTest {

    private static final List<String> ARGS = List.of("--expert", "/tmp/a file.txt");

    // --- wire format -------------------------------------------------------------------------------

    @Test
    void aRequestRoundTripsThroughTheWireFormat() {
        String line = SingleInstance.buildRequest("tok", ARGS);
        assertEquals(ARGS, SingleInstance.parseRequest(line, "tok"));
    }

    @Test
    void argumentsSurviveSpacesNewlinesAndUnicode() {
        // A path is user data: re-splitting it on the far side would open the wrong file, or none.
        List<String> nasty = List.of("/tmp/two words.txt", "/tmp/with\nnewline.md", "/tmp/ünïcode ✓.txt");
        assertEquals(nasty, SingleInstance.parseRequest(SingleInstance.buildRequest("tok", nasty), "tok"));
    }

    @Test
    void aRequestWithTheWrongTokenIsRejected() {
        assertNull(SingleInstance.parseRequest(SingleInstance.buildRequest("other", ARGS), "tok"));
    }

    @Test
    void malformedRequestsAreRejectedRatherThanPartiallyApplied() {
        assertNull(SingleInstance.parseRequest(null, "tok"));
        assertNull(SingleInstance.parseRequest("", "tok"));
        assertNull(SingleInstance.parseRequest("NOT-EDITORA tok 6162", "tok"));
        assertNull(SingleInstance.parseRequest(SingleInstance.MAGIC + " tok zzzz", "tok")); // not hex
        assertNull(SingleInstance.parseRequest(SingleInstance.MAGIC, "tok")); // no token at all
    }

    @Test
    void anEmptyExpectedTokenNeverAuthenticates() {
        // Guards the degenerate case where a corrupt endpoint yields a blank token: it must not become a
        // wildcard that lets any local process drive the editor.
        assertNull(SingleInstance.parseRequest(SingleInstance.MAGIC + "  6162", ""));
        assertNull(SingleInstance.parseRequest(SingleInstance.MAGIC + "  6162", null));
    }

    // --- claim / forward ---------------------------------------------------------------------------
    //
    // Claiming the endpoint is asynchronous (it drags java.net + security class loading off the startup
    // path), so these await it explicitly rather than sleeping.

    @Test
    void theFirstLaunchBecomesPrimaryAndPublishesAnEndpoint(@TempDir Path dir) throws Exception {
        SingleInstance.Result first = SingleInstance.start(dir, ARGS, true);
        try {
            assertEquals(SingleInstance.Role.PRIMARY, first.role());
            assertNotNull(first.instance());
            awaitServing(first);
            assertTrue(Files.isRegularFile(dir.resolve(SingleInstance.ENDPOINT_FILE)));
        } finally {
            close(first);
        }
    }

    @Test
    void aSecondLaunchIsForwardedToTheFirstRatherThanStartingAnother(@TempDir Path dir) throws Exception {
        SingleInstance.Result primary = SingleInstance.start(dir, List.of(), true);
        try {
            awaitServing(primary);
            CopyOnWriteArrayList<List<String>> received = new CopyOnWriteArrayList<>();
            primary.instance().setListener(received::add);

            SingleInstance.Result second = SingleInstance.start(dir, ARGS, true);
            assertEquals(SingleInstance.Role.FORWARDED, second.role());
            assertTrue(second.forwarded());
            assertNull(second.instance(), "a forwarded launch owns nothing to clean up");

            assertTrue(waitFor(() -> !received.isEmpty()), "the primary never received the launch");
            assertEquals(ARGS, received.get(0));
        } finally {
            close(primary);
        }
    }

    @Test
    void aLaunchArrivingBeforeTheUiIsReadyIsBufferedNotDropped(@TempDir Path dir) throws Exception {
        // A click can land while the primary is still building its window — the case that made the macOS
        // handler buffer its cold-launch event too.
        SingleInstance.Result primary = SingleInstance.start(dir, List.of(), true);
        try {
            awaitServing(primary);
            SingleInstance.Result second = SingleInstance.start(dir, ARGS, true);
            assertEquals(SingleInstance.Role.FORWARDED, second.role());

            CopyOnWriteArrayList<List<String>> received = new CopyOnWriteArrayList<>();
            // Listener installed only now, after the request has already been delivered.
            primary.instance().setListener(received::add);
            assertTrue(waitFor(() -> !received.isEmpty()), "a launch delivered before the UI existed was lost");
            assertEquals(ARGS, received.get(0));
        } finally {
            close(primary);
        }
    }

    @Test
    void anExplicitlyNewInstanceDoesNotForwardAndLeavesThePrimaryServing(@TempDir Path dir) throws Exception {
        SingleInstance.Result primary = SingleInstance.start(dir, List.of(), true);
        try {
            awaitServing(primary);
            // allowForward=false is App.shouldForwardLaunch saying no (e.g. --new-instance).
            SingleInstance.Result second = SingleInstance.start(dir, ARGS, false);
            assertEquals(SingleInstance.Role.STANDALONE, second.role());
            assertNull(second.instance(), "a live primary must not be evicted by a launch that declined to talk");
            assertTrue(primary.instance().serving(), "the original must still own the endpoint");
        } finally {
            close(primary);
        }
    }

    @Test
    void aStaleEndpointFromACrashIsReapedAndTheLaunchBecomesPrimary(@TempDir Path dir) throws Exception {
        // A crash leaves the file advertising a port nobody is listening on. Port 1 is reserved and will not
        // be bound on a test machine, so this models "the recorded port is dead".
        Files.writeString(
                dir.resolve(SingleInstance.ENDPOINT_FILE),
                "magic=" + SingleInstance.MAGIC + "\nport=1\ntoken=dead\npid=1\n");

        SingleInstance.Result result = SingleInstance.start(dir, ARGS, true);
        try {
            assertEquals(SingleInstance.Role.PRIMARY, result.role(), "a dead endpoint must not strand the launch");
            awaitServing(result);
        } finally {
            close(result);
        }
    }

    @Test
    void aCorruptEndpointDoesNotDisableHandoffForever(@TempDir Path dir) throws Exception {
        // A truncated write (disk full, a crash mid-publish) leaves a file naming no port. The claim moves
        // the new endpoint into place *without* REPLACE_EXISTING — that is what settles the startup race —
        // so an unusable leftover has to be reaped, or single-instance would stay off from then on, silently
        // and permanently. This failed on the first run of this test, which is why it is here.
        Files.writeString(dir.resolve(SingleInstance.ENDPOINT_FILE), "not a properties file at all ");

        SingleInstance.Result result = SingleInstance.start(dir, ARGS, true);
        try {
            assertEquals(SingleInstance.Role.PRIMARY, result.role());
            awaitServing(result);
        } finally {
            close(result);
        }
    }

    @Test
    void closingThePrimaryRemovesTheEndpointSoTheNextLaunchClaimsIt(@TempDir Path dir) throws Exception {
        SingleInstance.Result first = SingleInstance.start(dir, List.of(), true);
        awaitServing(first);
        first.instance().close();
        assertFalse(Files.exists(dir.resolve(SingleInstance.ENDPOINT_FILE)));

        SingleInstance.Result second = SingleInstance.start(dir, ARGS, true);
        try {
            assertEquals(SingleInstance.Role.PRIMARY, second.role());
            awaitServing(second);
        } finally {
            close(second);
        }
    }

    @Test
    void instancesInDifferentConfigDirsNeverSeeEachOther(@TempDir Path a, @TempDir Path b) throws Exception {
        // --dev and --config-dir scope an instance purely by living in different directories; a dev launch
        // handing off to the production editor would be the worst failure this could have.
        SingleInstance.Result prod = SingleInstance.start(a, List.of(), true);
        SingleInstance.Result dev = SingleInstance.start(b, ARGS, true);
        try {
            assertEquals(SingleInstance.Role.PRIMARY, prod.role());
            assertEquals(SingleInstance.Role.PRIMARY, dev.role(), "a separate config dir is a separate instance");
            awaitServing(prod);
            awaitServing(dev);
        } finally {
            close(prod);
            close(dev);
        }
    }

    /** Waits for the asynchronous claim to land and asserts this process ended up owning the endpoint. */
    private static void awaitServing(SingleInstance.Result r) throws InterruptedException {
        assertNotNull(r.instance());
        assertTrue(r.instance().awaitClaim(5000), "the claim never completed");
        assertTrue(r.instance().serving(), "the claim completed but did not acquire the endpoint");
    }

    private static void close(SingleInstance.Result r) {
        if (r != null && r.instance() != null) {
            r.instance().close();
        }
    }

    /** Polls a condition for up to ~2 s (the delivery hop is another thread). */
    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return false;
    }
}

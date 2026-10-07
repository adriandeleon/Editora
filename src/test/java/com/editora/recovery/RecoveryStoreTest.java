package com.editora.recovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryStoreTest {

    @TempDir
    Path config;

    private static RecoveryRecord record(String id, String path, String text) {
        return RecoveryCodecTest.record(id, path, text);
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }

    @Test
    void nothingIsCreatedUntilSomethingIsWritten() {
        try (RecoveryStore store = new RecoveryStore(config)) {
            assertEquals(RecoveryStore.Orphans.NONE, store.claimOrphans());
            assertFalse(Files.exists(config.resolve(RecoveryStore.DIR_NAME)));
        }
        assertFalse(Files.exists(config.resolve(RecoveryStore.DIR_NAME)));
    }

    @Test
    void writeReplaceRemove() throws IOException {
        try (RecoveryStore store = new RecoveryStore(config)) {
            store.write(record("a", "/x/a.txt", "one"));
            store.write(record("b", null, "untitled text"));
            assertEquals(List.of("a", "b"), store.ownRecordIds());
            Path a = store.sessionDir().resolve("a.rec");
            assertEquals(
                    "one", RecoveryCodec.decode(Files.readAllBytes(a), true).text());

            store.write(record("a", "/x/a.txt", "two, and longer"));
            assertEquals(
                    "two, and longer",
                    RecoveryCodec.decode(Files.readAllBytes(a), true).text());
            assertTrue(
                    files(store.sessionDir()).stream()
                            .noneMatch(f -> f.toString().endsWith(".tmp")),
                    "no temporary file is left behind");

            store.remove("a");
            assertEquals(List.of("b"), store.ownRecordIds());
            store.remove("a"); // a second removal is not an error
            store.remove("never-written");
        }
    }

    @Test
    void aBufferIdThatIsNotAFileNameIsRefused() {
        try (RecoveryStore store = new RecoveryStore(config)) {
            assertThrows(IllegalArgumentException.class, () -> store.write(record("../escape", null, "x")));
        }
    }

    @Test
    void aNormalCloseWithNoRecordsLeavesNothingBehind() throws IOException {
        RecoveryStore store = new RecoveryStore(config);
        store.write(record("a", null, "x"));
        store.remove("a");
        store.close();
        assertFalse(Files.exists(config.resolve(RecoveryStore.DIR_NAME)), "session and recovery directories removed");
    }

    @Test
    void aCloseWithRecordsKeepsThemForTheNextLaunch() throws IOException {
        RecoveryStore store = new RecoveryStore(config);
        store.write(record("a", null, "still unsaved"));
        store.close(); // e.g. a window torn down without the user being asked
        try (RecoveryStore next = new RecoveryStore(config)) {
            RecoveryStore.Orphans found = next.claimOrphans();
            assertEquals(1, found.entries().size());
            assertEquals("still unsaved", next.read(found.entries().get(0)).text());
        }
    }

    /** A running editor's records belong to it: another process neither lists nor deletes them. */
    @Test
    void aLiveSessionIsInvisibleToAnotherStore() throws IOException {
        try (RecoveryStore live = new RecoveryStore(config);
                RecoveryStore other = new RecoveryStore(config)) {
            live.write(record("a", "/x/a.txt", "being edited right now"));
            other.write(record("b", null, "the other editor's"));

            assertTrue(other.claimOrphans().entries().isEmpty());
            assertTrue(live.claimOrphans().entries().isEmpty());
            assertEquals(List.of("a"), live.ownRecordIds());
            assertEquals(List.of("b"), other.ownRecordIds());

            // The same buffer id in two sessions is two records.
            other.write(record("a", null, "same id, other session"));
            assertEquals(
                    "being edited right now",
                    RecoveryCodec.decode(Files.readAllBytes(live.sessionDir().resolve("a.rec")), true)
                            .text());
            other.remove("a");
            assertEquals(List.of("a"), live.ownRecordIds());
        }
    }

    @Test
    void aDeadSessionIsOfferedOnceAndOnlyToOneClaimer() throws IOException {
        RecoveryStore dead = new RecoveryStore(config);
        dead.write(record("a", "/x/a.txt", "text a"));
        dead.write(record("b", null, "text b"));
        Path deadDir = dead.sessionDir();
        dead.abandon(); // the process died: locks gone, files stay

        try (RecoveryStore first = new RecoveryStore(config);
                RecoveryStore second = new RecoveryStore(config)) {
            RecoveryStore.Orphans found = first.claimOrphans();
            assertEquals(2, found.entries().size());
            assertTrue(second.claimOrphans().entries().isEmpty(), "already taken over by the first");
            assertEquals(2, first.claimOrphans().entries().size(), "the claimer can list again");

            RecoveryStore.Entry a = found.entries().stream()
                    .filter(e -> e.record().bufferId().equals("a"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("text a", first.read(a).text());
            assertEquals(RecoveryStore.DiskState.MISSING, a.disk());

            assertThrows(IOException.class, () -> second.discard(a), "not the claimer's to delete");
            assertTrue(Files.exists(a.file()));

            first.discard(a);
            assertFalse(Files.exists(a.file()));
            assertTrue(Files.isDirectory(deadDir), "the other record is still there");
            first.discard(first.claimOrphans().entries().get(0));
            assertFalse(Files.exists(deadDir), "an emptied dead session is removed");
        }
    }

    @Test
    void anUnansweredOfferIsOfferedAgainByTheNextLaunch() throws IOException {
        RecoveryStore dead = new RecoveryStore(config);
        dead.write(record("a", null, "text"));
        dead.abandon();
        RecoveryStore undecided = new RecoveryStore(config);
        assertEquals(1, undecided.claimOrphans().entries().size());
        undecided.close(); // the user never answered
        try (RecoveryStore next = new RecoveryStore(config)) {
            assertEquals(1, next.claimOrphans().entries().size());
        }
    }

    /** A record that does not read back whole is never offered and never deleted. */
    @Test
    void aTornOrUnreadableRecordIsIgnoredAndPreserved() throws IOException {
        RecoveryStore dead = new RecoveryStore(config);
        dead.write(record("good", null, "intact"));
        dead.write(record("torn", null, "this one will be cut short"));
        dead.write(record("junk", null, "x"));
        Path dir = dead.sessionDir();
        dead.abandon();
        byte[] whole = Files.readAllBytes(dir.resolve("torn.rec"));
        byte[] torn = Arrays.copyOf(whole, whole.length - 5);
        Files.write(dir.resolve("torn.rec"), torn);
        Files.writeString(dir.resolve("junk.rec"), "not a record at all");
        Files.writeString(dir.resolve("half.rec.tmp"), "a write that never completed");

        try (RecoveryStore next = new RecoveryStore(config)) {
            RecoveryStore.Orphans found = next.claimOrphans();
            assertEquals(1, found.entries().size());
            assertEquals("good", found.entries().get(0).record().bufferId());
            assertEquals(List.of(dir.resolve("junk.rec"), dir.resolve("torn.rec")), found.unreadable());
            assertFalse(Files.exists(dir.resolve("half.rec.tmp")), "an uncommitted temp file is not a record");

            next.discard(found.entries().get(0));
            assertArrayEquals(torn, Files.readAllBytes(dir.resolve("torn.rec")), "left exactly as found");
            assertEquals("not a record at all", Files.readString(dir.resolve("junk.rec")));
        }
        assertTrue(Files.exists(dir.resolve("torn.rec")), "still there after a normal close");
        assertTrue(Files.exists(dir.resolve("junk.rec")));
    }

    @Test
    void anEmptyDeadSessionIsTidiedAway() throws IOException {
        RecoveryStore dead = new RecoveryStore(config);
        dead.write(record("a", null, "x"));
        dead.remove("a");
        Path dir = dead.sessionDir();
        dead.abandon();
        assertTrue(Files.isDirectory(dir));
        try (RecoveryStore next = new RecoveryStore(config)) {
            assertTrue(next.claimOrphans().entries().isEmpty());
            assertFalse(Files.exists(dir));
        }
    }

    @Test
    void theDiskStateSaysWhetherTheFileChangedUnderTheEdits(@TempDir Path work) throws Exception {
        Path file = work.resolve("doc.txt");
        Files.writeString(file, "on disk");
        long mtime = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);
        String hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest("on disk".getBytes(StandardCharsets.UTF_8)));

        assertEquals(
                RecoveryStore.DiskState.UNCHANGED, RecoveryStore.diskState(based(file.toString(), mtime, size, hash)));
        assertEquals(RecoveryStore.DiskState.NO_FILE, RecoveryStore.diskState(based(null, mtime, size, hash)));
        assertEquals(RecoveryStore.DiskState.UNKNOWN, RecoveryStore.diskState(based(file.toString(), -1, -1, null)));
        assertEquals(
                RecoveryStore.DiskState.REMOTE,
                RecoveryStore.diskState(based("sftp://host/home/u/doc.txt", mtime, size, hash)));

        // Touched but byte-identical: not a change.
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime + 60_000));
        assertEquals(
                RecoveryStore.DiskState.UNCHANGED, RecoveryStore.diskState(based(file.toString(), mtime, size, hash)));
        assertEquals(
                RecoveryStore.DiskState.CHANGED,
                RecoveryStore.diskState(based(file.toString(), mtime, size, null)),
                "without a fingerprint the metadata decides");

        Files.writeString(file, "ON DISK"); // same size, different bytes
        assertEquals(
                RecoveryStore.DiskState.CHANGED, RecoveryStore.diskState(based(file.toString(), mtime, size, hash)));
        Files.writeString(file, "longer on disk now");
        assertEquals(
                RecoveryStore.DiskState.CHANGED, RecoveryStore.diskState(based(file.toString(), mtime, size, hash)));

        Files.delete(file);
        assertEquals(
                RecoveryStore.DiskState.MISSING, RecoveryStore.diskState(based(file.toString(), mtime, size, hash)));
        assertEquals(
                RecoveryStore.DiskState.UNCHANGED,
                RecoveryStore.diskState(based(file.toString(), 0, 0, hash)),
                "a file that did not exist when the edits were made, and still does not");
    }

    private static RecoveryRecord based(String path, long mtime, long size, String fingerprint) {
        return new RecoveryRecord(
                "b", path, "doc.txt", null, "utf-8", true, "LF", mtime, size, fingerprint, 1, 0, "", null);
    }
}

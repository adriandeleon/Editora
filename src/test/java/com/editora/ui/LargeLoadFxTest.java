package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disk side of a load does the O(file) work — hashing, splitting into paragraphs — and decides whether the
 * document fits the heap, so the FX thread is left with the insertion alone and a file too large for what
 * is free opens as a read-only slice instead of exhausting the heap.
 */
@Tag("fx")
class LargeLoadFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void thePreparedLoadCarriesTheFingerprintTheBufferRecords(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            byte[] bytes = "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8);
            Path file = Files.write(dir.resolve("hashed.txt"), bytes);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");

            // Prepared here, off the FX thread, exactly as the read worker does.
            FileWorkflowCoordinator.PreparedLoad load = workflows.prepareLoad(file, false);
            assertEquals(
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    load.fingerprint());

            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setPath(file);
                return b;
            });
            FileWorkflowCoordinator.PreparedLoad prepared = load.preparedFor(buffer);
            assertNotNull(prepared.document(), "paragraphs are built with the read, not on the FX thread");
            assertSame(prepared, prepared.preparedFor(buffer));
            try {
                FxTestSupport.callOnFx(() -> workflows.applyPreparedLoad(buffer, prepared));
                assertSame(
                        load.fingerprint(),
                        FxTestSupport.callOnFx(() -> buffer.diskSnapshot().fingerprint()));
                assertSame(prepared.document().text(), FxTestSupport.callOnFx(buffer::getContent));
                assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            } finally {
                FxTestSupport.runOnFx(buffer::dispose);
            }
        }
    }

    @Test
    void aLargeFileThatDoesNotFitTheFreeHeapOpensAsAReadOnlySlice(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            String line = "0123456789 0123456789 0123456789 0123456789 0123456789 0123456789\n";
            int size = 5 * 1024 * 1024 + 4096;
            Path file = Files.writeString(dir.resolve("big.txt"), line.repeat(size / line.length() + 1));
            long bytes = Files.size(file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            long max = 2048L << 20;

            workflows.heapUsage = () -> new long[] {max, 64L << 20};
            FileWorkflowCoordinator.PreparedLoad roomy = workflows.prepareLoad(file, false);
            assertFalse(roomy.truncated());
            assertEquals(bytes, roomy.content().length());

            // 150 MB free: less than the characters of a 5 MB file need once the margin is set aside, so the file
            // is read only in part, before its lines are ever counted.
            long used = max - (150L << 20);
            workflows.heapUsage = () -> new long[] {max, used};
            FileWorkflowCoordinator.PreparedLoad tight = workflows.prepareLoad(file, false);
            assertTrue(tight.truncated(), "opened through the capped path instead of risking the heap");
            assertTrue(tight.large());
            assertEquals(
                    LoadHeapGuard.cap(bytes, LoadHeapGuard.cost(bytes, 0, false), max, used),
                    tight.content().length());
            assertNull(tight.sourceBytes(), "a slice has no whole-file bytes to compare a later save with");

            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setPath(file);
                return b;
            });
            try {
                String note = FxTestSupport.callOnFx(() -> workflows.applyPreparedLoad(buffer, tight));
                assertTrue(note.contains("read-only"), note);
                assertTrue(FxTestSupport.callOnFx(buffer::isReadOnly));
                assertTrue(FxTestSupport.callOnFx(buffer::isTruncatedLoad), "so a save can never truncate the file");
            } finally {
                FxTestSupport.runOnFx(buffer::dispose);
            }

            // A small file is never capped, whatever the heap looks like.
            Path small = Files.writeString(dir.resolve("small.txt"), line.repeat(100));
            assertFalse(workflows.prepareLoad(small, false).truncated());
        }
    }

    /**
     * Short lines cost more than their characters: a 2 MB file of 8-character lines is cheap to read but its
     * 250,000 paragraphs are not, so once its lines are counted it too opens as the part that fits.
     */
    @Test
    void aFileWhoseLinesDoNotFitOpensAsAReadOnlySlice(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("short.json"), "  1234,\n".repeat(250_000));
            long chars = Files.size(file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            long max = 2048L << 20;
            long cost = LoadHeapGuard.cost(chars, 250_001, false);
            assertTrue(LoadHeapGuard.cost(chars, 0, false) < cost / 4, "the characters alone are cheap");
            long used = max - LoadHeapGuard.MARGIN_BYTES - cost / 2; // room for about half the document
            workflows.heapUsage = () -> new long[] {max, used};

            FileWorkflowCoordinator.PreparedLoad load = workflows.prepareLoad(file, false);
            assertTrue(load.truncated(), "opened through the capped path once the lines were counted");
            assertTrue(load.large());
            assertTrue(load.content().length() < chars, "only part of it");
            assertTrue(load.content().length() > LoadHeapGuard.MIN_CAP_BYTES, "but as much as fits");
            assertNull(load.sourceBytes());

            workflows.heapUsage = () -> new long[] {max, 64L << 20};
            assertFalse(workflows.prepareLoad(file, false).truncated(), "and the whole of it when there is room");
        }
    }
}

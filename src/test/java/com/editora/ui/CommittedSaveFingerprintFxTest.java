package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A save's commit record identifies what was written by hash, not by keeping the bytes: one encoded copy of
 * every saved open file used to stay in memory, and the FX-thread acknowledgement re-hashed it after every
 * save.
 */
@Tag("fx")
class CommittedSaveFingerprintFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theCommitRecordKeepsAFingerprintThatTheBufferAdoptsAsIs(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("saved.txt"), "before\n");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setPath(file);
                workflows.loadInto(b, file);
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
                b.getArea().appendText("after — with a non-ASCII dash\n");
                assertTrue(workflows.save(b));
                return b;
            });
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer) || buffer.isDirty())) {
                assertTrue(System.nanoTime() < deadline, "save never completed");
                Thread.sleep(5);
                FxTestSupport.drainFx();
            }

            byte[] onDisk = Files.readAllBytes(file);
            String expected = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(onDisk));
            Map<String, ?> committed = FxTestSupport.field(workflows, "committedSaves");
            assertEquals(1, committed.size());
            Record commit = (Record) committed.values().iterator().next();
            Object fingerprint = null;
            for (var component : commit.getClass().getRecordComponents()) {
                assertFalse(component.getType().isArray(), "no copy of the written bytes: " + component.getName());
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                if (component.getName().equals("fingerprint")) {
                    fingerprint = accessor.invoke(commit);
                } else if (component.getName().equals("length")) {
                    assertEquals((long) onDisk.length, accessor.invoke(commit));
                }
            }
            assertEquals(expected, fingerprint);
            assertSame(
                    fingerprint,
                    FxTestSupport.callOnFx(() -> buffer.diskSnapshot().fingerprint()),
                    "the acknowledgement adopts the worker's hash instead of hashing the file again on FX");
        }
    }
}

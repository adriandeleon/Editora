package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.editora.config.AgentSessionHistory;
import com.editora.editor.EditorBuffer;
import com.editora.io.AtomicFileWrite;
import com.editora.io.DelegatingFileOperations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Data-safety contracts for the ACP filesystem bridge. */
@Tag("fx")
class AgentFileWriteFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void openBufferEditCanUndoBackToTheUsersUnsavedTextWithoutTouchingDisk(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("open.txt"), "saved copy\n");
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer created = new EditorBuffer();
                created.setPath(file);
                created.setContent("saved copy\n");
                created.markClean();
                created.replaceWholeDocument("unsaved user edit\n");
                created.getArea().getUndoManager().forgetHistory();
                return created;
            });
            OpsStub ops = new OpsStub();
            ops.openBuffer = buffer;
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);
            async.onClose(() -> FxTestSupport.runOnFx(buffer::dispose));

            // The agent has to have seen the unsaved text it replaces (an unread dirty buffer is refused).
            assertEquals("unsaved user edit\n", coordinator.readTextFile(file.toString(), null, null));
            coordinator.writeTextFile(file.toString(), "agent edit\n");

            assertEquals("agent edit\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("saved copy\n", Files.readString(file), "an open buffer remains review-first until Save");
            assertEquals(0, ops.refreshes.get());
            assertNull(ops.backgroundOpen.get());

            FxTestSupport.runOnFx(buffer.getArea()::undo);
            assertEquals("unsaved user edit\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(FxTestSupport.callOnFx(buffer.getArea()::isUndoAvailable));
        }
    }

    @Test
    void closedFileWriteIsAtomicAndOpenedForReview(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("closed.txt"), "original\n");
            OpsStub ops = new OpsStub();
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);

            coordinator.writeTextFile(file.toString(), "agent replacement\n");
            async.awaitFx();

            assertEquals("agent replacement\n", Files.readString(file));
            assertEquals(1, ops.refreshes.get());
            assertEquals(file, ops.backgroundOpen.get());
            try (var entries = Files.list(dir)) {
                assertEquals(1, entries.count(), "the atomic staging file is gone");
            }
        }
    }

    @Test
    void readOnlyOpenBufferCannotBeBypassedByAnAgentWrite(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("read-only.txt"), "protected\n");
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer created = new EditorBuffer();
                created.setPath(file);
                created.setContent("protected\n");
                created.markClean();
                created.setViewMode(true);
                return created;
            });
            OpsStub ops = new OpsStub();
            ops.openBuffer = buffer;
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);
            async.onClose(() -> FxTestSupport.runOnFx(buffer::dispose));

            IOException failure = assertThrows(
                    IOException.class, () -> coordinator.writeTextFile(file.toString(), "agent replacement\n"));

            assertTrue(failure.getMessage().contains("read-only"));
            assertEquals("protected\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("protected\n", Files.readString(file));
            assertEquals(0, ops.refreshes.get());
            assertNull(ops.backgroundOpen.get());
        }
    }

    @Test
    void failedClosedFileWritePreservesTheOriginalAndDoesNotReportAVisibleEdit(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("valuable.txt"), "precious original\n");
            AtomicFileWrite.FileOperations failingFiles = new DelegatingFileOperations() {
                @Override
                public void write(Path target, byte[] bytes) throws IOException {
                    assertFalse(target.equals(file), "ACP must never stream replacement bytes into the original");
                    Files.write(target, java.util.Arrays.copyOf(bytes, 3));
                    throw new IOException("simulated interrupted ACP write");
                }
            };
            OpsStub ops = new OpsStub();
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops, failingFiles);
            async.onClose(coordinator::shutdown);

            IOException failure = assertThrows(
                    IOException.class, () -> coordinator.writeTextFile(file.toString(), "agent replacement\n"));
            async.awaitFx();

            assertEquals("simulated interrupted ACP write", failure.getMessage());
            assertEquals("precious original\n", Files.readString(file));
            assertEquals(0, ops.refreshes.get());
            assertNull(ops.backgroundOpen.get());
            try (var entries = Files.list(dir)) {
                assertEquals(1, entries.count(), "failed ACP staging is cleaned up");
            }
        }
    }

    private static EditorBuffer openBuffer(Path file, String saved, String unsaved) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(saved);
            created.markClean();
            if (unsaved != null) {
                created.replaceWholeDocument(unsaved);
            }
            return created;
        });
    }

    @Test
    void aWriteComputedFromAnOlderReadDoesNotReplaceWhatTheUserTypedSince(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("typing.txt"), "one\n");
            EditorBuffer buffer = openBuffer(file, "one\n", null);
            OpsStub ops = new OpsStub();
            ops.openBuffer = buffer;
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);
            async.onClose(() -> FxTestSupport.runOnFx(buffer::dispose));

            assertEquals("one\n", coordinator.readTextFile(file.toString(), null, null));
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("typed after the agent read\n"));

            IOException refused =
                    assertThrows(IOException.class, () -> coordinator.writeTextFile(file.toString(), "ONE\n"));

            assertTrue(refused.getMessage().contains("Read it again"), refused.getMessage());
            assertEquals("one\ntyped after the agent read\n", FxTestSupport.callOnFx(buffer::getContent));

            // The remedy the error names works: re-read, then write.
            assertEquals(
                    "one\ntyped after the agent read\n",
                    coordinator.readTextFile(file.toString(), 1, 1) + "\n" + "typed after the agent read\n");
            coordinator.writeTextFile(file.toString(), "ONE\ntyped after the agent read\n");
            assertEquals("ONE\ntyped after the agent read\n", FxTestSupport.callOnFx(buffer::getContent));
            // ...and the agent's own write counts as known text for its next one.
            coordinator.writeTextFile(file.toString(), "TWO\n");
            assertEquals("TWO\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    @Test
    void unsavedTextTheAgentNeverReadIsNotOverwritten(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("unread.txt"), "saved copy\n");
            EditorBuffer buffer = openBuffer(file, "saved copy\n", "only in the editor\n");
            OpsStub ops = new OpsStub();
            ops.openBuffer = buffer;
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);
            async.onClose(() -> FxTestSupport.runOnFx(buffer::dispose));

            IOException refused = assertThrows(
                    IOException.class, () -> coordinator.writeTextFile(file.toString(), "from the disk copy\n"));

            assertTrue(refused.getMessage().contains("unsaved changes"), refused.getMessage());
            assertEquals("only in the editor\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals("saved copy\n", Files.readString(file));
        }
    }

    @Test
    void aFileOpenInAnotherWindowIsEditedThroughItsBufferNotUnderneathIt(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("elsewhere.txt"), "saved copy\n");
            EditorBuffer buffer = openBuffer(file, "saved copy\n", "unsaved in the other window\n");
            OpsStub ops = new OpsStub();
            ops.otherWindowBuffer = buffer; // no tab in the agent's own window
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);
            async.onClose(() -> FxTestSupport.runOnFx(buffer::dispose));

            assertEquals("unsaved in the other window\n", coordinator.readTextFile(file.toString(), null, null));
            coordinator.writeTextFile(file.toString(), "agent edit\n");
            async.awaitFx();

            assertEquals("agent edit\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals("saved copy\n", Files.readString(file), "nothing was written underneath the buffer");
            assertNull(ops.backgroundOpen.get());
            FxTestSupport.runOnFx(buffer.getArea()::undo);
            assertEquals("unsaved in the other window\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    @Test
    void aClosedFileIsSnapshottedFirstAndKeepsItsEncodingAndLineEndings(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            byte[] before = "caf\u00e9\r\nna\u00efve\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            Path file = Files.write(dir.resolve("legacy.txt"), before);
            OpsStub ops = new OpsStub();
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);

            assertEquals(
                    "caf\u00e9\r\nna\u00efve\r\n",
                    coordinator.readTextFile(file.toString(), null, null),
                    "a file that is not UTF-8 is readable, decoded as the editor would open it");
            coordinator.writeTextFile(file.toString(), "caf\u00e9\r\nna\u00efve\r\nmore\r\n");
            async.awaitFx();

            assertEquals(java.util.List.of("caf\u00e9\nna\u00efve\n"), ops.history, "previous text kept first");
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    "caf\u00e9\r\nna\u00efve\r\nmore\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                    Files.readAllBytes(file));
            assertEquals(file, ops.backgroundOpen.get());
        }
    }

    @Test
    void aClosedFileIsNotReplacedWhenItsPreviousTextCannotBeKept(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("valuable.txt"), "precious original\n");
            OpsStub ops = new OpsStub();
            ops.historyDurable = false;
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);

            IOException refused = assertThrows(
                    IOException.class, () -> coordinator.writeTextFile(file.toString(), "agent replacement\n"));
            async.awaitFx();

            assertTrue(refused.getMessage().contains("Local History"), refused.getMessage());
            assertEquals("precious original\n", Files.readString(file));
            assertNull(ops.backgroundOpen.get());
        }
    }

    @Test
    void aClosedFileChangedOnDiskSinceTheAgentReadItIsNotReplaced(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("moved-on.txt"), "v1\n");
            OpsStub ops = new OpsStub();
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);

            assertEquals("v1\n", coordinator.readTextFile(file.toString(), null, null));
            Files.writeString(file, "v2 written by another program\n");

            IOException refused =
                    assertThrows(IOException.class, () -> coordinator.writeTextFile(file.toString(), "V1\n"));

            assertTrue(refused.getMessage().contains("Read it again"), refused.getMessage());
            assertEquals("v2 written by another program\n", Files.readString(file));
            assertTrue(ops.history.isEmpty());
        }
    }

    /** The host is only ever handed absolute paths; a relative one must never mean "under the JVM's cwd". */
    @Test
    void aRelativePathIsNeverResolvedAgainstTheWorkingDirectory() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            String name = "acp-relative-" + System.nanoTime() + ".txt";
            Path underCwd = Path.of(name).toAbsolutePath();
            OpsStub ops = new OpsStub();
            AgentCoordinator coordinator = new AgentCoordinator(new CoordinatorHostStub(), ops);
            async.onClose(coordinator::shutdown);
            try {
                assertThrows(IOException.class, () -> coordinator.writeTextFile(name, "written by agent\n"));
                assertThrows(IOException.class, () -> coordinator.readTextFile(name, null, null));
                async.awaitFx();
                assertFalse(Files.exists(underCwd), "nothing was created under the working directory");
                assertNull(ops.backgroundOpen.get());
            } finally {
                Files.deleteIfExists(underCwd);
            }
        }
    }

    private static final class OpsStub implements AgentCoordinator.Ops {

        private final AtomicInteger refreshes = new AtomicInteger();
        private final AtomicReference<Path> backgroundOpen = new AtomicReference<>();
        private EditorBuffer openBuffer;
        private EditorBuffer otherWindowBuffer;
        private final java.util.List<String> history = new java.util.concurrent.CopyOnWriteArrayList<>();
        private boolean historyDurable = true;

        @Override
        public EditorBuffer bufferInAnotherWindow(Path file) {
            return otherWindowBuffer;
        }

        @Override
        public void recordHistory(Path file, String content, java.util.function.Consumer<Boolean> completion) {
            if (historyDurable) {
                history.add(content);
            }
            completion.accept(historyDurable);
        }

        @Override
        public Path projectRoot() {
            return null;
        }

        @Override
        public EditorBuffer bufferForPath(String path) {
            return openBuffer;
        }

        @Override
        public void toggleToolWindow() {}

        @Override
        public void openToolWindow(boolean focus) {}

        @Override
        public void closeToolWindow() {}

        @Override
        public void setToolWindowAvailable(boolean available) {}

        @Override
        public void refreshProjectTree() {
            refreshes.incrementAndGet();
        }

        @Override
        public void openBackgroundBuffer(Path target) {
            backgroundOpen.set(target);
        }

        @Override
        public void openPath(Path file) {}

        @Override
        public void rememberSession(
                String sessionId, String cwd, String candidateLabel, long updatedAt, String agentId) {}

        @Override
        public ObservableList<AgentSessionHistory.Entry> sessionHistory() {
            return FXCollections.observableArrayList();
        }
    }
}

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

    private static final class OpsStub implements AgentCoordinator.Ops {

        private final AtomicInteger refreshes = new AtomicInteger();
        private final AtomicReference<Path> backgroundOpen = new AtomicReference<>();
        private EditorBuffer openBuffer;

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
        public EditorBuffer openBackgroundBuffer(Path target) {
            backgroundOpen.set(target);
            return null;
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

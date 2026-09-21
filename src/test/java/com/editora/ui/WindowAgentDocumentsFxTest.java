package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.agent.runtime.AgentCancellation;
import com.editora.agent.runtime.AgentDocuments;
import com.editora.agent.runtime.AgentWorkspace;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class WindowAgentDocumentsFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private final class Host implements WindowAgentDocuments.Host, AutoCloseable {
        final Map<Path, EditorBuffer> buffers = new LinkedHashMap<>();
        final WindowAgentDocuments documents;
        final List<String> lspOpenedText = new java.util.ArrayList<>();
        volatile boolean delayOpen;
        final CountDownLatch openCalled = new CountDownLatch(1);
        volatile CompletableFuture<EditorBuffer> pendingOpen;

        Host() throws Exception {
            documents = new WindowAgentDocuments(this, new AgentWorkspace(dir));
        }

        EditorBuffer add(String name, String text) throws Exception {
            Path path = Files.writeString(dir.resolve(name), text);
            return FxTestSupport.callOnFx(() -> {
                EditorBuffer buffer = create(path);
                buffer.setContent(text);
                buffer.markClean();
                return buffer;
            });
        }

        public EditorBuffer find(Path path) {
            return buffers.get(path);
        }

        public void ensureLsp(EditorBuffer buffer) {
            assertTrue(javafx.application.Platform.isFxApplicationThread());
            lspOpenedText.add(buffer.getContent());
        }

        public List<EditorBuffer> buffers() {
            return List.copyOf(buffers.values());
        }

        public EditorBuffer create(Path path) {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(path);
            buffer.setContent("");
            buffer.markClean();
            buffers.put(path, buffer);
            return buffer;
        }

        public void open(Path path, AgentCancellation cancellation, CompletableFuture<EditorBuffer> result) {
            if (delayOpen) {
                pendingOpen = result;
                openCalled.countDown();
            } else {
                result.complete(null);
            }
        }

        public CompletableFuture<Boolean> save(EditorBuffer b, AgentCancellation c) {
            return CompletableFuture.completedFuture(false);
        }

        public byte[] saveBytes(EditorBuffer b) {
            return b.getContent().getBytes(StandardCharsets.UTF_8);
        }

        public AgentDocuments.Diagnostics diagnostics(Path p) {
            return new AgentDocuments.Diagnostics(false, 0, "unavailable");
        }

        public void showDiff(Path p, String b, String a) {}

        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> buffers.values().forEach(EditorBuffer::dispose));
        }
    }

    private AgentCancellation token() {
        return new AgentCancellation();
    }

    private AgentDocuments.Edit edit(AgentDocuments.Snapshot s, String replacement) {
        return new AgentDocuments.Edit(s.path(), s.revision(), "", replacement);
    }

    @Test
    void batchIsUndoableAndPreservesUnsavedTextAndDisk() throws Exception {
        try (Host host = new Host()) {
            var first = host.add("one.txt", "saved");
            var second = host.add("two.txt", "second");
            FxTestSupport.runOnFx(() -> first.replaceWholeDocument("user draft"));
            var one = host.documents.read(first.getPath(), token());
            var two = host.documents.read(second.getPath(), token());
            var result = host.documents.apply(List.of(edit(one, "agent one"), edit(two, "agent two")), token());
            assertEquals(2, result.size());
            assertTrue(result.stream().allMatch(AgentDocuments.Snapshot::dirty));
            assertEquals(
                    List.of("user draft", "second"),
                    host.lspOpenedText,
                    "LSP ownership must be established for all background files before text is changed");
            assertEquals("saved", Files.readString(first.getPath()));
            FxTestSupport.runOnFx(first.getArea()::undo);
            assertEquals("user draft", FxTestSupport.callOnFx(first::getContent));
            FxTestSupport.runOnFx(second.getArea()::undo);
            assertEquals("second", FxTestSupport.callOnFx(second::getContent));
        }
    }

    @Test
    void staleSecondTargetPreventsFirstMutation() throws Exception {
        try (Host host = new Host()) {
            var first = host.add("one.txt", "one");
            var second = host.add("two.txt", "two");
            var one = host.documents.read(first.getPath(), token());
            var two = host.documents.read(second.getPath(), token());
            FxTestSupport.runOnFx(() -> second.replaceWholeDocument("new user edit"));
            assertThrows(
                    IllegalStateException.class,
                    () -> host.documents.apply(List.of(edit(one, "bad"), edit(two, "bad")), token()));
            assertEquals("one", FxTestSupport.callOnFx(first::getContent));
            assertEquals("new user edit", FxTestSupport.callOnFx(second::getContent));
            assertTrue(host.lspOpenedText.isEmpty(), "failed preflight must not start LSP or mutate text");
        }
    }

    @Test
    void revertingTextStillInvalidatesRevision() throws Exception {
        try (Host host = new Host()) {
            var buffer = host.add("one.txt", "one");
            var original = host.documents.read(buffer.getPath(), token());
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("changed");
                buffer.getArea().undo();
            });
            assertEquals("one", FxTestSupport.callOnFx(buffer::getContent));
            assertThrows(
                    IllegalStateException.class, () -> host.documents.apply(List.of(edit(original, "bad")), token()));
        }
    }

    @Test
    void reopeningSamePathDoesNotReuseRevisionIdentity() throws Exception {
        try (Host host = new Host()) {
            var buffer = host.add("one.txt", "one");
            var original = host.documents.read(buffer.getPath(), token());
            FxTestSupport.runOnFx(buffer::dispose);
            host.add("one.txt", "one");
            assertThrows(
                    IllegalStateException.class, () -> host.documents.apply(List.of(edit(original, "bad")), token()));
        }
    }

    @Test
    void readOnlyAndAmbiguousEditsAreRejected() throws Exception {
        try (Host host = new Host()) {
            var buffer = host.add("one.txt", "same same");
            var original = host.documents.read(buffer.getPath(), token());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> host.documents.apply(
                            List.of(new AgentDocuments.Edit(original.path(), original.revision(), "same", "bad")),
                            token()));
            FxTestSupport.runOnFx(() -> buffer.setViewMode(true));
            assertThrows(
                    IllegalStateException.class, () -> host.documents.apply(List.of(edit(original, "bad")), token()));
            assertEquals("same same", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    @Test
    void cancelledQueuedFxMutationNeverRuns() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var cancellation = token();
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        javafx.application.Platform.runLater(() -> {
            blocking.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blocking.await(2, TimeUnit.SECONDS));
        try (var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> AgentFx.call(cancellation, () -> {
                ran.set(true);
                return true;
            }));
            cancellation.cancel();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            release.countDown();
            FxTestSupport.runOnFx(() -> {});
            assertFalse(ran.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationStopsAFileLoadAndLateCompletionCannotResumeIt() throws Exception {
        Files.writeString(dir.resolve("delayed.txt"), "content");
        try (Host host = new Host();
                var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            host.delayOpen = true;
            var cancellation = token();
            var result = worker.submit(() -> host.documents.read(dir.resolve("delayed.txt"), cancellation));
            assertTrue(host.openCalled.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertFalse(host.pendingOpen.complete(null));
            assertTrue(host.buffers.isEmpty());
        }
    }

    @Test
    void diskProofRejectsExternalChangesEvenWithUnchangedDocumentRevision() throws Exception {
        try (Host host = new Host()) {
            var buffer = host.add("one.txt", "one");
            var original = host.documents.read(buffer.getPath(), token());
            assertTrue(host.documents.saved(original, token()));
            Files.writeString(buffer.getPath(), "two");
            assertFalse(host.documents.saved(original, token()));
        }
    }

    @Test
    void createStaysInDocumentModelAndCannotOverwriteExistingTarget() throws Exception {
        try (Host host = new Host()) {
            Path path = dir.resolve("new.txt");
            var created = host.documents.create(path, "draft", token());
            assertTrue(created.dirty());
            assertFalse(Files.exists(path));
            assertThrows(IllegalStateException.class, () -> host.documents.create(path, "overwrite", token()));
            FxTestSupport.runOnFx(host.find(path).getArea()::undo);
            assertEquals("", FxTestSupport.callOnFx(host.find(path)::getContent));
        }
    }
}

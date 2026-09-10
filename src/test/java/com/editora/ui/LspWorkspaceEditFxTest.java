package com.editora.ui;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import com.editora.io.DocumentWriteSequencer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.lsp.WorkspaceEditMapper;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.RenameFileOptions;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying a server's {@link WorkspaceEdit} — the path a rename or a multi-file quick fix takes. This one
 * <b>writes and moves files on disk</b>, so its failure modes are the worst in the LSP layer: half a
 * refactoring leaves the workspace inconsistent, and a rename that clobbers an existing file destroys work.
 *
 * <p>The contract is <b>all-or-nothing</b>, which is only meaningful if the refusals are tested: a file that
 * cannot be opened editable, or a rename that would overwrite an existing file, must apply <em>nothing</em> —
 * not "everything up to the problem". That is asserted here by checking the untouched files afterwards, not
 * merely by checking the returned boolean.
 */
@Tag("fx")
class LspWorkspaceEditFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private LspCoordinator coordinator;
    private FakeHost host;
    private FakeOps ops;

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;
        String error;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            new ArrayList<>(buffers).forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {}

        @Override
        public void setError(String message) {
            error = message;
        }
    }

    private static final class FakeOps extends LspOpsStub {
        final Map<Path, EditorBuffer> open = new HashMap<>();
        final List<Path[]> renamed = new ArrayList<>();
        final List<Path> created = new ArrayList<>();
        final List<Path> deleted = new ArrayList<>();
        final List<Path> invalidated = new ArrayList<>();
        Consumer<Path> onInvalidate = ignored -> {};

        @Override
        public EditorBuffer bufferForPath(Path file) {
            return open.get(file == null ? null : file.toAbsolutePath().normalize());
        }

        /** These fakes never open a background tab, so a file with no open buffer is unopenable — which is
         *  exactly the condition the all-or-nothing refusal is about. */
        @Override
        public EditorBuffer openBackgroundBuffer(Path file) {
            return null;
        }

        @Override
        public void fileRenamed(Path from, Path to) {
            renamed.add(new Path[] {from, to});
        }

        @Override
        public void fileCreated(Path file) {
            created.add(file);
        }

        @Override
        public void fileDeleted(Path file) {
            deleted.add(file);
        }

        @Override
        public void invalidatePendingWrite(Path file) {
            invalidated.add(file);
            onInvalidate.accept(file);
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queued.addLast(command);
        }

        void runNext() {
            Runnable next = queued.pollFirst();
            assertTrue(next != null, "expected a queued workspace task");
            next.run();
        }

        int queued() {
            return queued.size();
        }
    }

    private static final class ThrowingEditorBuffer extends EditorBuffer {
        @Override
        public void applyLspEdits(List<LspTextEdit> edits) {
            throw new IllegalStateException("injected FX edit failure");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("java", "jdtls"));
        host = new FakeHost();
        ops = new FakeOps();
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.setServerAvailableForTest("java", true);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> host.buffers.forEach(EditorBuffer::dispose));
        manager.shutdownAll();
    }

    /** Creates the file and an open buffer for it, registered with the fakes. */
    private EditorBuffer openBuffer(String name, String text) throws Exception {
        return openBuffer(name, text, new EditorBuffer());
    }

    private <T extends EditorBuffer> T openBuffer(String name, String text, T buffer) throws Exception {
        Path f = root.resolve(name);
        Files.writeString(f, text);
        T opened = FxTestSupport.callOnFx(() -> {
            buffer.setPath(f);
            buffer.setContent(text);
            host.buffers.add(buffer);
            host.active = buffer;
            return buffer;
        });
        ops.open.put(f.toAbsolutePath().normalize(), opened);
        return opened;
    }

    private void useControlledCoordinator(LspCoordinator.WorkspaceFileOperations files, Executor executor)
            throws Exception {
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops, files, executor);
            coordinator.setServerAvailableForTest("java", true);
        });
    }

    private CompletableFuture<Boolean> applyAsync(WorkspaceEditMapper.Mapped mapped) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> coordinator.applyWorkspaceEditsAsync(mapped, result::complete));
        return result;
    }

    private static TextDocumentEdit edit(Path file, int line, int startCol, int endCol, String newText) {
        return edit(file, null, line, startCol, endCol, newText);
    }

    private static TextDocumentEdit edit(
            Path file, Integer version, int line, int startCol, int endCol, String newText) {
        var id = new VersionedTextDocumentIdentifier(file.toUri().toString(), version);
        var te = new TextEdit(new Range(new Position(line, startCol), new Position(line, endCol)), newText);
        return new TextDocumentEdit(id, List.of(Either.forLeft(te)));
    }

    /** Applies a workspace edit the way the manager's registered handler does. */
    private boolean apply(WorkspaceEdit edit) throws Exception {
        WorkspaceEditMapper.Mapped mapped = WorkspaceEditMapper.map(edit);
        assertTrue(mapped != null, "the edit should have mapped");
        return FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped));
    }

    // --- the happy paths -----------------------------------------------------------------------------

    @Test
    void aSingleFileEditIsApplied() throws Exception {
        EditorBuffer b = openBuffer("A.java", "class A {}\n");
        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(Either.forLeft(edit(b.getPath(), 0, 6, 7, "B"))));

        assertTrue(apply(we));

        assertEquals("class B {}\n", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void editsAcrossSeveralOpenFilesAreAllApplied() throws Exception {
        EditorBuffer a = openBuffer("A.java", "class A {}\n");
        EditorBuffer c = openBuffer("C.java", "class C {}\n");
        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
                Either.forLeft(edit(a.getPath(), 0, 6, 7, "X")), Either.forLeft(edit(c.getPath(), 0, 6, 7, "Y"))));

        assertTrue(apply(we));

        assertEquals("class X {}\n", FxTestSupport.callOnFx(a::getContent));
        assertEquals("class Y {}\n", FxTestSupport.callOnFx(c::getContent));
    }

    // --- all-or-nothing ------------------------------------------------------------------------------

    /**
     * If any touched file cannot be opened editable, <b>nothing</b> is applied. Asserted by checking the
     * file that <em>could</em> have been edited is untouched — a returned {@code false} alone would not
     * distinguish "refused" from "half-applied then reported failure".
     */
    @Test
    void anUnopenableFileRefusesTheWholeEdit() throws Exception {
        EditorBuffer a = openBuffer("A.java", "class A {}\n");
        Path missing = root.resolve("NotOpen.java");
        Files.writeString(missing, "class NotOpen {}\n");

        var we = new WorkspaceEdit();
        we.setDocumentChanges(
                List.of(Either.forLeft(edit(a.getPath(), 0, 6, 7, "X")), Either.forLeft(edit(missing, 0, 6, 12, "Y"))));

        assertFalse(apply(we), "the edit must be refused");
        assertEquals(
                "class A {}\n",
                FxTestSupport.callOnFx(a::getContent),
                "nothing may be applied when part of the edit cannot be — half a refactoring is worse");
    }

    /** A read-only buffer is equally a refusal, for the same reason. */
    @Test
    void aReadOnlyBufferRefusesTheWholeEdit() throws Exception {
        EditorBuffer a = openBuffer("A.java", "class A {}\n");
        EditorBuffer ro = openBuffer("RO.java", "class RO {}\n");
        FxTestSupport.runOnFx(() -> ro.setViewMode(true));

        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
                Either.forLeft(edit(a.getPath(), 0, 6, 7, "X")), Either.forLeft(edit(ro.getPath(), 0, 6, 8, "Y"))));

        assertFalse(apply(we));
        assertEquals("class A {}\n", FxTestSupport.callOnFx(a::getContent), "nothing applied");
    }

    @Test
    void aStaleVersionRefusesTheWholeEdit() throws Exception {
        EditorBuffer a = openBuffer("A.java", "class A {}\n");
        manager.openDocument(a.getPath(), root, "java", a.getContent());
        FxTestSupport.runOnFx(() -> a.setContent("// note\nclass A {}\n"));
        manager.changeDocument(a.getPath(), a.getContent());

        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(Either.forLeft(edit(a.getPath(), 1, 1, 6, 7, "B"))));

        assertFalse(apply(we));
        assertEquals("// note\nclass A {}\n", FxTestSupport.callOnFx(a::getContent));
    }

    @Test
    void aChangedRequestSnapshotRefusesAnUnversionedEdit() throws Exception {
        EditorBuffer a = openBuffer("A.java", "class A {}\n");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(new WorkspaceEditMapper.FileEdit(
                        a.getPath(),
                        List.of(new com.editora.editor.LspTextEdit(0, 6, 0, 7, "B")),
                        null,
                        "class A {}\n")),
                List.of());
        FxTestSupport.runOnFx(() -> a.setContent("// note\nclass A {}\n"));

        assertFalse(FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped)));
        assertEquals("// note\nclass A {}\n", FxTestSupport.callOnFx(a::getContent));
    }

    // --- file renames (#676) -------------------------------------------------------------------------

    /** Renaming a public class moves its file; the text edits land first, then the move. */
    @Test
    void aRenameMovesTheFileAfterApplyingTheEdits() throws Exception {
        EditorBuffer b = openBuffer("OldName.java", "class OldName {}\n");
        Path from = b.getPath();
        Path to = root.resolve("NewName.java");

        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
                Either.forLeft(edit(from, 0, 6, 13, "NewName")),
                Either.forRight(
                        new RenameFile(from.toUri().toString(), to.toUri().toString()))));

        assertTrue(apply(we));

        assertTrue(Files.exists(to), "the file should have moved to its new name");
        assertFalse(Files.exists(from), "…and the old name should be gone");
        assertEquals(1, ops.renamed.size(), "the open buffer/tab must be remapped");
        assertEquals(from, ops.renamed.get(0)[0]);
        assertEquals(to, ops.renamed.get(0)[1]);
    }

    /**
     * A rename that would clobber an existing file is refused <b>before anything is applied</b> — without the
     * up-front validation the text edits would already have landed when the move failed.
     */
    @Test
    void aRenameOntoAnExistingFileRefusesTheWholeEditUpFront() throws Exception {
        EditorBuffer b = openBuffer("OldName.java", "class OldName {}\n");
        Path from = b.getPath();
        Path occupied = root.resolve("Taken.java");
        Files.writeString(occupied, "class Taken { int keepMe; }\n");

        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
                Either.forLeft(edit(from, 0, 6, 13, "Taken")),
                Either.forRight(
                        new RenameFile(from.toUri().toString(), occupied.toUri().toString()))));

        assertFalse(apply(we), "clobbering an existing file must be refused");
        assertEquals(
                "class Taken { int keepMe; }\n", Files.readString(occupied), "the existing file must be untouched");
        assertEquals(
                "class OldName {}\n",
                FxTestSupport.callOnFx(b::getContent),
                "and the text edits must not have been applied either");
        assertTrue(Files.exists(from), "the source file must still be there");
    }

    /** …unless the server explicitly set the overwrite option. */
    @Test
    void anOverwritingRenameIsAllowedWhenTheServerSaysSo() throws Exception {
        EditorBuffer b = openBuffer("OldName.java", "class OldName {}\n");
        Path from = b.getPath();
        Path occupied = root.resolve("Taken.java");
        Files.writeString(occupied, "old contents\n");

        var options = new RenameFileOptions();
        options.setOverwrite(true);
        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(Either.forRight(
                new RenameFile(from.toUri().toString(), occupied.toUri().toString(), options))));

        assertTrue(apply(we));
        assertFalse(Files.exists(from));
        assertEquals("class OldName {}\n", Files.readString(occupied), "the source content moved over it");
    }

    /** A rename into a directory that does not exist yet must create it rather than fail. */
    @Test
    void aRenameIntoANewPackageDirectoryCreatesIt() throws Exception {
        EditorBuffer b = openBuffer("Moved.java", "class Moved {}\n");
        Path from = b.getPath();
        Path to = root.resolve("sub").resolve("dir").resolve("Moved.java");

        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(Either.forRight(
                new RenameFile(from.toUri().toString(), to.toUri().toString()))));

        assertTrue(apply(we));
        assertTrue(Files.exists(to), "the destination directory should have been created");
    }

    @Test
    void aLateFailureRollsBackEveryEarlierFileMove() throws Exception {
        EditorBuffer a = openBuffer("A.java", "class A {}\n");
        EditorBuffer b = openBuffer("B.java", "class B {}\n");
        Path movedA = root.resolve("MovedA.java");
        Path blocker = root.resolve("blocked");
        Files.writeString(blocker, "not a directory");
        Path impossibleB = blocker.resolve("MovedB.java");

        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
                Either.forLeft(edit(a.getPath(), 0, 6, 7, "X")),
                Either.forRight(new RenameFile(
                        a.getPath().toUri().toString(), movedA.toUri().toString())),
                Either.forRight(new RenameFile(
                        b.getPath().toUri().toString(), impossibleB.toUri().toString()))));

        assertFalse(apply(we));
        assertTrue(Files.exists(a.getPath()), "the first move must be rolled back");
        assertTrue(Files.exists(b.getPath()), "the second staged source must be restored");
        assertFalse(Files.exists(movedA));
        assertEquals("class A {}\n", FxTestSupport.callOnFx(a::getContent), "text applies only after all moves commit");
        assertTrue(ops.renamed.isEmpty(), "no in-memory rename events may be emitted for a failed batch");
    }

    // --- what the mapper refuses outright ------------------------------------------------------------

    @Test
    void createAndDeleteResourceOperationsApply() throws Exception {
        Path old = root.resolve("Old.java");
        Path created = root.resolve("New.java");
        Files.writeString(old, "old");
        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
                Either.forRight(new org.eclipse.lsp4j.CreateFile(created.toUri().toString())),
                Either.forRight(new org.eclipse.lsp4j.DeleteFile(old.toUri().toString()))));

        assertTrue(apply(we));
        assertTrue(Files.isRegularFile(created));
        assertFalse(Files.exists(old));
        assertEquals(List.of(created), ops.created);
        assertEquals(List.of(old), ops.deleted);
    }

    @Test
    void productionApplyPathRunsResourceTransactionAsynchronously() throws Exception {
        Path old = root.resolve("AsyncOld.java");
        Path created = root.resolve("AsyncNew.java");
        Files.writeString(old, "old");
        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
                Either.forRight(new org.eclipse.lsp4j.CreateFile(created.toUri().toString())),
                Either.forRight(new org.eclipse.lsp4j.DeleteFile(old.toUri().toString()))));
        var result = new java.util.concurrent.CompletableFuture<Boolean>();

        FxTestSupport.runOnFx(
                () -> coordinator.applyWorkspaceEditsAsync(WorkspaceEditMapper.map(we), result::complete));

        assertTrue(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(Files.exists(created));
        assertFalse(Files.exists(old));
    }

    @Test
    void failedAsyncApplyReportsOnlyAfterItsResourceRollback() throws Exception {
        EditorBuffer buffer = openBuffer("Stale.java", "class Stale {}\n");
        Path created = root.resolve("MustRollback.java");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(new WorkspaceEditMapper.FileEdit(buffer.getPath(), List.of(), null, "older text")),
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(created, false, false)),
                List.of());
        var result = new java.util.concurrent.CompletableFuture<Boolean>();

        FxTestSupport.runOnFx(() -> coordinator.applyWorkspaceEditsAsync(mapped, result::complete));

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(Files.exists(created), "the failure response must wait until rollback has restored disk state");
    }

    @Test
    void failedCreateRollsBackEarlierCreates() throws Exception {
        Path first = root.resolve("First.java");
        Path occupied = root.resolve("Occupied.java");
        Files.writeString(occupied, "keep");
        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
                Either.forRight(new org.eclipse.lsp4j.CreateFile(first.toUri().toString())),
                Either.forRight(
                        new org.eclipse.lsp4j.CreateFile(occupied.toUri().toString()))));

        assertFalse(apply(we));
        assertFalse(Files.exists(first));
        assertEquals("keep", Files.readString(occupied));
    }

    private enum StagedInterference {
        EDIT,
        READ_ONLY,
        CLOSE
    }

    @ParameterizedTest(name = "{0} after staging rejects and rolls back the complete workspace edit")
    @EnumSource(StagedInterference.class)
    void bufferChangesAfterResourceStagingAreRejected(StagedInterference interference) throws Exception {
        String original = "class Stable {}\n";
        EditorBuffer buffer = openBuffer("Stable.java", original);
        Path created = root.resolve("CreatedBeforeValidation.java");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(new WorkspaceEditMapper.FileEdit(
                        buffer.getPath(), List.of(new LspTextEdit(0, 6, 0, 12, "Changed")), null, original)),
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(created, false, false)),
                List.of());
        ManualExecutor executor = new ManualExecutor();
        var files = new DelegatingWorkspaceFileOperations() {
            @Override
            public void createFile(Path path) throws IOException {
                super.createFile(path);
                try {
                    FxTestSupport.runOnFx(() -> {
                        switch (interference) {
                            case EDIT -> buffer.replaceWholeDocument("// user edit\n" + original);
                            case READ_ONLY -> buffer.setViewMode(true);
                            case CLOSE -> {
                                ops.open.remove(
                                        buffer.getPath().toAbsolutePath().normalize());
                                buffer.dispose();
                            }
                        }
                    });
                } catch (Exception failure) {
                    throw new IOException("failed to inject staged-buffer interference", failure);
                }
            }
        };
        useControlledCoordinator(files, executor);

        CompletableFuture<Boolean> result = applyAsync(mapped);
        executor.runNext(); // stage, inject the buffer change, then enqueue final validation on FX
        assertTrue(Files.exists(created), "the resource operation must be staged before the interference");

        FxTestSupport.drainFx();
        assertFalse(result.isDone(), "failure is not reported until the staged resource is restored");
        assertEquals(1, executor.queued(), "rollback must use the owned worker boundary");
        executor.runNext();
        FxTestSupport.drainFx();

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(Files.exists(created), "the staged create must be rolled back");
        String expected = interference == StagedInterference.EDIT ? "// user edit\n" + original : original;
        assertEquals(expected, buffer.getContent(), "the user's current buffer state must survive");
        assertTrue(ops.created.isEmpty(), "no successful resource notification may escape a rejected edit");
    }

    @Test
    void aQueuedSaveCapturedBeforeRenameCannotRecreateTheSourcePath() throws Exception {
        Path source = Files.writeString(root.resolve("Queued.java"), "current");
        Path destination = root.resolve("Moved.java");
        DocumentWriteSequencer writes = new DocumentWriteSequencer();
        ops.onInvalidate = writes::supersede;
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(new WorkspaceEditMapper.FileRename(source, destination, false)),
                List.of(),
                List.of());

        try (DocumentWriteSequencer.Ticket oldSave = writes.begin(source)) {
            assertTrue(FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped)));
            var outcome = oldSave.runIfCurrent(() -> {
                Files.writeString(source, "obsolete queued save");
                return true;
            });

            assertFalse(outcome.executed(), "the resource transaction must supersede the old save ticket");
        }
        assertFalse(Files.exists(source));
        assertEquals("current", Files.readString(destination));
        assertTrue(ops.invalidated.contains(source));
        assertTrue(ops.invalidated.contains(destination));
    }

    @Test
    void rollbackFailureReportsTheProblemAndRetainsTheRecoveryStage() throws Exception {
        Path a = Files.writeString(root.resolve("RecoverA.txt"), "recoverable A");
        Path b = Files.writeString(root.resolve("RecoverB.txt"), "recoverable B");
        Path movedA = root.resolve("MovedA.txt");
        Path movedB = root.resolve("MovedB.txt");
        AtomicReference<Path> aStage = new AtomicReference<>();
        var files = new DelegatingWorkspaceFileOperations() {
            @Override
            public void move(Path from, Path to, CopyOption... options) throws IOException {
                boolean sourceStage = to.getFileName().toString().endsWith(".source");
                if (from.equals(a) && sourceStage) {
                    aStage.set(to);
                } else if (from.equals(b) && sourceStage) {
                    throw new IOException("fail second source stage");
                } else if (from.equals(aStage.get()) && to.equals(a)) {
                    throw new IOException("fail rollback restore");
                }
                super.move(from, to, options);
            }
        };
        useControlledCoordinator(files, Runnable::run);
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(
                        new WorkspaceEditMapper.FileRename(a, movedA, false),
                        new WorkspaceEditMapper.FileRename(b, movedB, false)),
                List.of(),
                List.of());

        assertFalse(FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped)));

        assertFalse(Files.exists(a), "the injected rollback failure is observable at the original path");
        assertEquals("recoverable A", Files.readString(aStage.get()), "the only copy must remain recoverable");
        assertEquals("recoverable B", Files.readString(b), "independent source files must still be restored");
        assertTrue(host.error != null && !host.error.isBlank(), "irrecoverable rollback must be reported");
    }

    @Test
    void rollbackNeverOverwritesAPathRecreatedByACompetingSave() throws Exception {
        String original = "class Original {}\n";
        Path source = Files.writeString(root.resolve("Original.java"), original);
        Path destination = root.resolve("Moved.java");
        AtomicReference<Path> sourceStage = new AtomicReference<>();
        var files = new DelegatingWorkspaceFileOperations() {
            @Override
            public void move(Path from, Path to, CopyOption... options) throws IOException {
                boolean stagingSource =
                        from.equals(source) && to.getFileName().toString().endsWith(".source");
                if (stagingSource) {
                    sourceStage.set(to);
                }
                super.move(from, to, options);
                if (from.equals(sourceStage.get()) && to.equals(destination)) {
                    Files.writeString(source, "newer save at original path");
                }
            }
        };
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(new WorkspaceEditMapper.FileRename(source, destination, false)),
                List.of(),
                List.of());
        ManualExecutor executor = new ManualExecutor();
        useControlledCoordinator(files, executor);

        CompletableFuture<Boolean> result = applyAsync(mapped);
        executor.runNext();
        FxTestSupport.drainFx();
        executor.runNext();
        FxTestSupport.drainFx();

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("newer save at original path", Files.readString(source));
        assertEquals(original, Files.readString(sourceStage.get()), "the staged preimage must also remain recoverable");
        assertTrue(host.error != null && !host.error.isBlank());
        assertTrue(ops.renamed.isEmpty());
    }

    @Test
    void rollbackRetainsConcurrentCreateAndDeletePathChanges() throws Exception {
        Path created = root.resolve("Created.java");
        Path deleted = Files.writeString(root.resolve("Deleted.java"), "deleted preimage");
        AtomicReference<Path> deletedStage = new AtomicReference<>();
        var files = new DelegatingWorkspaceFileOperations() {
            @Override
            public void createFile(Path path) throws IOException {
                super.createFile(path);
                if (path.equals(created)) {
                    Files.writeString(path, "concurrent content in created path");
                }
            }

            @Override
            public void move(Path from, Path to, CopyOption... options) throws IOException {
                super.move(from, to, options);
                if (from.equals(deleted) && to.getFileName().toString().endsWith(".deleted")) {
                    deletedStage.set(to);
                    Files.writeString(deleted, "concurrently recreated delete path");
                }
            }
        };
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(created, false, false)),
                List.of(new WorkspaceEditMapper.FileDelete(deleted, false, false)));
        ManualExecutor executor = new ManualExecutor();
        useControlledCoordinator(files, executor);

        CompletableFuture<Boolean> result = applyAsync(mapped);
        executor.runNext();
        FxTestSupport.drainFx();
        executor.runNext();
        FxTestSupport.drainFx();

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("concurrent content in created path", Files.readString(created));
        assertEquals("concurrently recreated delete path", Files.readString(deleted));
        assertEquals("deleted preimage", Files.readString(deletedStage.get()));
        assertTrue(host.error != null && !host.error.isBlank());
        assertTrue(ops.created.isEmpty());
        assertTrue(ops.deleted.isEmpty());
    }

    @Test
    void anFxEditExceptionRollsBackResourcesAndCompletesAsFailed() throws Exception {
        String firstOriginal = "class First {}\n";
        String throwingOriginal = "class Throws {}\n";
        EditorBuffer first = openBuffer("First.java", firstOriginal);
        EditorBuffer throwing = openBuffer("Throws.java", throwingOriginal, new ThrowingEditorBuffer());
        Path created = root.resolve("MustNotSurvive.java");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(
                        new WorkspaceEditMapper.FileEdit(
                                first.getPath(), List.of(new LspTextEdit(0, 6, 0, 11, "Changed")), null, firstOriginal),
                        new WorkspaceEditMapper.FileEdit(
                                throwing.getPath(),
                                List.of(new LspTextEdit(0, 6, 0, 12, "Changed")),
                                null,
                                throwingOriginal)),
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(created, false, false)),
                List.of());
        ManualExecutor executor = new ManualExecutor();
        useControlledCoordinator(LspCoordinator.WorkspaceFileOperations.SYSTEM, executor);

        CompletableFuture<Boolean> result = applyAsync(mapped);
        executor.runNext();
        FxTestSupport.drainFx();
        assertFalse(result.isDone(), "the callback must wait for resource rollback after the edit exception");
        executor.runNext();
        FxTestSupport.drainFx();

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(Files.exists(created));
        assertEquals(firstOriginal, first.getContent(), "an earlier text edit must be rolled back too");
        assertFalse(first.isDirty(), "restoring the clean preimage must also restore clean state");
        assertEquals(throwingOriginal, throwing.getContent());
        assertTrue(ops.created.isEmpty());
    }

    /** A text edit appearing AFTER a rename addresses the post-rename world — refused rather than guessed. */
    @Test
    void aTextEditAfterARenameIsRefused() throws Exception {
        EditorBuffer b = openBuffer("A.java", "class A {}\n");
        Path from = b.getPath();
        Path to = root.resolve("B.java");

        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
                Either.forRight(
                        new RenameFile(from.toUri().toString(), to.toUri().toString())),
                Either.forLeft(edit(to, 0, 6, 7, "B"))));

        assertTrue(
                WorkspaceEditMapper.map(we) == null,
                "an edit after a rename would need path remapping mid-apply — refused instead");
    }
}

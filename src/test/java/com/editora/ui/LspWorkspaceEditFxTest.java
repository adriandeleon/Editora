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

        String status;

        @Override
        public void setStatus(String message) {
            status = message;
        }

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

        @Override
        public List<EditorBuffer> buffersAtOrUnder(Path path) {
            if (path == null) {
                return List.of();
            }
            Path target = path.toAbsolutePath().normalize();
            return open.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(target))
                    .map(Map.Entry::getValue)
                    .distinct()
                    .toList();
        }

        /** These fakes never open a background tab, so a file with no open buffer is unopenable — which is
         *  exactly the condition the all-or-nothing refusal is about. */
        @Override
        public EditorBuffer openBackgroundBuffer(Path file) {
            return null;
        }

        /** Off models a window whose tab lookup misses: the file moves and the tab stays behind. */
        boolean remapTabs = true;

        /** Like the real window: the tab holding {@code from} follows the file to {@code to}. */
        @Override
        public void fileRenamed(Path from, Path to) {
            renamed.add(new Path[] {from, to});
            EditorBuffer moved = remapTabs ? open.remove(from.toAbsolutePath().normalize()) : null;
            if (moved != null) {
                moved.setPath(to);
                open.put(to.toAbsolutePath().normalize(), moved);
            }
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

        /** What Local History was asked to keep, with the content each file had at that moment. */
        final Map<Path, String> captured = new java.util.LinkedHashMap<>();

        boolean captureSucceeds = true;
        Path projectRoot;

        @Override
        public void captureBeforeDestruction(Path file, Consumer<Boolean> completion) {
            try {
                captured.put(file, Files.readString(file));
            } catch (IOException e) {
                captured.put(file, null);
            }
            completion.accept(captureSucceeds);
        }

        @Override
        public Path lspProjectRoot() {
            return projectRoot;
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
        public boolean applyLspEditsAtomically(List<LspTextEdit> edits) {
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

    /** {@code exists}/{@code isRegularFile}/{@code identity} as a case-insensitive, case-preserving volume answers. */
    private static final class CaseInsensitiveVolume extends DelegatingWorkspaceFileOperations {
        private static Path actual(Path path) {
            Path parent = path.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                return path;
            }
            try (var siblings = Files.list(parent)) {
                String wanted = path.getFileName().toString();
                return siblings.filter(p -> p.getFileName().toString().equalsIgnoreCase(wanted))
                        .findFirst()
                        .orElse(path);
            } catch (java.io.IOException e) {
                return path;
            }
        }

        @Override
        public boolean exists(Path path) {
            return super.exists(actual(path));
        }

        @Override
        public boolean isRegularFile(Path path) {
            return super.isRegularFile(actual(path));
        }

        @Override
        public LspCoordinator.WorkspaceFileIdentity identity(Path path) throws java.io.IOException {
            return super.identity(actual(path));
        }
    }

    /**
     * Renaming class Httpclient → HttpClient on macOS/Windows: the destination "exists" because it is the
     * source under another letter case. That was taken for a collision and the whole rename was refused.
     */
    @Test
    void aCaseOnlyFileRenameIsNotACollisionOnACaseInsensitiveVolume() throws Exception {
        useControlledCoordinator(new CaseInsensitiveVolume(), Runnable::run);
        EditorBuffer b = openBuffer("Httpclient.java", "class Httpclient {}\n");
        Path from = b.getPath();
        Path to = root.resolve("HttpClient.java");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(new WorkspaceEditMapper.FileEdit(
                        from, List.of(new LspTextEdit(0, 6, 0, 16, "HttpClient")), null, "class Httpclient {}\n")),
                List.of(new WorkspaceEditMapper.FileRename(from, to, false)),
                List.of(),
                List.of());

        assertTrue(FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped)));

        assertEquals("class HttpClient {}\n", FxTestSupport.callOnFx(b::getContent));
        try (var files = Files.list(root)) {
            assertEquals(
                    List.of("HttpClient.java"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    /**
     * The blocked-files note belongs to the operation it was raised for. A server-initiated edit that was
     * blocked has no outcome report of its own, and its note used to swallow the failure message of the next
     * operation (a jdtls Generate… that failed said nothing at all).
     */
    @Test
    void aBlockedEditFromAnEarlierOperationDoesNotSilenceALaterFailure() throws Exception {
        Path file = root.resolve("A.java");
        FxTestSupport.runOnFx(() -> {
            coordinator.editBlocked(List.of(file)); // e.g. an unsolicited workspace/applyEdit
            coordinator.beginReportedEdit(); // …then the user starts something else
            coordinator.reportEdit(false, "applied", "Generate failed");
        });
        assertEquals("Generate failed", host.status);

        FxTestSupport.runOnFx(() -> {
            coordinator.beginReportedEdit();
            coordinator.editBlocked(List.of(file)); // blocked during this operation: its files were named
            host.status = null;
            coordinator.reportEdit(false, "applied", "Rename failed");
        });
        assertEquals(null, host.status, "the more specific message stays");
    }

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

    @Test
    void anOverwritingRenameRefusesToOrphanAnOpenDestinationBuffer() throws Exception {
        EditorBuffer source = openBuffer("OldName.java", "class OldName {}\n");
        EditorBuffer destination = openBuffer("Taken.java", "class Taken { int keepMe; }\n");
        Path from = source.getPath();
        Path to = destination.getPath();
        var options = new RenameFileOptions();
        options.setOverwrite(true);
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(), List.of(new WorkspaceEditMapper.FileRename(from, to, true)), List.of(), List.of());

        assertFalse(FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped)));
        assertEquals("class OldName {}\n", Files.readString(from));
        assertEquals("class Taken { int keepMe; }\n", Files.readString(to));
        assertEquals("class Taken { int keepMe; }\n", FxTestSupport.callOnFx(destination::getContent));
        assertTrue(ops.renamed.isEmpty());
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
    void deletingADirtyOpenFileIsRefusedBeforeTheFilesystemTransaction() throws Exception {
        EditorBuffer dirty = openBuffer("Dirty.java", "class Dirty {}\n");
        FxTestSupport.runOnFx(() -> dirty.replaceWholeDocument("class Dirty { int unsaved; }\n"));
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(),
                List.of(),
                List.of(new WorkspaceEditMapper.FileDelete(dirty.getPath(), false, false)));

        assertFalse(FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped)));
        assertEquals("class Dirty {}\n", Files.readString(dirty.getPath()));
        assertEquals("class Dirty { int unsaved; }\n", FxTestSupport.callOnFx(dirty::getContent));
        assertTrue(ops.deleted.isEmpty());
    }

    @Test
    void textEditsRefuseANarrowedBufferInsteadOfUsingRegionRelativeCoordinates() throws Exception {
        EditorBuffer buffer = openBuffer("Narrow.java", "before\ntarget\nafter\n");
        assertTrue(FxTestSupport.callOnFx(() -> buffer.narrowTo(7, 13)));
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(new WorkspaceEditMapper.FileEdit(
                        buffer.getPath(), List.of(new LspTextEdit(1, 0, 1, 6, "changed")), null, null)),
                List.of(),
                List.of(),
                List.of());

        assertFalse(FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped)));
        assertEquals("before\ntarget\nafter\n", FxTestSupport.callOnFx(buffer::getContent));
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

    // --- edits to files the server did not have open (the cross-file rename case) -------------------

    /** Ages the file and records that state as the one the buffer mirrors, as the editor's loader does. */
    private void mirrorDisk(EditorBuffer buffer, long ageMillis) throws Exception {
        Path f = buffer.getPath();
        long at = System.currentTimeMillis() - ageMillis;
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(at % 1000 == 0 ? at + 1 : at));
        long modified = Files.getLastModifiedTime(f).toMillis();
        long size = Files.size(f);
        FxTestSupport.runOnFx(() -> {
            buffer.setDiskSnapshot(modified, size, "loaded"); // a load records the content it read
            buffer.markClean();
        });
    }

    /** An edit as {@code LspManager} plans it for a target the server computed from the file on disk. */
    private static WorkspaceEditMapper.Mapped closedFileEdit(EditorBuffer buffer, long requestSentAt) {
        return new WorkspaceEditMapper.Mapped(
                List.of(new WorkspaceEditMapper.FileEdit(
                        buffer.getPath(), List.of(new LspTextEdit(0, 6, 0, 7, "Renamed")), null, null, requestSentAt)),
                List.of());
    }

    @Test
    void aClosedFileEditAppliesToACleanBufferThatMirrorsTheDisk() throws Exception {
        EditorBuffer b = openBuffer("B.java", "class B {}\n");
        mirrorDisk(b, 60_000);

        assertTrue(FxTestSupport.callOnFx(
                () -> coordinator.applyWorkspaceEdits(closedFileEdit(b, System.currentTimeMillis()))));
        assertEquals("class Renamed {}\n", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void theAsyncPathAppliesAClosedFileEditToo() throws Exception {
        EditorBuffer b = openBuffer("B.java", "class B {}\n");
        mirrorDisk(b, 60_000);

        assertTrue(applyAsync(closedFileEdit(b, System.currentTimeMillis()))
                .get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("class Renamed {}\n", FxTestSupport.callOnFx(b::getContent));
    }

    /**
     * jdtls's answer to renaming a class from a usage site while the declaring file is not open on the
     * server: an edit to that file (planned against its disk preimage) plus a move of the same file. The
     * production path re-checks the preimage after the move, when the old path no longer exists.
     */
    @Test
    void theAsyncPathAppliesAClosedFileEditThatAlsoMovesTheFile() throws Exception {
        EditorBuffer b = openBuffer("Foo.java", "class B {}\n");
        mirrorDisk(b, 60_000);
        Path from = b.getPath();
        Path to = root.resolve("Renamed.java");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(new WorkspaceEditMapper.FileEdit(
                        from, List.of(new LspTextEdit(0, 6, 0, 7, "Renamed")), null, null, System.currentTimeMillis())),
                List.of(new WorkspaceEditMapper.FileRename(from, to, false)));

        assertTrue(applyAsync(mapped).get(10, java.util.concurrent.TimeUnit.SECONDS), "refused: " + host.error);

        assertEquals("class Renamed {}\n", FxTestSupport.callOnFx(b::getContent));
        assertTrue(Files.exists(to), "the file should have moved");
        assertFalse(Files.exists(from));
        assertEquals(to, FxTestSupport.callOnFx(b::getPath), "the tab follows the file");
    }

    /** The move must not hide a real change: the file was written after the request, then moved. */
    @Test
    void theAsyncPathStillRefusesAMovedClosedFileThatChangedAfterTheRequest() throws Exception {
        EditorBuffer b = openBuffer("Foo.java", "class B {}\n");
        mirrorDisk(b, 60_000);
        Path from = b.getPath();
        Path to = root.resolve("Renamed.java");
        long sentBeforeTheLastWrite = Files.getLastModifiedTime(from).toMillis() - 5_000;
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(new WorkspaceEditMapper.FileEdit(
                        from, List.of(new LspTextEdit(0, 6, 0, 7, "Renamed")), null, null, sentBeforeTheLastWrite)),
                List.of(new WorkspaceEditMapper.FileRename(from, to, false)));

        assertFalse(applyAsync(mapped).get(10, java.util.concurrent.TimeUnit.SECONDS));

        assertTrue(Files.exists(from), "nothing may be staged for a stale target");
        assertFalse(Files.exists(to));
        assertEquals("class B {}\n", FxTestSupport.callOnFx(b::getContent));
        assertTrue(host.error != null && host.error.contains("Foo.java"), "the blocking file is named: " + host.error);
    }

    /** A tab left behind on a path that no longer exists is a half-applied refactoring, not a success. */
    @Test
    void aTabThatDoesNotFollowItsMovedFileIsNotReportedAsApplied() throws Exception {
        EditorBuffer b = openBuffer("OldName.java", "class OldName {}\n");
        Path from = b.getPath();
        Path to = root.resolve("NewName.java");
        ops.remapTabs = false;
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(new WorkspaceEditMapper.FileEdit(
                        from, List.of(new LspTextEdit(0, 6, 0, 13, "NewName")), null, "class OldName {}\n")),
                List.of(new WorkspaceEditMapper.FileRename(from, to, false)));

        assertFalse(applyAsync(mapped).get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(
                host.error != null && host.error.contains("OldName.java"), "the stranded tab is named: " + host.error);
    }

    @Test
    void aRenameDestinationIsWrittenInTheTabsSpelling() {
        Path tab = Path.of("/home/u/dev/app/src/Foo.java").toAbsolutePath();
        Path serverFrom = Path.of("/mnt/data/dev/app/src/Foo.java").toAbsolutePath();
        Path serverTo = Path.of("/mnt/data/dev/app/src/Baz.java").toAbsolutePath();

        assertEquals(
                Path.of("/home/u/dev/app/src/Baz.java").toAbsolutePath(),
                LspCoordinator.inSpellingOf(tab, serverFrom, serverTo));
        assertEquals(
                Path.of("/home/u/dev/app/src/sub/Baz.java").toAbsolutePath(),
                LspCoordinator.inSpellingOf(tab, serverFrom, serverFrom.resolveSibling("sub/Baz.java")));
        assertEquals(serverTo, LspCoordinator.inSpellingOf(serverFrom, serverFrom, serverTo), "same spelling");
        Path elsewhere = Path.of("/srv/other/Baz.java").toAbsolutePath();
        assertEquals(elsewhere, LspCoordinator.inSpellingOf(tab, serverFrom, elsewhere), "outside the shared tree");
    }

    @Test
    void aClosedFileEditIsRefusedForUnsavedChangesAndNamesTheFile() throws Exception {
        EditorBuffer b = openBuffer("B.java", "class B {}\n");
        mirrorDisk(b, 60_000);
        FxTestSupport.runOnFx(b::markUnsaved);

        assertFalse(FxTestSupport.callOnFx(
                () -> coordinator.applyWorkspaceEdits(closedFileEdit(b, System.currentTimeMillis()))));
        assertEquals("class B {}\n", FxTestSupport.callOnFx(b::getContent), "nothing may be half-applied");
        assertTrue(host.error != null && host.error.contains("B.java"), "the blocking file is named: " + host.error);
    }

    /** The H12 protection at the applier: the file changed between the request and now. */
    @Test
    void aClosedFileEditIsRefusedWhenTheFileChangedAfterTheRequest() throws Exception {
        EditorBuffer b = openBuffer("B.java", "class B {}\n");
        mirrorDisk(b, 60_000);
        long sentBeforeTheLastWrite = Files.getLastModifiedTime(b.getPath()).toMillis() - 5_000;

        var result = applyAsync(closedFileEdit(b, sentBeforeTheLastWrite));

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("class B {}\n", FxTestSupport.callOnFx(b::getContent));
        assertTrue(host.error != null && host.error.contains("B.java"));
    }

    /** A clean buffer that was loaded from an older version of the file is not what the server edited. */
    @Test
    void aClosedFileEditIsRefusedWhenTheBufferDoesNotMirrorTheDisk() throws Exception {
        EditorBuffer b = openBuffer("B.java", "class B {}\n");
        mirrorDisk(b, 60_000);
        long modified = Files.getLastModifiedTime(b.getPath()).toMillis();
        FxTestSupport.runOnFx(() -> b.setDiskSnapshot(modified - 30_000, 3)); // loaded before an external write

        assertFalse(FxTestSupport.callOnFx(
                () -> coordinator.applyWorkspaceEdits(closedFileEdit(b, System.currentTimeMillis()))));
        assertEquals("class B {}\n", FxTestSupport.callOnFx(b::getContent));
    }

    /**
     * "Changed on disk → Keep" re-baselines the snapshot's time and size without loading: the buffer is clean
     * and its snapshot matches the file, yet it holds different text. The server computed its edit from the
     * file, so applying it to that buffer put the change at the wrong place and reported success.
     */
    @Test
    void aClosedFileEditIsRefusedForABufferThatKeptItsTextOverTheDisk() throws Exception {
        EditorBuffer b = openBuffer("B.java", "class B {}\n");
        mirrorDisk(b, 60_000);
        long modified = Files.getLastModifiedTime(b.getPath()).toMillis();
        long size = Files.size(b.getPath());
        FxTestSupport.runOnFx(() -> b.setDiskSnapshot(modified, size)); // what Keep does: no content was read

        assertFalse(FxTestSupport.callOnFx(
                () -> coordinator.applyWorkspaceEdits(closedFileEdit(b, System.currentTimeMillis()))));
        assertEquals("class B {}\n", FxTestSupport.callOnFx(b::getContent));
        assertTrue(host.error != null && host.error.contains("B.java"));
    }

    @Test
    void blockedFileNamesAreSummarised() {
        assertEquals("A.java", LspCoordinator.blockedFileNames(List.of(Path.of("/p/A.java"))));
        assertEquals(
                "A.java, B.java, C.java +2",
                LspCoordinator.blockedFileNames(List.of(
                        Path.of("/p/A.java"),
                        Path.of("/p/B.java"),
                        Path.of("/p/C.java"),
                        Path.of("/p/D.java"),
                        Path.of("/p/E.java"))));
    }

    // --- data-loss review: E8, E18, E19, E20 ---------------------------------------------------------

    private static WorkspaceEditMapper.Mapped deleting(Path... files) {
        return new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(),
                List.of(),
                java.util.Arrays.stream(files)
                        .map(file -> new WorkspaceEditMapper.FileDelete(file, true, false))
                        .toList());
    }

    private List<String> hiddenStages() throws IOException {
        try (var tree = Files.walk(root)) {
            return tree.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".editora-lsp-"))
                    .toList();
        }
    }

    /**
     * E8. The dirty-buffer check ran once, before files were loaded and staged; a keystroke that landed in
     * the delete target after it closed the tab with its unsaved text and removed the file.
     */
    @Test
    void aDeleteTargetThatBecomesDirtyAfterThePreflightIsNotDeleted() throws Exception {
        EditorBuffer victim = openBuffer("Victim.java", "saved line\n");
        ManualExecutor executor = new ManualExecutor();
        useControlledCoordinator(LspCoordinator.WorkspaceFileOperations.SYSTEM, executor);

        CompletableFuture<Boolean> result = applyAsync(deleting(victim.getPath()));
        FxTestSupport.runOnFx(() -> {
            executor.runNext(); // stage the delete; the finish is now queued behind this FX turn…
            victim.getArea().insertText(0, "UNSAVED WORK "); // …and so is a keystroke already in the queue
        });
        FxTestSupport.drainFx();
        assertFalse(result.isDone(), "the refusal is reported only after the staged file is back");
        executor.runNext(); // rollback
        FxTestSupport.drainFx();

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("saved line\n", Files.readString(victim.getPath()), "the file is back where it was");
        assertTrue(ops.deleted.isEmpty(), "the tab must not be closed");
        assertEquals("UNSAVED WORK saved line\n", FxTestSupport.callOnFx(victim::getContent));
        assertTrue(host.error != null && host.error.contains("Victim.java"), "the user is told why: " + host.error);
        assertEquals(List.of(), hiddenStages());
    }

    /** E8, the same race for a buffer under a deleted folder and for an overwritten create target. */
    @Test
    void aBufferUnderADeletedFolderThatBecomesDirtyAfterThePreflightBlocksTheEdit() throws Exception {
        Files.createDirectories(root.resolve("pkg"));
        EditorBuffer inside = openBuffer("pkg/Inside.java", "class Inside {}\n");
        ManualExecutor executor = new ManualExecutor();
        useControlledCoordinator(LspCoordinator.WorkspaceFileOperations.SYSTEM, executor);

        CompletableFuture<Boolean> result = applyAsync(deleting(root.resolve("pkg")));
        executor.runNext(); // list the folder for Local History
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            executor.runNext(); // stage
            inside.getArea().insertText(0, "// typed\n");
        });
        FxTestSupport.drainFx();
        executor.runNext(); // rollback
        FxTestSupport.drainFx();

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("class Inside {}\n", Files.readString(inside.getPath()));
        assertTrue(ops.deleted.isEmpty());
    }

    /** E19. One edit that cannot be placed used to be skipped while the rest was applied and reported done. */
    @Test
    void anEditThatCannotBePlacedAppliesNothingAnywhere() throws Exception {
        String firstOriginal = "class First {}\n";
        EditorBuffer first = openBuffer("First.java", firstOriginal);
        EditorBuffer second = openBuffer("Second.java", "abcdef\n");
        Path created = root.resolve("MustNotSurvive.java");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(
                        new WorkspaceEditMapper.FileEdit(
                                first.getPath(), List.of(new LspTextEdit(0, 6, 0, 11, "Changed")), null, firstOriginal),
                        new WorkspaceEditMapper.FileEdit(
                                second.getPath(),
                                List.of(new LspTextEdit(0, 0, 0, 3, "X"), new LspTextEdit(0, 2, 0, 4, "Y")),
                                null,
                                "abcdef\n")),
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(created, false, false)),
                List.of());

        CompletableFuture<Boolean> result = applyAsync(mapped);

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(firstOriginal, FxTestSupport.callOnFx(first::getContent), "the valid file is not edited either");
        assertFalse(FxTestSupport.callOnFx(first::isDirty));
        assertEquals("abcdef\n", FxTestSupport.callOnFx(second::getContent));
        assertFalse(Files.exists(created), "the staged create is rolled back");
        assertTrue(host.error != null && host.error.contains("Second.java"), "the user is told which: " + host.error);

        assertFalse(FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped)), "the direct path too");
        assertEquals(firstOriginal, FxTestSupport.callOnFx(first::getContent));
    }

    /** E18. A code action that deletes a folder or replaces a file is applied only after the user agreed. */
    @Test
    void anUnpreviewedDestructiveEditAsksFirstAndIsNotAppliedWhenDeclined() throws Exception {
        Path folder = Files.createDirectories(root.resolve("pkg"));
        Path inside = Files.writeString(folder.resolve("Inside.java"), "class Inside {}\n");
        Path existing = Files.writeString(root.resolve("Existing.java"), "precious existing content\n");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(existing, true, false)),
                List.of(new WorkspaceEditMapper.FileDelete(folder, true, false)));
        List<List<com.editora.lsp.WorkspaceEditHazards.Hazard>> asked = new ArrayList<>();
        AtomicReference<Boolean> answer = new AtomicReference<>(false);
        FxTestSupport.runOnFx(() -> coordinator.destructiveEditConfirmer = hazards -> {
            asked.add(hazards);
            return answer.get();
        });

        CompletableFuture<Boolean> declined = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> coordinator.applyWorkspaceEditsConfirmed(mapped, declined::complete));

        assertFalse(declined.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, asked.size());
        assertEquals(
                List.of(folder, existing),
                asked.get(0).stream()
                        .map(com.editora.lsp.WorkspaceEditHazards.Hazard::path)
                        .toList(),
                "the question names every path that would be lost");
        assertEquals("class Inside {}\n", Files.readString(inside));
        assertEquals("precious existing content\n", Files.readString(existing));
        assertTrue(ops.captured.isEmpty());

        answer.set(true);
        CompletableFuture<Boolean> accepted = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> coordinator.applyWorkspaceEditsConfirmed(mapped, accepted::complete));

        assertTrue(accepted.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(Files.exists(folder));
        assertEquals("", Files.readString(existing));
        // E18: what the edit destroyed was copied to Local History while it still had its content.
        assertEquals("class Inside {}\n", ops.captured.get(inside));
        assertEquals("precious existing content\n", ops.captured.get(existing));
    }

    @Test
    void anEditThatDestroysNothingIsNotAskedAbout() throws Exception {
        Path old = Files.writeString(root.resolve("Old.java"), "old");
        Path fresh = root.resolve("Fresh.java");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(),
                List.of(new WorkspaceEditMapper.FileCreate(fresh, true, false)), // nothing there to replace
                List.of(new WorkspaceEditMapper.FileDelete(old, false, false))); // one file: captured, not asked
        FxTestSupport.runOnFx(() -> coordinator.destructiveEditConfirmer = hazards -> {
            throw new AssertionError("must not ask: " + hazards);
        });

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> coordinator.applyWorkspaceEditsConfirmed(mapped, result::complete));

        assertTrue(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(Files.exists(old));
        assertEquals(Map.of(old, "old"), ops.captured, "the deleted file is in Local History");
    }

    /** E18. With history on, a file whose copy could not be kept is not deleted. */
    @Test
    void aFailedLocalHistoryCopyRefusesTheEdit() throws Exception {
        Path old = Files.writeString(root.resolve("Old.java"), "only copy");
        ops.captureSucceeds = false;

        CompletableFuture<Boolean> result = applyAsync(deleting(old));

        assertFalse(result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("only copy", Files.readString(old));
        assertTrue(host.error != null && host.error.contains("Old.java"), host.error);
    }

    /** E18, the reviewer's probe: unticking a file kept it in a folder the same edit then deleted. */
    @Test
    void aPreviewedRenameIsRefusedWhenATickedDeleteWouldRemoveAnUntickedFile() throws Exception {
        Path p = Files.createDirectories(root.resolve("p"));
        Path q = root.resolve("q");
        Path a = Files.writeString(p.resolve("A.java"), "class A {}\n");
        Path b = Files.writeString(p.resolve("B.java"), "class B { /* the user unticked this file */ }\n");
        Path existing = Files.writeString(root.resolve("Existing.java"), "precious existing content\n");
        var mapped = new WorkspaceEditMapper.Mapped(
                List.of(),
                List.of(
                        new WorkspaceEditMapper.FileRename(a, q.resolve("A.java"), false),
                        new WorkspaceEditMapper.FileRename(b, q.resolve("B.java"), false)),
                List.of(new WorkspaceEditMapper.FileCreate(existing, true, false)),
                List.of(new WorkspaceEditMapper.FileDelete(p, true, false)));
        java.util.Set<Path> listed = new java.util.LinkedHashSet<>(List.of(a, b, p, existing));

        FxTestSupport.runOnFx(() -> coordinator.applyPreviewed(mapped, java.util.Set.of(a, p, existing), listed, "q"));
        FxTestSupport.drainFx();

        assertEquals("class B { /* the user unticked this file */ }\n", Files.readString(b));
        assertTrue(Files.exists(a), "nothing of the edit is applied");
        assertEquals("precious existing content\n", Files.readString(existing));
        assertTrue(host.error != null && host.error.contains("B.java"), host.error);

        // Unticking the delete and the overwrite as well leaves a plain move of A.
        CompletableFuture<Boolean> moved = new CompletableFuture<>();
        var onlyA = com.editora.lsp.RenamePreview.filter(mapped, java.util.Set.of(a), listed);
        FxTestSupport.runOnFx(() -> coordinator.applyWorkspaceEditsAsync(onlyA, moved::complete));
        assertTrue(moved.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(Files.exists(q.resolve("A.java")));
        assertTrue(Files.exists(b));
        assertEquals("precious existing content\n", Files.readString(existing));
    }

    @Test
    void previewRowsSpellOutDeletesAndReplacements() throws Exception {
        Path p = Files.createDirectories(root.resolve("p"));
        Path existing = Files.writeString(root.resolve("Existing.java"), "x");
        var deleted = new com.editora.lsp.RenamePreview.FileChange(
                p, 0, null, com.editora.lsp.WorkspaceEditHazards.Kind.DELETE_DIRECTORY, false);
        var replaced = new com.editora.lsp.RenamePreview.FileChange(existing, 0, null, null, true);

        assertTrue(coordinator.previewLabel(deleted).contains("deleted"), coordinator.previewLabel(deleted));
        assertTrue(coordinator.previewLabel(replaced).contains("replaces"), coordinator.previewLabel(replaced));
    }

    /** E20. A finished transaction leaves no journal; one that could not roll back keeps it for recovery. */
    @Test
    void theJournalLivesExactlyAsLongAsFilesAreStaged() throws Exception {
        Path journals = root.resolve("journals");
        Path old = Files.writeString(root.resolve("Old.java"), "old");
        ManualExecutor executor = new ManualExecutor();
        useControlledCoordinator(LspCoordinator.WorkspaceFileOperations.SYSTEM, executor);
        FxTestSupport.runOnFx(() -> coordinator.workspaceEditJournalDir = journals);

        CompletableFuture<Boolean> result = applyAsync(deleting(old));
        executor.runNext(); // stage
        try (var files = Files.list(journals)) {
            assertEquals(1, files.count(), "a journal exists while the file is moved aside");
        }
        assertEquals(1, hiddenStages().size());
        assertEquals(
                List.of(),
                com.editora.lsp.WorkspaceEditJournal.pending(journals),
                "a transaction still running is not offered for recovery");
        FxTestSupport.drainFx();
        executor.runNext(); // commit
        assertTrue(result.get(10, java.util.concurrent.TimeUnit.SECONDS));

        try (var files = Files.list(journals)) {
            assertEquals(0, files.count(), "the journal goes with the staged file");
        }
        assertEquals(List.of(), hiddenStages());
    }

    /** E20. Files an interrupted transaction left staged are offered at the next project open, and restored. */
    @Test
    void anInterruptedTransactionIsOfferedAndRestoredWhenItsProjectOpens() throws Exception {
        Path journals = root.resolve("journals");
        Path project = Files.createDirectories(root.resolve("project"));
        Path lost = Files.writeString(project.resolve("Lost.java"), "class Lost {}\n");
        Path stage = project.resolve(".editora-lsp-123.deleted");
        var journal = com.editora.lsp.WorkspaceEditJournal.begin(journals);
        journal.deleting(lost, stage);
        Files.move(lost, stage);
        journal.abandon(); // the process died here

        List<List<Path>> offered = new ArrayList<>();
        ops.projectRoot = root.resolve("another-project");
        FxTestSupport.runOnFx(() -> {
            coordinator.workspaceEditJournalDir = journals;
            coordinator.interruptedEditPrompt = interrupted -> {
                offered.add(interrupted.originals());
                return LspCoordinator.InterruptedEditChoice.RESTORE;
            };
            coordinator.offerInterruptedEdits();
        });
        Thread.sleep(300);
        FxTestSupport.drainFx();
        assertTrue(offered.isEmpty(), "another project's leftovers are not this window's to offer");
        assertTrue(Files.exists(stage), "and nothing is touched without being asked");

        ops.projectRoot = project;
        FxTestSupport.runOnFx(() -> coordinator.offerInterruptedEdits());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(lost) && System.nanoTime() < deadline) {
            Thread.sleep(20);
            FxTestSupport.drainFx();
        }

        assertEquals(List.of(List.of(lost)), offered);
        assertEquals("class Lost {}\n", Files.readString(lost));
        assertFalse(Files.exists(stage));
    }

    @Test
    void decliningARecoveryLeavesEveryFileWhereItIs() throws Exception {
        Path journals = root.resolve("journals");
        Path project = Files.createDirectories(root.resolve("project"));
        Path lost = Files.writeString(project.resolve("Lost.java"), "class Lost {}\n");
        Path stage = project.resolve(".editora-lsp-456.deleted");
        var journal = com.editora.lsp.WorkspaceEditJournal.begin(journals);
        journal.deleting(lost, stage);
        Files.move(lost, stage);
        journal.abandon();
        CompletableFuture<Boolean> asked = new CompletableFuture<>();
        ops.projectRoot = project;

        FxTestSupport.runOnFx(() -> {
            coordinator.workspaceEditJournalDir = journals;
            coordinator.interruptedEditPrompt = interrupted -> {
                asked.complete(true);
                return LspCoordinator.InterruptedEditChoice.LATER;
            };
            coordinator.offerInterruptedEdits();
        });

        assertTrue(asked.get(10, java.util.concurrent.TimeUnit.SECONDS));
        FxTestSupport.drainFx();
        assertEquals("class Lost {}\n", Files.readString(stage));
        assertFalse(Files.exists(lost));
        try (var files = Files.list(journals)) {
            assertEquals(1, files.count(), "\"not now\" keeps the journal for the next project open");
        }
    }
}

package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    }

    private static final class FakeOps extends LspOpsStub {
        final Map<Path, EditorBuffer> open = new HashMap<>();
        final List<Path[]> renamed = new ArrayList<>();

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

    /** Creates the file and an open buffer for it, registered with the fakes. */
    private EditorBuffer openBuffer(String name, String text) throws Exception {
        Path f = root.resolve(name);
        Files.writeString(f, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(f);
            x.setContent(text);
            host.buffers.add(x);
            host.active = x;
            return x;
        });
        ops.open.put(f.toAbsolutePath().normalize(), b);
        return b;
    }

    private static TextDocumentEdit edit(Path file, int line, int startCol, int endCol, String newText) {
        var id = new VersionedTextDocumentIdentifier(file.toUri().toString(), 1);
        var te = new TextEdit(new Range(new Position(line, startCol), new Position(line, endCol)), newText);
        return new TextDocumentEdit(id, List.of(te));
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

    // --- what the mapper refuses outright ------------------------------------------------------------

    /** Create/delete resource operations are not supported; the mapper refuses so nothing half-applies. */
    @Test
    void aCreateOrDeleteResourceOperationIsRefusedByTheMapper() {
        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(Either.forRight(new org.eclipse.lsp4j.CreateFile(
                root.resolve("New.java").toUri().toString()))));

        assertTrue(WorkspaceEditMapper.map(we) == null, "an unsupported resource op must refuse the whole edit");
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

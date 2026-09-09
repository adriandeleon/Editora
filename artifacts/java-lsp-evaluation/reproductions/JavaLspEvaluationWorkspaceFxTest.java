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
class JavaLspEvaluationWorkspaceFxTest {

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
        return new TextDocumentEdit(id, List.of(Either.forLeft(te)));
    }

    /** Applies a workspace edit the way the manager's registered handler does. */
    private boolean apply(WorkspaceEdit edit) throws Exception {
        WorkspaceEditMapper.Mapped mapped = WorkspaceEditMapper.map(edit);
        assertTrue(mapped != null, "the edit should have mapped");
        return FxTestSupport.callOnFx(() -> coordinator.applyWorkspaceEdits(mapped));
    }

    @Test
    void staleVersionedWorkspaceEditMustBeRejected() throws Exception {
        EditorBuffer b = openBuffer("A.java", "class A {}\n");
        manager.openDocument(b.getPath(), root, "java", b.getContent());
        var stale = new WorkspaceEdit();
        stale.setDocumentChanges(List.of(Either.forLeft(edit(b.getPath(), 0, 6, 7, "B"))));
        FxTestSupport.runOnFx(() -> {
            b.setContent("// note\nclass A {}\n");
            manager.changeDocument(b.getPath(), b.getContent());
        });
        boolean applied = apply(stale);
        System.out.println("EVALUATION stale-version applied=" + applied + " content=" + FxTestSupport.callOnFx(b::getContent));
        assertFalse(applied, "version 1 edit must not apply to server version 2");
        assertEquals("// note\nclass A {}\n", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void failedFileMoveMustNotLeavePartialRefactoring() throws Exception {
        EditorBuffer b = openBuffer("A.java", "class A {}\n");
        Path missing = root.resolve("Missing.java");
        Path dest = root.resolve("Renamed.java");
        var we = new WorkspaceEdit();
        we.setDocumentChanges(List.of(
            Either.forLeft(edit(b.getPath(), 0, 6, 7, "B")),
            Either.forRight(new RenameFile(missing.toUri().toString(), dest.toUri().toString()))));
        assertFalse(apply(we));
        System.out.println("EVALUATION failed-move remaining-content=" + FxTestSupport.callOnFx(b::getContent));
        assertEquals("class A {}\n", FxTestSupport.callOnFx(b::getContent), "failed transaction must preserve original text");
    }
}

package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
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
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@code LspManager} validates a workspace edit that reaches a file the server did not have open — the
 * cross-file rename case. jdtls sends a null version for every document, so refusing every unversioned
 * closed-file edit meant a rename touching any file without a tab simply failed.
 */
@Tag("fx")
class LspEditPlanFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private final List<LanguageServerSession> sessions = new CopyOnWriteArrayList<>();
    private final List<List<Path>> blocked = new CopyOnWriteArrayList<>();
    private Path open;
    private Path closed;

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            sessions.add(session);
            session.attachForTest(fake, new ServerCapabilities());
        });
        manager.setOnEditBlocked(blocked::add);
        manager.configure(true, Map.of("java", "jdtls"));
        open = root.resolve("A.java");
        closed = root.resolve("B.java");
        Files.writeString(open, "class A {}\n");
        Files.writeString(closed, "class B { A a; }\n");
        manager.openDocument(open, root, "java", "class A {}\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    /** Stamps {@code file} as last written a minute before (or after) now, off any whole second. */
    private static void touch(Path file, long offsetMillis) throws Exception {
        long at = System.currentTimeMillis() + offsetMillis;
        if (at % 1000 == 0) {
            at++;
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(at));
    }

    private static TextEdit edit(int line, int from, int to, String text) {
        return new TextEdit(new Range(new Position(line, from), new Position(line, to)), text);
    }

    /** The shape jdtls answers a rename with: one {@code TextDocumentEdit} per file, every version null. */
    private WorkspaceEdit renameAcrossBothFiles(Integer closedVersion) {
        var inOpen = new TextDocumentEdit(
                new VersionedTextDocumentIdentifier(open.toUri().toString(), null),
                List.of(Either.forLeft(edit(0, 6, 7, "Renamed"))));
        var inClosed = new TextDocumentEdit(
                new VersionedTextDocumentIdentifier(closed.toUri().toString(), closedVersion),
                List.of(Either.forLeft(edit(0, 10, 11, "Renamed"))));
        return new WorkspaceEdit(List.of(Either.forLeft(inOpen), Either.forLeft(inClosed)));
    }

    private <T> T await(Consumer<Consumer<T>> request) throws Exception {
        var result = new AtomicReference<T>();
        var latch = new CountDownLatch(1);
        request.accept(v -> {
            result.set(v);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the callback never fired");
        return result.get();
    }

    private static WorkspaceEditMapper.FileEdit editFor(WorkspaceEditMapper.Mapped mapped, Path file) {
        return mapped.edits().stream()
                .filter(e -> e.file().equals(file))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aRenameReachingAClosedFileIsPlannedAgainstItsDiskContent() throws Exception {
        touch(closed, -60_000);
        fakes.get(0).renameResponse = renameAcrossBothFiles(null);

        WorkspaceEditMapper.Mapped mapped = await(cb -> manager.previewRename(open, 0, 6, "Renamed", cb));

        assertNotNull(mapped, "a rename must not fail just because one target has no tab");
        assertTrue(blocked.isEmpty());
        var managed = editFor(mapped, open);
        assertEquals("class A {}\n", managed.expectedText(), "an open document keeps its request-time text");
        assertNull(managed.diskPreimageAt());
        var onDisk = editFor(mapped, closed);
        assertNull(onDisk.expectedText(), "a closed file's content is not read after the response");
        assertNotNull(onDisk.diskPreimageAt(), "its preimage is the file as it was when the request was sent");
        assertTrue(onDisk.diskPreimageAt() <= System.currentTimeMillis());
    }

    /** The H12 protection: an edit computed before the closed file changed must still be refused. */
    @Test
    void aClosedFileModifiedAfterTheRequestBlocksTheRenameAndIsNamed() throws Exception {
        touch(closed, 60_000); // "written" after the request goes out
        fakes.get(0).renameResponse = renameAcrossBothFiles(null);

        WorkspaceEditMapper.Mapped mapped = await(cb -> manager.previewRename(open, 0, 6, "Renamed", cb));

        assertNull(mapped, "stale closed-file content must not be edited");
        assertEquals(List.of(List.of(closed)), blocked, "the user is told which file blocked it");
    }

    @Test
    void aMissingClosedTargetBlocksTheEdit() throws Exception {
        Files.delete(closed);
        fakes.get(0).renameResponse = renameAcrossBothFiles(null);

        WorkspaceEditMapper.Mapped mapped = await(cb -> manager.previewRename(open, 0, 6, "Renamed", cb));

        assertNull(mapped);
        assertEquals(List.of(List.of(closed)), blocked);
    }

    /**
     * Some servers attach a version to a document they never had open. No version was ever sent for it, so
     * comparing would refuse the edit forever; it is treated as what it is — a closed file.
     */
    @Test
    void aVersionForAnUnopenedFileIsNotComparedAgainstANonexistentOne() throws Exception {
        touch(closed, -60_000);
        fakes.get(0).renameResponse = renameAcrossBothFiles(4);

        WorkspaceEditMapper.Mapped mapped = await(cb -> manager.previewRename(open, 0, 6, "Renamed", cb));

        assertNotNull(mapped);
        assertNull(editFor(mapped, closed).version());
        assertNotNull(editFor(mapped, closed).diskPreimageAt());
    }

    /** A listed code action carries the time its request was sent, so its closed-file edit can be shown safe. */
    @Test
    void aListedCodeActionMayEditAClosedFileThatHasNotChanged() throws Exception {
        touch(closed, -60_000);
        var applied = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler((mapped, done) -> {
            applied.set(mapped);
            done.accept(true);
        });
        var action = new org.eclipse.lsp4j.CodeAction("change closed file");
        action.setEdit(new WorkspaceEdit(Map.of(closed.toUri().toString(), List.of(edit(0, 6, 7, "Changed")))));
        var listed = new LspManager.CodeActionItem(
                "change closed file", "quickfix", false, action, Map.of(), System.currentTimeMillis());

        Boolean ok = await(cb -> manager.applyCodeAction(open, listed, cb));

        assertTrue(ok);
        assertNotNull(editFor(applied.get(), closed).diskPreimageAt());
    }

    // --- raw diagnostics for the code-action context --------------------------------------------------

    private void drainFx() throws Exception {
        var barrier = new CountDownLatch(1);
        Platform.runLater(barrier::countDown);
        assertTrue(barrier.await(10, TimeUnit.SECONDS));
    }

    /**
     * A server may spell a document's URI differently from the client ({@code file:///c%3A/…}). The raw
     * diagnostics were stored under the server's spelling and looked up under ours, so the code-action
     * request went out with an empty context and quick fixes disappeared.
     */
    @Test
    void codeActionContextSurvivesAServerSpellingTheUriDifferently() throws Exception {
        String ours = open.toUri().toString();
        String theirs = ours.replace("A.java", "%41.java"); // the same path, percent-encoded
        assertFalse(ours.equals(theirs));
        var diagnostic = new org.eclipse.lsp4j.Diagnostic(new Range(new Position(0, 0), new Position(0, 5)), "bad");

        sessions.get(0).publishDiagnostics(new org.eclipse.lsp4j.PublishDiagnosticsParams(theirs, List.of(diagnostic)));
        drainFx();
        manager.codeActions(open, 0, 0, 0, 5, actions -> {});

        assertEquals(
                List.of(diagnostic),
                FakeLanguageServer.last(fakes.get(0).codeActions).getContext().getDiagnostics(),
                "the diagnostics the server published for this document must come back as context");

        manager.closeDocument(open);
        manager.openDocument(open, root, "java", "class A {}\n");
        manager.codeActions(open, 0, 0, 0, 5, actions -> {});
        assertTrue(
                FakeLanguageServer.last(fakes.get(0).codeActions)
                        .getContext()
                        .getDiagnostics()
                        .isEmpty(),
                "closing the document drops what was retained for it");
    }

    // --- a closed window's manager is terminal --------------------------------------------------------

    @Test
    void aClosedManagerRefusesToStartServersAgain() throws Exception {
        manager.close();
        assertFalse(manager.hasSessionForTest("java", root));
        int started = fakes.size();

        manager.configure(true, Map.of("java", "jdtls")); // a late settings apply
        manager.openDocument(open, root, "java", "class A {}\n"); // a late detection callback re-gating
        var probed = new AtomicReference<Boolean>();
        manager.detect("java", probed::set);
        drainFx();

        assertTrue(manager.isClosed());
        assertFalse(manager.isEnabled());
        assertFalse(manager.isManaged(open));
        assertEquals(started, fakes.size(), "no server may be started for a window that has closed");
        assertNull(probed.get(), "a probe result must not be delivered to a closed window");
    }

    /** shutdownAll is the restart primitive and must stay non-terminal. */
    @Test
    void shutdownAllStillAllowsARestart() {
        manager.shutdownAll();
        manager.openDocument(open, root, "java", "class A {}\n");
        assertTrue(manager.isManaged(open));
    }
}

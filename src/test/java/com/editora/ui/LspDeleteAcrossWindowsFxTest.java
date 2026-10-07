package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.WorkspaceEditMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workspace edit's {@code DeleteFile} ends in {@code onProjectFileDeleted}, which closes the file's tab
 * in <em>every</em> window. So the dirty-buffer check that guards it has to see every window too — and has
 * to be asked again where the transaction commits, because the delete is staged across several FX turns
 * in which the user (in any window) can keep typing.
 *
 * <p>Two real windows, one real coordinator: the edit is applied from window A while the file is open in
 * window B only.
 */
@Tag("fx")
class LspDeleteAcrossWindowsFxTest {

    private FxWindowFixture fx;
    private MainController a;
    private MainController b;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        a = fx.controller;
        b = FxTestSupport.callOnFx(() -> {
            fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            return (MainController)
                    FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
        });
        assertNotSame(a, b, "a genuinely second window");
        file = Files.writeString(
                Files.createDirectories(fx.configDir.resolve("work")).resolve("Victim.txt"), "saved\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** Opens {@link #file} in window B and waits for the load to land. */
    private EditorBuffer openInB() throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(b, "fileWorkflows"), "openPath", new Class<?>[] {Path.class}, file));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> bufferInB());
            if (buffer != null && FxTestSupport.callOnFx(() -> "saved\n".equals(buffer.getContent()))) {
                return buffer;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the file did not open in window B");
    }

    private EditorBuffer bufferInB() {
        return (EditorBuffer) FxTestSupport.call(b, "openBufferFor", new Class<?>[] {Path.class}, file);
    }

    private WorkspaceEditMapper.Mapped delete() {
        return new WorkspaceEditMapper.Mapped(
                List.of(), List.of(), List.of(), List.of(new WorkspaceEditMapper.FileDelete(file, false, false)));
    }

    private void assertNothingLost(EditorBuffer buffer) throws Exception {
        assertEquals("saved\n", Files.readString(file), "the file is still on disk");
        assertNotNull(FxTestSupport.callOnFx(this::bufferInB), "window B still has its tab");
        assertFalse(FxTestSupport.callOnFx(buffer::isDisposed));
        assertEquals("UNSAVED WORK saved\n", FxTestSupport.callOnFx(buffer::getContent));
        try (var siblings = Files.list(file.getParent())) {
            assertEquals(
                    List.of("Victim.txt"),
                    siblings.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aFileWithUnsavedChangesInAnotherWindowIsNotDeleted() throws Exception {
        EditorBuffer buffer = openInB();
        LspCoordinator lspOfA = FxTestSupport.field(a, "lspCoordinator");
        CompletableFuture<Boolean> done = new CompletableFuture<>();

        FxTestSupport.runOnFx(() -> {
            buffer.getArea().insertText(0, "UNSAVED WORK ");
            lspOfA.applyWorkspaceEditsAsync(delete(), done::complete);
        });

        assertFalse(done.get(20, TimeUnit.SECONDS));
        FxTestSupport.drainFx();
        assertNothingLost(buffer);
    }

    /** The reviewer's probe, across windows: the keystroke lands after the preflight has already passed. */
    @Test
    void aFileThatBecomesDirtyInAnotherWindowWhileTheDeleteIsStagedIsNotDeleted() throws Exception {
        EditorBuffer buffer = openInB();
        LspCoordinator lspOfA = FxTestSupport.field(a, "lspCoordinator");
        CompletableFuture<Boolean> done = new CompletableFuture<>();

        FxTestSupport.runOnFx(() -> {
            lspOfA.applyWorkspaceEditsAsync(delete(), done::complete);
            // Where a key event already in the FX queue runs: after the preflight, before the finish.
            buffer.getArea().insertText(0, "UNSAVED WORK ");
        });

        assertFalse(done.get(20, TimeUnit.SECONDS));
        FxTestSupport.drainFx();
        assertNothingLost(buffer);
    }

    @Test
    void aCleanFileOpenInAnotherWindowIsDeletedAndItsTabClosed() throws Exception {
        openInB();
        LspCoordinator lspOfA = FxTestSupport.field(a, "lspCoordinator");
        CompletableFuture<Boolean> done = new CompletableFuture<>();

        FxTestSupport.runOnFx(() -> lspOfA.applyWorkspaceEditsAsync(delete(), done::complete));

        assertTrue(done.get(20, TimeUnit.SECONDS));
        assertFalse(Files.exists(file));
        assertEquals(null, FxTestSupport.callOnFx(this::bufferInB), "the tab follows the file in every window");
        // What the edit deleted was copied to Local History first, so it can be brought back.
        com.editora.config.ConfigManager config = FxTestSupport.field(a, "config");
        List<com.editora.config.HistoryRevision> revisions =
                config.getHistory().get(com.editora.config.PathKeys.normalizedKey(file));
        assertNotNull(revisions, "the deleted file is in Local History");
        com.editora.config.HistoryRevision revision = revisions.stream()
                .filter(item -> com.editora.config.HistoryRevision.REASON_DELETE.equals(item.reason()))
                .findFirst()
                .orElseThrow();
        com.editora.history.HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
        assertEquals("saved\n", blobs.get(revision.sha256()));
    }
}

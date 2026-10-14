package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.lsp.LspManager;
import com.editora.lsp.WorkspaceEditJournal;
import com.editora.lsp.WorkspaceEditMapper;
import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.Vfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cross-file workspace edit on a remote project: its creates, renames and deletes are staged and committed
 * over SFTP, its crash journal names the remote files so they can be put back after a restart, and the check
 * that a closed file has not changed since the request reads the file's time by its host's clock.
 */
@Tag("fx")
class RemoteWorkspaceEditFxTest {

    @TempDir
    Path base;

    @TempDir
    Path local;

    private EmbeddedSftpFixture sftp;

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sftp != null) {
            sftp.close();
        }
        ExecTargets.setRemoteEnabled(null);
    }

    private Path journals() {
        return local.resolve("journals");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 6}) // OpenSSH speaks 3; MINA's server 6
    void createsRenamesAndDeletesAreAppliedOnTheHost(int protocol) throws Exception {
        sftp = EmbeddedSftpFixture.start(base);
        Path app = Files.createDirectories(sftp.serverPath("app"));
        Files.writeString(app.resolve("Old.py"), "old\n");
        Files.writeString(app.resolve("Gone.py"), "gone\n");
        Path root = sftp.speakProtocolVersion(protocol).resolve("app");
        var host = new LspCoordinatorFixture.Host();
        var ops = new LspCoordinatorFixture.Ops();
        ops.projectRoot = root;
        LspManager manager = new LspManager((f, d) -> {}, (t, m) -> {});
        try {
            LspCoordinator coordinator = FxTestSupport.callOnFx(() -> {
                LspCoordinator c = new LspCoordinator(
                        host, manager, ops, LspCoordinator.WorkspaceFileOperations.SYSTEM, Runnable::run);
                c.workspaceEditJournalDir = journals();
                return c;
            });
            var mapped = new WorkspaceEditMapper.Mapped(
                    List.of(),
                    List.of(new WorkspaceEditMapper.FileRename(
                            root.resolve("Old.py"), root.resolve("pkg/New.py"), false)),
                    List.of(new WorkspaceEditMapper.FileCreate(root.resolve("Made.py"), false, false)),
                    List.of(new WorkspaceEditMapper.FileDelete(root.resolve("Gone.py"), false, false)));

            CompletableFuture<Boolean> applied = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> coordinator.applyWorkspaceEditsAsync(mapped, applied::complete));

            assertTrue(applied.get(30, TimeUnit.SECONDS), "errors: " + host.errors);
            assertEquals("old\n", Files.readString(app.resolve("pkg/New.py")));
            assertFalse(Files.exists(app.resolve("Old.py")));
            assertTrue(Files.isRegularFile(app.resolve("Made.py")));
            assertFalse(Files.exists(app.resolve("Gone.py")));
            try (var left = Files.list(app)) {
                assertEquals(
                        List.of(),
                        left.filter(p -> p.getFileName().toString().startsWith(".editora-lsp-"))
                                .toList(),
                        "no staged copies are left on the host");
            }
            assertEquals(List.of(), WorkspaceEditJournal.pending(journals()), "the journal went with the edit");
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void anInterruptedRemoteEditIsFoundByItsHostAndPutBack() throws Exception {
        sftp = EmbeddedSftpFixture.start(base);
        Path app = Files.createDirectories(sftp.serverPath("app"));
        Files.writeString(app.resolve("A.py"), "precious\n");
        Path root = sftp.remotePath("app");
        Path file = root.resolve("A.py");
        Path stage = root.resolve(".editora-lsp-1.deleted");
        WorkspaceEditJournal journal = WorkspaceEditJournal.begin(journals());
        journal.deleting(file, stage);
        Files.move(file, stage);
        journal.abandon(); // the process dies here

        try (var written = Files.list(journals())) {
            String text = Files.readString(written.findFirst().orElseThrow());
            assertTrue(text.contains("sftp%3A%2F%2F"), "a remote path is journalled with its host: " + text);
        }

        // Opened without that host connected, the journal is left for later, not discarded as finished.
        sftp.disconnect();
        assertEquals(List.of(), WorkspaceEditJournal.pending(journals()));
        try (var kept = Files.list(journals())) {
            assertEquals(1, kept.count(), "the journal is kept until its host is back");
        }

        root = sftp.reconnect().resolve("app");
        List<WorkspaceEditJournal.Interrupted> pending = WorkspaceEditJournal.pending(journals());
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).touches(root));
        assertFalse(pending.get(0).touches(Path.of(sftp.serverPath("app").toString())), "not the same local path");
        var restored = pending.get(0).restore();

        assertEquals(1, restored.restored().size());
        assertEquals("precious\n", Files.readString(app.resolve("A.py")));
        assertFalse(Files.exists(app.resolve(".editora-lsp-1.deleted")));
        assertEquals(
                Vfs.toStorableString(root.resolve("A.py")),
                Vfs.toStorableString(restored.restored().get(0)));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // reads the host's clock through a real shell
    void aClosedFileIsJudgedByItsHostsClock() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        Path onServer = Files.writeString(sftp.serverPath("old.py"), "x\n");
        Files.setLastModifiedTime(onServer, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        Path old = sftp.remotePath("old.py");
        Path fresh = Files.writeString(sftp.remotePath("fresh.py"), "y\n");
        long now = System.currentTimeMillis();

        assertTrue(WorkspaceEditMapper.unchangedSince(
                old, Files.getLastModifiedTime(old).toMillis(), now));
        assertFalse(
                WorkspaceEditMapper.unchangedSince(
                        fresh, Files.getLastModifiedTime(fresh).toMillis(), now),
                "written just now: within the clock's and the timestamp's uncertainty");

        ExecTargets.setRemoteEnabled(() -> false); // nothing can be asked of the host: nothing is proven
        assertFalse(WorkspaceEditMapper.unchangedSince(
                old, Files.getLastModifiedTime(old).toMillis(), now));
    }
}

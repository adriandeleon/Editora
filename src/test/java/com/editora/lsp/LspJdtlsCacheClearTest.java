package com.editora.lsp;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LspManager#clearJdtlsWorkspaces} ({@code lsp.clearJavaIndexCaches}): nothing else ever deletes a jdtls
 * workspace, so the folder grew by one per Java project ever opened. The clear must free the idle ones and never
 * touch one a server is using.
 */
class LspJdtlsCacheClearTest {

    @TempDir
    Path tempDir;

    private Path workspace(String name, int bytes) throws Exception {
        Path ws = tempDir.resolve(name);
        Files.createDirectories(ws.resolve(".metadata"));
        Files.write(ws.resolve(".metadata/index.dat"), new byte[bytes]);
        return ws;
    }

    @Test
    void idleWorkspacesAreDeletedAndTheirSizeReported() throws Exception {
        Path a = workspace("clear-idle-a", 1000);
        Path b = workspace("clear-idle-b-2", 500);

        var result = LspManager.clearJdtlsWorkspaces(tempDir);

        assertEquals(new LspManager.JdtlsCacheClearResult(2, 1500, 0), result);
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertTrue(Files.isDirectory(tempDir), "the base folder itself stays");
    }

    @Test
    void aWorkspaceClaimedByALiveSessionIsKept() throws Exception {
        Path ws = workspace("clear-claimed", 100);
        String claimed = LspManager.claimUsableJdtlsWorkspaceName(tempDir, "clear-claimed");
        try {
            assertEquals("clear-claimed", claimed);
            var result = LspManager.clearJdtlsWorkspaces(tempDir);
            assertEquals(new LspManager.JdtlsCacheClearResult(0, 0, 1), result);
            assertTrue(Files.exists(ws.resolve(".metadata/index.dat")));
        } finally {
            LspManager.releaseJdtlsWorkspaceName(claimed);
        }
        // The clear released only its own claims: the session's claim was still in force above, and the name
        // is claimable again now that the session let it go.
        String again = LspManager.claimUsableJdtlsWorkspaceName(tempDir, "clear-claimed");
        LspManager.releaseJdtlsWorkspaceName(again);
        assertEquals("clear-claimed", again);
    }

    @Test
    void aWorkspaceLockedByAnotherServerIsKept() throws Exception {
        Path ws = workspace("clear-locked", 100);
        Path idle = workspace("clear-unlocked", 100);
        Path lockFile = ws.resolve(".metadata/.lock");
        try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
            var result = LspManager.clearJdtlsWorkspaces(tempDir);
            assertEquals(1, result.deleted());
            assertEquals(1, result.kept());
        }
        assertTrue(Files.exists(ws.resolve(".metadata/index.dat")), "a locked index is never half-deleted");
        assertFalse(Files.exists(idle));
    }

    @Test
    void failureMarkersGoWithTheirWorkspace() throws Exception {
        workspace("clear-marked", 10);
        Path marker = tempDir.resolve(".clear-marked.initialize-failed");
        Path orphan = tempDir.resolve(".clear-gone.initialize-failed");
        Files.writeString(marker, "initialize failed");
        Files.writeString(orphan, "initialize failed");
        Path unrelated = tempDir.resolve("notes.txt");
        Files.writeString(unrelated, "not a workspace");

        LspManager.clearJdtlsWorkspaces(tempDir);

        assertFalse(Files.exists(marker));
        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(unrelated), "only workspace folders and their markers are touched");
    }

    @Test
    void aMissingBaseClearsNothing() {
        var none = new LspManager.JdtlsCacheClearResult(0, 0, 0);
        assertEquals(none, LspManager.clearJdtlsWorkspaces(tempDir.resolve("never-created")));
        assertEquals(none, LspManager.clearJdtlsWorkspaces(null));
    }
}

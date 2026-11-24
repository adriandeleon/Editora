package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A new file under a folder that does not exist yet (Find File on {@code new/dir/A.java}) roots its language
 * server at that folder. The server process still has to start: before, it was started in the missing folder and
 * failed with "Exec failed, error: 2" (smoke test 2026-11-24).
 */
class LanguageServerSessionLaunchDirectoryTest {

    @Test
    void anExistingRootIsWhereTheServerStarts(@TempDir Path dir) {
        assertEquals(dir.toAbsolutePath(), LanguageServerSession.launchDirectory(dir));
    }

    @Test
    void aMissingRootStartsTheServerInItsNearestExistingParent(@TempDir Path dir) {
        Path missing = dir.resolve("not").resolve("made").resolve("yet");
        assertEquals(dir.toAbsolutePath(), LanguageServerSession.launchDirectory(missing));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aServerRootedInAMissingFolderStillStarts(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("not").resolve("made");
        Path log = dir.resolve("requests.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var spec = new LspServerRegistry.ServerSpec(
                "test",
                List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        LspCancelRequestProcessTest.SlowServer.class.getName(),
                        log.toString()),
                List.of());
        var session = new LanguageServerSession(spec, missing, d -> {}, (type, message) -> {});
        try {
            assertTrue(session.start(), "the server did not launch for a root that does not exist yet");
            assertTrue(Files.notExists(missing), "starting the server must not make the folder");
        } finally {
            session.dispose();
        }
    }
}

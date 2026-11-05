package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server's folder on another machine is listed again and the difference reported: {@code git pull} on the host
 * created a module the server never heard of (Python review 2, B108). Listed here on a local folder, which the
 * scanner reads the same way as an SFTP one.
 */
class RemoteRootScannerTest {

    @Test
    void theDifferenceBetweenTwoListingsIsReported() {
        Path a = Path.of("/r/a.py");
        Path b = Path.of("/r/b.py");
        Path c = Path.of("/r/c.py");
        var changes = RemoteRootScanner.diff(Map.of(a, 1L, b, 1L), Map.of(a, 2L, c, 1L));
        assertEquals(3, changes.size());
        assertTrue(changes.contains(new LspManager.WatchedFile(a, LspManager.WatchedKind.CHANGED)));
        assertTrue(changes.contains(new LspManager.WatchedFile(b, LspManager.WatchedKind.DELETED)));
        assertTrue(changes.contains(new LspManager.WatchedFile(c, LspManager.WatchedKind.CREATED)));
        assertTrue(RemoteRootScanner.diff(Map.of(a, 1L), Map.of(a, 1L)).isEmpty());
    }

    @Test
    void aModuleCreatedSinceTheLastListingIsReportedAndEnvironmentsAreSkipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("app.py"), "import newmod\n");
        Path venv = Files.createDirectories(tmp.resolve("venv"));
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr\n");
        List<LspManager.WatchedFile> seen = new CopyOnWriteArrayList<>();
        try (RemoteRootScanner scanner = new RemoteRootScanner(tmp, seen::addAll, () -> false)) {
            scanner.scanAndReport(); // the baseline: nothing to report
            assertTrue(seen.isEmpty());
            Path module = Files.writeString(
                    Files.createDirectories(tmp.resolve("pkg")).resolve("newmod.py"), "N = 1\n");
            Files.writeString(venv.resolve("installed.py"), "");
            scanner.scanAndReport();
            assertEquals(List.of(new LspManager.WatchedFile(module, LspManager.WatchedKind.CREATED)), seen);
        }
        assertFalse(RemoteRootScanner.list(tmp).containsKey(venv.resolve("pyvenv.cfg")), "an environment is skipped");
    }
}

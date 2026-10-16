package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.PathKeys;
import com.editora.config.TrustStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which folder the HTTP trust dialog offers, compared the way the trust store compares it (S6). */
class HttpSecretGateTest {

    @Test
    void aFileInsideTheProjectOffersTheProject(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Path sub = Files.createDirectories(project.resolve("api"));
        assertEquals(PathKeys.canonical(project), HttpSecretGate.trustRoot(sub.resolve("a.http"), project));
        Path outside = Files.createDirectories(dir.resolve("elsewhere"));
        assertEquals(PathKeys.canonical(outside), HttpSecretGate.trustRoot(outside.resolve("a.http"), project));
        assertEquals(PathKeys.canonical(outside), HttpSecretGate.trustRoot(outside.resolve("a.http"), null));
    }

    @Test
    void aLinkedSubfolderOffersTheRealFolderAndTrustingItCoversTheNextRun(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("p"));
        Path real = Files.createDirectories(dir.resolve("real"));
        Path link = project.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException noLinks) {
            Assumptions.abort("symbolic links are not available here");
        }
        Path file = link.resolve("a.http");
        Path offered = HttpSecretGate.trustRoot(file, project);
        assertEquals(PathKeys.canonical(real), offered, "the folder that is actually checked, not the project");
        TrustStore store = new TrustStore();
        store.trust(offered);
        assertTrue(store.isTrusted(PathKeys.canonical(file.getParent())), "the next run is not asked again");
    }
}

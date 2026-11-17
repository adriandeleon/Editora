package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01: the language servers' files a window's set-up needs (the bundled XML schemas and their catalog, the
 * loose-files root) are made by the launch off the FX thread, and a window then finds them made without looking.
 */
class StartupAheadTest {

    private final List<String> heard = new CopyOnWriteArrayList<>();

    private void listen() {
        Thread self = Thread.currentThread();
        BlockingSites.listenForTest((site, thread) -> {
            if (thread == self) {
                heard.add(site);
            }
        });
    }

    @AfterEach
    void stop() {
        BlockingSites.listenForTest(null);
    }

    @Test
    void theCatalogEnsuredInThisRunIsNotLookedForAgain(@TempDir Path config) throws Exception {
        Path workDir = config.resolve("cache/lemminx");
        listen();
        assertNull(XmlBundledSchemas.catalogBeside(workDir), "not written yet: looked for, and missing");
        assertEquals(List.of("XmlBundledSchemas.catalogBeside"), heard);

        Path catalog = XmlBundledSchemas.ensure(config);
        heard.clear();
        assertEquals(catalog, XmlBundledSchemas.catalogBeside(workDir));
        assertEquals(List.of(), heard, "ensured in this run: no look at the disk");
        assertNull(XmlBundledSchemas.catalogBeside(null));
    }

    @Test
    void ensuringInTheBackgroundWritesTheSchemasOffTheCallingThread(@TempDir Path config) throws Exception {
        XmlBundledSchemas.ensureInBackground(null); // nothing to do
        Path catalog = XmlBundledSchemas.dir(config).resolve("catalog.xml");
        listen();
        XmlBundledSchemas.ensureInBackground(config);
        assertEquals(List.of(), heard, "nothing on the calling thread");
        com.editora.testkit.Await.holdsWithin(() -> Files.isRegularFile(catalog), java.time.Duration.ofSeconds(10));
        assertTrue(Files.isRegularFile(catalog), "written by the background run");
    }

    @Test
    void theLooseRootIsMadeOncePerRun(@TempDir Path config) throws Exception {
        Path root = config.resolve("lsp/loose-files").toAbsolutePath().normalize();
        listen();
        assertTrue(LspManager.makeLooseRoot(root));
        assertTrue(Files.isDirectory(root));
        assertTrue(LspManager.makeLooseRoot(root));
        assertEquals(List.of("LspManager.makeLooseRoot"), heard, "made once, then known");

        Path blocked = Files.writeString(config.resolve("a-file"), "x").resolve("loose");
        assertFalse(LspManager.makeLooseRoot(blocked), "a root that cannot be made is refused");
    }
}

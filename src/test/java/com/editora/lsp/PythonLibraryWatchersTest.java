package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a Python server hears about its environment (Python review 2, B102, B103). */
class PythonLibraryWatchersTest {

    private static Path venv(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("pyvenv.cfg"), "home = /usr/bin\n");
        Files.writeString(dir.resolve("bin/python"), "");
        return Files.createDirectories(dir.resolve("lib/python3.13/site-packages"));
    }

    @Test
    void aVirtualEnvironmentsSitePackagesAreFound(@TempDir Path tmp) throws Exception {
        Path sp = venv(tmp.resolve(".venv"));
        assertEquals(
                List.of(sp),
                PythonLibraryWatchers.sitePackages(
                        tmp.resolve(".venv/bin/python").toString()));
        assertEquals(List.of(), PythonLibraryWatchers.sitePackages("/usr/bin/python3"), "a system Python: none");
        assertEquals(List.of(), PythonLibraryWatchers.sitePackages("python3"));
    }

    @Test
    void aPackageInstalledIntoSitePackagesIsReported(@TempDir Path tmp) throws Exception {
        Path sp = venv(tmp.resolve(".venv"));
        List<LspManager.WatchedFile> seen = new CopyOnWriteArrayList<>();
        try (RootWatcher w = RootWatcher.startShallow(sp, seen::addAll)) {
            assertNotNull(w);
            Path pkg = Files.createDirectories(sp.resolve("requests"));
            Files.writeString(pkg.resolve("__init__.py"), "");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (seen.stream().noneMatch(c -> c.file().equals(pkg)) && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertTrue(
                    seen.contains(new LspManager.WatchedFile(pkg, LspManager.WatchedKind.CREATED)),
                    "pip install's package folder: " + seen);
            assertFalse(w.watches(pkg), "the package's own folders are not watched");
        }
    }

    @Test
    void creatingOrRemovingAnEnvironmentIsNoticed() {
        assertTrue(PythonLibraryWatchers.touchesEnvironment(
                List.of(new LspManager.WatchedFile(Path.of("/p/.venv"), LspManager.WatchedKind.CREATED))));
        assertTrue(PythonLibraryWatchers.touchesEnvironment(
                List.of(new LspManager.WatchedFile(Path.of("/p/venv"), LspManager.WatchedKind.DELETED))));
        assertTrue(PythonLibraryWatchers.touchesEnvironment(
                List.of(new LspManager.WatchedFile(Path.of("/p/x/pyvenv.cfg"), LspManager.WatchedKind.CHANGED))));
        assertFalse(PythonLibraryWatchers.touchesEnvironment(
                List.of(new LspManager.WatchedFile(Path.of("/p/app.py"), LspManager.WatchedKind.CREATED))));
        assertFalse(
                PythonLibraryWatchers.touchesEnvironment(
                        List.of(new LspManager.WatchedFile(Path.of("/p/.env"), LspManager.WatchedKind.CHANGED))),
                "a dotenv file edited is no environment");
    }
}

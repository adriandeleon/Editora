package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1-6: the file list behind Search Everywhere follows the project — a newly saved file is offered, a deleted
 * one is not, and a change the index was not told about file by file makes the next use walk again.
 */
@Tag("fx")
class IndexCoordinatorStaleFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static IndexCoordinator coordinator(Path root) throws Exception {
        com.editora.config.Settings settings = new com.editora.config.Settings();
        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public com.editora.config.Settings settings() {
                return settings;
            }
        };
        return FxTestSupport.callOnFx(() -> new IndexCoordinator(host, new IndexCoordinator.Ops() {
            @Override
            public Path projectRoot() {
                return root;
            }

            @Override
            public void openAndGoto(Path file, int line, int column) {}

            @Override
            public boolean respectGitignore() {
                return false;
            }
        }));
    }

    /** Builds (or re-builds, when stale) and waits for the walk to land. */
    private static void ensureBuilt(IndexCoordinator c) throws Exception {
        CountDownLatch landed = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> c.ensureBuilt(landed::countDown));
        assertTrue(landed.await(20, TimeUnit.SECONDS), "the walk landed");
        FxTestSupport.drainFx();
    }

    /** Lets the index worker finish what was queued, then the FX callback it posted. */
    private static void settle(IndexCoordinator c) throws Exception {
        java.util.concurrent.ExecutorService worker = FxTestSupport.field(c, "worker");
        worker.submit(() -> {}).get(20, TimeUnit.SECONDS);
        FxTestSupport.drainFx();
    }

    private static List<String> files(IndexCoordinator c, String query) throws Exception {
        return FxTestSupport.callOnFx(() -> c.searchFiles(query, 40).stream()
                .map(IndexCoordinator.FileHit::relativePath)
                .toList());
    }

    private static List<String> symbols(IndexCoordinator c, String query) throws Exception {
        return FxTestSupport.callOnFx(() ->
                c.searchSymbols(query, 40).stream().map(h -> h.symbol().name()).toList());
    }

    @Test
    void aFileFirstSavedAfterTheWalkIsOffered(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Alpha.java"), "class Alpha {}\n");
        IndexCoordinator c = coordinator(root);
        try {
            ensureBuilt(c);
            Path fresh = root.resolve("Fresh.java");
            Files.writeString(fresh, "class Fresh {}\n");
            FxTestSupport.runOnFx(() -> {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setPath(fresh);
                buffer.setContent("class Fresh {}\n");
                c.onBufferSaved(buffer);
                c.onBufferSaved(buffer); // a second save must not list it twice
            });
            settle(c); // a save's declarations are scanned on the index's thread and land on the FX thread (PERF-01)
            assertEquals(List.of("Fresh.java"), files(c, "Fresh"));
            assertEquals(List.of("Fresh"), symbols(c, "Fresh"));
        } finally {
            FxTestSupport.runOnFx(c::dispose);
        }
    }

    @Test
    void aDeletedFileOrFolderIsNoLongerOffered(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Doomed.java"), "class Doomed {}\n");
        Path pkg = Files.createDirectories(root.resolve("gone"));
        Files.writeString(pkg.resolve("Inner.java"), "class Inner {}\n");
        Files.writeString(root.resolve("Kept.java"), "class Kept {}\n");
        IndexCoordinator c = coordinator(root);
        try {
            ensureBuilt(c);
            assertEquals(List.of("Doomed.java"), files(c, "Doomed"));
            FxTestSupport.runOnFx(() -> {
                c.onFileDeleted(root.resolve("Doomed.java"));
                c.onFileDeleted(pkg);
            });
            assertEquals(List.of(), files(c, "Doomed"));
            assertEquals(List.of(), symbols(c, "Doomed"));
            assertEquals(List.of(), files(c, "Inner"));
            assertEquals(List.of(), symbols(c, "Inner"));
            assertEquals(List.of("Kept.java"), files(c, "Kept"));
            assertTrue(FxTestSupport.callOnFx(c::isBuilt), "a delete it was told about needs no re-walk");
        } finally {
            FxTestSupport.runOnFx(c::dispose);
        }
    }

    @Test
    void anExternalChangeOrARenameMakesTheNextUseWalkAgain(@TempDir Path root) throws Exception {
        Path alpha = root.resolve("Alpha.java");
        Files.writeString(alpha, "class Alpha {}\n");
        Files.writeString(root.resolve("Doomed.java"), "class Doomed {}\n");
        IndexCoordinator c = coordinator(root);
        try {
            FxTestSupport.runOnFx(c::markStale);
            assertFalse(FxTestSupport.callOnFx(c::isBuilt), "nothing is built yet, stale or not");
            ensureBuilt(c);
            assertTrue(FxTestSupport.callOnFx(c::isBuilt));

            // A checkout: files come and go behind the editor's back.
            Files.delete(root.resolve("Doomed.java"));
            Files.writeString(root.resolve("Arrived.java"), "class Arrived {}\n");
            FxTestSupport.runOnFx(c::markStale);
            assertFalse(FxTestSupport.callOnFx(c::isBuilt));
            ensureBuilt(c);
            assertEquals(List.of(), files(c, "Doomed"));
            assertEquals(List.of(), symbols(c, "Doomed"), "the old walk's symbols went with it");
            assertEquals(List.of("Arrived.java"), files(c, "Arrived"));
            assertEquals(List.of("Arrived"), symbols(c, "Arrived"));

            // A rename: the old name goes at once, the new one arrives with the next walk.
            Path renamed = root.resolve("Renamed.java");
            Files.move(alpha, renamed);
            FxTestSupport.runOnFx(() -> c.onFileRenamed(alpha, renamed));
            assertEquals(List.of(), files(c, "Alpha"));
            ensureBuilt(c);
            assertEquals(List.of("Renamed.java"), files(c, "Renamed"));
        } finally {
            FxTestSupport.runOnFx(c::dispose);
        }
    }

    /** F5: a stale index re-walks by stamp, so a checkout costs a read of only the files it changed. */
    @Test
    void aStaleReWalkReadsOnlyTheChangedFiles(@TempDir Path root) throws Exception {
        java.nio.file.attribute.FileTime old =
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000);
        for (String name : List.of("One", "Two", "Three")) {
            Files.setLastModifiedTime(Files.writeString(root.resolve(name + ".java"), "class " + name + " {}\n"), old);
        }
        IndexCoordinator c = coordinator(root);
        try {
            ensureBuilt(c);
            Files.writeString(root.resolve("Two.java"), "class TwoAfter {}\n");
            FxTestSupport.runOnFx(c::markStale);
            assertEquals(List.of("Two"), symbols(c, "Two"), "a stale index still answers until the re-walk lands");
            ensureBuilt(c);
            assertEquals(2, (int) FxTestSupport.callOnFx(() -> c.lastReused));
            assertEquals(List.of("TwoAfter"), symbols(c, "TwoAfter"));
            assertEquals(
                    List.of(), symbols(c, "Two").stream().filter("Two"::equals).toList());
            assertEquals(List.of("Three"), symbols(c, "Three"));

            FxTestSupport.runOnFx(c::rebuild); // asked for by name: everything is read again
            settle(c);
            assertEquals(0, (int) FxTestSupport.callOnFx(() -> c.lastReused));
        } finally {
            FxTestSupport.runOnFx(c::dispose);
        }
    }
}

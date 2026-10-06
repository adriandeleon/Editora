package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.editora.editor.EditorBuffer;
import com.editora.ui.ProjectPanel.FsChange;
import com.editora.ui.ProjectPanel.FsKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One changed file used to mark the whole symbol index stale, so the next Search Everywhere walked and read
 * the project again — and a save counted as such a change, undoing the incremental {@code onBufferSaved}.
 * A batch that names its files now patches exactly those; anything less precise still falls back to a walk.
 */
@Tag("fx")
class IndexCoordinatorExternalChangeFxTest {

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
                return true;
            }
        }));
    }

    private static void ensureBuilt(IndexCoordinator c) throws Exception {
        CountDownLatch landed = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> c.ensureBuilt(landed::countDown));
        assertTrue(landed.await(20, TimeUnit.SECONDS), "the walk landed");
        FxTestSupport.drainFx();
    }

    /** Lets the index worker finish what was queued, then the FX callback it posted. */
    private static void settle(IndexCoordinator c) throws Exception {
        ExecutorService worker = FxTestSupport.field(c, "worker");
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
    void namedChangesPatchTheIndexWithoutMarkingItStale(@TempDir Path root) throws Exception {
        Path alpha = Files.writeString(root.resolve("Alpha.java"), "class Alpha {}\n");
        Path doomed = Files.writeString(root.resolve("Doomed.java"), "class Doomed {}\n");
        Files.writeString(root.resolve(".gitignore"), "generated/\n");
        Path generated = Files.createDirectory(root.resolve("generated"));
        IndexCoordinator c = coordinator(root);
        try {
            ensureBuilt(c);

            Files.writeString(alpha, "class Renamed {}\n");
            Files.delete(doomed);
            Path arrived = Files.writeString(root.resolve("Arrived.java"), "class Arrived {}\n");
            Path ignored = Files.writeString(generated.resolve("Gen.java"), "class Gen {}\n");
            FxTestSupport.runOnFx(() -> c.onExternalChanges(
                    List.of(
                            new FsChange(alpha, FsKind.CHANGED),
                            new FsChange(doomed, FsKind.DELETED),
                            new FsChange(arrived, FsKind.CREATED),
                            new FsChange(ignored, FsKind.CREATED)),
                    true));
            settle(c);

            assertTrue(FxTestSupport.callOnFx(c::isBuilt), "no re-walk is owed");
            assertEquals(List.of("Renamed"), symbols(c, "Renamed"));
            assertEquals(List.of(), symbols(c, "Alpha"));
            assertEquals(List.of(), files(c, "Doomed"));
            assertEquals(List.of("Arrived.java"), files(c, "Arrived"));
            assertEquals(List.of("Arrived"), symbols(c, "Arrived"));
            assertEquals(List.of(), files(c, "Gen"), "the walk's pruning applies to a patched file too");
            assertEquals(List.of(), symbols(c, "Gen"));
        } finally {
            FxTestSupport.runOnFx(c::dispose);
        }
    }

    @Test
    void theIndexStaysBuiltAfterASaveAndReflectsTheSavedContent(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("Saved.java"), "class Before {}\n");
        IndexCoordinator c = coordinator(root);
        try {
            ensureBuilt(c);
            Files.writeString(file, "class After {}\n");
            FxTestSupport.runOnFx(() -> {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setPath(file);
                buffer.setContent("class After {}\n");
                c.onBufferSaved(buffer);
                // Even if the watcher's report of that write got through, it is a patch, not a re-walk.
                c.onExternalChanges(List.of(new FsChange(file, FsKind.CREATED)), true);
            });
            settle(c);

            assertTrue(FxTestSupport.callOnFx(c::isBuilt));
            assertEquals(List.of("After"), symbols(c, "After"));
            assertEquals(List.of(), symbols(c, "Before"));
            assertEquals(List.of("Saved.java"), files(c, "Saved"));
        } finally {
            FxTestSupport.runOnFx(c::dispose);
        }
    }

    @Test
    void anIncompleteAccountOrANewFolderStillMeansWalkingAgain(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Alpha.java"), "class Alpha {}\n");
        IndexCoordinator c = coordinator(root);
        try {
            ensureBuilt(c);
            FxTestSupport.runOnFx(() -> c.onExternalChanges(List.of(), false)); // OVERFLOW
            assertFalse(FxTestSupport.callOnFx(c::isBuilt));

            ensureBuilt(c);
            Path pkg = Files.createDirectory(root.resolve("pkg"));
            Files.writeString(pkg.resolve("Inner.java"), "class Inner {}\n");
            FxTestSupport.runOnFx(() -> c.onExternalChanges(List.of(new FsChange(pkg, FsKind.CREATED)), true));
            settle(c);
            assertFalse(FxTestSupport.callOnFx(c::isBuilt), "what is inside a new folder was never reported");
            ensureBuilt(c);
            assertEquals(List.of("Inner"), symbols(c, "Inner"));
        } finally {
            FxTestSupport.runOnFx(c::dispose);
        }
    }
}

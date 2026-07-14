package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Project tree's drag-to-move end-to-end (the private {@code moveInto}, invoked via reflection):
 * files actually move on disk into the target folder, the rename callback fires per moved file (so open
 * buffers can follow), and a name conflict in the target is skipped rather than clobbering.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectPanelMoveFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rename(Path from, Path to) {}

    @Test
    void movesFilesIntoAFolderAndSkipsConflicts(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("c.txt"), "c");
        Path sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("c.txt"), "existing"); // conflict target for c.txt

        List<Rename> renames = new ArrayList<>();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p =
                    new ProjectPanel(x -> {}, (from, to) -> renames.add(new Rename(from, to)), x -> {}, x -> false);
            p.setRoot(root);
            return p;
        });

        // Move a.txt (no conflict) into sub/.
        FxTestSupport.call(
                panel, "moveInto", new Class<?>[] {List.class, Path.class}, List.of(root.resolve("a.txt")), sub);
        assertTrue(Files.exists(sub.resolve("a.txt")), "a.txt moved into sub/");
        assertFalse(Files.exists(root.resolve("a.txt")), "a.txt gone from root");
        assertEquals(1, renames.size(), "one rename notified");
        assertEquals(root.resolve("a.txt"), renames.get(0).from());
        assertEquals(sub.resolve("a.txt"), renames.get(0).to());

        // Move c.txt into sub/ where a sub/c.txt already exists → skipped, nothing clobbered.
        FxTestSupport.call(
                panel, "moveInto", new Class<?>[] {List.class, Path.class}, List.of(root.resolve("c.txt")), sub);
        assertTrue(Files.exists(root.resolve("c.txt")), "c.txt stays in root (target name taken)");
        assertEquals("existing", Files.readString(sub.resolve("c.txt")), "existing sub/c.txt untouched");
        assertEquals(1, renames.size(), "no new rename for the skipped conflict");
    }
}

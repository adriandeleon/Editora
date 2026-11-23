package com.editora.ui;

import java.util.List;

import com.editora.diff.PatchParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The names a patch section's two sides are shown under. */
class DiffCoordinatorTest {

    private static PatchParser.FilePatch patch(String oldPath, String newPath) {
        return new PatchParser.FilePatch(
                oldPath, newPath, List.of(), List.of(), 0, 0, true, true, List.of(), List.of());
    }

    @Test
    void eachSideIsNamedByItsOwnPathOrTheOther() {
        assertArrayEquals(new String[] {"a.txt", "b.txt"}, DiffCoordinator.patchNames(patch("a.txt", "b.txt"), "f"));
        assertArrayEquals(
                new String[] {"new.txt", "new.txt"},
                DiffCoordinator.patchNames(patch("/dev/null", "new.txt"), "f"),
                "an added file");
        assertArrayEquals(
                new String[] {"gone.txt", "gone.txt"},
                DiffCoordinator.patchNames(patch("gone.txt", "/dev/null"), "f"),
                "a removed file");
        assertArrayEquals(
                new String[] {"f", "f"},
                DiffCoordinator.patchNames(patch(" ", null), "f"),
                "neither named: the fallback");
        assertArrayEquals(new String[] {"f", "f"}, DiffCoordinator.patchNames(patch(null, ""), "f"));
    }
}

package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveFileOrderTest {

    @Test
    void isActiveMatchesByNormalizedAbsolutePath() {
        Path a = Path.of("/proj/src/Main.java");
        assertTrue(ActiveFileOrder.isActive(Path.of("/proj/src/Main.java"), a));
        assertTrue(ActiveFileOrder.isActive(Path.of("/proj/src/../src/Main.java"), a)); // normalized
        assertFalse(ActiveFileOrder.isActive(Path.of("/proj/src/Other.java"), a));
        assertFalse(ActiveFileOrder.isActive(null, a));
        assertFalse(ActiveFileOrder.isActive(a, null));
    }

    @Test
    void activeFirstPutsActiveOnTopThenTiebreak() {
        Path active = Path.of("/proj/B.java");
        List<Path> paths =
                new ArrayList<>(List.of(Path.of("/proj/C.java"), Path.of("/proj/A.java"), Path.of("/proj/B.java")));
        paths.sort(ActiveFileOrder.activeFirst(
                p -> p, active, Comparator.comparing(p -> p.getFileName().toString())));
        assertEquals(List.of("B.java", "A.java", "C.java"), names(paths));
    }

    @Test
    void activeFirstIsPlainTiebreakWhenNoActive() {
        List<Path> paths = new ArrayList<>(List.of(Path.of("/p/C"), Path.of("/p/A"), Path.of("/p/B")));
        paths.sort(ActiveFileOrder.activeFirst(
                p -> p, null, Comparator.comparing(p -> p.getFileName().toString())));
        assertEquals(List.of("A", "B", "C"), names(paths));
    }

    private static List<String> names(List<Path> paths) {
        List<String> out = new ArrayList<>();
        for (Path p : paths) {
            out.add(p.getFileName().toString());
        }
        return out;
    }
}

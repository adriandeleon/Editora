package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sorting a listing asked the filesystem "is this a directory?" from inside the comparator: two stats per
 * comparison. The order is unchanged; the question is now put once per entry.
 */
class ProjectPathOrderTest {

    @Test
    void theDirectoryQuestionIsAskedOncePerEntry() {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            paths.add(Path.of("dir", (i % 2 == 0 ? "d" : "f") + (499 - i)));
        }
        AtomicInteger asked = new AtomicInteger();

        List<Path> sorted = ProjectPathOrder.sorted(paths, p -> {
            asked.incrementAndGet();
            return p.getFileName().toString().startsWith("d");
        });

        assertEquals(500, asked.get());
        assertEquals(500, sorted.size());
        assertEquals("d1", sorted.get(0).getFileName().toString());
        assertEquals("f0", sorted.get(250).getFileName().toString());
    }

    @Test
    void foldersComeFirstThenNamesWithoutCaseBias() {
        List<Path> sorted = ProjectPathOrder.sorted(
                List.of(Path.of("b.txt"), Path.of("Zeta"), Path.of("A.txt"), Path.of("alpha")),
                p -> !p.toString().endsWith(".txt"));
        assertEquals(List.of(Path.of("alpha"), Path.of("Zeta"), Path.of("A.txt"), Path.of("b.txt")), sorted);
    }
}

package com.editora.todo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A TODO scan that a newer one has superseded stops instead of walking and reading the rest of the project:
 * both run on one thread, so the stale scan was pure delay for the one the panel is waiting for.
 */
class TodoServiceCancelTest {

    private static Path project(Path root) throws Exception {
        for (int d = 0; d < 20; d++) {
            Path dir = Files.createDirectories(root.resolve("d" + d));
            for (int f = 0; f < 10; f++) {
                Files.writeString(dir.resolve("f" + f + ".txt"), "// TODO one\n");
            }
        }
        return root;
    }

    @Test
    void aSupersededScanStopsDuringTheWalk(@TempDir Path root) throws Exception {
        project(root);
        TodoService service = new TodoService();
        try {
            var patterns = TodoPatterns.compile(TodoPatterns.defaults());
            assertEquals(200, service.run(patterns, root, Map.of(), () -> false).totalMatches(), "the control");

            AtomicInteger asked = new AtomicInteger();
            TodoService.Outcome outcome = service.run(patterns, root, Map.of(), () -> asked.incrementAndGet() > 5);

            assertEquals(List.of(), outcome.files());
            assertTrue(asked.get() < 40, "it stopped within a few entries, not after all 220: " + asked.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aSupersededScanStopsBetweenFilesToo(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("only"));
        for (int f = 0; f < 50; f++) {
            Files.writeString(dir.resolve("f" + f + ".txt"), "// TODO one\n");
        }
        TodoService service = new TodoService();
        try {
            var patterns = TodoPatterns.compile(TodoPatterns.defaults());
            // Lets the whole walk through (52 questions: the root, the directory, 50 files), then says stop.
            AtomicInteger asked = new AtomicInteger();
            TodoService.Outcome outcome = service.run(patterns, root, Map.of(), () -> asked.incrementAndGet() > 60);

            assertEquals(0, outcome.totalMatches());
            assertTrue(asked.get() < 80, "reading stopped after a few files: " + asked.get());
        } finally {
            service.shutdown();
        }
    }
}

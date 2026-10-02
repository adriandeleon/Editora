package com.editora.ui;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.search.SearchQuery;
import com.editora.search.SearchService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class SearchServiceLifecycleFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void openBufferMatchesAreBoundedBeforePublication() throws Exception {
        SearchService service = new SearchService();
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            var outcome = new java.util.concurrent.atomic.AtomicReference<SearchService.Outcome>();
            service.search(
                    new SearchQuery("hit", false, false, false),
                    null,
                    Map.of(Path.of("many.txt"), "hit\n".repeat(6_000)),
                    result -> {
                        outcome.set(result);
                        delivered.countDown();
                    });

            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertEquals(5_000, outcome.get().totalMatches());
            assertTrue(outcome.get().truncated());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void resultAlreadyQueuedForFxIsDroppedWhenANewerSearchStarts() throws Exception {
        SearchService service = new SearchService();
        CountDownLatch fxBlocked = new CountDownLatch(1);
        CountDownLatch releaseFx = new CountDownLatch(1);
        Platform.runLater(() -> {
            fxBlocked.countDown();
            try {
                releaseFx.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(fxBlocked.await(5, TimeUnit.SECONDS));

        try {
            List<Integer> delivered = new CopyOnWriteArrayList<>();
            service.search(
                    new SearchQuery("one", false, false, false),
                    null,
                    Map.of(Path.of("first.txt"), "one"),
                    result -> delivered.add(result.totalMatches()));
            currentSearch(service).get(5, TimeUnit.SECONDS); // its FX delivery is queued behind the blocker

            CountDownLatch second = new CountDownLatch(1);
            service.search(
                    new SearchQuery("two", false, false, false),
                    null,
                    Map.of(Path.of("second.txt"), "two two"),
                    result -> {
                        delivered.add(result.totalMatches());
                        second.countDown();
                    });
            currentSearch(service).get(5, TimeUnit.SECONDS);
            releaseFx.countDown();

            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(2), delivered);
        } finally {
            releaseFx.countDown();
            service.shutdown();
        }
    }

    @Test
    void openDiskMatchesDoNotHideLaterClosedFileMatches(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("one.txt"), "");
        Files.writeString(dir.resolve("two.txt"), "");
        List<Path> order;
        try (var paths = Files.walk(dir)) {
            order = paths.filter(Files::isRegularFile).toList();
        }
        Path first = order.get(0);
        Path second = order.get(1);
        Files.writeString(first, "needle\n".repeat(6_000));
        Files.writeString(second, "needle");

        SearchService service = new SearchService();
        service.setBackend(false, List.of(), false);
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            var outcome = new java.util.concurrent.atomic.AtomicReference<SearchService.Outcome>();
            service.search(new SearchQuery("needle", false, false, false), dir, Map.of(first, "no matches"), result -> {
                outcome.set(result);
                delivered.countDown();
            });
            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertEquals(1, outcome.get().totalMatches());
            assertEquals(
                    second.toAbsolutePath().normalize(),
                    outcome.get().files().get(0).file().toAbsolutePath().normalize());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void supersedingADenseSearchCancelsMatchProductionPromptly() throws Exception {
        SearchService service = new SearchService();
        try {
            List<Integer> delivered = new CopyOnWriteArrayList<>();
            service.search(
                    new SearchQuery("x", false, false, false),
                    null,
                    Map.of(Path.of("dense.txt"), "x".repeat(2_000_000)),
                    result -> delivered.add(result.totalMatches()));

            CountDownLatch second = new CountDownLatch(1);
            service.search(
                    new SearchQuery("done", false, false, false),
                    null,
                    Map.of(Path.of("second.txt"), "done"),
                    result -> {
                        delivered.add(result.totalMatches());
                        second.countDown();
                    });

            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1), delivered);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void skippedOversizeFileIsReportedAsIncomplete(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("large.txt"), "x".repeat(2 * 1024 * 1024 + 1));
        SearchService service = new SearchService();
        service.setBackend(false, List.of(), false);
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            var outcome = new java.util.concurrent.atomic.AtomicReference<SearchService.Outcome>();
            service.search(new SearchQuery("needle", false, false, false), dir, Map.of(), result -> {
                outcome.set(result);
                delivered.countDown();
            });
            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertTrue(outcome.get().truncated());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void openOversizeFileUsesAuthoritativeContentWithoutConsumingDiskBudget(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("large.txt"), "x".repeat(2 * 1024 * 1024 + 1));
        SearchService service = new SearchService();
        service.setBackend(false, List.of(), false);
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            var outcome = new java.util.concurrent.atomic.AtomicReference<SearchService.Outcome>();
            service.search(new SearchQuery("needle", false, false, false), dir, Map.of(file, "needle"), result -> {
                outcome.set(result);
                delivered.countDown();
            });
            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertEquals(1, outcome.get().totalMatches());
            assertFalse(outcome.get().truncated(), "the shadowed disk copy must not make a complete search partial");
        } finally {
            service.shutdown();
        }
    }

    // --- a superseded search tells its owner (the generation guard never calls onResult for it) --------

    @Test
    void aSupersededSearchReportsThatInsteadOfAResult() throws Exception {
        SearchService service = new SearchService();
        try {
            List<String> events = new CopyOnWriteArrayList<>();
            CountDownLatch second = new CountDownLatch(1);
            // Both started from the FX thread in one go, as two quick Enter presses are: the first never
            // gets to deliver.
            FxTestSupport.runOnFx(() -> {
                service.search(
                        new SearchQuery("x", false, false, false),
                        null,
                        Map.of(Path.of("dense.txt"), "x".repeat(2_000_000)),
                        List.of(),
                        List.of(),
                        result -> events.add("first:result"),
                        () -> events.add("first:superseded"));
                service.search(
                        new SearchQuery("done", false, false, false),
                        null,
                        Map.of(Path.of("second.txt"), "done"),
                        List.of(),
                        List.of(),
                        result -> {
                            events.add("second:result");
                            second.countDown();
                        },
                        () -> events.add("second:superseded"));
            });
            assertTrue(second.await(10, TimeUnit.SECONDS));
            FxTestSupport.runOnFx(() -> {});

            assertEquals(List.of("first:superseded", "second:result"), events, "exactly one callback per search");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shutdownReportsTheSearchStillInFlightAsSuperseded() throws Exception {
        SearchService service = new SearchService();
        CountDownLatch fxBlocked = new CountDownLatch(1);
        CountDownLatch releaseFx = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        try {
            service.search(
                    new SearchQuery("one", false, false, false),
                    null,
                    Map.of(Path.of("first.txt"), "one"),
                    List.of(),
                    List.of(),
                    result -> events.add("result"),
                    () -> events.add("superseded"));
            Platform.runLater(() -> {
                fxBlocked.countDown();
                try {
                    releaseFx.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(fxBlocked.await(5, TimeUnit.SECONDS));
            service.shutdown(); // the result may already be queued behind the blocker; it must still be dropped
        } finally {
            releaseFx.countDown();
        }
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> {});

        assertEquals(1, events.size(), "one callback, whichever won the race: " + events);
    }

    // --- include/exclude globs on the built-in walker and the open buffers ----------------------------

    @Test
    void anExcludedDirectoryNameDropsEverythingBeneathItOnTheWalkerAndInOpenBuffers(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.createDirectories(dir.resolve("mod/target/classes"));
        Files.createDirectories(dir.resolve("node_modules/pkg"));
        Files.writeString(dir.resolve("src/App.java"), "needle");
        Files.writeString(dir.resolve("mod/target/classes/App.txt"), "needle");
        Files.writeString(dir.resolve("node_modules/pkg/index.js"), "needle");
        Path openUnderTarget = dir.resolve("mod/target/Open.txt");

        SearchService service = new SearchService();
        service.setBackend(false, List.of(), false); // the Java walker, .gitignore off: only the globs prune
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            var outcome = new java.util.concurrent.atomic.AtomicReference<SearchService.Outcome>();
            service.search(
                    new SearchQuery("needle", false, false, false),
                    dir,
                    Map.of(openUnderTarget, "needle"),
                    List.of(),
                    com.editora.search.Globs.split("target, node_modules"),
                    result -> {
                        outcome.set(result);
                        delivered.countDown();
                    });
            assertTrue(delivered.await(10, TimeUnit.SECONDS));

            List<String> files = outcome.get().files().stream()
                    .map(f -> dir.relativize(f.file()).toString().replace('\\', '/'))
                    .toList();
            assertEquals(List.of("src/App.java"), files, "as `rg -g '!target' -g '!node_modules'` would");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aBraceIncludeGlobMatchesOnTheWalker(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.js"), "needle");
        Files.writeString(dir.resolve("b.ts"), "needle");
        Files.writeString(dir.resolve("c.css"), "needle");

        SearchService service = new SearchService();
        service.setBackend(false, List.of(), false);
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            var outcome = new java.util.concurrent.atomic.AtomicReference<SearchService.Outcome>();
            service.search(
                    new SearchQuery("needle", false, false, false),
                    dir,
                    Map.of(),
                    com.editora.search.Globs.split("*.{js,ts}"),
                    List.of(),
                    result -> {
                        outcome.set(result);
                        delivered.countDown();
                    });
            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertEquals(2, outcome.get().fileCount(), "*.{js,ts} is one glob, not `*.{js` and `ts}`");
        } finally {
            service.shutdown();
        }
    }

    /** The open-buffer overlay reads a regex the way ripgrep reads it for the closed files. */
    @Test
    void openBuffersAreMatchedWithTheRipgrepRegexDialect() throws Exception {
        SearchService service = new SearchService();
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            var outcome = new java.util.concurrent.atomic.AtomicReference<SearchService.Outcome>();
            service.search(
                    new SearchQuery("\\w+", true, true, false), null, Map.of(Path.of("a.txt"), "café"), result -> {
                        outcome.set(result);
                        delivered.countDown();
                    });
            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertEquals(1, outcome.get().totalMatches());
            assertEquals(4, outcome.get().files().get(0).matches().get(0).length(), "\\w is Unicode-aware");
        } finally {
            service.shutdown();
        }
    }

    private static Future<?> currentSearch(SearchService service) throws Exception {
        Field field = SearchService.class.getDeclaredField("currentSearch");
        field.setAccessible(true);
        return (Future<?>) field.get(service);
    }
}

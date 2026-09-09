package com.editora.ui;

import java.lang.reflect.Field;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static Future<?> currentSearch(SearchService service) throws Exception {
        Field field = SearchService.class.getDeclaredField("currentSearch");
        field.setAccessible(true);
        return (Future<?>) field.get(service);
    }
}

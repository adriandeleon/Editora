package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.search.SearchQuery;
import com.editora.search.SearchService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1-6: the tool window, the popup and the MCP bridge each search on their own line, so none of them drops
 * another's search — and a caller that waits for an answer always gets one.
 */
@Tag("fx")
class SearchChannelsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static SearchQuery q(String text) {
        return new SearchQuery(text, true, false, false);
    }

    @Test
    void searchesOnDifferentChannelsAllAnswer(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "one two three four\n");
        SearchService service = new SearchService();
        try {
            SearchService.Channel popup = service.newChannel();
            List<String> answered = new CopyOnWriteArrayList<>();
            List<String> dropped = new CopyOnWriteArrayList<>();
            CountDownLatch all = new CountDownLatch(4);
            // Submitted in one FX pulse: nothing can be delivered in between, which is the losing case.
            Platform.runLater(() -> {
                service.search(
                        q("one"),
                        root,
                        Map.of(),
                        List.of(),
                        List.of(),
                        o -> done(answered, "panel", o, all),
                        () -> dropped.add("panel"));
                service.search(
                        popup,
                        q("two"),
                        root,
                        Map.of(),
                        List.of(),
                        List.of(),
                        o -> done(answered, "popup", o, all),
                        () -> dropped.add("popup"));
                service.searchDetached(
                        q("three"), root, Map.of(), o -> done(answered, "mcp-1", o, all), () -> dropped.add("mcp-1"));
                service.searchDetached(
                        q("four"), root, Map.of(), o -> done(answered, "mcp-2", o, all), () -> dropped.add("mcp-2"));
            });
            assertTrue(all.await(20, TimeUnit.SECONDS), "answered: " + answered + ", dropped: " + dropped);
            assertEquals(List.of(), dropped);
            assertEquals(
                    List.of("mcp-1:1", "mcp-2:1", "panel:1", "popup:1"),
                    answered.stream().sorted().toList());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aNewerSearchStillSupersedesTheOlderOneOnItsOwnChannel(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "one two\n");
        SearchService service = new SearchService();
        try {
            SearchService.Channel popup = service.newChannel();
            List<String> answered = new CopyOnWriteArrayList<>();
            List<String> dropped = new CopyOnWriteArrayList<>();
            CountDownLatch last = new CountDownLatch(1);
            Platform.runLater(() -> {
                service.search(
                        popup,
                        q("one"),
                        root,
                        Map.of(),
                        List.of(),
                        List.of(),
                        o -> answered.add("first"),
                        () -> dropped.add("first"));
                service.search(
                        popup,
                        q("two"),
                        root,
                        Map.of(),
                        List.of(),
                        List.of(),
                        o -> done(answered, "second", o, last),
                        () -> dropped.add("second"));
            });
            assertTrue(last.await(20, TimeUnit.SECONDS));
            FxTestSupport.drainFx();
            assertEquals(List.of("second:1"), answered);
            assertEquals(List.of("first"), dropped);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shutdownAnswersADetachedSearchThatCanNoLongerRun() throws Exception {
        SearchService service = new SearchService();
        CountDownLatch abandoned = new CountDownLatch(1);
        Platform.runLater(() -> {
            service.searchDetached(q("x"), null, Map.of(Path.of("a.txt"), "x"), o -> {}, abandoned::countDown);
            service.shutdown();
        });
        assertTrue(abandoned.await(10, TimeUnit.SECONDS), "a blocked caller is released, not left to time out");
    }

    private static void done(List<String> answered, String who, SearchService.Outcome outcome, CountDownLatch latch) {
        answered.add(who + ":" + outcome.totalMatches());
        latch.countDown();
    }
}

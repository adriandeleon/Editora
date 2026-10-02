package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.collections.FXCollections;

import com.editora.io.DocumentWriteSequencer;
import com.editora.search.SearchQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Find in Files, the panel-facing half: Replace All acts on the result set <em>with the query that produced
 * it</em>, and a search that a newer one supersedes still closes its background-task handle.
 */
@Tag("fx")
class SearchCoordinatorSnapshotFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Counts background-task handles and lets a test wait for a particular status line. */
    private static final class RecordingHost extends CoordinatorHostStub {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile CountDownLatch results;

        @Override
        public AutoCloseable startBackgroundTask(String label) {
            opened.incrementAndGet();
            return closed::incrementAndGet;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
            CountDownLatch latch = results;
            if (latch != null && !message.equals(tr("search.searching"))) {
                latch.countDown(); // a search's result (or a replace's verdict) has been reported
            }
        }

        CountDownLatch expectResult() {
            results = new CountDownLatch(1);
            return results;
        }

        String last() {
            return statuses.get(statuses.size() - 1);
        }
    }

    private static SearchCoordinator coordinator(RecordingHost host, Path root) throws Exception {
        DocumentWriteSequencer sequencer = new DocumentWriteSequencer();
        SearchCoordinator.Ops ops = SearchCoordinator.ops(
                new SearchCoordinator.Navigation(
                        () -> root, (file, line, col, focus) -> {}, () -> true, () -> {}, () -> {}),
                new SearchCoordinator.ReplaceSupport(
                        file -> null, buffer -> false, (file, content, done) -> done.accept(true), sequencer::begin),
                new SearchCoordinator.Persistence(query -> {}, FXCollections::observableArrayList, found -> {}));
        SearchCoordinator coordinator = FxTestSupport.callOnFx(
                () -> new SearchCoordinator(host, ops, Executors.newSingleThreadExecutor(), count -> true));
        coordinator.service().setBackend(false, List.of(), false); // the built-in walker: no rg on CI
        return coordinator;
    }

    private static void search(SearchCoordinator coordinator, SearchQuery query, String include, String exclude) {
        FxTestSupport.call(
                coordinator,
                "runFileSearch",
                new Class[] {SearchQuery.class, String.class, String.class},
                query,
                include,
                exclude);
    }

    private static List<Path> shownFiles(SearchCoordinator coordinator) throws Exception {
        return FxTestSupport.callOnFx(
                () -> new ArrayList<>(FxTestSupport.<List<Path>>field(coordinator.panel(), "lastFiles")));
    }

    // --- B6: the background-task handle of a superseded search ------------------------------------------

    @Test
    void aSupersededSearchClosesItsBackgroundTaskHandle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "needle");
        RecordingHost host = new RecordingHost();
        try (AsyncTestScope async = new AsyncTestScope()) {
            SearchCoordinator coordinator = coordinator(host, dir);
            async.onClose(coordinator::shutdown);
            CountDownLatch done = host.expectResult();

            // Three searches before the first can answer: only the last one ever reaches its result callback,
            // which was the only place a handle was closed — so "Searching… (N)" counted up for good.
            FxTestSupport.runOnFx(() -> {
                search(coordinator, new SearchQuery("one", false, false, false), "", "");
                search(coordinator, new SearchQuery("two", false, false, false), "", "");
                search(coordinator, new SearchQuery("needle", false, false, false), "", "");
            });
            async.await(done, "the last search's result");
            async.awaitFx();

            assertEquals(3, host.opened.get());
            assertEquals(3, host.closed.get(), "every search released its handle, superseded or not");
            assertEquals(tr("search.summary", 1, 1), host.last());
        }
    }

    @Test
    void shutdownClosesTheHandleOfASearchStillInFlight(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "needle");
        RecordingHost host = new RecordingHost();
        try (AsyncTestScope async = new AsyncTestScope()) {
            SearchCoordinator coordinator = coordinator(host, dir);
            FxTestSupport.runOnFx(() -> {
                search(coordinator, new SearchQuery("needle", false, false, false), "", "");
                coordinator.shutdown(); // the window closes before the result can be delivered
            });
            async.awaitFx();

            assertEquals(1, host.opened.get());
            assertEquals(1, host.closed.get());
        }
    }

    // --- B4(c): Replace All uses the query the shown results came from -----------------------------------

    @Test
    void replaceAllWithEditedFieldsRefreshesInsteadOfRewritingTheOldResults(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "a.c abc\n");
        RecordingHost host = new RecordingHost();
        try (AsyncTestScope async = new AsyncTestScope()) {
            SearchCoordinator coordinator = coordinator(host, dir);
            async.onClose(coordinator::shutdown);
            SearchQuery literal = new SearchQuery("a.c", true, false, false);

            CountDownLatch first = host.expectResult();
            FxTestSupport.runOnFx(() -> search(coordinator, literal, "", ""));
            async.await(first, "the literal search");
            async.awaitFx();
            assertEquals(tr("search.summary", 1, 1), host.last(), "the preview shows the one literal match");
            List<Path> shown = shownFiles(coordinator);
            assertEquals(List.of(file), shown);

            // The user toggles .* and presses Replace All without searching again. As a regex "a.c" also
            // matches "abc", which the preview never showed.
            SearchQuery regex = new SearchQuery("a.c", true, true, false);
            CountDownLatch refreshed = host.expectResult();
            SearchCoordinator.ReplaceResult refused = async.await(
                    FxTestSupport.callOnFx(() -> coordinator.replaceShownResults(regex, "", "", "X", shown)));
            async.await(refreshed, "the refreshed search");
            async.awaitFx();

            assertEquals(0, refused.count());
            assertTrue(refused.cancelled(), "nothing is replaced under semantics the preview did not show");
            assertEquals("a.c abc\n", Files.readString(file));
            assertEquals(tr("search.replaceStale", tr("search.summary", 2, 1)), host.last(), "and the status says why");

            // The results now belong to the regex query: the same press replaces exactly what is shown.
            CountDownLatch replaced = host.expectResult();
            SearchCoordinator.ReplaceResult applied = async.await(FxTestSupport.callOnFx(
                    () -> coordinator.replaceShownResults(regex, "", "", "X", shownFiles(coordinator))));
            async.await(replaced, "the replace verdict");
            assertEquals(2, applied.count());
            assertFalse(applied.cancelled());
            assertEquals("X X\n", Files.readString(file));
        }
    }

    @Test
    void replaceAllWithEditedGlobsRefreshesToo(@TempDir Path dir) throws Exception {
        Path kept = Files.writeString(dir.resolve("keep.txt"), "old");
        Path other = Files.writeString(dir.resolve("other.md"), "old");
        RecordingHost host = new RecordingHost();
        try (AsyncTestScope async = new AsyncTestScope()) {
            SearchCoordinator coordinator = coordinator(host, dir);
            async.onClose(coordinator::shutdown);
            SearchQuery query = new SearchQuery("old", true, false, false);

            CountDownLatch first = host.expectResult();
            FxTestSupport.runOnFx(() -> search(coordinator, query, "", ""));
            async.await(first, "the unfiltered search");
            async.awaitFx();
            List<Path> shown = shownFiles(coordinator);
            assertEquals(2, shown.size());

            // Narrowing the include field does not narrow what an old result set would rewrite.
            CountDownLatch refreshed = host.expectResult();
            SearchCoordinator.ReplaceResult refused = async.await(
                    FxTestSupport.callOnFx(() -> coordinator.replaceShownResults(query, "*.txt", "", "new", shown)));
            async.await(refreshed, "the refreshed search");
            async.awaitFx();

            assertTrue(refused.cancelled());
            assertEquals("old", Files.readString(other), "the file the new include excludes is untouched");
            assertEquals("old", Files.readString(kept));
            assertEquals(List.of(kept), shownFiles(coordinator), "and the preview now matches the fields");
        }
    }

    @Test
    void theSnapshotComparesTheParsedGlobsNotTheirSpelling() {
        SearchCoordinator.SearchSnapshot snapshot = new SearchCoordinator.SearchSnapshot(
                new SearchQuery("x", true, false, false), List.of("*.{js,ts}", "src/**"), List.of("target"));
        SearchQuery same = new SearchQuery("x", true, false, false);

        assertTrue(
                snapshot.matches(same, " *.{js,ts} ,src/**", "target,"), "whitespace and a stray comma are not edits");
        assertFalse(snapshot.matches(new SearchQuery("x", false, false, false), "*.{js,ts},src/**", "target"));
        assertFalse(snapshot.matches(new SearchQuery("x", true, true, false), "*.{js,ts},src/**", "target"));
        assertFalse(snapshot.matches(new SearchQuery("x", true, false, true), "*.{js,ts},src/**", "target"));
        assertFalse(snapshot.matches(new SearchQuery("y", true, false, false), "*.{js,ts},src/**", "target"));
        assertFalse(snapshot.matches(same, "*.js", "target"));
        assertFalse(snapshot.matches(same, "*.{js,ts},src/**", ""));
    }
}

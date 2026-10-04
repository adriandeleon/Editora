package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import javafx.collections.FXCollections;

import com.editora.io.DocumentWriteSequencer;
import com.editora.search.FileResult;
import com.editora.search.LineMatch;
import com.editora.search.Ripgrep;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Find in Files with the <b>ripgrep</b> backend, search through Replace All: the matches the panel lists
 * are the ones Replace All rewrites, also for a regex ripgrep and {@code java.util.regex} read differently.
 * Skipped where {@code rg} is not installed; {@code SearchServiceRematchTest} pins the same rule without it.
 */
@Tag("fx")
class SearchCoordinatorRipgrepReplaceFxTest {

    private static final List<String> RG = List.of(Ripgrep.DEFAULT_COMMAND);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Captures each delivered result set and lets a test wait for the status line that follows it. */
    private static final class RecordingHost extends CoordinatorHostStub {
        final List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile CountDownLatch results;

        @Override
        public void setStatus(String message) {
            statuses.add(message);
            CountDownLatch latch = results;
            if (latch != null && !message.equals(tr("search.searching"))) {
                latch.countDown();
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
        coordinator.service().setBackend(true, RG, false);
        return coordinator;
    }

    /** Searches, waits for the panel, and returns the listed matches as {@code file:line:col+length}. */
    private static List<String> search(
            AsyncTestScope async, RecordingHost host, SearchCoordinator coordinator, SearchQuery query, Path root)
            throws Exception {
        CountDownLatch done = host.expectResult();
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                coordinator,
                "runFileSearch",
                new Class[] {SearchQuery.class, String.class, String.class},
                query,
                "",
                ""));
        async.await(done, "the search result");
        async.awaitFx();
        // The service is asked again for the same query to read the match list itself (the panel only
        // exposes its files); both runs go through ripgrep.
        CountDownLatch listed = new CountDownLatch(1);
        List<String> out = new ArrayList<>();
        SearchService lister = new SearchService();
        lister.setBackend(true, RG, false);
        lister.search(query, root, Map.of(), outcome -> {
            for (FileResult fr : outcome.files()) {
                for (LineMatch m : fr.matches()) {
                    out.add(fr.file().getFileName() + ":" + m.line() + ":" + m.col() + "+" + m.length());
                }
            }
            listed.countDown();
        });
        async.onClose(lister::shutdown);
        async.await(listed, "the listed matches");
        return out;
    }

    private static List<Path> shownFiles(SearchCoordinator coordinator) throws Exception {
        return FxTestSupport.callOnFx(
                () -> new ArrayList<>(FxTestSupport.<List<Path>>field(coordinator.panel(), "lastFiles")));
    }

    private static SearchCoordinator.ReplaceResult replaceAll(
            AsyncTestScope async, RecordingHost host, SearchCoordinator coordinator, SearchQuery query, String with)
            throws Exception {
        CountDownLatch replaced = host.expectResult();
        List<Path> shown = shownFiles(coordinator);
        SearchCoordinator.ReplaceResult result =
                async.await(FxTestSupport.callOnFx(() -> coordinator.replaceShownResults(query, "", "", with, shown)));
        async.await(replaced, "the replace verdict");
        async.awaitFx();
        return result;
    }

    @Test
    void aPosixClassReplacesExactlyWhatThePanelListed(@TempDir Path dir) throws Exception {
        assumeTrue(Ripgrep.detect(RG), "ripgrep is not installed");
        Path posix = Files.writeString(dir.resolve("posix.txt"), "item 123 digit 45\nedit 7\n");
        Path digitsOnly = Files.writeString(dir.resolve("only.txt"), "8080\n");
        RecordingHost host = new RecordingHost();
        try (AsyncTestScope async = new AsyncTestScope()) {
            SearchCoordinator coordinator = coordinator(host, dir);
            async.onClose(coordinator::shutdown);
            SearchQuery query = new SearchQuery("[[:digit:]]+", true, true, false);

            List<String> listed = search(async, host, coordinator, query, dir);
            // ripgrep reads the class as digits, java.util.regex as the set {: d i g t}. Whichever reading
            // the panel shows, Replace All must rewrite that and nothing else.
            assertEquals(List.of("posix.txt:1:1+2", "posix.txt:1:10+5", "posix.txt:2:2+3"), listed);
            assertEquals(tr("search.summary", 3, 1), host.last());

            SearchCoordinator.ReplaceResult result = replaceAll(async, host, coordinator, query, "N");

            assertEquals(listed.size(), result.count(), "as many replacements as listed matches");
            assertEquals("Nem 123 N 45\neN 7\n", Files.readString(posix));
            assertEquals("8080\n", Files.readString(digitsOnly), "a file with no listed match is not touched");
        }
    }

    @Test
    void wordAnchorsReplaceExactlyWhatThePanelListed(@TempDir Path dir) throws Exception {
        assumeTrue(Ripgrep.detect(RG), "ripgrep is not installed");
        Path words = Files.writeString(dir.resolve("words.txt"), "a foo b\n");
        Path tags = Files.writeString(dir.resolve("tags.txt"), "a foo b <foo> c\n");
        RecordingHost host = new RecordingHost();
        try (AsyncTestScope async = new AsyncTestScope()) {
            SearchCoordinator coordinator = coordinator(host, dir);
            async.onClose(coordinator::shutdown);
            SearchQuery query = new SearchQuery("\\<foo\\>", true, true, false);

            List<String> listed = search(async, host, coordinator, query, dir);
            assertEquals(List.of("tags.txt:1:9+5"), listed, "the literal <foo> Replace All will rewrite");
            assertEquals(List.of(tags), shownFiles(coordinator));

            SearchCoordinator.ReplaceResult result = replaceAll(async, host, coordinator, query, "BAR");

            assertEquals(1, result.count());
            assertEquals("a foo b BAR c\n", Files.readString(tags));
            assertEquals("a foo b\n", Files.readString(words));
        }
    }

    @Test
    void wholeWordWithAPunctuationEdgeReplacesExactlyWhatThePanelListed(@TempDir Path dir) throws Exception {
        assumeTrue(Ripgrep.detect(RG), "ripgrep is not installed");
        Path file = Files.writeString(dir.resolve("Anno.java"), "class A {\n    @Override\n    int x@Override;\n}\n");
        RecordingHost host = new RecordingHost();
        try (AsyncTestScope async = new AsyncTestScope()) {
            SearchCoordinator coordinator = coordinator(host, dir);
            async.onClose(coordinator::shutdown);
            SearchQuery query = new SearchQuery("@Override", true, false, true);

            List<String> listed = search(async, host, coordinator, query, dir);
            assertEquals(List.of("Anno.java:3:10+9"), listed);

            SearchCoordinator.ReplaceResult result = replaceAll(async, host, coordinator, query, "@Deprecated");

            assertEquals(listed.size(), result.count());
            assertTrue(Files.readString(file).contains("    @Override\n    int x@Deprecated;"));
        }
    }
}

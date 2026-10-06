package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.SearchMatches;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Find in a document too large — or a result too long — to treat like an ordinary one: the search runs off
 * the FX thread, only a page of the matches is held, and next/previous, Replace and Replace All still reach
 * every match. "Large" and "long" are set small here through the bar's test seam, so the same code paths run
 * on a few lines of text.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindLargeDocumentFxTest {

    /** Ten matches of "x", three characters apart: x0 … x9. */
    private static final String TEN = "x0 x1 x2 x3 x4 x5 x6 x7 x8 x9";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A worker that runs nothing until the test runs it — on the test's thread, which is not the FX thread. */
    private static final class Held implements Executor {
        final ConcurrentLinkedDeque<Runnable> queue = new ConcurrentLinkedDeque<>();

        @Override
        public void execute(Runnable task) {
            queue.add(task);
        }

        /** Runs what is queued here, then lets the FX thread take the results. */
        void runAll() throws Exception {
            for (Runnable task = queue.poll(); task != null; task = queue.poll()) {
                task.run();
            }
            FxTestSupport.drainFx();
        }
    }

    private static final class Harness {
        EditorBuffer buffer;
        FindReplaceBar bar;
        final Held worker = new Held();
        final List<String> statuses = new ArrayList<>();

        CodeArea area() {
            return buffer.getFocusedArea();
        }

        void query(String find, String replace) {
            FxTestSupport.<TextField>field(bar, "findField").setText(find);
            FxTestSupport.<TextField>field(bar, "replaceField").setText(replace);
            FxTestSupport.invoke(bar, "recompute"); // what the query debounce does
        }

        String count() {
            return FxTestSupport.<Label>field(bar, "countLabel").getText();
        }

        String selection() {
            return area().getSelection().getStart() + "-"
                    + area().getSelection().getEnd();
        }
    }

    /**
     * @param syncChars documents up to this many characters are searched inline (0 = everything off-thread)
     * @param page how many matches the bar holds
     */
    private Harness harness(String text, int syncChars, int page) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Harness h = new Harness();
            h.buffer = new EditorBuffer();
            h.buffer.setContent(text);
            h.bar = new FindReplaceBar(() -> h.buffer, h.statuses::add);
            new Scene(new VBox(h.bar, h.buffer.getNode()), 800, 600);
            h.bar.searchOn(h.worker, syncChars, page);
            h.bar.show(false);
            return h;
        });
    }

    private static SearchMatches overlayMatches(EditorBuffer buffer) {
        Object overlay = FxTestSupport.field(buffer, "searchOverlay");
        return overlay == null ? SearchMatches.EMPTY : FxTestSupport.field(overlay, "matches");
    }

    // --- off the FX thread ---

    @Test
    void aLargeDocumentIsNotSearchedOnTheFxThread() throws Exception {
        Harness h = harness("needle hay needle hay needle", 0, 100);
        FxTestSupport.runOnFx(() -> {
            h.query("needle", "");
            // The request has returned and the FX thread has searched nothing: the work sits with the worker.
            assertEquals(1, h.worker.queue.size(), "the search was handed to the worker");
            assertTrue(h.bar.currentMatches().isEmpty(), "no result yet");
            assertEquals("", h.count());
            assertEquals(0, h.area().getSelection().getLength());
        });

        h.worker.runAll(); // the search runs here, on the test thread

        FxTestSupport.runOnFx(() -> {
            assertEquals(3, h.bar.currentMatches().size());
            assertEquals(tr("find.count", 1L, 3L), h.count());
            assertEquals("0-6", h.selection(), "the first match is selected once the result arrives");
            assertEquals(3, overlayMatches(h.buffer).size());
        });
    }

    @Test
    void aSearchSupersededByANewQueryNeverShows() throws Exception {
        Harness h = harness("needle hay needle hay needle", 0, 100);
        FxTestSupport.runOnFx(() -> {
            h.query("needle", "");
            h.query("hay", "");
            assertEquals(2, h.worker.queue.size());
        });
        h.worker.runAll();
        FxTestSupport.runOnFx(() -> {
            assertEquals(2, h.bar.currentMatches().size(), "only the latest query's matches arrive");
            assertEquals("7-10", h.selection());
            assertEquals(tr("find.count", 1L, 2L), h.count());
        });
    }

    @Test
    void aResultForTextThatHasSinceChangedIsDropped() throws Exception {
        Harness h = harness("needle hay needle", 0, 100);
        FxTestSupport.runOnFx(() -> {
            h.query("needle", "");
            h.area().insertText(0, "needle "); // edited while the search is in flight
        });
        h.worker.runAll();
        FxTestSupport.runOnFx(() -> {
            assertTrue(h.bar.currentMatches().isEmpty(), "offsets found in the old text are not applied");
            assertEquals(0, h.area().getSelection().getLength());
            FxTestSupport.invoke(h.bar, "recomputeHighlightsOnly"); // what the edit debounce then does
        });
        h.worker.runAll();
        FxTestSupport.runOnFx(() -> {
            assertEquals(3, h.bar.currentMatches().size());
            assertEquals(0, h.area().getSelection().getLength(), "an edit's re-highlight selects nothing");
        });
    }

    @Test
    void closingTheBarDropsASearchInFlight() throws Exception {
        Harness h = harness("needle hay needle", 0, 100);
        FxTestSupport.runOnFx(() -> {
            h.query("needle", "");
            h.bar.hideBar();
        });
        h.worker.runAll();
        FxTestSupport.runOnFx(() -> {
            assertTrue(overlayMatches(h.buffer).isEmpty(), "nothing is highlighted after the bar has closed");
            assertEquals(0, h.area().getSelection().getLength());
        });
    }

    // --- a result longer than the page ---

    @Test
    void theCountSaysWhenThereAreMoreMatchesThanAreHeld() throws Exception {
        Harness h = harness(TEN, Integer.MAX_VALUE, 4);
        FxTestSupport.runOnFx(() -> {
            h.query("x", "");
            assertEquals(4, h.bar.currentMatches().size(), "the cap is honoured");
            assertEquals(tr("find.count", 1L, "4+"), h.count(), "and the label does not pass 4 off as the total");
            assertEquals("0-1", h.selection());
        });
    }

    @Test
    void nextAndPreviousReachEveryMatchBeyondThePage() throws Exception {
        Harness h = harness(TEN, Integer.MAX_VALUE, 4);
        FxTestSupport.runOnFx(() -> {
            h.query("x", "");
            List<String> visited = new ArrayList<>();
            visited.add(h.selection());
            for (int i = 0; i < 10; i++) {
                h.bar.findNext();
                visited.add(h.selection());
                assertTrue(h.bar.currentMatches().size() <= 4, "never more than a page is held");
            }
            assertEquals(
                    List.of("0-1", "3-4", "6-7", "9-10", "12-13", "15-16", "18-19", "21-22", "24-25", "27-28", "0-1"),
                    visited,
                    "next walks all ten matches and wraps");

            h.bar.findPrevious(); // from the first match: wraps to the last
            assertEquals("27-28", h.selection());
            assertEquals(tr("find.count", 10L, 10L), h.count(), "at the end the total is known exactly");
            h.bar.findPrevious();
            h.bar.findPrevious();
            assertEquals("21-22", h.selection());
            assertEquals(tr("find.count", 8L, 10L), h.count());
        });
    }

    @Test
    void nextBeyondThePageSearchesOffTheFxThreadInALargeDocument() throws Exception {
        Harness h = harness(TEN, 0, 4);
        FxTestSupport.runOnFx(() -> h.query("x", ""));
        h.worker.runAll();
        List<String> visited = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            visited.add(FxTestSupport.callOnFx(h::selection));
            FxTestSupport.runOnFx(h.bar::findNext);
            h.worker.runAll(); // a step inside the page queued nothing; one past its edge queued a search
        }
        assertEquals(List.of("0-1", "3-4", "6-7", "9-10", "12-13", "15-16"), visited);
        assertEquals(tr("find.count", 7L, "8+"), FxTestSupport.callOnFx(h::count));
    }

    @Test
    void replaceAllRewritesEveryMatchNotJustThePageAsOneUndoStep() throws Exception {
        Harness h = harness(TEN, Integer.MAX_VALUE, 4);
        FxTestSupport.runOnFx(() -> {
            h.query("x", "yy");
            h.bar.replaceAllMatches();
            assertEquals("yy0 yy1 yy2 yy3 yy4 yy5 yy6 yy7 yy8 yy9", h.buffer.getContent());
            assertEquals(tr("find.replaced", 10), h.statuses.get(h.statuses.size() - 1));
            h.area().undo();
            assertEquals(TEN, h.buffer.getContent(), "one undo takes the whole replace back");
        });
    }

    @Test
    void replaceAllInALargeDocumentKeepsItsOwnStatus() throws Exception {
        Harness h = harness(TEN, 0, 4);
        FxTestSupport.runOnFx(() -> h.query("x", "y"));
        h.worker.runAll();
        FxTestSupport.runOnFx(h.bar::replaceAllMatches);
        h.worker.runAll(); // the search after the replace finds nothing — and must not report over it
        FxTestSupport.runOnFx(() -> {
            assertEquals("y0 y1 y2 y3 y4 y5 y6 y7 y8 y9", h.buffer.getContent());
            assertEquals(tr("find.replaced", 10), h.statuses.get(h.statuses.size() - 1));
        });
    }

    @Test
    void replaceActsOnAMatchOutsideThePageHeld() throws Exception {
        Harness h = harness(TEN, Integer.MAX_VALUE, 4);
        FxTestSupport.runOnFx(() -> {
            h.query("x", "Y");
            h.area().selectRange(21, 22); // x7, which the first page (x0..x3) does not hold
            h.bar.replaceCurrentMatch();
            assertEquals("x0 x1 x2 x3 x4 x5 x6 Y7 x8 x9", h.buffer.getContent());
            assertEquals("24-25", h.selection(), "and moves on to the next match");

            h.area().selectRange(1, 2); // not a match
            h.bar.replaceCurrentMatch();
            assertEquals(
                    "x0 x1 x2 x3 x4 x5 x6 Y7 x8 x9", h.buffer.getContent(), "an arbitrary selection is left alone");
        });
    }

    // --- the overlay ---

    @Test
    void theOverlayLooksOnlyAtTheMatchesInTheViewport() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            FxTestSupport.runOnFx(() -> {
                Stage stage = FxTestSupport.field(fx.controller, "stage");
                stage.setWidth(1000);
                stage.setHeight(700);
                if (!stage.isShowing()) {
                    stage.show();
                }
            });
            int lines = 20_000;
            Path file = fx.configDir.resolve("matches.txt");
            Files.writeString(file, "match\n".repeat(lines));
            FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
            EditorBuffer buffer = await(() -> {
                EditorBuffer b = (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {});
                return b != null && file.equals(b.getPath()) && !b.isLoading() ? b : null;
            });
            List<int[]> all = new ArrayList<>();
            for (int i = 0; i < lines; i++) {
                all.add(new int[] {6 * i, 6 * i + 5});
            }
            FxTestSupport.runOnFx(() -> buffer.setSearchMatches(SearchMatches.ofPairs(all), 0));

            Object overlay = FxTestSupport.callOnFx(() -> FxTestSupport.field(buffer, "searchOverlay"));
            assertNotNull(overlay);
            int visited = await(() -> {
                int n = (int) FxTestSupport.call(overlay, "visitedInLastRedraw", new Class[] {});
                return n > 0 ? n : null;
            });
            int visible = FxTestSupport.callOnFx(() -> buffer.getArea().lastVisibleParToAllParIndex()
                    - buffer.getArea().firstVisibleParToAllParIndex()
                    + 1);
            assertTrue(visible < 200, "precondition: a viewport, not the document (" + visible + " lines)");
            assertTrue(
                    visited <= visible + 1,
                    "a redraw looked at " + visited + " of " + lines + " matches for " + visible + " visible lines");
        } finally {
            fx.dispose();
        }
    }

    /** Polls a value on the FX thread until it is there: rendering has no barrier to wait on. */
    private static <T> T await(java.util.concurrent.Callable<T> probe) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (true) {
            T value = FxTestSupport.callOnFx(probe);
            if (value != null) {
                return value;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for the editor");
            }
            Thread.sleep(25);
        }
    }
}

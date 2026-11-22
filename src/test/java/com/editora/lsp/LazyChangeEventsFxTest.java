package com.editora.lsp;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LazyChangeEvents}: the changes of one {@code didChange}, worked out when first read (PERF-01, Pauta 6.2.0):
 * a splice when the change is incremental, the whole (or rewritten) text otherwise, and a change that changes
 * nothing, with the FX thread told, when the two texts are equal.
 */
@Tag("fx")
class LazyChangeEventsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage(); // onIdentical is told on the FX thread
    }

    private static final Runnable NOT_IDENTICAL = () -> {
        throw new AssertionError("the texts differ");
    };

    private static TextDocumentContentChangeEvent only(LazyChangeEvents events) {
        assertEquals(1, events.size());
        return events.get(0);
    }

    @Test
    void anEqualTextStillSendsAChangeAndTellsTheFxThread() throws Exception {
        CountDownLatch told = new CountDownLatch(2);
        AtomicInteger toldOnFx = new AtomicInteger();
        Runnable onIdentical = () -> {
            if (Platform.isFxApplicationThread()) {
                toldOnFx.incrementAndGet();
            }
            told.countDown();
        };

        TextDocumentContentChangeEvent splice = only(new LazyChangeEvents("abc", "abc", true, null, onIdentical));
        assertEquals(new Range(new Position(0, 0), new Position(0, 0)), splice.getRange(), "an empty splice");
        assertEquals("", splice.getText());

        TextDocumentContentChangeEvent whole = only(new LazyChangeEvents("abc", "abc", false, null, onIdentical));
        assertNull(whole.getRange(), "sent whole when the change is not incremental");
        assertEquals("abc", whole.getText());

        assertTrue(told.await(10, TimeUnit.SECONDS), "both equal texts were told");
        assertEquals(2, toldOnFx.get(), "on the FX thread");
    }

    @Test
    void anIncrementalChangeIsTheSpliceBetweenTheTwoTexts() {
        TextDocumentContentChangeEvent sameLength = only(new LazyChangeEvents("abc", "abd", true, null, NOT_IDENTICAL));
        assertEquals(new Range(new Position(0, 2), new Position(0, 3)), sameLength.getRange());
        assertEquals("d", sameLength.getText(), "the same length, a different text: still a splice");

        TextDocumentContentChangeEvent longer =
                only(new LazyChangeEvents("one\ntwo", "one\ntwo!", true, null, NOT_IDENTICAL));
        assertEquals(new Range(new Position(1, 3), new Position(1, 3)), longer.getRange());
        assertEquals("!", longer.getText());
    }

    @Test
    void aChangeThatCannotBeASpliceSendsTheWholeText() {
        LazyChangeEvents nothingHeld = new LazyChangeEvents(null, "abc", true, null, NOT_IDENTICAL);
        assertEquals(3, nothingHeld.estimatedChars());
        TextDocumentContentChangeEvent first = only(nothingHeld);
        assertNull(first.getRange(), "no text held before: sent whole although asked for a splice");
        assertEquals("abc", first.getText());

        TextDocumentContentChangeEvent aliased =
                only(new LazyChangeEvents("ab", "abc", true, s -> s.toUpperCase(Locale.ROOT), NOT_IDENTICAL));
        assertNull(aliased.getRange(), "a text the server is sent rewritten goes whole");
        assertEquals("ABC", aliased.getText(), "rewritten");

        TextDocumentContentChangeEvent full = only(new LazyChangeEvents("ab", "abc", false, null, NOT_IDENTICAL));
        assertNull(full.getRange());
        assertEquals("abc", full.getText());
    }

    @Test
    void theChangesAreWorkedOutOnceWhateverReadsThem() {
        AtomicInteger diffs = new AtomicInteger();
        Thread self = Thread.currentThread();
        com.editora.perf.BlockingSites.listenForTest((site, thread) -> {
            if (LazyChangeEvents.SITE.equals(site) && thread == self) {
                diffs.incrementAndGet();
            }
        });
        try {
            LazyChangeEvents events = new LazyChangeEvents("ab", "abc", true, null, NOT_IDENTICAL);
            List<TextDocumentContentChangeEvent> first = events.events();
            assertEquals(first, List.copyOf(events));
            assertEquals(1, events.size());
            assertEquals(1, diffs.get(), "one diff for every read");
        } finally {
            com.editora.perf.BlockingSites.listenForTest(null);
        }
    }
}

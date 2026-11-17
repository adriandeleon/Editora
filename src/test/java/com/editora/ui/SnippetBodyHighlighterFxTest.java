package com.editora.ui;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A snippet or template body is coloured once typing pauses, off the FX thread (PERF-08, Pauta 6.0.1). */
@Tag("fx")
class SnippetBodyHighlighterFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static StyleSpans<Collection<String>> keywordSpans(String text) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        b.add(List.of("keyword"), text.length());
        return b.create();
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!FxTestSupport.callOnFx(done::getAsBoolean) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void typingIsColouredOnceAtThePauseNotPerKeystroke() throws Exception {
        AtomicInteger computed = new AtomicInteger();
        AtomicReference<String> onThread = new AtomicReference<>();
        CodeArea body = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea();
            new SnippetBodyHighlighter(area, () -> "java", (text, lang) -> {
                computed.incrementAndGet();
                onThread.set(Thread.currentThread().getName());
                return "java".equals(lang) ? keywordSpans(text) : null;
            });
            for (char c : "public static void main".toCharArray()) {
                area.appendText(String.valueOf(c));
            }
            return area;
        });
        assertEquals(0, computed.get(), "nothing per keystroke");
        waitFor(() -> body.getStyleOfChar(3).contains("keyword"));
        assertEquals(1, computed.get(), "one colouring for the burst");
        assertEquals("snippet-body-highlight", onThread.get());
        assertTrue(FxTestSupport.callOnFx(() -> body.getStyleOfChar(20).contains("keyword")));
    }

    @Test
    void anAnswerForTextEditedSinceIsDropped() throws Exception {
        AtomicInteger computed = new AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<SnippetBodyHighlighter> made = new AtomicReference<>();
        CodeArea body = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea("class A {}");
            made.set(new SnippetBodyHighlighter(area, () -> "java", (text, lang) -> {
                if (computed.incrementAndGet() == 1) {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    StyleSpansBuilder<Collection<String>> stale = new StyleSpansBuilder<>();
                    stale.add(List.of("stale"), text.length());
                    return stale.create();
                }
                return keywordSpans(text);
            }));
            made.get().highlightNow();
            return area;
        });
        FxTestSupport.runOnFx(() -> body.insertText(0, "// "));
        release.countDown(); // the first answer lands after the edit: dropped
        waitFor(() -> computed.get() == 2 && body.getStyleOfChar(1).contains("keyword"));
        assertEquals(2, computed.get());
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(() -> body.getStyleOfChar(12).contains("keyword")));
        assertTrue(FxTestSupport.callOnFx(() -> !body.getStyleOfChar(5).contains("stale")), "the stale answer");
    }

    @Test
    void noGrammarShowsTheBodyPlain() throws Exception {
        CodeArea body = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea("echo hi");
            area.setStyle(0, 4, List.of("keyword"));
            new SnippetBodyHighlighter(area, () -> "global", (text, lang) -> null).highlightNow();
            return area;
        });
        waitFor(() -> body.getStyleOfChar(1).isEmpty());
        assertTrue(FxTestSupport.callOnFx(() -> body.getStyleOfChar(1).isEmpty()));
    }
}

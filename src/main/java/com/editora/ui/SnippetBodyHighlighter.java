package com.editora.ui;

import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.jspecify.annotations.Nullable;

/**
 * Colours a snippet or file-template body as it is edited (PERF-08, Pauta 6.0.1): once typing pauses, not per
 * keystroke, and tokenized off the FX thread. The answer is applied only to the text it was worked out for: an
 * edit since then (counted, not compared) drops it, and the next pause asks again. A template body is a file, so
 * nothing caps its size.
 */
final class SnippetBodyHighlighter {

    /** How long typing must pause before the body is coloured again. */
    static final Duration PAUSE = Duration.millis(120);

    /** Where bodies are tokenized; replaceable so a test can run it in place. */
    static Executor worker =
            task -> Thread.ofVirtual().name("snippet-body-highlight").start(task);

    private final CodeArea body;
    private final Supplier<String> language;
    private final BiFunction<String, String, @Nullable StyleSpans<Collection<String>>> styles;
    private final PauseTransition pause = new PauseTransition(PAUSE);

    /** Edits seen; an answer for an older count is dropped. */
    private long edits;

    /**
     * Follows {@code body}'s edits. {@code styles} works out the spans for (text, language) off the FX thread; null
     * means the language has no grammar and the body is shown plain.
     */
    SnippetBodyHighlighter(
            CodeArea body,
            Supplier<String> language,
            BiFunction<String, String, @Nullable StyleSpans<Collection<String>>> styles) {
        this.body = body;
        this.language = language;
        this.styles = styles;
        pause.setOnFinished(e -> highlightNow());
        body.plainTextChanges().subscribe(change -> {
            edits++;
            pause.playFromStart();
        });
    }

    /** The language changed: the body is coloured again once the typing in its field pauses. */
    void languageChanged() {
        pause.playFromStart();
    }

    /** Colours the body for what it holds now (the pause's end; tests skip the wait with it). */
    void highlightNow() {
        pause.stop();
        long asked = edits;
        String text = body.getText();
        String lang = language.get();
        worker.execute(() -> {
            StyleSpans<Collection<String>> spans;
            try {
                spans = styles.apply(text, lang);
            } catch (RuntimeException tokenizerHiccup) {
                return; // keep the last styling rather than show a half-coloured body
            }
            StyleSpans<Collection<String>> done = spans;
            Runnable apply = () -> {
                if (asked != edits || body.getLength() != text.length()) {
                    return; // edited since: the pause after that edit asks again
                }
                if (done == null) {
                    if (!text.isEmpty()) {
                        body.clearStyle(0, text.length());
                    }
                } else {
                    body.setStyleSpans(0, done);
                }
            };
            if (Platform.isFxApplicationThread()) {
                apply.run();
            } else {
                Platform.runLater(apply);
            }
        });
    }
}

package com.editora.ui;

import java.util.Collection;
import java.util.List;

import com.editora.process.OutputBatch;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * The write side of a console {@link CodeArea} (Run, the build tabs, Debug): appends styled text, trims to
 * the console's cap and keeps the view following the tail — as <em>one</em> rich-text edit, one style
 * application and one trim for everything a single output-pump drain delivers.
 *
 * <p>Each of those used to happen per line: an append, a {@code setStyleSpans}, and at the cap a
 * {@code getText}/{@code deleteText}/{@code moveTo}/scroll, 256 times a drain. Text arriving while the pump
 * is delivering ({@link OutputBatch}) is collected here and applied when the drain has handed over its last
 * line; text appended at any other time — a typed stdin echo, a notice, a test — is applied at once, so
 * nothing outside a drain ever sees a console that is behind.
 */
final class ConsoleAppender {

    private final CodeArea console;
    private final int maxChars;

    private final StringBuilder text = new StringBuilder();
    private StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
    /** Characters of {@link #text} that {@link #spans} covers so far (the builder cannot be asked). */
    private int spanned;

    private final Runnable flush = this::flush;

    ConsoleAppender(CodeArea console, int maxChars) {
        this.console = console;
        this.maxChars = maxChars;
    }

    /**
     * Appends {@code appended}. {@code style} styles its first {@code style.length()} characters — a line
     * without its newline, say — and may be null for text in the default foreground.
     */
    void append(String appended, StyleSpans<Collection<String>> style) {
        if (appended.isEmpty()) {
            return;
        }
        if (style != null && style.length() > 0) {
            pad(text.length());
            spans.addAll(style);
            spanned += style.length();
        }
        text.append(appended);
        if (!OutputBatch.defer(flush)) {
            flush();
        }
    }

    /** Applies whatever is waiting. Call before reading the console's own text from inside a drain. */
    void flush() {
        if (text.isEmpty()) {
            return;
        }
        String appended = text.toString();
        StyleSpans<Collection<String>> style = null;
        if (spanned > 0) {
            pad(appended.length());
            style = spans.create();
        }
        discard(); // first: a failing edit must not leave its text to be applied again
        int start = console.getLength();
        int caretBefore = console.getCaretPosition();
        boolean follow = caretBefore >= start; // scrolled back? stay put
        console.appendText(appended);
        if (style != null) {
            console.setStyleSpans(start, style);
        }
        ConsoleNav.afterAppend(console, caretBefore, follow, maxChars);
    }

    /** Drops text that has not been applied yet: the console is about to be cleared. */
    void discard() {
        text.setLength(0);
        spans = new StyleSpansBuilder<>();
        spanned = 0;
    }

    /** Covers the unstyled text up to {@code length} (a newline, a plain line) with the default style. */
    private void pad(int length) {
        if (length > spanned) {
            spans.add(List.of(), length - spanned);
            spanned = length;
        }
    }
}

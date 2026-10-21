package com.editora.editor.http;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The {@code .http} part of a buffer's Run gutter: one ▶ per request line, the running request's ■ (which follows
 * its request when lines move, see {@link HttpRunMark}), what a click and a hover do, and the injected
 * {@link HttpHooks}. Kept out of {@link EditorBuffer}, which only asks it about lines; FX thread only.
 */
public final class HttpGutter {

    private HttpHooks hooks = HttpHooks.NONE;
    /** The 0-based start lines of each request (each gets a Run glyph). */
    private List<Integer> requestLines = List.of();
    /** The request (index in file order) whose ▶ shows as running — a ■ that cancels it; -1 = none. */
    private int runningIndex = -1;
    /** The running request's method/URL line as it read when the run started: the ■ follows it. */
    private @Nullable String runningText;
    /** …and the lines after it (its headers), to tell it from another request to the same URL. */
    private List<String> runningBlock = List.of();

    public HttpHooks hooks() {
        return hooks;
    }

    public void setHooks(HttpHooks hooks) {
        this.hooks = hooks == null ? HttpHooks.NONE : hooks;
    }

    public List<Integer> requestLines() {
        return requestLines;
    }

    boolean isRequestLine(int line) {
        return requestLines.contains(line);
    }

    /** The line of the running request's glyph, or -1. */
    public int runningLine() {
        return runningIndex >= 0 && runningIndex < requestLines.size() ? requestLines.get(runningIndex) : -1;
    }

    /** Marks request {@code index} (-1 = none) as running; returns the lines whose glyph changed (-1 = none). */
    public int[] setRunning(int index, IntFunction<String> textOf) {
        int before = runningLine();
        runningIndex = index;
        int after = runningLine();
        runningText = after >= 0 ? textOf.apply(after) : null;
        runningBlock = after >= 0 ? HttpRunMark.blockAfter(after, textOf) : List.of();
        return before == after ? new int[] {-1, -1} : new int[] {before, after};
    }

    /**
     * As {@link #setRunning(int, IntFunction)}, then repaints the gutter lines whose glyph changed through
     * {@code refresh} — each one and, when it is folded away ({@code hidden}), the line its glyph shows on.
     */
    public void setRunning(int index, IntFunction<String> textOf, IntPredicate hidden, IntConsumer refresh) {
        for (int line : setRunning(index, textOf)) {
            if (line >= 0) {
                refresh.accept(line);
                int shown = glyphLine(line, hidden); // a folded request's ■ is on its ### line
                if (shown != line) {
                    refresh.accept(shown);
                }
            }
        }
    }

    /**
     * The index to mark for request {@code index} of the text a run started with, whose method/URL line read
     * {@code requestLine}: itself, or — when the requests moved since — the one that still reads that line.
     */
    public int follow(int index, String requestLine, IntFunction<String> textOf) {
        boolean moved = index < 0
                || index >= requestLines.size()
                || requestLine == null
                || !requestLine.equals(textOf.apply(requestLines.get(index)));
        return moved && index >= 0 ? HttpRunMark.find(requestLines, textOf, requestLine, index) : index;
    }

    /** Takes the request lines of a new run scan; the ■ follows its request's line, or goes when it was edited. */
    public void setRequestLines(List<Integer> now, IntFunction<String> textOf) {
        if (!now.equals(requestLines)) {
            int follow = HttpRunMark.find(now, textOf, runningText, runningBlock, runningIndex);
            runningIndex = runningIndex < 0 ? -1 : follow;
        }
        requestLines = now;
    }

    // --- folded requests: a request whose method line is folded away draws its glyph on the line that shows ---

    /**
     * The line that draws request line {@code requestLine}'s glyph: itself, or — when it is folded away
     * ({@code hidden}) — the nearest shown line above it, the fold's {@code ###} header. Fold All used to leave a
     * file with no ▶ at all.
     */
    public static int glyphLine(int requestLine, IntPredicate hidden) {
        int line = requestLine;
        while (line > 0 && hidden.test(line)) {
            line--;
        }
        return line;
    }

    /** Whether {@code line} draws a request glyph (its own, or a folded request's below it). */
    public boolean isGlyphLine(int line, IntPredicate hidden) {
        return requestLineAt(line, hidden) >= 0;
    }

    /** The request line whose glyph {@code line} draws (the first, when a fold hides several), or -1. */
    int requestLineAt(int line, IntPredicate hidden) {
        if (hidden.test(line)) {
            return -1;
        }
        if (requestLines.contains(line)) {
            return line;
        }
        for (int r : requestLines) {
            if (r > line) {
                return hidden.test(r) && glyphLine(r, hidden) == line ? r : -1;
            }
        }
        return -1;
    }

    /** The line that draws the running request's ■ (see {@link #glyphLine}), or -1. */
    public int runningGlyphLine(IntPredicate hidden) {
        int running = runningLine();
        return running < 0 ? -1 : glyphLine(running, hidden);
    }

    /** {@link #tooltip(int)} for the glyph drawn on {@code line}, which may be a folded request's. */
    @Nullable
    public String tooltip(int line, IntPredicate hidden) {
        int r = requestLineAt(line, hidden);
        return r < 0 ? null : tooltip(r);
    }

    /** {@link #click(int)} on the glyph drawn on {@code line}, which may be a folded request's. */
    public void click(int line, IntPredicate hidden) {
        int r = requestLineAt(line, hidden);
        if (r >= 0) {
            click(r);
        }
    }

    /** The hover text of the glyph on {@code line}: "cancel" on the ■, the run tooltip on a ▶, else null. */
    @Nullable
    String tooltip(int line) {
        if (line == runningLine()) {
            return tr("editmenu.cancelHttpRequest.tip");
        }
        return isRequestLine(line) ? hooks.runTooltip().get() : null;
    }

    /** A click on the glyph of {@code line}: the ■ cancels the run, a ▶ runs its request. */
    void click(int line) {
        if (line == runningLine()) {
            hooks.cancel().run();
        } else {
            hooks.run().accept(line);
        }
    }
}

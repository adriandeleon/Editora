package com.editora.editor;

import java.util.List;
import java.util.function.IntFunction;

import static com.editora.i18n.Messages.tr;

/**
 * The {@code .http} part of a buffer's Run gutter: one ▶ per request line, the running request's ■ (which follows
 * its request when lines move, see {@link HttpRunMark}), what a click and a hover do, and the injected
 * {@link HttpHooks}. Kept out of {@link EditorBuffer}, which only asks it about lines; FX thread only.
 */
final class HttpGutter {

    private HttpHooks hooks = HttpHooks.NONE;
    /** The 0-based start lines of each request (each gets a Run glyph). */
    private List<Integer> requestLines = List.of();
    /** The request (index in file order) whose ▶ shows as running — a ■ that cancels it; -1 = none. */
    private int runningIndex = -1;
    /** The running request's method/URL line as it read when the run started: the ■ follows it. */
    private String runningText;

    HttpHooks hooks() {
        return hooks;
    }

    void setHooks(HttpHooks hooks) {
        this.hooks = hooks == null ? HttpHooks.NONE : hooks;
    }

    List<Integer> requestLines() {
        return requestLines;
    }

    boolean isRequestLine(int line) {
        return requestLines.contains(line);
    }

    /** The line of the running request's glyph, or -1. */
    int runningLine() {
        return runningIndex >= 0 && runningIndex < requestLines.size() ? requestLines.get(runningIndex) : -1;
    }

    /** Marks request {@code index} (-1 = none) as running; returns the lines whose glyph changed (-1 = none). */
    int[] setRunning(int index, IntFunction<String> textOf) {
        int before = runningLine();
        runningIndex = index;
        int after = runningLine();
        runningText = after >= 0 ? textOf.apply(after) : null;
        return before == after ? new int[] {-1, -1} : new int[] {before, after};
    }

    /**
     * The index to mark for request {@code index} of the text a run started with, whose method/URL line read
     * {@code requestLine}: itself, or — when the requests moved since — the one that still reads that line.
     */
    int follow(int index, String requestLine, IntFunction<String> textOf) {
        boolean moved = index < 0
                || index >= requestLines.size()
                || requestLine == null
                || !requestLine.equals(textOf.apply(requestLines.get(index)));
        return moved && index >= 0 ? HttpRunMark.find(requestLines, textOf, requestLine, index) : index;
    }

    /** Takes the request lines of a new run scan; the ■ follows its request's line, or goes when it was edited. */
    void setRequestLines(List<Integer> now, IntFunction<String> textOf) {
        if (!now.equals(requestLines)) {
            int follow = HttpRunMark.find(now, textOf, runningText, runningIndex);
            runningIndex = runningIndex < 0 ? -1 : follow;
        }
        requestLines = now;
    }

    /** The hover text of the glyph on {@code line}: "cancel" on the ■, the run tooltip on a ▶, else null. */
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

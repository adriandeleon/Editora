package com.editora.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * One incremental syntax-highlight pass: everything {@link EditorBuffer} computes off the FX thread
 * between "the text changed" and "apply these spans". Pure — text and the previous pass's per-line state
 * in, a finished {@link Result} out — so the part that has to be right can be tested against real grammars
 * without a toolkit.
 *
 * <p><b>A pass restyles only as far as the edit reaches.</b> It starts at the first owed line, from the
 * grammar state stored for the line before it, and stops at the first line at or past the end of the owed
 * range whose end state equals the state stored for the corresponding line of the old text (and, with
 * bracket colours on, whose nesting depth does too). From there on the text is unchanged and so is what
 * the tokenizer is carrying into it, so every later line would come out exactly as it already is. Typing a
 * character therefore re-tokenizes a line or two wherever it happens in the file; opening a block comment
 * still restyles to the matching close — or the end of the file — because the state really differs that
 * far. A pass with nothing to resume from (no stored state, or the whole document owed) runs to the end.
 *
 * <p>The spans come back complete: the semantic-token overlay and the bracket colours are laid over the
 * token spans here, on the pool thread, and so are the spliced per-line arrays and the symbol list. What is
 * left for the FX thread is one {@code setStyleSpans} over the restyled range and three reference swaps.
 */
final class HighlightPass {

    private HighlightPass() {}

    /**
     * What a pass leaves for the next one: the grammar end-state of every line and, when bracket colours
     * are on, the nesting depth at the end of every line ({@code null} otherwise). Never modified once
     * built — a pass reads the previous table from the pool thread while the FX thread still owns it.
     */
    record Lines(IStateStack[] states, int[] depths) {
        int count() {
            return states.length;
        }
    }

    /**
     * @param lines the last applied pass's table, or {@code null} to tokenize the whole document
     * @param symbols the last applied pass's symbols (sorted by line)
     * @param dirtyStart start offset in {@code text} of the range owed (see {@link HighlightDirty}), or
     *     negative for the whole document
     * @param dirtyEnd end offset (exclusive) of that range; past the end of the text = to the end
     * @param semantic semantic tokens anchored to {@code text} to overlay, or {@code null} for none
     */
    record Request(
            String text,
            IGrammar grammar,
            Lines lines,
            List<TextMateHighlighter.Symbol> symbols,
            int dirtyStart,
            int dirtyEnd,
            boolean brackets,
            List<SemanticToken> semantic) {}

    /**
     * @param fromOffset where {@code spans} start
     * @param spans the finished styles for the restyled range, or {@code null} when that range is empty
     * @param lines the table for the whole of the new text
     * @param symbols the symbols of the whole of the new text — the request's own list when unchanged
     * @param firstLine first line tokenized
     * @param lastLine last line tokenized; before the last line of the text when the pass converged
     */
    record Result(
            int fromOffset,
            StyleSpans<Collection<String>> spans,
            Lines lines,
            List<TextMateHighlighter.Symbol> symbols,
            boolean symbolsChanged,
            int firstLine,
            int lastLine) {

        /** Number of characters restyled. */
        int length() {
            return spans == null ? 0 : spans.length();
        }
    }

    /** Runs the pass. Returns {@code null} when {@code cancelled} reported true before it finished. */
    static Result run(Request r, BooleanSupplier cancelled) {
        String text = r.text();
        if (text == null || r.grammar() == null || cancelled.getAsBoolean()) {
            return null;
        }
        Lines old = r.lines();
        boolean brackets = r.brackets();
        boolean resume = old != null && r.dirtyStart() >= 0 && (!brackets || old.depths() != null);
        int dirtyStart = resume ? Math.min(r.dirtyStart(), text.length()) : 0;
        int dirtyEnd = resume ? Math.min(Math.max(r.dirtyEnd(), dirtyStart), text.length()) : text.length();
        // One scan for the three line numbers the pass needs: the line holding the start of the owed
        // range (and where it begins), the line holding its end, and the line count.
        int first = 0;
        int from = 0;
        int reachLine = 0;
        int lineCount = 1;
        for (int nl = text.indexOf('\n'); nl >= 0; nl = text.indexOf('\n', nl + 1)) {
            lineCount++;
            if (nl < dirtyStart) {
                first++;
                from = nl + 1;
            }
            if (nl < dirtyEnd) {
                reachLine++;
            }
        }
        // Resuming at a line needs the state the line before it ended in; without one, start over.
        if (resume && first > 0 && (first > old.count() || old.states()[first - 1] == null)) {
            resume = false;
        }
        if (!resume) {
            first = 0;
            from = 0;
        }
        boolean canConverge = resume;
        int firstLine = first;
        int reach = reachLine;
        // Lines past the owed range are unchanged, so new line j is old line j - delta.
        int delta = old == null ? 0 : lineCount - old.count();
        BracketColors.Scanner scanner = brackets
                ? new BracketColors.Scanner(
                        text, from, firstLine == 0 ? 0 : old.depths()[firstLine - 1], BracketColors.COLORS)
                : null;
        IntList depths = new IntList();
        IntList bounds = new IntList(); // start, end of each tokenized line
        TextMateHighlighter.IncrementalAnalysis a = TextMateHighlighter.analyzeFrom(
                text,
                r.grammar(),
                firstLine,
                firstLine == 0 ? null : old.states()[firstLine - 1],
                cancelled,
                new TextMateHighlighter.LineHook() {
                    @Override
                    public void run(String style, int length) {
                        if (scanner != null) {
                            scanner.run(style, length);
                        }
                    }

                    @Override
                    public boolean lineDone(int line, int lineStart, int lineEnd, IStateStack endState) {
                        int depth = scanner == null ? 0 : scanner.depth();
                        depths.add(depth);
                        bounds.add(lineStart);
                        bounds.add(lineEnd);
                        if (!canConverge || line < reach) {
                            return false;
                        }
                        int oldLine = line - delta;
                        return oldLine >= 0
                                && oldLine < old.count()
                                && Objects.equals(endState, old.states()[oldLine])
                                && (scanner == null || depth == old.depths()[oldLine]);
                    }
                });
        if (a == null) {
            return null;
        }
        int tokenized = a.endStates().size();
        int lastLine = firstLine + tokenized - 1;
        int tail = lineCount - lastLine - 1; // lines after the pass, kept from the old table
        IStateStack[] states = new IStateStack[lineCount];
        int[] lineDepths = brackets ? new int[lineCount] : null;
        if (firstLine > 0) {
            System.arraycopy(old.states(), 0, states, 0, firstLine);
        }
        for (int i = 0; i < tokenized; i++) {
            states[firstLine + i] = a.endStates().get(i);
        }
        if (tail > 0) {
            System.arraycopy(old.states(), lastLine + 1 - delta, states, lastLine + 1, tail);
        }
        if (brackets) {
            if (firstLine > 0) {
                System.arraycopy(old.depths(), 0, lineDepths, 0, firstLine);
            }
            System.arraycopy(depths.values, 0, lineDepths, firstLine, tokenized);
            if (tail > 0) {
                System.arraycopy(old.depths(), lastLine + 1 - delta, lineDepths, lastLine + 1, tail);
            }
        }
        List<TextMateHighlighter.Symbol> previous = r.symbols() == null ? List.of() : r.symbols();
        List<TextMateHighlighter.Symbol> symbols =
                mergeSymbols(previous, a.symbols(), firstLine, tail > 0 ? lastLine - delta : Integer.MAX_VALUE, delta);
        boolean symbolsChanged = !symbols.equals(previous);
        StyleSpans<Collection<String>> spans = a.spans();
        if (spans != null && r.semantic() != null) {
            StyleSpans<Collection<String>> sem =
                    semanticSpans(r.semantic(), firstLine, bounds, a.fromOffset(), spans.length());
            if (sem != null) {
                // Semantic wins where present.
                spans = spans.overlay(sem, (lex, s) -> s.isEmpty() ? lex : s);
            }
        }
        if (spans != null && scanner != null) {
            // Bracket colours go on last so they win on the bracket characters, and REPLACE rather than
            // union — see BracketColors.buildSpans for why unioning loses under every theme.
            StyleSpans<Collection<String>> bc = BracketColors.buildSpans(spans.length(), scanner.marks());
            if (bc != null) {
                spans = spans.overlay(bc, (lex, b) -> b.isEmpty() ? lex : b);
            }
        }
        return new Result(
                a.fromOffset(),
                spans,
                new Lines(states, lineDepths),
                symbolsChanged ? symbols : previous,
                symbolsChanged,
                firstLine,
                lastLine);
    }

    /**
     * The symbols of the new text: the old ones above the pass, the pass's own, and the old ones below it
     * ({@code line > lastOldLine}) moved by {@code delta} lines.
     */
    private static List<TextMateHighlighter.Symbol> mergeSymbols(
            List<TextMateHighlighter.Symbol> previous,
            List<TextMateHighlighter.Symbol> fresh,
            int firstLine,
            int lastOldLine,
            int delta) {
        List<TextMateHighlighter.Symbol> merged = new ArrayList<>(previous.size() + fresh.size());
        for (TextMateHighlighter.Symbol s : previous) {
            if (s.line() < firstLine) {
                merged.add(s);
            }
        }
        merged.addAll(fresh);
        for (TextMateHighlighter.Symbol s : previous) {
            if (s.line() > lastOldLine) {
                merged.add(delta == 0 ? s : new TextMateHighlighter.Symbol(s.line() + delta, s.name(), s.kind()));
            }
        }
        return merged;
    }

    /**
     * A sparse {@link StyleSpans} of {@code length} characters starting at {@code fromOffset}, carrying the
     * CSS classes of each semantic token on the tokenized lines; gaps are empty styles so it can
     * {@code overlay} the token spans. Returns {@code null} if no token falls in the range.
     *
     * <p>Tokens are sorted by position (the decoder preserves wire order), so a single forward cursor
     * builds the spans; an out-of-order or overlapping token is skipped defensively.
     *
     * @param bounds start and end offset of each tokenized line, from {@code firstLine} on
     */
    private static StyleSpans<Collection<String>> semanticSpans(
            List<SemanticToken> tokens, int firstLine, IntList bounds, int fromOffset, int length) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int cursor = 0; // offset within the range of the next unstyled char
        int lines = bounds.size / 2;
        for (SemanticToken t : tokens) {
            int index = t.line() - firstLine;
            if (index < 0 || index >= lines) {
                continue;
            }
            int lineStart = bounds.values[2 * index];
            int col = Math.min(Math.max(0, t.startChar()), bounds.values[2 * index + 1] - lineStart);
            int off = lineStart + col - fromOffset;
            if (off < cursor || off >= length) {
                continue; // before the cursor (overlap/out-of-order) or past the range end
            }
            int len = Math.min(t.length(), length - off);
            if (len <= 0) {
                continue;
            }
            if (off > cursor) {
                b.add(Collections.emptyList(), off - cursor);
            }
            b.add(classes(t.cssClasses()), len);
            cursor = off + len;
        }
        if (cursor == 0) {
            return null;
        }
        if (cursor < length) {
            b.add(Collections.emptyList(), length - cursor);
        }
        return b.create();
    }

    /**
     * Whether any character of {@code spans} (which start at offset {@code from}) carries a style class,
     * other than a lone styled character at one of the {@code except} offsets.
     */
    static boolean anyStyled(StyleSpans<Collection<String>> spans, int from, int[] except) {
        int at = from;
        for (var span : spans) {
            boolean excepted = span.getLength() == 1 && except != null && (at == except[0] || at == except[1]);
            if (!span.getStyle().isEmpty() && !excepted) {
                return true;
            }
            at += span.getLength();
        }
        return false;
    }

    /** Split class lists per class string: servers use a few dozen type/modifier combinations. */
    private static final ConcurrentHashMap<String, List<String>> CLASSES = new ConcurrentHashMap<>();

    private static List<String> classes(String cssClasses) {
        List<String> split = CLASSES.get(cssClasses);
        if (split == null) {
            split = List.of(cssClasses.split(" "));
            if (CLASSES.size() < 1024) {
                CLASSES.put(cssClasses, split);
            }
        }
        return split;
    }

    /** A growable {@code int} array (per-line depths and offsets, without boxing each one). */
    private static final class IntList {
        int[] values = new int[64];
        int size;

        void add(int value) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }
    }

    // ---- scheduling ---------------------------------------------------------------------------------

    /** Passes waiting for a grammar, and whether one of them currently holds a pool thread. */
    private static final class Lane {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean running;
    }

    private static final ConcurrentHashMap<IGrammar, Lane> LANES = new ConcurrentHashMap<>();

    /**
     * Runs {@code pass} on {@code pool}, one pass per grammar at a time. A tm4e grammar tokenizes
     * single-threaded, so passes for the same language are serialized on its monitor either way (see
     * {@link TextMateHighlighter#analyzeFrom}); handing them all to the pool at once just parks its
     * threads on that monitor — a session restore with a dozen Java tabs held every pool thread there
     * while the TODO scans, grammar loads and other languages' passes queued behind them. Queued per
     * grammar instead, a waiting pass occupies no thread. Each pass is re-submitted rather than looped, so
     * a long lane still interleaves with the rest of the pool's work.
     */
    static void submit(Executor pool, IGrammar grammar, Runnable pass) {
        Lane lane = LANES.computeIfAbsent(grammar, g -> new Lane());
        synchronized (lane) {
            lane.queue.add(pass);
            if (lane.running) {
                return;
            }
            lane.running = true;
        }
        pool.execute(() -> runNext(pool, lane));
    }

    private static void runNext(Executor pool, Lane lane) {
        Runnable pass;
        synchronized (lane) {
            pass = lane.queue.poll();
        }
        try {
            if (pass != null) {
                pass.run();
            }
        } finally {
            boolean more;
            synchronized (lane) {
                more = !lane.queue.isEmpty();
                lane.running = more;
            }
            if (more) {
                pool.execute(() -> runNext(pool, lane));
            }
        }
    }
}

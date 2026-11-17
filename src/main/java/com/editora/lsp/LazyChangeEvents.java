package com.editora.lsp;

import java.util.AbstractList;
import java.util.List;
import java.util.function.UnaryOperator;

import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.jspecify.annotations.Nullable;

/**
 * The content changes of one {@code didChange}, worked out when they are first read (PERF-01, Pauta 6.0.1). The
 * session queues the notification on the FX thread with this list in it; the writer thread reads it when the
 * notification's turn comes to be encoded, so the whole-document compare, the prefix/suffix diff and the JSON all
 * run there, in wire order. The list holds the two texts it was made from, which the FX thread never changes
 * (strings), so what it works out is what the session's shadow said at the time of the send.
 *
 * <p>When the two texts turn out to be equal (an edit and its undo), the notification still goes out, with a change
 * that changes nothing, so the version the server holds is the one the session counted; {@code onIdentical} then
 * runs on the FX thread, as the caller used to learn from the return value.
 */
final class LazyChangeEvents extends AbstractList<TextDocumentContentChangeEvent> {

    /** The diff of a document against what its server holds, named for {@link BlockingSites}. */
    public static final String SITE = "LanguageServerSession.syncDiff";

    private final @Nullable String old;
    private final String text;
    private final boolean incremental;
    private final @Nullable UnaryOperator<String> wire;
    private final Runnable onIdentical;
    private @Nullable List<TextDocumentContentChangeEvent> events;

    /**
     * @param old the text the server held before, or null when it is to be sent whole
     * @param incremental whether the change is sent as a splice of {@code old} (otherwise the whole text)
     * @param wire the rewrite of the whole text the server is sent instead of it (an alias), or null
     */
    LazyChangeEvents(
            @Nullable String old,
            String text,
            boolean incremental,
            @Nullable UnaryOperator<String> wire,
            Runnable onIdentical) {
        this.old = old;
        this.text = text;
        this.incremental = incremental && old != null && wire == null;
        this.wire = wire;
        this.onIdentical = onIdentical;
    }

    /** How many chars the changes encode to at most: the whole text. */
    long estimatedChars() {
        return text.length();
    }

    @Override
    public TextDocumentContentChangeEvent get(int index) {
        return events().get(index);
    }

    @Override
    public int size() {
        return events().size();
    }

    /** Works the changes out on the first read, on whichever thread reads first (the writer in the app). */
    synchronized List<TextDocumentContentChangeEvent> events() {
        List<TextDocumentContentChangeEvent> e = events;
        if (e == null) {
            e = compute();
            events = e;
        }
        return e;
    }

    private List<TextDocumentContentChangeEvent> compute() {
        BlockingSites.enter(SITE);
        String before = old;
        boolean identical = before != null && before.length() == text.length() && before.equals(text);
        if (identical) {
            try {
                OffFx.onFx(onIdentical);
            } catch (IllegalStateException noToolkit) {
                // no FX thread to tell (a test without one): the change still goes out
            }
            return incremental ? List.of(noChange()) : List.of(whole());
        }
        if (!incremental || before == null) {
            return List.of(whole());
        }
        TextSyncDiff.Delta d = TextSyncDiff.diff(before, text);
        if (d == null) {
            return List.of(noChange());
        }
        return List.of(
                new TextDocumentContentChangeEvent(TextSyncDiff.rangeOf(before, d.start(), d.end()), d.replacement()));
    }

    private TextDocumentContentChangeEvent whole() {
        UnaryOperator<String> w = wire;
        return new TextDocumentContentChangeEvent(w == null ? text : w.apply(text));
    }

    /** An empty splice at the start: the server's text stays as it is, its version moves on. */
    private static TextDocumentContentChangeEvent noChange() {
        return new TextDocumentContentChangeEvent(new Range(new Position(0, 0), new Position(0, 0)), "");
    }
}

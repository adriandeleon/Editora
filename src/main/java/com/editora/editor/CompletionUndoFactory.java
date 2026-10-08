package com.editora.editor;

import java.time.Duration;
import java.util.*;
import java.util.function.*;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TextChange;
import org.fxmisc.richtext.util.UndoUtils;
import org.fxmisc.undo.UndoManager;
import org.fxmisc.undo.UndoManagerFactory;
import org.fxmisc.undo.impl.ChangeQueue;
import org.fxmisc.undo.impl.MultiChangeUndoManagerImpl;
import org.reactfx.EventStream;

/** Keeps UndoFX's replay/merge/mark machinery, with a bounded queue able to rebase delayed imports. */
final class CompletionUndoFactory implements UndoManagerFactory {
    /**
     * Most text (removed plus inserted characters) the undo queue of one document retains. The entry count
     * alone bounds nothing: one entry can hold a rewrite of a multi-megabyte document. 64 M characters is at
     * most 128 MB of UTF-16 text, and far beyond any history made of ordinary edits.
     */
    static final long RETAINED_CHARS = 64L << 20;

    private final int capacity;

    /** The text one undo entry retains: for every change in the batch, what it removed and what it inserted. */
    static long retainedChars(Object batch) {
        if (!(batch instanceof List<?> changes)) {
            return 0;
        }
        long chars = 0;
        for (Object change : changes) {
            if (change instanceof TextChange<?, ?> text) {
                chars += (long) text.getRemovalEnd() + text.getInsertionEnd() - 2L * text.getPosition();
            }
        }
        return chars;
    }

    CompletionUndoFactory(int capacity) {
        this.capacity = capacity;
    }

    /**
     * The one undo history of {@code view}'s document. A split's second view shares the document, and its
     * change stream is the document's, so a manager per view would each record the other's undo as a fresh
     * edit and "undo" it back in. Both views are given this one instead; it replays a change through
     * {@code target} — the view the user is in — so that view's caret is the one taken to the change.
     *
     * <p>{@code pause} is UndoFX's preventMergeDelay: edits further apart than that start a new undo group
     * (the idle break of {@link UndoMerge}).
     */
    static UndoManager<?> forDocument(CodeArea view, Supplier<CodeArea> target, int capacity, Duration pause) {
        return forDocument(view, target, capacity, pause, () -> false);
    }

    /**
     * As above, leaving out of the history every change made while {@code untracked} holds: text the editor
     * put there itself and the user never typed (a followed log's new lines). Recorded, one Undo would take
     * the newest lines of the log back out and mark the file modified. The owner of such a change keeps the
     * history consistent — a change that moves earlier text must forget it.
     */
    static UndoManager<?> forDocument(
            CodeArea view,
            Supplier<CodeArea> target,
            int capacity,
            Duration pause,
            java.util.function.BooleanSupplier untracked) {
        var factory = new CompletionUndoFactory(capacity);
        return view.isPreserveStyle()
                ? factory.createMultiChangeUM(
                        view.multiRichChanges().filter(changes -> !untracked.getAsBoolean()),
                        TextChange::invert,
                        changes ->
                                UndoUtils.applyMultiRichTextChange(target.get()).accept(changes),
                        TextChange::mergeWith,
                        TextChange::isIdentity,
                        pause)
                : factory.createMultiChangeUM(
                        view.multiPlainChanges().filter(changes -> !untracked.getAsBoolean()),
                        TextChange::invert,
                        changes -> UndoUtils.applyMultiPlainTextChange(target.get())
                                .accept(changes),
                        TextChange::mergeWith,
                        TextChange::isIdentity,
                        pause);
    }

    @Override
    public <C> UndoManager<C> createSingleChangeUM(
            EventStream<C> changes,
            Function<? super C, ? extends C> invert,
            Consumer<C> apply,
            BiFunction<C, C, Optional<C>> merge,
            Predicate<C> identity,
            Duration delay) {
        return UndoManagerFactory.fixedSizeHistoryFactory(capacity)
                .createSingleChangeUM(changes, invert, apply, merge, identity, delay);
    }

    @Override
    public <C> UndoManager<List<C>> createMultiChangeUM(
            EventStream<List<C>> changes,
            Function<? super C, ? extends C> invert,
            Consumer<List<C>> apply,
            BiFunction<C, C, Optional<C>> merge,
            Predicate<C> identity,
            Duration delay) {
        var queue = new RebasableQueue<List<C>>(capacity);
        var delegate = new MultiChangeUndoManagerImpl<>(queue, invert, apply, merge, identity, changes, delay);
        var manager = new CompletionUndoManager<>(delegate, queue);
        queue.replaced = manager::replaceEntries;
        return manager;
    }

    /** Delegates normal history unchanged. Rewriting happens inside push, before UndoFX invalidates its values. */
    static final class RebasableQueue<C> implements ChangeQueue<C> {
        private final BudgetedChangeQueue<C> delegate;
        private C target;
        private Consumer<IdentityHashMap<C, C>> replaced = ignored -> {};

        RebasableQueue(int capacity) {
            delegate = new BudgetedChangeQueue<>(capacity, RETAINED_CHARS, CompletionUndoFactory::retainedChars);
        }

        /** Entries held, for tests of the bounds. */
        int size() {
            return delegate.size();
        }

        /** Characters the held entries retain, for tests of the bounds. */
        long retained() {
            return delegate.retained();
        }

        void target(Object batch) {
            @SuppressWarnings("unchecked")
            var value = (C) batch;
            target = value;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public boolean hasPrev() {
            return delegate.hasPrev();
        }

        @Override
        public C peekNext() {
            return delegate.peekNext();
        }

        @Override
        public C peekPrev() {
            return delegate.peekPrev();
        }

        @Override
        public C next() {
            return delegate.next();
        }

        @Override
        public C prev() {
            return delegate.prev();
        }

        @Override
        public QueuePosition getCurrentPosition() {
            return delegate.getCurrentPosition();
        }

        @Override
        public void forgetHistory() {
            target = null;
            spanEntry = null;
            spanOwned = false;
            delegate.forgetHistory();
        }

        // --- spans: a run of edits kept as ONE entry (a macro replay) -----------------------------

        private boolean span;
        /** The entry holding everything the open span has changed so far; null before its first change. */
        private C spanEntry;
        /** Whether {@link #spanEntry} is a list this queue built, which it may therefore grow in place. */
        private boolean spanOwned;
        /** Size of {@link #spanEntry} when its cost was last accounted for. */
        private int spanAccounted;

        /**
         * Until {@link #endSpan}, every change pushed is folded into a single entry instead of becoming its
         * own. One entry is the only shape that undoes as one step <em>and</em> survives the history bound:
         * a replay of a few hundred passes makes more entries than the queue holds, so grouping them from
         * the outside could only ever undo the newest few hundred.
         *
         * <p>An entry of this queue is a list of text changes applied in order, so folding is appending —
         * with a change that continues the previous one (the next typed character) merged into it, which
         * keeps a long run of typing a single change.
         */
        void beginSpan() {
            span = true;
            spanEntry = null;
            spanOwned = false;
        }

        void endSpan() {
            if (span && spanOwned && onTop(spanEntry)) {
                delegate.prev();
                delegate.push(spanEntry); // the same entry again: its cost grew while it was grown in place
            }
            span = false;
            spanEntry = null;
            spanOwned = false;
        }

        boolean spanOpen() {
            return span;
        }

        private boolean onTop(C entry) {
            return entry != null && !delegate.hasNext() && delegate.hasPrev() && delegate.peekPrev() == entry;
        }

        /** Folds {@code change} into the span's entry. UndoFX pushes one change at a time (or none). */
        @SuppressWarnings("unchecked")
        private void pushIntoSpan(C change) {
            if (spanEntry != null && delegate.hasNext() && delegate.peekNext() == spanEntry) {
                // UndoFX stepped back over the span's entry and merged the new change into it itself.
                delegate.push(change);
                spanEntry = change;
                spanOwned = false;
                return;
            }
            if (onTop(spanEntry) && spanEntry instanceof List<?> && change instanceof List<?> incoming) {
                List<Object> batch = spanOwned ? (List<Object>) spanEntry : new ArrayList<>((List<Object>) spanEntry);
                append(batch, incoming);
                // Re-pushed (which re-measures it) when first built and each time it doubles; in between it
                // grows in place, or a replay typing n characters would measure n entries n times over.
                if (!spanOwned || batch.size() >= 2 * spanAccounted) {
                    delegate.prev();
                    delegate.push((C) batch);
                    spanEntry = (C) batch;
                    spanOwned = true;
                    spanAccounted = Math.max(1, batch.size());
                }
                return;
            }
            delegate.push(change);
            spanEntry = change;
            spanOwned = false;
        }

        /** Appends {@code incoming} to {@code batch}; a single change that continues the last one merges into it. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void append(List<Object> batch, List<?> incoming) {
            if (incoming.size() == 1
                    && !batch.isEmpty()
                    && batch.getLast() instanceof TextChange last
                    && incoming.getFirst() instanceof TextChange next) {
                Optional<?> merged = last.mergeWith(next);
                if (merged.isPresent()) {
                    if (((TextChange) merged.get()).isIdentity()) {
                        batch.removeLast();
                    } else {
                        batch.set(batch.size() - 1, merged.get());
                    }
                    return;
                }
            }
            batch.addAll(incoming);
        }

        @Override
        @SafeVarargs
        public final void push(C... changes) {
            C accepted = target;
            target = null;
            if (span && changes.length == 1) {
                pushIntoSpan(changes[0]);
                return;
            }
            if (accepted != null && changes.length == 1 && !delegate.hasNext() && rebase(accepted, changes[0])) return;
            delegate.push(changes);
        }

        @SuppressWarnings("unchecked")
        private boolean rebase(C accepted, C imports) {
            var later = new ArrayList<C>();
            int traversed = 0;
            boolean found = false;
            try {
                while (delegate.hasPrev() && traversed < 128) {
                    C entry = delegate.prev();
                    traversed++;
                    if (entry == accepted) {
                        found = true;
                        break;
                    }
                    later.add(entry);
                }
            } finally {
                for (int i = 0; i < traversed; i++) delegate.next();
            }
            if (!found) return false;
            Collections.reverse(later);
            if (!(accepted instanceof List<?>) || !(imports instanceof List<?>)) return false;
            var plan = ImportUndoRebase.plan(
                    (List<Object>) accepted, (List<List<Object>>) (List<?>) later, (List<Object>) imports);
            if (plan == null) return false;
            for (int i = 0; i < traversed; i++) delegate.prev();
            C[] rewritten = (C[]) new Object[later.size() + 1];
            rewritten[0] = (C) plan.accepted();
            var replacements = new IdentityHashMap<C, C>();
            replacements.put(accepted, rewritten[0]);
            for (int i = 0; i < later.size(); i++) {
                rewritten[i + 1] = (C) plan.later().get(i);
                replacements.put(later.get(i), rewritten[i + 1]);
            }
            delegate.push(rewritten);
            replaced.accept(replacements);
            return true;
        }
    }
}

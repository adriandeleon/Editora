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
            delegate.forgetHistory();
        }

        @Override
        @SafeVarargs
        public final void push(C... changes) {
            C accepted = target;
            target = null;
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

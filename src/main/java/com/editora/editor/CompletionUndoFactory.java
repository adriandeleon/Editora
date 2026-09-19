package com.editora.editor;

import java.time.Duration;
import java.util.*;
import java.util.function.*;

import org.fxmisc.undo.UndoManager;
import org.fxmisc.undo.UndoManagerFactory;
import org.fxmisc.undo.impl.ChangeQueue;
import org.fxmisc.undo.impl.FixedSizeChangeQueue;
import org.fxmisc.undo.impl.MultiChangeUndoManagerImpl;
import org.reactfx.EventStream;

/** Keeps UndoFX's replay/merge/mark machinery, with a bounded queue able to rebase delayed imports. */
final class CompletionUndoFactory implements UndoManagerFactory {
    private final int capacity;

    CompletionUndoFactory(int capacity) {
        this.capacity = capacity;
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
        private final FixedSizeChangeQueue<C> delegate;
        private C target;
        private Consumer<IdentityHashMap<C, C>> replaced = ignored -> {};

        RebasableQueue(int capacity) {
            delegate = new FixedSizeChangeQueue<>(capacity);
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

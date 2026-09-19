package com.editora.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.beans.value.ObservableBooleanValue;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.undo.UndoManager;
import org.reactfx.Subscription;
import org.reactfx.value.Val;

/** Groups a completion and safely rebased imports without folding intervening typing into undo. */
final class CompletionUndoManager<C> implements UndoManager<C> {
    private static final int MAX_GROUPS = 32;
    private static final int MAX_STEPS = 32;
    private final UndoManager<C> delegate;
    private final CompletionUndoFactory.RebasableQueue<?> queue;
    private final ArrayDeque<Group<C>> groups = new ArrayDeque<>();
    private Group<C> recording;
    private Subscription capture;
    private boolean closed;

    private static final class Group<C> {
        final List<C> changes = new ArrayList<>();
        boolean valid = true;
    }

    CompletionUndoManager(UndoManager<C> delegate, CompletionUndoFactory.RebasableQueue<?> queue) {
        this.delegate = delegate;
        this.queue = queue;
    }

    @SuppressWarnings("unchecked")
    void replaceEntries(java.util.IdentityHashMap<?, ?> replacements) {
        for (var group : groups) {
            for (int i = 0; i < group.changes.size(); i++) {
                Object replacement = replacements.get(group.changes.get(i));
                if (replacement != null) group.changes.set(i, (C) replacement);
            }
        }
    }

    private void begin(Group<C> group) {
        delegate.preventMerge();
        recording = group;
        capture = delegate.nextUndoProperty().observeChanges((observable, old, change) -> {
            if (change != null && !delegate.isPerformingAction()) {
                if (group.changes.size() >= MAX_STEPS) group.valid = false;
                else group.changes.add(change);
                delegate.preventMerge();
            }
        });
    }

    private void end() {
        if (capture != null) capture.unsubscribe();
        capture = null;
        recording = null;
        delegate.preventMerge();
    }

    private Runnable beginCompletion() {
        if (closed || recording != null) return () -> {};
        var group = new Group<C>();
        if (groups.size() == MAX_GROUPS) groups.removeFirst();
        groups.addLast(group);
        begin(group);
        return this::end;
    }

    private Consumer<Runnable> captureAppend() {
        Group<C> group = recording != null ? recording : groups.peekLast();
        return action -> {
            boolean append = !closed
                    && recording == null
                    && group != null
                    && group.valid
                    && !group.changes.isEmpty()
                    && delegate.getNextUndo() == group.changes.getLast();
            boolean rebase =
                    !closed && recording == null && !append && group != null && group.valid && !group.changes.isEmpty();
            if (append) begin(group);
            else if (rebase) {
                delegate.preventMerge();
                queue.target(group.changes.getLast());
            }
            try {
                action.run();
            } finally {
                if (append) end();
                if (rebase) {
                    queue.target(null);
                    delegate.preventMerge();
                }
            }
        };
    }

    static Runnable begin(CodeArea first, CodeArea second) {
        var ends = new ArrayList<Runnable>();
        for (CodeArea area : second == null ? List.of(first) : List.of(first, second)) {
            if (area.getUndoManager() instanceof CompletionUndoManager<?> manager) ends.add(manager.beginCompletion());
        }
        return () -> ends.forEach(Runnable::run);
    }

    static Consumer<Runnable> captureAdditionalEdits(CodeArea first, CodeArea second) {
        Consumer<Runnable> apply = Runnable::run;
        for (CodeArea area : second == null ? List.of(first) : List.of(first, second)) {
            if (area.getUndoManager() instanceof CompletionUndoManager<?> manager) {
                Consumer<Runnable> previous = apply;
                Consumer<Runnable> append = manager.captureAppend();
                apply = action -> append.accept(() -> previous.accept(action));
            }
        }
        return apply;
    }

    @Override
    public boolean undo() {
        return perform(false);
    }

    @Override
    public boolean redo() {
        return perform(true);
    }

    private boolean perform(boolean redo) {
        C next = redo ? delegate.getNextRedo() : delegate.getNextUndo();
        if (next == null) return false;
        var reverse = groups.descendingIterator();
        while (reverse.hasNext()) {
            var group = reverse.next();
            if (!group.valid || group.changes.isEmpty()) continue;
            int first = redo ? 0 : group.changes.size() - 1;
            if (group.changes.get(first) != next) continue;
            boolean performed = false;
            for (int i = first; i >= 0 && i < group.changes.size(); i += redo ? 1 : -1) {
                // Identity checks keep a trimmed/branched history from crossing an unrelated edit.
                if ((redo ? delegate.getNextRedo() : delegate.getNextUndo()) != group.changes.get(i)) break;
                if (!(redo ? delegate.redo() : delegate.undo())) break;
                performed = true;
            }
            return performed;
        }
        return redo ? delegate.redo() : delegate.undo();
    }

    @Override
    public Val<Boolean> undoAvailableProperty() {
        return delegate.undoAvailableProperty();
    }

    @Override
    public boolean isUndoAvailable() {
        return delegate.isUndoAvailable();
    }

    @Override
    public Val<C> nextUndoProperty() {
        return delegate.nextUndoProperty();
    }

    @Override
    public Val<C> nextRedoProperty() {
        return delegate.nextRedoProperty();
    }

    @Override
    public Val<Boolean> redoAvailableProperty() {
        return delegate.redoAvailableProperty();
    }

    @Override
    public boolean isRedoAvailable() {
        return delegate.isRedoAvailable();
    }

    @Override
    public ObservableBooleanValue performingActionProperty() {
        return delegate.performingActionProperty();
    }

    @Override
    public boolean isPerformingAction() {
        return delegate.isPerformingAction();
    }

    @Override
    public void preventMerge() {
        delegate.preventMerge();
    }

    @Override
    public void forgetHistory() {
        groups.clear();
        delegate.forgetHistory();
    }

    @Override
    public UndoPosition getCurrentPosition() {
        return delegate.getCurrentPosition();
    }

    @Override
    public ObservableBooleanValue atMarkedPositionProperty() {
        return delegate.atMarkedPositionProperty();
    }

    @Override
    public boolean isAtMarkedPosition() {
        return delegate.isAtMarkedPosition();
    }

    @Override
    public void close() {
        closed = true;
        end();
        groups.clear();
        delegate.close();
    }
}

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
    /** Follow-up edits joined onto a user edit (see {@link #joinLast}); kept apart so they never evict a completion. */
    private final ArrayDeque<Group<C>> joins = new ArrayDeque<>();

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
        for (var all : List.of(groups, joins)) {
            for (var group : all) {
                for (int i = 0; i < group.changes.size(); i++) {
                    Object replacement = replacements.get(group.changes.get(i));
                    if (replacement != null) group.changes.set(i, (C) replacement);
                }
            }
        }
    }

    /** Every group, newest first, the joined ones ahead of the completions. */
    private List<Group<C>> newestFirst() {
        var out = new ArrayList<Group<C>>(joins.size() + groups.size());
        joins.descendingIterator().forEachRemaining(out::add);
        groups.descendingIterator().forEachRemaining(out::add);
        return out;
    }

    private Runnable beginJoined() {
        C last = delegate.getNextUndo();
        if (closed || recording != null || last == null || delegate.isPerformingAction()) return () -> {};
        Group<C> group = null;
        for (var candidate : newestFirst()) {
            if (candidate.valid && !candidate.changes.isEmpty() && candidate.changes.getLast() == last) {
                group = candidate; // already the tail of a step: extend that one
                break;
            }
        }
        if (group == null) {
            group = new Group<>();
            group.changes.add(last);
            if (joins.size() == MAX_GROUPS) joins.removeFirst();
            joins.addLast(group);
        }
        begin(group);
        return this::end;
    }

    /**
     * Makes the edits applied until the returned action runs part of the undo step of the change recorded
     * last — for an edit that follows from the user's own and cannot merge with it because it is elsewhere
     * in the document (a snippet field's mirrors). A no-op inside a completion, which is grouped already.
     */
    static Runnable joinLast(CodeArea area) {
        return area.getUndoManager() instanceof CompletionUndoManager<?> manager ? manager.beginJoined() : () -> {};
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

    /** Runs {@code action} with its edits grouped, for undo and redo, with the edit now on top of the history. */
    private void joinLast(Runnable action) {
        C last = delegate.getNextUndo();
        if (closed || recording != null || last == null || delegate.isPerformingAction()) {
            action.run();
            return;
        }
        var group = new Group<C>();
        group.changes.add(last);
        if (groups.size() == MAX_GROUPS) groups.removeFirst();
        groups.addLast(group);
        begin(group);
        try {
            action.run();
        } finally {
            end();
        }
    }

    /**
     * Runs {@code action} so that whatever it edits is undone and redone together with the edit that was
     * just committed — a follow-up that belongs to the user's keystroke (the paired-tag rename) must not
     * be a separate undo step, or one undo leaves the document in a state the user never made.
     */
    static void joinLastEdit(CodeArea first, CodeArea second, Runnable action) {
        Runnable run = action;
        for (CodeArea area : views(first, second)) {
            if (area.getUndoManager() instanceof CompletionUndoManager<?> manager) {
                Runnable inner = run;
                run = () -> manager.joinLast(inner);
            }
        }
        run.run();
    }

    /** The views whose managers to drive: a split's second view shares the first one's, which counts once. */
    private static List<CodeArea> views(CodeArea first, CodeArea second) {
        return second == null || second.getUndoManager() == first.getUndoManager()
                ? List.of(first)
                : List.of(first, second);
    }

    static Runnable begin(CodeArea first, CodeArea second) {
        var ends = new ArrayList<Runnable>();
        for (CodeArea area : views(first, second)) {
            if (area.getUndoManager() instanceof CompletionUndoManager<?> manager) ends.add(manager.beginCompletion());
        }
        return () -> ends.forEach(Runnable::run);
    }

    static Consumer<Runnable> captureAdditionalEdits(CodeArea first, CodeArea second) {
        Consumer<Runnable> apply = Runnable::run;
        for (CodeArea area : views(first, second)) {
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
        for (var group : newestFirst()) {
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
        joins.clear();
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
        if (closed) return; // shared by a split's two views, each of which closes it
        closed = true;
        end();
        groups.clear();
        joins.clear();
        delegate.close();
    }
}

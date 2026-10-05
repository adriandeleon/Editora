package com.editora.snippet;

import java.util.ArrayList;
import java.util.List;

import org.fxmisc.richtext.CodeArea;

/** A bounded stack of nested expansions. Parents track ranges while only the child handles Tab/typing. */
public final class SnippetSessions {
    private final List<SnippetSession> stack = new ArrayList<>();
    private final java.util.function.Function<CodeArea, Runnable> undoJoin;

    public SnippetSessions() {
        this(null);
    }

    /**
     * @param undoJoin opens an undo group that folds the edits made until the returned action runs into the
     *     step of the edit before them, so a field's mirrors are undone with the Backspace or paste that
     *     changed it; {@code null} leaves each mirror edit its own step
     */
    public SnippetSessions(java.util.function.Function<CodeArea, Runnable> undoJoin) {
        this.undoJoin = undoJoin;
    }

    private SnippetSession active() {
        return stack.isEmpty() ? null : stack.getLast();
    }

    public boolean isActive() {
        return active() != null && active().isActive();
    }

    /** How many expansions are stacked: 0 when idle, more than 1 while one runs inside another's field. */
    public int depth() {
        return stack.size();
    }

    public void start(CodeArea area, ParsedSnippet parsed, int from, int to, String indent) {
        start(area, parsed, from, to, indent, null);
    }

    /** As above, with the buffer's indent unit for the snippet's own indentation ({@code null} = keep tabs). */
    public void start(CodeArea area, ParsedSnippet parsed, int from, int to, String indent, String indentUnit) {
        SnippetSession parent = active();
        if (stack.size() >= 16 || parent != null && !parent.suspendForChild(area, from, to)) {
            cancel();
            parent = null;
        }
        SnippetSession child = new SnippetSession(area, parsed, from, to, indent, indentUnit);
        child.setUndoJoin(undoJoin);
        if (child.isActive()) {
            stack.add(child);
            child.setOnEnd(() -> ended(child));
        } else if (parent != null) parent.resume();
    }

    private void ended(SnippetSession session) {
        int index = stack.indexOf(session);
        if (index < 0) return;
        if (!session.completed()) {
            cancel();
            return;
        }
        List<SnippetSession> removed = new ArrayList<>(stack.subList(index, stack.size()));
        stack.subList(index, stack.size()).clear();
        removed.forEach(SnippetSession::cancel);
        if (active() != null) active().resume();
    }

    public void next() {
        if (active() != null) active().next();
    }

    public void previous() {
        if (active() != null) active().previous();
    }

    public void finish() {
        if (active() != null) active().finish();
    }

    /** Escape/Undo/disposal abandons the entire chain, without moving the caret. */
    public void cancel() {
        var sessions = new ArrayList<>(stack);
        stack.clear();
        sessions.forEach(SnippetSession::cancel);
    }

    public boolean replaceInActiveField(int from, int to, String text) {
        return active() != null && active().replaceInActiveField(from, to, text);
    }

    public void withExternalEdits(Runnable action) {
        var sessions = List.copyOf(stack);
        sessions.forEach(s -> s.setExternalEdit(true));
        try {
            action.run();
        } finally {
            sessions.forEach(s -> s.setExternalEdit(false));
        }
    }
}

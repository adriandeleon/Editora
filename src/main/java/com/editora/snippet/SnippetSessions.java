package com.editora.snippet;

import java.util.ArrayList;
import java.util.List;

import org.fxmisc.richtext.CodeArea;

/** A bounded stack of nested expansions. Parents track ranges while only the child handles Tab/typing. */
public final class SnippetSessions {
    private final List<SnippetSession> stack = new ArrayList<>();
    private final java.util.function.Function<CodeArea, Runnable> undoJoin;
    private Runnable onChanged = () -> {};

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

    /**
     * Whether a session should take a Tab, Shift+Tab or Escape pressed in {@code area}: one is running in
     * that view <em>and the caret is still in one of its fields</em>. The caret rule is applied here, now,
     * rather than waiting for the deferred check, so a session the caret has just left never takes the key.
     */
    public boolean ownsKeys(CodeArea area) {
        SnippetSession s = active();
        if (s == null || s.area() != area) {
            return false;
        }
        s.settle();
        return isActive();
    }

    /** The view the running session is in, or null. */
    public CodeArea area() {
        return active() == null ? null : active().area();
    }

    /** Ends the chain when the user is now working in another view of the document than the session's. */
    public void focusMovedTo(CodeArea area) {
        if (active() != null && active().area() != area) {
            cancel();
        }
    }

    /** Run whenever the fields to draw or the progress to report may have changed, and when a chain ends. */
    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    /** Every tracked range of every stacked session (see {@link SnippetSession#marks}). */
    public void marks(SnippetSession.MarkSink sink) {
        for (SnippetSession s : stack) {
            s.marks(sink);
        }
    }

    /** {@code {position, count}} of the active field in the innermost session, or null when idle. */
    public int[] progress() {
        return isActive() ? active().progress() : null;
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
            child.setOnChanged(() -> onChanged.run());
        } else if (parent != null) parent.resume();
        onChanged.run();
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
        onChanged.run();
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
        if (stack.isEmpty()) return;
        var sessions = new ArrayList<>(stack);
        stack.clear();
        sessions.forEach(SnippetSession::cancel);
        onChanged.run();
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

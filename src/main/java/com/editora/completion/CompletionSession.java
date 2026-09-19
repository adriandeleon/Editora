package com.editora.completion;

/** Tracks only a growing/shrinking identifier suffix. Any other edit invalidates request coordinates. */
public final class CompletionSession {
    private final int start;
    private final int line;
    private final int column;
    private final int originalCaret;
    private final String initialPrefix;
    private String prefix;
    private int caret;
    private long version;
    private boolean valid = true;
    private CompletionResult result;

    public CompletionSession(int caret, int line, int column, String prefix, long version) {
        this.start = caret - prefix.length();
        this.originalCaret = this.caret = caret;
        this.line = line;
        this.column = column;
        this.initialPrefix = this.prefix = prefix;
        this.version = version;
    }

    public void changed(int position, String removed, String inserted, long version) {
        if (!valid) return;
        if (position < start
                || position + removed.length() != caret
                || !inserted.chars().allMatch(Character::isJavaIdentifierPart)
                || !removed.chars().allMatch(Character::isJavaIdentifierPart)) {
            valid = false;
            return;
        }
        String next = prefix.substring(0, position - start) + inserted;
        // A server may already have narrowed by the original prefix, even on a complete list.
        if (!next.startsWith(initialPrefix)) {
            valid = false;
            return;
        }
        prefix = next;
        caret = position + inserted.length();
        this.version = version;
    }

    public boolean matches(int caret, long version) {
        return valid && this.caret == caret && this.version == version;
    }

    public String prefix() {
        return prefix;
    }

    public int originalCaret() {
        return originalCaret;
    }

    public int caret() {
        return caret;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public CompletionResult result() {
        return result;
    }

    public void result(CompletionResult value) {
        result = value;
    }

    /** Rebase the suffix end; the replacement start must remain in the unchanged prefix. */
    public Completion.ReplaceRange range(Completion.ReplaceRange range) {
        if (range == null || !range.hasEnd()) return range;
        if (range.endLine() != line || range.endCharacter() < column) return range;
        return new Completion.ReplaceRange(
                range.line(), range.character(), range.endLine(), range.endCharacter() + caret - originalCaret);
    }
}

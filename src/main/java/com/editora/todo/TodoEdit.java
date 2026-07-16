package com.editora.todo;

/**
 * Pure rebuilds of a structured TODO comment line for the tool window's edit actions — set priority, mark
 * done (rewrite the keyword to {@code DONE}), reopen, and edit the description. Each returns the whole new
 * line (the controller replaces that paragraph undoably in the source buffer). The part before the keyword —
 * indentation + the comment opener ({@code //}, {@code #}, {@code /*}) — is preserved verbatim; the parts
 * after are re-emitted in canonical order {@code KEYWORD [tag] (priority) description}. No JavaFX / no IO, so
 * it is unit-tested.
 */
public final class TodoEdit {

    private TodoEdit() {}

    /**
     * Rebuilds {@code lineText} keeping everything before {@code keywordStart} (the 0-based index of the
     * keyword in the line) and re-emitting {@code keyword [tag] (priority) description} from the given values
     * (a null/blank tag, priority, or description is omitted). Used by the {@code with*} helpers below.
     */
    public static String rebuild(
            String lineText, int keywordStart, String keyword, String tag, String priority, String description) {
        String prefix = lineText.substring(0, Math.max(0, Math.min(keywordStart, lineText.length())));
        StringBuilder sb = new StringBuilder(prefix).append(keyword);
        if (tag != null && !tag.isEmpty()) {
            sb.append(" [").append(tag).append(']');
        }
        if (priority != null && !priority.isEmpty()) {
            sb.append(" (").append(priority).append(')');
        }
        if (description != null && !description.isEmpty()) {
            sb.append(' ').append(description);
        }
        // Re-append the line's block-comment terminator: it belongs to the comment, not to the text we
        // re-emit (see TodoComment.closerStart). Dropping it left an unterminated /* … , silently commenting
        // out everything below it.
        int closer = TodoComment.closerStart(lineText);
        if (closer >= 0) {
            sb.append(' ').append(lineText.substring(closer).strip());
        }
        return sb.toString();
    }

    /** Sets (or clears, when {@code priority} is null/blank) the {@code (priority)} of the comment. */
    public static String withPriority(String lineText, int keywordStart, TodoComment parsed, String priority) {
        return rebuild(lineText, keywordStart, parsed.keyword(), parsed.tag(), priority, parsed.description());
    }

    /** Rewrites the keyword (e.g. to {@code DONE} for mark-done, or back to {@code TODO} to reopen). */
    public static String withKeyword(String lineText, int keywordStart, TodoComment parsed, String keyword) {
        return rebuild(lineText, keywordStart, keyword, parsed.tag(), parsed.priority(), parsed.description());
    }

    /** Replaces the free-text description (a null/blank description removes it). */
    public static String withDescription(String lineText, int keywordStart, TodoComment parsed, String description) {
        return rebuild(
                lineText,
                keywordStart,
                parsed.keyword(),
                parsed.tag(),
                parsed.priority(),
                description == null ? "" : description.strip());
    }

    /** Sets (or clears, when {@code tag} is null/blank) the {@code [tag]} of the comment. */
    public static String withTag(String lineText, int keywordStart, TodoComment parsed, String tag) {
        return rebuild(lineText, keywordStart, parsed.keyword(), tag, parsed.priority(), parsed.description());
    }
}

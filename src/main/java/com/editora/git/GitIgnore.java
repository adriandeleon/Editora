package com.editora.git;

/**
 * Pure helpers for editing a {@code .gitignore} file — used by the Project tree's "Add to .gitignore"
 * action. Toolkit-free and unit-tested; the coordinator does the actual file read/write.
 */
public final class GitIgnore {

    private GitIgnore() {}

    /**
     * The {@code .gitignore} content after adding {@code entry} as an ignore line, or {@code null} when an
     * identical line is already present (nothing to do). Backslashes are normalized to forward slashes,
     * existing content is preserved, and a trailing newline is ensured so the new entry sits on its own line.
     */
    public static String withEntry(String existing, String entry) {
        String line = entry.replace('\\', '/').strip();
        if (line.isEmpty()) {
            return null;
        }
        String content = existing == null ? "" : existing;
        for (String l : content.split("\n", -1)) {
            if (l.strip().equals(line)) {
                return null; // already ignored by an exact match
            }
        }
        StringBuilder sb = new StringBuilder(content);
        if (!content.isEmpty() && !content.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append(line).append('\n');
        return sb.toString();
    }

    /** The ignore entry for a repo-relative path: a directory gets a trailing {@code /} (ignore the whole tree). */
    public static String entryFor(String relPath, boolean directory) {
        String rel = relPath.replace('\\', '/').strip();
        if (rel.isEmpty()) {
            return rel;
        }
        return directory && !rel.endsWith("/") ? rel + "/" : rel;
    }
}

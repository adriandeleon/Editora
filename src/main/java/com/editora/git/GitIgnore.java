package com.editora.git;

/**
 * Pure helpers for editing a {@code .gitignore} file — used by the Project tree's "Add to .gitignore"
 * action. Toolkit-free and unit-tested; the coordinator does the actual file read/write.
 */
public final class GitIgnore {

    private GitIgnore() {}

    /**
     * The {@code .gitignore} content after adding {@code entry} as an ignore line, or {@code null} when an
     * identical line is already present (nothing to do). {@code entry} is a finished pattern from
     * {@link #entryFor} and is written verbatim — its backslashes are escapes, not path separators. Existing
     * content is preserved, and a trailing newline is ensured so the new entry sits on its own line.
     */
    public static String withEntry(String existing, String entry) {
        String line = entry == null ? "" : entry;
        if (line.isBlank()) {
            return null;
        }
        String content = existing == null ? "" : existing;
        for (String l : content.split("\n", -1)) {
            if (l.equals(line) || l.strip().equals(line)) {
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

    /**
     * The ignore entry that matches exactly the repo-relative path {@code relPath} and nothing else: a
     * directory gets a trailing {@code /} (ignore the whole tree).
     *
     * <p>The entry is <b>anchored</b> with a leading {@code /}. An unanchored single-segment name
     * ({@code build}, {@code notes.txt}) matches at every depth, so ignoring one file silently ignored every
     * same-named file in the repository. The leading slash also means a name that itself begins with
     * {@code #} or {@code !} can no longer be read as a comment or a negation. Glob metacharacters
     * ({@code * ? [ ]}) in the name are escaped so they match literally, and a trailing space — which Git
     * would strip — is escaped too. Backslashes in {@code relPath} are path separators (Windows) and become
     * {@code /}.
     */
    public static String entryFor(String relPath, boolean directory) {
        String rel = relPath.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        boolean dir = directory;
        while (rel.endsWith("/")) {
            rel = rel.substring(0, rel.length() - 1);
            dir = true;
        }
        if (rel.isBlank()) {
            return "";
        }
        StringBuilder entry = new StringBuilder(rel.length() + 4).append('/');
        for (int i = 0; i < rel.length(); i++) {
            char c = rel.charAt(i);
            boolean trailingSpace = c == ' ' && rel.substring(i).isBlank();
            if (c == '*' || c == '?' || c == '[' || c == ']' || trailingSpace) {
                entry.append('\\');
            }
            entry.append(c);
        }
        return dir ? entry.append('/').toString() : entry.toString();
    }
}

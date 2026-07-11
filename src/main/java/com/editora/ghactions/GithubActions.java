package com.editora.ghactions;

/**
 * Content detection for GitHub Actions workflow files. A workflow is a YAML file (so the buffer language
 * stays {@code yaml}), recognized by its top-level {@code on:} trigger key and {@code jobs:} map — a
 * signature specific enough to avoid false positives on arbitrary YAML, and robust regardless of the file's
 * path (so it works for a sample outside {@code .github/workflows/} too). Pure, java.base-only, unit-tested.
 */
public final class GithubActions {

    private GithubActions() {}

    /** Only sniff the head of the file — a workflow declares {@code on:}/{@code jobs:} near the top. */
    private static final int MAX_SNIFF_LINES = 400;

    /** Whether {@code text} looks like a GitHub Actions workflow (top-level {@code on:} + {@code jobs:}). */
    public static boolean looksLikeWorkflow(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean hasOn = false;
        boolean hasJobs = false;
        int pos = 0;
        int len = text.length();
        // Iterate lines via indexOf (no whole-string split) so a large file can't cost a full line array.
        for (int line = 0; pos < len && line < MAX_SNIFF_LINES; line++) {
            int nl = text.indexOf('\n', pos);
            int end = nl < 0 ? len : nl;
            char c0 = text.charAt(pos);
            // Top-level keys sit at column 0 (no leading whitespace) and aren't comments.
            if (!(c0 == ' ' || c0 == '\t' || c0 == '\r' || c0 == '#')) {
                String key = topLevelKey(text.substring(pos, end));
                if ("on".equals(key)) {
                    hasOn = true;
                } else if ("jobs".equals(key)) {
                    hasJobs = true;
                }
                if (hasOn && hasJobs) {
                    return true;
                }
            }
            pos = end + 1;
        }
        return false;
    }

    /** The unquoted key of a {@code key:} / {@code "key":} / {@code 'key':} top-level line, or {@code null}. */
    private static String topLevelKey(String line) {
        int colon = colonBeforeValue(line);
        if (colon < 0) {
            return null;
        }
        String key = line.substring(0, colon).strip();
        if (key.length() >= 2
                && ((key.charAt(0) == '"' && key.charAt(key.length() - 1) == '"')
                        || (key.charAt(0) == '\'' && key.charAt(key.length() - 1) == '\''))) {
            key = key.substring(1, key.length() - 1);
        }
        return key;
    }

    /** Index of the {@code :} that separates a mapping key from its value ({@code : } or trailing {@code :}). */
    private static int colonBeforeValue(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '#') {
                return -1;
            }
            if (c == ':' && (i + 1 == line.length() || line.charAt(i + 1) == ' ' || line.charAt(i + 1) == '\t')) {
                return i;
            }
        }
        return -1;
    }
}

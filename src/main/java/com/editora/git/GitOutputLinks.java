package com.editora.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File paths printed by Git's human-readable status and diff formats.  Kept separate from the view so paths
 * can be styled and opened without trying to infer a file from arbitrary command output.
 */
public final class GitOutputLinks {

    public record Link(int start, int end, String file) {
        public boolean contains(int offset) {
            return offset >= start && offset < end;
        }
    }

    private static final Pattern STATUS =
            Pattern.compile("^\\s*(?:modified|deleted|new file|typechange):\\s+(.+?)(?:\\s+->\\s+.*)?\\s*$");
    private static final Pattern RENAME = Pattern.compile("^\\s*renamed:\\s+(.+?) -> (.+?)\\s*$");
    private static final Pattern RENAME_PART = Pattern.compile("^\\s*renamed (?:from|to):\\s+(.+?)\\s*$");
    private static final Pattern MODE = Pattern.compile("^\\s*(?:create|delete) mode \\d+ (.+?)\\s*$");
    private static final Pattern DIFF_FILE = Pattern.compile("^(?:---|\\+\\+\\+) [ab]/(.+?)\\s*$");
    private static final Pattern DIFF_PAIR = Pattern.compile("^diff --git a/(.+?) b/(.+?)\\s*$");

    private GitOutputLinks() {}

    /** Finds file tokens in one line of ordinary Git output. */
    public static List<Link> find(String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        List<Link> links = new ArrayList<>();
        Matcher pair = DIFF_PAIR.matcher(line);
        if (pair.matches()) {
            add(links, line, pair.start(1), pair.end(1));
            add(links, line, pair.start(2), pair.end(2));
            return List.copyOf(links);
        }
        Matcher status = STATUS.matcher(line);
        if (status.matches()) {
            add(links, line, status.start(1), status.end(1));
            return List.copyOf(links);
        }
        Matcher rename = RENAME.matcher(line);
        if (rename.matches()) {
            add(links, line, rename.start(1), rename.end(1));
            add(links, line, rename.start(2), rename.end(2));
            return List.copyOf(links);
        }
        Matcher renamePart = RENAME_PART.matcher(line);
        if (renamePart.matches()) {
            add(links, line, renamePart.start(1), renamePart.end(1));
            return List.copyOf(links);
        }
        Matcher mode = MODE.matcher(line);
        if (mode.matches()) {
            add(links, line, mode.start(1), mode.end(1));
            return List.copyOf(links);
        }
        Matcher diffFile = DIFF_FILE.matcher(line);
        if (diffFile.matches() && !"/dev/null".equals(diffFile.group(1))) {
            add(links, line, diffFile.start(1), diffFile.end(1));
        }
        return List.copyOf(links);
    }

    /** The file link at {@code offset}, or null when that character is plain transcript text. */
    public static Link at(String text, int offset) {
        if (text == null || offset < 0 || offset >= text.length()) {
            return null;
        }
        int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int lineEnd = text.indexOf('\n', offset);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        for (Link link : find(text.substring(lineStart, lineEnd))) {
            Link absolute = new Link(lineStart + link.start(), lineStart + link.end(), link.file());
            if (absolute.contains(offset)) {
                return absolute;
            }
        }
        return null;
    }

    private static void add(List<Link> links, String line, int start, int end) {
        String file = line.substring(start, end);
        if (!file.isBlank() && !"/dev/null".equals(file)) {
            links.add(new Link(start, end, file));
        }
    }
}

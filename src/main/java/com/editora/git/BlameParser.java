package com.editora.git;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser for {@code git blame --porcelain} (or {@code --line-porcelain}) output → one {@link BlameLine}
 * per source line (in file order). Each porcelain block starts with a {@code <commit id> <orig> <final> [<n>]}
 * header, carries {@code author}/{@code author-time}/{@code summary} fields the first time (or, for
 * {@code --line-porcelain}, every time) a commit is named, and ends with the {@code \t}-prefixed content line. Lines not yet committed carry the all-zero sha → {@code uncommitted}. Unit-tested.
 */
public final class BlameParser {

    /**
     * One blamed line: the commit it last changed in, the author, commit time (epoch seconds), and subject.
     * {@code path} is the file's repo-relative name <em>in that commit</em> — blame follows whole-file renames,
     * so for a line older than a move it is the old path, the only one {@code <hash>:<path>} resolves.
     * {@code previousPath} is its name in the commit's parent ({@code null} when the commit added the file).
     */
    public record BlameLine(
            String hash,
            String author,
            long epochSeconds,
            String summary,
            boolean uncommitted,
            String path,
            String previousPath) {

        public BlameLine(String hash, String author, long epochSeconds, String summary, boolean uncommitted) {
            this(hash, author, epochSeconds, summary, uncommitted, null, null);
        }
    }

    /** A block header: the commit id (40 hex digits for SHA-1, 64 for SHA-256), then the line numbers. */
    private static final Pattern HEADER = Pattern.compile("^([0-9a-f]{40,64}) \\d+ \\d+");

    /** What git says about a commit; {@code --porcelain} says it once, in the commit's first block. */
    private static final class CommitInfo {
        String author = "";
        String summary = "";
        long time;
        String path;
        String previousPath;
    }

    private BlameParser() {}

    /**
     * Parses {@code --porcelain} or {@code --line-porcelain} output. The former describes a commit only in
     * the first block that names it (and repeats {@code filename}/{@code previous} only for a commit that
     * touched the file under more than one path), so the fields are remembered per commit.
     */
    public static List<BlameLine> parse(String porcelain) {
        List<BlameLine> out = new ArrayList<>();
        if (porcelain == null || porcelain.isEmpty()) {
            return out;
        }
        Map<String, CommitInfo> commits = new HashMap<>();
        String hash = null;
        CommitInfo info = null;
        String blockPrevious = null;
        for (String line : porcelain.split("\n", -1)) {
            if (line.startsWith("\t")) {
                // The content line terminates the current block: emit it.
                if (hash != null) {
                    boolean uncommitted = hash.chars().allMatch(c -> c == '0');
                    out.add(new BlameLine(
                            hash, info.author, info.time, info.summary, uncommitted, info.path, info.previousPath));
                }
                hash = null;
                info = null;
                blockPrevious = null;
                continue;
            }
            if (info == null) {
                Matcher header = line.length() >= 40 ? HEADER.matcher(line) : null;
                if (header != null && header.find()) {
                    hash = header.group(1);
                    info = commits.computeIfAbsent(hash, h -> new CommitInfo());
                }
                continue; // anything else before a header belongs to no block
            }
            if (line.startsWith("author ")) {
                info.author = line.substring("author ".length()).strip();
            } else if (line.startsWith("author-time ")) {
                try {
                    info.time = Long.parseLong(
                            line.substring("author-time ".length()).strip());
                } catch (NumberFormatException ignored) {
                    // leave time = 0
                }
            } else if (line.startsWith("summary ")) {
                info.summary = line.substring("summary ".length()).strip();
            } else if (line.startsWith("previous ")) {
                // previous <parent commit id> <the file's path there>
                int space = line.indexOf(' ', "previous ".length());
                if (space > 0 && space + 1 < line.length()) {
                    blockPrevious = StatusParser.unquotePath(line.substring(space + 1));
                }
            } else if (line.startsWith("filename ")) {
                // Git writes "previous" (when there is one) immediately before "filename", as a pair.
                info.path = StatusParser.unquotePath(line.substring("filename ".length()));
                info.previousPath = blockPrevious;
            }
        }
        return out;
    }
}

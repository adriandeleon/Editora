package com.editora.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure parser for {@code git blame --line-porcelain} output → one {@link BlameLine} per source line
 * (in file order). Each porcelain block starts with a {@code <40-hex-sha> <orig> <final> [<n>]} header,
 * repeats {@code author}/{@code author-time}/{@code summary} fields, and ends with the {@code \t}-prefixed
 * content line. Lines not yet committed carry the all-zero sha → {@code uncommitted}. Unit-tested.
 */
public final class BlameParser {

    /** One blamed line: the commit it last changed in, the author, commit time (epoch seconds), and subject. */
    public record BlameLine(String hash, String author, long epochSeconds, String summary,
            boolean uncommitted) { }

    private static final Pattern HEADER = Pattern.compile("^[0-9a-f]{40} \\d+ \\d+");

    private BlameParser() {
    }

    public static List<BlameLine> parse(String porcelain) {
        List<BlameLine> out = new ArrayList<>();
        if (porcelain == null || porcelain.isEmpty()) {
            return out;
        }
        String hash = null;
        String author = "";
        String summary = "";
        long time = 0;
        for (String line : porcelain.split("\n", -1)) {
            if (line.startsWith("\t")) {
                // The content line terminates the current block: emit it.
                if (hash != null) {
                    boolean uncommitted = hash.chars().allMatch(c -> c == '0');
                    out.add(new BlameLine(hash, author, time, summary, uncommitted));
                }
                hash = null;
                author = "";
                summary = "";
                time = 0;
            } else if (line.length() >= 40 && HEADER.matcher(line).find()) {
                hash = line.substring(0, 40);
            } else if (line.startsWith("author ")) {
                author = line.substring("author ".length()).strip();
            } else if (line.startsWith("author-time ")) {
                try {
                    time = Long.parseLong(line.substring("author-time ".length()).strip());
                } catch (NumberFormatException ignored) {
                    // leave time = 0
                }
            } else if (line.startsWith("summary ")) {
                summary = line.substring("summary ".length()).strip();
            }
        }
        return out;
    }
}

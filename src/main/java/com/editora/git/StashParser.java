package com.editora.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser for {@code git stash list} output. Each line looks like
 * {@code stash@{0}: WIP on main: 1a2b3c subject} or {@code stash@{1}: On main: a message} → one
 * {@link StashEntry}. Locale-stable because the service runs git under {@code LC_ALL=C}. Unit-tested.
 */
public final class StashParser {

    /**
     * One stash entry: its list index, the {@code stash@{N}} ref, the branch it was made on, and the subject.
     * {@code hash} is the stash commit ({@code ""} when the listing did not carry it) and {@code epochSeconds}
     * when it was made (0 when unknown). The ref is a <em>position</em> — it names another stash as soon as
     * one is pushed or dropped — so reads use the hash and mutations check the ref still resolves to it.
     */
    public record StashEntry(int index, String ref, String branch, String subject, String hash, long epochSeconds) {

        public StashEntry(int index, String ref, String branch, String subject) {
            this(index, ref, branch, subject, "", 0L);
        }
    }

    /** The {@code git stash list} format {@link #parseDetailed} reads: commit, commit time, reflog subject. */
    public static final String DETAILED_FORMAT = "--format=%H%x1f%ct%x1f%gs";

    private static final Pattern SUBJECT = Pattern.compile("^(?:WIP on|On)\\s+([^:]+):\\s*(.*)$");

    private static final Pattern LINE = Pattern.compile("^stash@\\{(\\d+)\\}:\\s*(?:WIP on|On)\\s+([^:]+):\\s*(.*)$");
    private static final Pattern REF = Pattern.compile("stash@\\{(\\d+)\\}");

    private StashParser() {}

    /**
     * Parses {@code git stash list} run with {@link #DETAILED_FORMAT}. The index is the line's position:
     * the list is always newest first, and the selector git would print ({@code %gd}) turns into a date
     * under {@code log.date} or {@code --date}, which is not a ref.
     */
    public static List<StashEntry> parseDetailed(String out) {
        List<StashEntry> list = new ArrayList<>();
        if (out == null || out.isEmpty()) {
            return list;
        }
        for (String raw : out.split("\n")) {
            String[] fields = raw.split("\u001f", 3);
            if (fields.length < 3 || fields[0].isBlank()) {
                continue;
            }
            long epoch = 0L;
            try {
                epoch = Long.parseLong(fields[1].strip());
            } catch (NumberFormatException ignored) {
                // leave the time unknown
            }
            String subject = fields[2].strip();
            String branch = "";
            Matcher m = SUBJECT.matcher(subject);
            if (m.matches()) {
                branch = m.group(1).strip();
                subject = m.group(2).strip();
            }
            int index = list.size();
            list.add(new StashEntry(index, "stash@{" + index + "}", branch, subject, fields[0].strip(), epoch));
        }
        return list;
    }

    public static List<StashEntry> parse(String out) {
        List<StashEntry> list = new ArrayList<>();
        if (out == null || out.isEmpty()) {
            return list;
        }
        for (String raw : out.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (m.matches()) {
                int idx = Integer.parseInt(m.group(1));
                list.add(new StashEntry(
                        idx,
                        "stash@{" + idx + "}",
                        m.group(2).strip(),
                        m.group(3).strip()));
                continue;
            }
            // Fallback for any unexpected shape: split on the first ": " into ref + subject.
            int colon = line.indexOf(": ");
            if (colon > 0) {
                String ref = line.substring(0, colon).strip();
                String subject = line.substring(colon + 2).strip();
                int idx = -1;
                Matcher im = REF.matcher(ref);
                if (im.find()) {
                    idx = Integer.parseInt(im.group(1));
                }
                list.add(new StashEntry(idx, ref, "", subject));
            }
        }
        return list;
    }
}

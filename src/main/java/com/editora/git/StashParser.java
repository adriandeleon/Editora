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

    /** One stash entry: its list index, the {@code stash@{N}} ref, the branch it was made on, and the subject. */
    public record StashEntry(int index, String ref, String branch, String subject) {}

    private static final Pattern LINE = Pattern.compile("^stash@\\{(\\d+)\\}:\\s*(?:WIP on|On)\\s+([^:]+):\\s*(.*)$");
    private static final Pattern REF = Pattern.compile("stash@\\{(\\d+)\\}");

    private StashParser() {}

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

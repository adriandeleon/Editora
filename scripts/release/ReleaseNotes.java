package release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The GitHub release's notes, from {@code CHANGELOG.md}: the install text ({@code .github/release-notes.md}), then
 * the released version's section. JReleaser publishes the result as-is ({@code changelog.external} in
 * {@code jreleaser.yml}) instead of a list of commits, and the Welcome page's What's New opens that release, so this
 * is what a user reads about the version.
 *
 * <pre>
 *   java scripts/release/ReleaseNotes.java &lt;version&gt; CHANGELOG.md .github/release-notes.md out/release-notes.md
 *   java scripts/release/ReleaseNotes.java --unreleased &lt;version&gt; …   (a dry run or an -rc: "## [Unreleased]" will do)
 * </pre>
 *
 * <p>JReleaser trims a body over 10,000 characters to its first 9,995 and attaches the whole as a file, and an Editora
 * section is usually far longer than that. A section that does not fit is shown by its entries' bold leads, one line
 * each, under its own headings, with a link to the full text at the tag.
 *
 * <p>Fails, naming the heading it wanted, when the changelog has no section for the version: a tag pushed before
 * {@code [Unreleased]} was renamed would otherwise publish empty notes. Tested by {@code ReleaseNotesTest} beside it.
 */
public final class ReleaseNotes {

    /** JReleaser's own ceiling for a GitHub release body. */
    static final int LIMIT = 10_000;

    static final String REPOSITORY = "https://github.com/adriandeleon/Editora";

    private ReleaseNotes() {}

    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        List<String> rest = new ArrayList<>(List.of(args));
        boolean unreleased = rest.remove("--unreleased");
        if (rest.size() != 4) {
            err.println("usage: ReleaseNotes [--unreleased] <version> <CHANGELOG.md> <install.md> <out.md>");
            return 2;
        }
        String version = rest.get(0);
        String changelog = Files.readString(Path.of(rest.get(1)));
        String install = Files.readString(Path.of(rest.get(2)));
        Path target = Path.of(rest.get(3));

        Optional<String> section = section(changelog, version);
        String ref = "v" + version;
        if (section.isEmpty() && unreleased) {
            section = section(changelog, "Unreleased");
            ref = "master";
        }
        if (section.isEmpty() || section.get().isBlank()) {
            err.println("::error title=No CHANGELOG.md section::CHANGELOG.md has no \"## [" + version + "]\" section"
                    + " with entries. Rename \"## [Unreleased]\" to \"## [" + version + "] - <date>\" in the commit"
                    + " being tagged.");
            return 1;
        }
        String body = compose(install, section.get(), REPOSITORY + "/blob/" + ref + "/CHANGELOG.md");
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, body);
        out.println("release notes: " + body.length() + " characters"
                + (body.contains(section.get()) ? "" : ", the section summarised to its entries' leads") + " -> "
                + target);
        return 0;
    }

    /**
     * The text under {@code ## <name>} ({@code ## [name]}, and anything after the name such as a date, are accepted),
     * up to the next {@code ## } heading; empty when there is no such heading.
     */
    static Optional<String> section(String changelog, String name) {
        Pattern heading = Pattern.compile("^## \\[?" + Pattern.quote(name) + "]?(\\s.*)?$");
        StringBuilder body = null;
        for (String line : changelog.lines().toList()) {
            if (body == null) {
                if (heading.matcher(line).matches()) {
                    body = new StringBuilder();
                }
            } else if (line.startsWith("## ")) {
                break;
            } else {
                body.append(line).append('\n');
            }
        }
        return body == null ? Optional.empty() : Optional.of(body.toString().strip());
    }

    /** The body: the install text and the section, or the section's summary when both do not fit. */
    static String compose(String install, String section, String fullText) {
        String head = install.strip() + "\n\n## What's changed\n\n";
        String full = head + section + "\n";
        if (full.length() <= LIMIT) {
            return full;
        }
        String tail = "\nEvery entry in full: [CHANGELOG.md](" + fullText + ").\n";
        // Room is kept for the tail and for the line that says how many were left out.
        int room = LIMIT - tail.length() - 40;
        StringBuilder body = new StringBuilder(head);
        Map<String, List<String>> groups = groups(section);
        int total = groups.values().stream().mapToInt(List::size).sum();
        int shown = 0;
        boolean stopped = false;
        for (var group : groups.entrySet()) {
            String title = group.getKey().isEmpty() ? "" : "### " + group.getKey() + "\n\n";
            StringBuilder part = new StringBuilder(title);
            for (String entry : group.getValue()) {
                String line = "- " + lead(entry) + "\n";
                if (body.length() + part.length() + line.length() + 1 > room) {
                    stopped = true;
                    break;
                }
                part.append(line);
                shown++;
            }
            if (part.length() > title.length()) {
                body.append(part).append('\n');
            }
            if (stopped) {
                break;
            }
        }
        String text = body.toString().stripTrailing() + "\n";
        if (shown < total) {
            text += "- … and " + (total - shown) + " more.\n";
        }
        return text + tail;
    }

    /**
     * The section's entries by {@code ### } heading, in the order the headings first appear: a section that grew by
     * merges can repeat "Added" and "Fixed", and the summary shows each once. Entries before any heading are under "".
     * An entry's wrapped lines and nested lists are joined into it.
     */
    static Map<String, List<String>> groups(String section) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        String group = "";
        StringBuilder entry = null;
        for (String line : (section + "\n").lines().toList()) {
            boolean ends = line.isBlank() || line.startsWith("### ") || line.startsWith("- ");
            if (ends && entry != null) {
                groups.computeIfAbsent(group, k -> new ArrayList<>()).add(entry.toString());
                entry = null;
            }
            if (line.startsWith("### ")) {
                group = line.substring(4).strip();
            } else if (line.startsWith("- ")) {
                entry = new StringBuilder(line.substring(2).strip());
            } else if (entry != null && !line.isBlank()) {
                entry.append(' ').append(line.strip());
            }
        }
        if (entry != null) {
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(entry.toString());
        }
        return groups;
    }

    /** An entry's bold lead ({@code **…**}), else its first sentence, never longer than 200 characters. */
    static String lead(String entry) {
        if (entry.startsWith("**")) {
            int end = entry.indexOf("**", 2);
            if (end > 2) {
                return entry.substring(0, end + 2);
            }
        }
        int stop = entry.indexOf(". ");
        String sentence = stop < 0 ? entry : entry.substring(0, stop + 1);
        return sentence.length() <= 200 ? sentence : sentence.substring(0, 199).strip() + "…";
    }
}

package com.editora;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CHANGELOG.md} keeps the shape Keep a Changelog 1.1 and REPO-02 (Pauta 6.2.0) ask for: {@code ## [Unreleased]}
 * first, every other release headed {@code ## [x.y.z] - YYYY-MM-DD}, its entries under the six standard headings
 * only, and every entry opening with a bold one-line summary. Entries are the top-level list items; the items nested
 * under one belong to it.
 */
@Tag("guard")
class ChangelogFormatTest {

    private static final Path CHANGELOG = Path.of("CHANGELOG.md");
    private static final Pattern RELEASE = Pattern.compile("## \\[\\d+\\.\\d+\\.\\d+] - \\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> SECTIONS =
            Set.of("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security");

    private static List<String> lines() throws Exception {
        assertTrue(Files.isRegularFile(CHANGELOG), "Run from the Maven project root");
        return Files.readAllLines(CHANGELOG);
    }

    @Test
    void unreleasedComesFirstAndEveryReleaseHasAVersionAndADate() throws Exception {
        var releases = new ArrayList<String>();
        var bad = new ArrayList<String>();
        List<String> lines = lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("## ")) {
                if (!releases.isEmpty() && !RELEASE.matcher(line).matches()) {
                    bad.add((i + 1) + ": " + line);
                }
                releases.add(line);
            }
        }
        assertTrue(!releases.isEmpty(), "CHANGELOG.md has no ## sections");
        assertEquals("## [Unreleased]", releases.get(0), "The first section must be ## [Unreleased]");
        assertTrue(bad.isEmpty(), () -> "Head each release ## [x.y.z] - YYYY-MM-DD:\n" + String.join("\n", bad));
    }

    @Test
    void entriesSitUnderTheStandardHeadingsOnly() throws Exception {
        var bad = new ArrayList<String>();
        List<String> lines = lines();
        boolean started = false;
        boolean inSection = false;
        var seen = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("## ")) {
                started = true;
                inSection = false;
                seen.clear();
            } else if (line.startsWith("### ")) {
                String name = line.substring(4).strip();
                if (!SECTIONS.contains(name)) {
                    bad.add((i + 1) + ": " + line + " (use Added, Changed, Deprecated, Removed, Fixed or Security)");
                } else if (seen.contains(name)) {
                    bad.add((i + 1) + ": " + line + " (a second one in the same release)");
                }
                seen.add(name);
                inSection = true;
            } else if (line.startsWith("#") && started) {
                bad.add((i + 1) + ": " + line + " (only ## releases and ### sections)");
            } else if (line.startsWith("- ") && !inSection) {
                bad.add((i + 1) + ": an entry outside a ### section");
            }
        }
        assertTrue(bad.isEmpty(), () -> "CHANGELOG.md sections:\n" + String.join("\n", bad));
    }

    @Test
    void everyEntryOpensWithABoldSummary() throws Exception {
        var bad = new ArrayList<String>();
        List<String> lines = lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("- ") && !line.startsWith("- **")) {
                bad.add((i + 1) + ": " + line);
            }
        }
        assertTrue(
                bad.isEmpty(),
                () -> "Open each entry with a bold one-line summary for users (- **…**):\n" + String.join("\n", bad));
    }
}

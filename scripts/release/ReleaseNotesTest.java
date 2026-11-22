package release;

import static lib.Assert.assertContains;
import static lib.Assert.assertEquals;
import static lib.Assert.assertFalse;
import static lib.Assert.assertNotContains;
import static lib.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lib.Repo;
import lib.TempDir;
import lib.Test;

class ReleaseNotesTest implements AutoCloseable {

    private final TempDir scratch = new TempDir();

    @Override
    public void close() {
        scratch.close();
    }

    private record Run(int code, String out, String err) {}

    private static Run run(String... args) throws IOException {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = ReleaseNotes.run(args, new PrintStream(out), new PrintStream(err));
        return new Run(code, out.toString(), err.toString());
    }

    private static String section(String log, String name) {
        return ReleaseNotes.section(log, name).orElse(null);
    }

    @Test
    void aSectionIsFoundByItsHeadingWithOrWithoutBracketsAndDate() {
        String log = "# Changelog\n\n## [0.2.0] - 2026-11-01\n\n- **Two.**\n\n## 0.1.0\n\n- **One.**\n";
        assertEquals("- **Two.**", section(log, "0.2.0"));
        assertEquals("- **One.**", section(log, "0.1.0"));
        assertEquals(null, section("## [0.1.01]\n\n- x\n", "0.1.0"), "a version that only starts with it is not it");
        assertEquals(null, section("## [0.2.0]\n\n- x\n", "0.2"), "nor one it is a prefix of");
    }

    @Test
    void aSectionStopsAtTheNextVersionAndIsAbsentWithoutItsHeading() {
        String log = "## [Unreleased]\n\n### Added\n\n- **New.**\n\n## [0.1.0] - 2026-10-01\n\n- **Old.**\n";
        assertEquals("### Added\n\n- **New.**", section(log, "Unreleased"));
        assertEquals(Optional.empty(), ReleaseNotes.section(log, "0.3.0"));
    }

    @Test
    void aSectionThatFitsIsCopiedWholeAfterTheInstallText() {
        String body = ReleaseNotes.compose("## Install\n\nRun it.\n", "### Fixed\n\n- **A fix.** Details.", "URL");
        assertEquals("## Install\n\nRun it.\n\n## What's changed\n\n### Fixed\n\n- **A fix.** Details.\n", body);
    }

    @Test
    void groupsMergeRepeatedHeadingsAndJoinWrappedLinesAndNestedLists() {
        Map<String, List<String>> groups = ReleaseNotes.groups("### Added\n\n- **One** wrapped\n  over two lines.\n"
                + "- Two:\n  - a nested point.\n\n### Fixed\n\n- Three.\n\n### Added\n\n- Four.");
        assertEquals("[Added, Fixed]", String.valueOf(groups.keySet()), "each heading once, in first order");
        assertEquals("[**One** wrapped over two lines., Two: - a nested point., Four.]",
                String.valueOf(groups.get("Added")));
    }

    @Test
    void anEntrysLeadIsItsBoldTextElseItsFirstSentence() {
        assertEquals("**The lead.**", ReleaseNotes.lead("**The lead.** The rest."));
        assertEquals("`Engine.remove(id)`, for later.", ReleaseNotes.lead("`Engine.remove(id)`, for later. More."));
        assertTrue(ReleaseNotes.lead("x".repeat(300)).length() <= 200, "a lead is capped");
    }

    @Test
    void aSectionTooLongIsSummarisedToItsLeadsUnderTheLimit() {
        StringBuilder section = new StringBuilder("### Added\n\n");
        for (int i = 0; i < 400; i++) {
            section.append("- **Entry ")
                    .append(i)
                    .append(" with a lead long enough to need a summary.** ")
                    .append("Detail. ".repeat(10))
                    .append('\n');
        }
        String body = ReleaseNotes.compose("## Install\n", section.toString(), "https://example/CHANGELOG.md");
        assertTrue(body.length() <= ReleaseNotes.LIMIT, "over the limit: " + body.length());
        assertNotContains(body, "Detail.");
        assertContains(body, " more.\n");
        assertTrue(body.endsWith("[CHANGELOG.md](https://example/CHANGELOG.md).\n"), body);
    }

    @Test
    void theNotesAreWrittenForTheVersionAndATagWithoutItsSectionFails() throws IOException {
        Path log = scratch.resolve("CHANGELOG.md");
        Files.writeString(log, "## [Unreleased]\n\n- **Next.**\n\n## [1.2.0] - 2026-11-01\n\n- **Shipped.**\n");
        Path install = scratch.resolve("install.md");
        Files.writeString(install, "## Install\n\nDownload it.\n");
        Path out = scratch.resolve("out/release-notes.md");

        Run tagged = run("1.2.0", log.toString(), install.toString(), out.toString());
        assertEquals(0, tagged.code(), tagged.err());
        assertEquals("## Install\n\nDownload it.\n\n## What's changed\n\n- **Shipped.**\n", Files.readString(out));

        Run missing = run("1.3.0", log.toString(), install.toString(), out.toString());
        assertEquals(1, missing.code());
        assertContains(missing.err(), "\"## [1.3.0]\"");

        Run dryRun = run("--unreleased", "1.3.0", log.toString(), install.toString(), out.toString());
        assertEquals(0, dryRun.code(), dryRun.err());
        assertContains(Files.readString(out), "- **Next.**");
    }

    @Test
    void anEmptySectionIsRefused() throws IOException {
        Path log = scratch.resolve("CHANGELOG.md");
        Files.writeString(log, "## [1.2.0] - 2026-11-01\n\n## [1.1.0]\n\n- **Old.**\n");
        Path install = scratch.resolve("install.md");
        Files.writeString(install, "## Install\n");
        Run run = run("1.2.0", log.toString(), install.toString(), scratch.resolve("notes.md").toString());
        assertEquals(1, run.code());
    }

    /** The real changelog: every released section, and the one being written, become notes that fit. */
    @Test
    void everySectionOfTheRealChangelogFitsAReleaseBody() throws IOException {
        String changelog = Files.readString(Repo.root().resolve("CHANGELOG.md"));
        String install = Files.readString(Repo.root().resolve(".github/release-notes.md"));
        var headings = Pattern.compile("(?m)^## \\[([^\\]]+)]").matcher(changelog);
        int sections = 0;
        while (headings.find()) {
            String name = headings.group(1);
            String section = ReleaseNotes.section(changelog, name).orElseThrow();
            if (section.isBlank()) {
                continue;
            }
            String body = ReleaseNotes.compose(install, section, "https://example/CHANGELOG.md");
            assertTrue(body.length() <= ReleaseNotes.LIMIT, name + ": " + body.length() + " characters");
            assertTrue(body.startsWith(install.strip()), name + ": the install text comes first");
            assertFalse(body.contains("\n- … and 0 more."), name);
            sections++;
        }
        assertTrue(sections > 10, "found only " + sections + " sections: the heading scan is broken");
    }
}

package com.editora.git;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E8: the Git console's line patterns must stay linear-ish on hostile lines. A hook can print anything, and
 * one line of 2,000 leading spaces used to cost seconds across the three diffstat patterns (4,000 did not
 * finish in a minute), on the thread that styles the console.
 */
class GitOutputBacktrackingTest {

    private static final Duration LIMIT = Duration.ofSeconds(5);

    private static List<String> hostileLines() {
        String spaces = " ".repeat(20_000);
        return List.of(
                spaces,
                spaces + "x",
                "x" + spaces + "y",
                spaces + "x" + spaces + "y" + spaces,
                "\t".repeat(20_000) + "done",
                spaces + "modified:" + spaces,
                spaces + "create mode" + spaces,
                // With a pipe the stat pattern does run; a few thousand blanks must still be quick.
                " ".repeat(3_000) + "|" + " ".repeat(3_000),
                "x" + " ".repeat(3_000) + "|" + " ".repeat(3_000) + "x",
                " ".repeat(3_000) + "a b".repeat(500) + " | zz");
    }

    @Test
    void longWhitespaceRunsDoNotStallThePatterns() {
        assertTimeoutPreemptively(LIMIT, () -> {
            for (String line : hostileLines()) {
                GitOutputLinks.find(line);
                GitOutputHighlights.find(line);
                GitOutputDiffs.graph(line);
                GitOutputDiffs.at(line, line.length() - 1);
            }
        });
    }

    @Test
    void statRowsAreStillRecognised() {
        String row = " src/main/java/Main.java | 4 ++--";
        List<GitOutputLinks.Link> links = GitOutputLinks.find(row);
        assertEquals(1, links.size());
        assertEquals("src/main/java/Main.java", links.get(0).file());
        assertEquals(1, links.get(0).start());

        List<GitOutputHighlights.Span> spans = GitOutputHighlights.find(row);
        assertEquals("git-output-count", spans.get(0).styleClass());
        assertEquals("diff-inserted", spans.get(1).styleClass());
        assertEquals("diff-deleted", spans.get(2).styleClass());

        int[] graph = GitOutputDiffs.graph(row);
        assertNotNull(graph);
        assertEquals("4 ++--", row.substring(graph[0], graph[1]));

        assertEquals(
                "a b.png",
                GitOutputLinks.find(" a b.png | Bin 0 -> 12 bytes").get(0).file());
        assertNull(GitOutputDiffs.graph(" a b.png | Bin 0 -> 12 bytes"));
        assertTrue(GitOutputLinks.find("   just some indented hook output").isEmpty());
        assertEquals("f.txt", GitOutputLinks.find("\tmodified:   f.txt").get(0).file());
        assertEquals(
                "git-output-summary",
                GitOutputHighlights.find(" 2 files changed, 3 insertions(+)")
                        .get(0)
                        .styleClass());
    }
}

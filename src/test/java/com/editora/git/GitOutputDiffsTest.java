package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitOutputDiffsTest {

    private static final String PULL = """
            $ git pull --progress --ff-only
            From github.com:me/repo
               1a2b3c4..5d6e7f8  master     -> origin/master
            Updating 1a2b3c4..5d6e7f8
            Fast-forward
             src/Main.java | 4 ++--
             docs/{old => new}/guide.md | 2 +-
             logo.png | Bin 0 -> 120 bytes
             2 files changed, 3 insertions(+), 3 deletions(-)
            exit 0 · 812 ms
            """;

    @Test
    void theGraphOfAPulledFileOpensItsDiffBetweenTheTwoCommits() {
        int graph = PULL.indexOf("++--");
        GitOutputDiffs.Target target = GitOutputDiffs.at(PULL, graph);
        assertNotNull(target);
        assertEquals("1a2b3c4", target.oldRev());
        assertEquals("5d6e7f8", target.newRev());
        assertEquals("src/Main.java", target.oldPath());
        assertEquals("src/Main.java", target.newPath());
        assertEquals("4 ++--", PULL.substring(target.start(), target.end()), "the count is part of the target");
        assertNotNull(GitOutputDiffs.at(PULL, graph + 3), "its last character too");
    }

    @Test
    void onlyTheGraphIsALink() {
        assertNull(GitOutputDiffs.at(PULL, PULL.indexOf("src/Main.java")), "the path stays the open-file link");
        assertNull(GitOutputDiffs.at(PULL, PULL.indexOf("Bin")), "a binary row has no graph");
        assertNull(GitOutputDiffs.at(PULL, PULL.indexOf("3 insertions")));
        assertNull(GitOutputDiffs.at(PULL, -1));
        assertNull(GitOutputDiffs.at(PULL, PULL.length()));
    }

    @Test
    void aRenamedFileIsReadAtItsOldPathOnTheOldSide() {
        GitOutputDiffs.Target target = GitOutputDiffs.at(PULL, PULL.indexOf("2 +-"));
        assertNotNull(target);
        assertEquals("docs/old/guide.md", target.oldPath());
        assertEquals("docs/new/guide.md", target.newPath());
        assertArrayEquals(new String[] {"a.txt", "b.txt"}, GitOutputDiffs.renamedPaths("a.txt => b.txt"));
        assertArrayEquals(
                new String[] {"src/a.txt", "src/sub/a.txt"}, GitOutputDiffs.renamedPaths("src/{ => sub}/a.txt"));
    }

    @Test
    void aStatWithNoUpdatingLineOfItsOwnCommandIsNotALink() {
        // A merge commit's stat names no range, and the pull above it is another command's.
        String text = PULL + """
                $ git stash show
                 src/Main.java | 4 ++--
                 1 file changed, 2 insertions(+), 2 deletions(-)
                """;
        assertNull(GitOutputDiffs.at(text, text.lastIndexOf("++--")));
        assertNull(GitOutputDiffs.at(" src/Main.java | 4 ++--\n", 18));
    }

    @Test
    void graphFindsTheSpanToStyle() {
        assertArrayEquals(new int[] {17, 23}, GitOutputDiffs.graph(" src/Main.java | 4 ++--"));
        assertNull(GitOutputDiffs.graph(" logo.png | Bin 0 -> 120 bytes"));
        assertNull(GitOutputDiffs.graph("Fast-forward"));
    }
}

package com.editora.git;

import java.nio.file.Path;
import java.util.List;

import com.editora.git.GitService.Commit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure helpers behind the diff viewer's git plumbing. */
class GitDiffHelpersTest {

    @Test
    void parseLogReadsTabSeparatedFields() {
        String out = "abc123def\tabc123d\tJane Doe\t2026-06-01\tFix the parser\n"
                + "999000aaa\t999000a\tJohn Roe\t2026-05-30\tAdd feature; with tabs?\n";
        List<Commit> commits = GitService.parseLog(out);
        assertEquals(2, commits.size());
        Commit c = commits.get(0);
        assertEquals("abc123def", c.hash());
        assertEquals("abc123d", c.shortHash());
        assertEquals("Jane Doe", c.author());
        assertEquals("2026-06-01", c.date());
        assertEquals("Fix the parser", c.subject());
    }

    @Test
    void parseLogSkipsBlankLines() {
        assertEquals(0, GitService.parseLog("\n  \n").size());
        assertEquals(0, GitService.parseLog("").size());
    }

    @Test
    void repoRelativeIsForwardSlashAndUnderRoot() {
        Path root = Path.of("/work/proj");
        assertEquals("src/Main.java", GitService.repoRelative(root, Path.of("/work/proj/src/Main.java")));
        assertEquals("README.md", GitService.repoRelative(root, Path.of("/work/proj/README.md")));
    }

    @Test
    void repoRelativeNullWhenOutsideRoot() {
        assertNull(GitService.repoRelative(Path.of("/work/proj"), Path.of("/elsewhere/x.txt")));
        assertNull(GitService.repoRelative(null, Path.of("/x")));
        assertNull(GitService.repoRelative(Path.of("/work/proj"), null));
    }
}

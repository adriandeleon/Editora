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

    @Test
    void repoRelativeResolvesSymlinksSoASymlinkedRepoIsStillInRepo(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        // git rev-parse --show-toplevel returns the REAL root, but the buffer's file keeps its as-opened
        // (symlinked) path. Without resolving both, they share no prefix and every path-based Git op reports
        // "not in repo" — the exact breakage for a macOS /tmp project or any symlinked work dir.
        Path realRoot = java.nio.file.Files.createDirectories(tmp.resolve("realrepo"));
        java.nio.file.Files.writeString(realRoot.resolve("a.txt"), "x");
        Path linkedRoot = java.nio.file.Files.createSymbolicLink(tmp.resolve("linked"), realRoot);

        // root as git reports it (real), file as opened (through the symlink).
        assertEquals("a.txt", GitService.repoRelative(realRoot, linkedRoot.resolve("a.txt")));
        // A not-yet-saved file under the symlinked repo still resolves (ancestor-walk fallback).
        assertEquals("new.txt", GitService.repoRelative(realRoot, linkedRoot.resolve("new.txt")));
        // A file genuinely outside is still rejected.
        assertNull(GitService.repoRelative(realRoot, tmp.resolve("outside.txt")));
    }
}

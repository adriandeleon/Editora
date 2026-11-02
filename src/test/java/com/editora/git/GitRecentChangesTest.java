package com.editora.git;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recent-changes log parser: counts per file and per folder, the newest author, shallow cuts and the caps. */
class GitRecentChangesTest {

    private static String header(String hash, String parents, long epoch, String author) {
        return GitRecentChanges.HEADER + hash + "\t" + parents + "\t" + epoch + "\t" + author;
    }

    private static GitRecentChanges.Result parse(boolean shallow, List<String> lines) {
        GitRecentChanges.Parser parser = new GitRecentChanges.Parser(shallow);
        for (String line : lines) {
            if (parser.accept(line)) {
                break;
            }
        }
        return parser.finish("head");
    }

    @Test
    void filesAndFoldersCountTheirCommitsAndKeepTheNewestAuthor() {
        GitRecentChanges.Result result = parse(
                false,
                List.of(
                        header("c3", "c2", 300, "Cleo"),
                        "",
                        "pkg/kubelet/kubelet.go",
                        "pkg/kubelet/cm/cm.go",
                        "",
                        header("c2", "c1", 200, "Bo"),
                        "",
                        "pkg/kubelet/kubelet.go",
                        "README.md",
                        "",
                        header("c1", "", 100, "Ada"),
                        "",
                        "pkg/api/types.go"));
        assertEquals(3, result.commits());
        assertFalse(result.truncated());
        GitRecentChanges.Stat kubelet = result.stat("pkg/kubelet/kubelet.go", false);
        assertEquals(2, kubelet.commits());
        assertEquals("Cleo", kubelet.lastAuthor(), "newest first: the first commit seen is the last change");
        assertEquals(300, kubelet.lastEpochSeconds());
        // A folder counts a commit once, however many of its files it touched.
        assertEquals(2, result.stat("pkg/kubelet", true).commits());
        assertEquals(3, result.stat("pkg", true).commits());
        assertEquals(1, result.stat("pkg/kubelet/cm", true).commits());
        assertEquals(3, result.stat("", true).commits(), "the root holds every commit");
        assertEquals(1, result.stat("pkg/api", true).commits());
        assertEquals("Ada", result.stat("pkg/api", true).lastAuthor());
        // Direct: only commits that touch a file in the folder itself.
        assertEquals(2, result.direct().get("pkg/kubelet").commits());
        assertNull(result.direct().get("pkg"), "pkg holds no file of its own");
        assertEquals(1, result.direct().get("").commits(), "README.md is the root's own");
        assertNull(result.stat("pkg/kubelet/kubelet.go", true), "a file is not a folder");
    }

    @Test
    void aShallowClonesGraftedCommitIsLeftOutAndItsDateKept() {
        // git log --depth 1: the one commit has no parents and lists every file of its tree as added.
        GitRecentChanges.Result shallow =
                parse(true, List.of(header("c9", "", 900, "bot"), "", "a.go", "b/c.go", "b/d.go"));
        assertTrue(shallow.shallow());
        assertEquals(0, shallow.commits(), "the cut is no change");
        assertTrue(shallow.files().isEmpty());
        assertEquals(900, shallow.boundaryEpochSeconds());

        GitRecentChanges.Result deeper = parse(
                true,
                List.of(
                        header("c9", "c8", 900, "Ada"),
                        "",
                        "a.go",
                        "",
                        header("c8", "", 800, "bot"),
                        "",
                        "a.go",
                        "b/c.go"));
        assertEquals(1, deeper.commits());
        assertEquals(1, deeper.stat("a.go", false).commits());
        assertNull(deeper.stat("b/c.go", false));
        assertEquals(800, deeper.boundaryEpochSeconds());

        GitRecentChanges.Result root = parse(false, List.of(header("c1", "", 100, "Ada"), "", "a.go"));
        assertEquals(1, root.commits(), "a real root commit of a full clone counts");
    }

    @Test
    void quotedNamesAndMalformedHeadersAreSkipped() {
        GitRecentChanges.Result result = parse(
                false,
                List.of(
                        header("c2", "c1", 200, "Bo"),
                        "",
                        "\"tab\\there.go\"",
                        "",
                        GitRecentChanges.HEADER + "broken",
                        "orphan.go",
                        header("c1", "c0", 100, "Ada"),
                        "",
                        "ok.go"));
        assertEquals(1, result.commits(), "a commit with only a quoted name changed nothing that is shown");
        assertNull(result.stat("orphan.go", false));
        assertEquals(1, result.stat("ok.go", false).commits());
    }

    @Test
    void theCapsStopTheReadAndFlagTheResult() {
        GitRecentChanges.Parser parser = new GitRecentChanges.Parser(false);
        boolean stopped = false;
        for (int i = 0; i <= GitRecentChanges.MAX_COMMITS && !stopped; i++) {
            stopped = parser.accept(header("c" + i, "p", 1000 - i, "Ada"))
                    || parser.accept("")
                    || parser.accept("f" + (i % 7) + ".go");
        }
        assertTrue(stopped, "the commit after the cap stops the read");
        GitRecentChanges.Result commits = parser.finish("head");
        assertTrue(commits.truncated());
        assertEquals(GitRecentChanges.MAX_COMMITS, commits.commits());

        GitRecentChanges.Parser lines = new GitRecentChanges.Parser(false);
        lines.accept(header("big", "p", 1, "bot"));
        boolean cut = false;
        for (int i = 0; i <= GitRecentChanges.MAX_PATH_LINES && !cut; i++) {
            cut = lines.accept("vendor/f" + i + ".go");
        }
        assertTrue(cut);
        GitRecentChanges.Result paths = lines.finish("head");
        assertTrue(paths.truncated());
        assertEquals(0, paths.commits(), "a commit read only in part is not counted");
    }

    @Test
    void theLogIsStreamedWithoutMergesRenamesOrQuoting() {
        List<String> args = GitRecentChanges.logArgs(90);
        assertTrue(args.contains("--since=90.days"));
        assertTrue(args.containsAll(List.of("--no-merges", "--no-renames", "--name-only", "--relative")));
        assertEquals("core.quotePath=false", args.get(1));
        assertEquals(List.of("--", "."), args.subList(args.size() - 2, args.size()));
    }
}

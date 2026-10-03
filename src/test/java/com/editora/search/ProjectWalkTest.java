package com.editora.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shared pruned walk. Every case here is a way the post-filtering walk it replaced got a project wrong:
 * entering an ignored directory, charging ignored files to the cap, dying on one unreadable directory, and
 * stopping without saying so.
 */
class ProjectWalkTest {

    private static Path file(Path root, String rel) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        return Files.writeString(f, "x");
    }

    private static List<String> walk(Path root, ProjectWalk.Options options) {
        List<String> seen = new ArrayList<>();
        ProjectWalk.walk(root, options, (f, rel, attrs) -> {
            seen.add(rel);
            return ProjectWalk.Verdict.ACCEPT;
        });
        seen.sort(null);
        return seen;
    }

    private static ProjectWalk.Options unbounded(GitignoreFilter ignore) {
        return new ProjectWalk.Options(Integer.MAX_VALUE, Integer.MAX_VALUE, ignore);
    }

    @Test
    void ignoredAndDotDirectoriesAreNeverEntered(@TempDir Path root) throws Exception {
        file(root, "src/A.java");
        file(root, "target/classes/x.properties");
        file(root, "node_modules/pkg/index.js");
        file(root, "sub/node_modules/deep/y.js");
        file(root, ".git/objects/ab/cdef");
        file(root, ".hidden");
        Files.writeString(root.resolve(".gitignore"), "target/\nnode_modules\n");

        List<String> entered = new ArrayList<>();
        List<String> files = new ArrayList<>();
        ProjectWalk.walk(root, unbounded(GitignoreFilter.load(root)), new ProjectWalk.Visitor() {
            @Override
            public boolean enter(Path dir, String rel) {
                entered.add(rel);
                return true;
            }

            @Override
            public ProjectWalk.Verdict file(Path file, String rel, java.nio.file.attribute.BasicFileAttributes attrs) {
                files.add(rel);
                return ProjectWalk.Verdict.ACCEPT;
            }
        });

        assertEquals(List.of("src/A.java"), files, "a directory-only rule and a slash-less rule both prune");
        entered.sort(null);
        assertEquals(List.of("src", "sub"), entered, "target/, node_modules/ and .git/ are not even listed");
    }

    @Test
    void theCapCountsAcceptedFilesOnlyAndReportsWhenItCutsTheWalkShort(@TempDir Path root) throws Exception {
        for (int i = 0; i < 200; i++) {
            file(root, "node_modules/pkg/f" + i + ".js"); // far more ignored entries than the cap
        }
        file(root, "src/A.java");
        file(root, "src/B.java");
        Files.writeString(root.resolve(".gitignore"), "node_modules/\n");

        List<String> seen = new ArrayList<>();
        ProjectWalk.Outcome exact = ProjectWalk.walk(
                root, new ProjectWalk.Options(Integer.MAX_VALUE, 2, GitignoreFilter.load(root)), (f, rel, a) -> {
                    seen.add(rel);
                    return ProjectWalk.Verdict.ACCEPT;
                });
        assertEquals(2, exact.accepted(), "200 ignored files must not use up a cap of 2");
        assertEquals(2, seen.size());
        assertFalse(exact.truncated(), "exactly at the cap with nothing left over is a complete walk");

        ProjectWalk.Outcome cut = ProjectWalk.walk(
                root,
                new ProjectWalk.Options(Integer.MAX_VALUE, 1, GitignoreFilter.load(root)),
                (f, rel, a) -> ProjectWalk.Verdict.ACCEPT);
        assertEquals(1, cut.accepted());
        assertTrue(cut.capped() && cut.truncated(), "a walk that stopped early must say so");
    }

    @Test
    void skippedFilesCostNothingAndStopIsNotATruncation(@TempDir Path root) throws Exception {
        file(root, "a.txt");
        file(root, "b.txt");
        file(root, "c.txt");
        ProjectWalk.Outcome skipped = ProjectWalk.walk(
                root, new ProjectWalk.Options(5, 1, GitignoreFilter.NONE), (f, rel, a) -> ProjectWalk.Verdict.SKIP);
        assertEquals(0, skipped.accepted());
        assertFalse(skipped.truncated(), "nothing was accepted, so the cap was never reached");

        int[] offered = {0};
        ProjectWalk.Outcome stopped = ProjectWalk.walk(root, unbounded(GitignoreFilter.NONE), (f, rel, a) -> {
            offered[0]++;
            return ProjectWalk.Verdict.STOP;
        });
        assertEquals(1, offered[0], "STOP ends the walk at once");
        assertFalse(stopped.truncated(), "the caller chose to stop; the tree was not cut short on it");
    }

    @Test
    void anUnreadableDirectoryIsSteppedOverNotFatal(@TempDir Path root) throws Exception {
        file(root, "a/first.txt");
        Path locked = Files.createDirectories(root.resolve("b-locked"));
        file(root, "b-locked/secret.txt");
        file(root, "c/last.txt");
        assumeTrue(
                root.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "permission bits are a POSIX concept");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(locked), "running as root: nothing is unreadable");
            List<String> seen = new ArrayList<>();
            ProjectWalk.Outcome outcome = ProjectWalk.walk(root, unbounded(GitignoreFilter.NONE), (f, rel, a) -> {
                seen.add(rel);
                return ProjectWalk.Verdict.ACCEPT;
            });
            seen.sort(null);
            assertEquals(List.of("a/first.txt", "c/last.txt"), seen, "the walk continues past the locked directory");
            assertEquals(1, outcome.unreadable());
            assertFalse(outcome.truncated());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void theDepthLimitIsReportedUnlessWhatItHidIsIgnoredAnyway(@TempDir Path root) throws Exception {
        file(root, "a/b/deep.txt");
        ProjectWalk.Outcome shallow = ProjectWalk.walk(
                root,
                new ProjectWalk.Options(2, Integer.MAX_VALUE, GitignoreFilter.NONE),
                (f, rel, a) -> ProjectWalk.Verdict.ACCEPT);
        assertTrue(shallow.depthLimited() && shallow.truncated(), "a/b was not descended into");
        assertEquals(0, shallow.accepted(), "a directory at the limit is not offered as a file");

        ProjectWalk.Outcome ignored = ProjectWalk.walk(
                root,
                new ProjectWalk.Options(2, Integer.MAX_VALUE, GitignoreFilter.parse("b/\n")),
                (f, rel, a) -> ProjectWalk.Verdict.ACCEPT);
        assertFalse(ignored.truncated(), "an ignored directory beyond the limit hid nothing the caller wanted");
    }

    @Test
    void relativePathsAreSlashSeparatedOnEveryPlatform(@TempDir Path root) throws Exception {
        file(root, "one/two/three.txt");
        assertEquals(List.of("one/two/three.txt"), walk(root, unbounded(GitignoreFilter.NONE)));
        assertEquals("one/two", ProjectWalk.relativize(root, root.resolve("one").resolve("two")));
    }

    @Test
    void buildOutputIsRecognisedByNameButAPackageOfThatNameIsKept() {
        assertTrue(ProjectWalk.isBuildOutputDir("target"));
        assertTrue(ProjectWalk.isBuildOutputDir("module-a/build"));
        assertTrue(ProjectWalk.isBuildOutputDir("web/node_modules"));
        assertTrue(ProjectWalk.isBuildOutputDir("src/main/js/node_modules"), "never a package, wherever it sits");
        assertFalse(
                ProjectWalk.isBuildOutputDir("src/test/java/com/acme/build"),
                "a package named build is source, not build output");
        assertFalse(ProjectWalk.isBuildOutputDir("module/src/main/java/org/x/target"));
        assertFalse(ProjectWalk.isBuildOutputDir("docs"));
        assertFalse(ProjectWalk.isBuildOutputDir(""));
    }

    @Test
    void aMissingRootIsAnEmptyCompleteWalk(@TempDir Path root) {
        ProjectWalk.Outcome outcome = ProjectWalk.walk(
                root.resolve("nope"), unbounded(GitignoreFilter.NONE), (f, rel, a) -> ProjectWalk.Verdict.ACCEPT);
        assertEquals(new ProjectWalk.Outcome(0, false, false, 0), outcome);
    }
}

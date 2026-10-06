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

    // --- A12-19: a project root that is itself a symbolic link ---------------------------------------------

    @Test
    void aSymlinkedRootIsWalkedAndPathsStayUnderTheLink(@TempDir Path dir) throws Exception {
        Path real = Files.createDirectories(dir.resolve("data/proj"));
        Files.createDirectories(real.resolve("src"));
        Files.writeString(real.resolve("src/A.java"), "class A {}");
        Files.writeString(real.resolve("top.txt"), "x");
        Path link = dir.resolve("proj");
        try {
            Files.createSymbolicLink(link, real);
        } catch (java.io.IOException | UnsupportedOperationException noLinks) {
            assumeTrue(false, "symbolic links unavailable");
        }

        List<Path> files = new ArrayList<>();
        List<String> rels = new ArrayList<>();
        ProjectWalk.Outcome outcome =
                ProjectWalk.walk(link, new ProjectWalk.Options(10, 100, GitignoreFilter.NONE), (file, rel, attrs) -> {
                    assertTrue(attrs.isRegularFile(), rel);
                    files.add(file);
                    rels.add(rel);
                    return ProjectWalk.Verdict.ACCEPT;
                });

        assertEquals(2, outcome.accepted());
        assertEquals(List.of("src/A.java", "top.txt"), rels.stream().sorted().toList());
        assertTrue(files.stream().allMatch(f -> f.startsWith(link)), "offered under the root as given: " + files);
    }

    // --- nested .gitignore files -----------------------------------------------------------------------

    @Test
    void aNestedGitignoreAppliesToItsOwnSubtreeOnly(@TempDir Path root) throws Exception {
        // A monorepo: each package ignores its own node_modules and dist; the root file knows nothing of them.
        Files.writeString(root.resolve(".gitignore"), "*.log\n");
        file(root, "packages/a/src/index.js");
        file(root, "packages/a/node_modules/dep/index.js");
        file(root, "packages/a/dist/bundle.js");
        file(root, "packages/a/keep.log.txt");
        file(root, "packages/a/debug.log");
        Files.writeString(root.resolve("packages/a/.gitignore"), "node_modules\n/dist\n");
        file(root, "packages/b/src/main.js");
        file(root, "packages/b/node_modules/dep/index.js"); // b has no .gitignore of its own
        file(root, "packages/b/dist/bundle.js");
        file(root, "packages/a/src/dist/kept.js"); // "/dist" is anchored to packages/a

        List<String> entered = new ArrayList<>();
        List<String> files = new ArrayList<>();
        ProjectWalk.walk(root, unbounded(GitignoreFilter.load(root)), new ProjectWalk.Visitor() {
            @Override
            public boolean enter(Path dir, String rel) {
                entered.add(rel);
                return true;
            }

            @Override
            public ProjectWalk.Verdict file(Path f, String rel, java.nio.file.attribute.BasicFileAttributes attrs) {
                files.add(rel);
                return ProjectWalk.Verdict.ACCEPT;
            }
        });
        files.sort(null);

        assertEquals(
                List.of(
                        "packages/a/keep.log.txt",
                        "packages/a/src/dist/kept.js",
                        "packages/a/src/index.js",
                        "packages/b/dist/bundle.js",
                        "packages/b/node_modules/dep/index.js",
                        "packages/b/src/main.js"),
                files);
        assertFalse(entered.contains("packages/a/node_modules"), "pruned before it is listed: " + entered);
        assertFalse(entered.contains("packages/a/dist"));
        assertTrue(entered.contains("packages/b/node_modules"), "a's rules do not reach its sibling");
    }

    @Test
    void aNestedNegationOutranksTheRootRule(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "*.gen\n");
        file(root, "a/x.gen");
        file(root, "b/y.gen");
        Files.writeString(root.resolve("b/.gitignore"), "!y.gen\n");

        assertEquals(List.of("b/y.gen"), walk(root, unbounded(GitignoreFilter.load(root))));
    }

    @Test
    void nestedFilesAreNotReadWhenGitignoreIsOffOrTheFilterIsLiteral(@TempDir Path root) throws Exception {
        file(root, "pkg/node_modules/dep.js");
        file(root, "pkg/src.js");
        Files.writeString(root.resolve("pkg/.gitignore"), "node_modules\n");

        // "Respect .gitignore" switched off: NONE means everything, nested files included.
        assertEquals(List.of("pkg/node_modules/dep.js", "pkg/src.js"), walk(root, unbounded(GitignoreFilter.NONE)));
        // A filter parsed from text is exactly that text.
        assertEquals(
                List.of("pkg/node_modules/dep.js", "pkg/src.js"),
                walk(root, unbounded(GitignoreFilter.parse("*.tmp\n"))));
        // A root with no .gitignore of its own still honours the nested ones.
        assertEquals(List.of("pkg/src.js"), walk(root, unbounded(GitignoreFilter.load(root))));
    }

    @Test
    void offersAgreesWithTheWalkAboutOneFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        file(root, "pkg/node_modules/dep.js");
        file(root, "pkg/src.js");
        file(root, "target/out.js");
        file(root, ".idea/x.xml");
        Files.writeString(root.resolve("pkg/.gitignore"), "node_modules\n");
        GitignoreFilter ignore = GitignoreFilter.load(root);

        assertTrue(ProjectWalk.offers(root, root.resolve("pkg/src.js"), ignore));
        assertFalse(ProjectWalk.offers(root, root.resolve("pkg/node_modules/dep.js"), ignore));
        assertFalse(ProjectWalk.offers(root, root.resolve("target/out.js"), ignore));
        assertFalse(ProjectWalk.offers(root, root.resolve(".idea/x.xml"), ignore));
        assertFalse(ProjectWalk.offers(root, root.resolve("pkg/.hidden"), ignore));
        assertFalse(ProjectWalk.offers(root, root, ignore));
    }
}

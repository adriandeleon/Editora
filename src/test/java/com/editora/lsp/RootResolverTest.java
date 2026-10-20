package com.editora.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootResolverTest {

    private static final List<String> MARKERS = LspServerRegistry.JAVA_ROOT_MARKERS;

    @Test
    void projectRootWinsWhenFileIsUnderIt(@TempDir Path tmp) {
        Path project = tmp.resolve("proj");
        Path file = project.resolve("a/b/Main.java");
        assertEquals(project.toAbsolutePath().normalize(), RootResolver.resolve(project, file, MARKERS));
    }

    @Test
    void projectRootIgnoredWhenFileIsOutsideIt(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        Files.createFile(other.resolve("pom.xml"));
        Path file = Files.createFile(other.resolve("Main.java"));
        // The active project doesn't contain the file → fall back to the file's own marker root.
        assertEquals(other.toAbsolutePath().normalize(), RootResolver.resolve(project, file, MARKERS));
    }

    @Test
    void findsNearestMarkerAncestor(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("repo"));
        Files.createFile(root.resolve("pom.xml"));
        Path file = Files.createDirectories(root.resolve("src/main/java/app")).resolve("Main.java");
        Files.createFile(file);
        assertEquals(root, RootResolver.findMarkerRoot(file, MARKERS));
        assertEquals(root, RootResolver.resolve(null, file, MARKERS));
    }

    @Test
    void nearestMarkerWinsOverFartherOne(@TempDir Path tmp) throws IOException {
        Path outer = Files.createDirectories(tmp.resolve("outer"));
        Files.createFile(outer.resolve(".git")); // a file named .git for the test
        Path inner = Files.createDirectories(outer.resolve("module"));
        Files.createFile(inner.resolve("build.gradle"));
        Path file = Files.createDirectories(inner.resolve("src")).resolve("Main.java");
        Files.createFile(file);
        assertEquals(inner, RootResolver.findMarkerRoot(file, MARKERS));
    }

    @Test
    void fallsBackToParentDirWhenNoMarker(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("loose"));
        Path file = dir.resolve("Scratch.java");
        Files.createFile(file);
        assertNull(RootResolver.findMarkerRoot(file, MARKERS));
        assertEquals(dir.toAbsolutePath().normalize(), RootResolver.resolve(null, file, MARKERS));
    }

    // --- filesOnly: a directory named like a build marker must not root a build tool (#451) ---------

    @Test
    void aDirectoryNamedLikeABuildMarkerDoesNotRootWhenFilesOnly(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Files.createDirectory(dir.resolve("pom.xml")); // a *folder* named pom.xml (legal, if unusual)
        Path file = Files.createFile(dir.resolve("Main.java"));
        // Default (files-or-dirs) roots there — the LSP arm — but the build path (filesOnly) must not.
        assertEquals(dir.toAbsolutePath().normalize(), RootResolver.findMarkerRoot(file, List.of("pom.xml")));
        assertNull(RootResolver.findMarkerRoot(file, List.of("pom.xml"), true));
    }

    @Test
    void aRealBuildFileStillRootsWithFilesOnly(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Files.createFile(dir.resolve("pom.xml"));
        Path file = Files.createFile(dir.resolve("Main.java"));
        assertEquals(dir.toAbsolutePath().normalize(), RootResolver.findMarkerRoot(file, List.of("pom.xml"), true));
    }

    @Test
    void filesOnlyFalseStillMatchesADirectoryMarker(@TempDir Path tmp) throws IOException {
        // An LSP directory marker (.git) must keep rooting under the default (filesOnly=false) call.
        Path dir = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectory(dir.resolve(".git"));
        Path file = Files.createFile(dir.resolve("Main.java"));
        assertEquals(dir.toAbsolutePath().normalize(), RootResolver.findMarkerRoot(file, List.of(".git")));
        assertNull(RootResolver.findMarkerRoot(file, List.of(".git"), true), "filesOnly ignores a dir marker");
    }

    /** A dotfiles {@code ~/.git} or a stray {@code ~/package.json} must not make the home directory a workspace. */
    @Test
    void aMarkerInTheHomeDirectoryDoesNotRootALooseFileThere(@TempDir Path tmp) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home/me"));
        Files.createDirectories(home.resolve(".git"));
        Files.createFile(home.resolve("package.json"));
        Path dir = Files.createDirectories(home.resolve("Downloads/tmp"));
        Path file = Files.createFile(dir.resolve("note.py"));

        assertEquals(dir, RootResolver.resolve(null, file, List.of(".git", "package.json"), home));
        // A real project below home is unaffected.
        Path project = Files.createDirectories(home.resolve("src/app"));
        Files.createFile(project.resolve("package.json"));
        Path inProject = Files.createFile(project.resolve("index.py"));
        assertEquals(project, RootResolver.resolve(null, inProject, List.of(".git", "package.json"), home));
    }

    @Test
    void aFilesystemRootIsNeverAnInferredWorkspace(@TempDir Path tmp) {
        Path fsRoot = tmp.toAbsolutePath().getRoot();
        assertTrue(RootResolver.tooBroad(fsRoot, null));
        assertFalse(RootResolver.tooBroad(tmp, null));
    }

    /** One project reached by two spellings (a symlinked parent) is one workspace, in the project's spelling. */
    @Test
    void aFileReachedByItsRealPathStillBelongsToTheSymlinkedProject(@TempDir Path tmp) throws IOException {
        Path data = Files.createDirectories(tmp.resolve("data/proj/src"));
        Files.createFile(tmp.resolve("data/proj/pom.xml"));
        Path real = Files.createFile(data.resolve("B.java")).toRealPath();
        Path link;
        try {
            link = Files.createSymbolicLink(tmp.resolve("work"), tmp.resolve("data"));
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available here");
            return;
        }
        Path project = link.resolve("proj");

        assertEquals(project.toAbsolutePath().normalize(), RootResolver.resolve(project, real, MARKERS, null));
    }

    /**
     * A {@code docs/requirements.txt} (Read the Docs) made {@code docs/} a Python server root of its own, where
     * the project's own package did not resolve (Python review B9): a pyproject (or setup.py/cfg) above wins,
     * then the repository, and only then the nearest requirements file.
     */
    @Test
    void pythonPrefersItsProjectFileThenTheRepoOverANestedRequirementsFile(@TempDir Path tmp) throws IOException {
        var tiers = LspServerRegistry.rootMarkerTiersForServer("python");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Files.createFile(repo.resolve("pyproject.toml"));
        Path docs = Files.createDirectories(repo.resolve("docs"));
        Files.createFile(docs.resolve("requirements.txt"));
        Path conf = Files.createFile(docs.resolve("conf.py"));
        assertEquals(repo.toAbsolutePath().normalize(), RootResolver.resolveTiered(null, conf, tiers, null));

        Path git = Files.createDirectories(tmp.resolve("gitrepo"));
        Files.createDirectories(git.resolve(".git"));
        Path tools = Files.createDirectories(git.resolve("tools"));
        Files.createFile(tools.resolve("requirements.txt"));
        assertEquals(
                git.toAbsolutePath().normalize(),
                RootResolver.resolveTiered(null, Files.createFile(tools.resolve("x.py")), tiers, null));

        Path loose = Files.createDirectories(tmp.resolve("scripts"));
        Files.createFile(loose.resolve("requirements.txt"));
        Path sub = Files.createDirectories(loose.resolve("sub"));
        assertEquals(
                loose.toAbsolutePath().normalize(),
                RootResolver.resolveTiered(null, Files.createFile(sub.resolve("y.py")), tiers, null),
                "no project file and no repo: the requirements file still marks the root");

        Path mono = Files.createDirectories(tmp.resolve("mono"));
        Files.createDirectories(mono.resolve(".git"));
        Path svc = Files.createDirectories(mono.resolve("svc"));
        Files.createFile(svc.resolve("pyproject.toml"));
        assertEquals(
                svc.toAbsolutePath().normalize(),
                RootResolver.resolveTiered(null, Files.createFile(svc.resolve("m.py")), tiers, null),
                "a nested package in a monorepo keeps its own root");
    }

    // --- Maven pass 2 (B27 bounded marker walk, B11 roots below the project) ---

    @Test
    void theMarkerWalkStopsAtTheProjectRoot(@TempDir Path dir) throws IOException {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        Path src = Files.createDirectories(repo.resolve("app").resolve("src"));
        Path file = Files.writeString(src.resolve("A.java"), "");
        assertEquals(repo, RootResolver.findMarkerRoot(file, List.of("pom.xml"), true), "unbounded: the pom above");
        assertNull(
                RootResolver.findMarkerRoot(file, List.of("pom.xml"), true, repo.resolve("app")),
                "opened inside the module: the pom above the opened folder must not root a build");
        assertEquals(
                repo,
                RootResolver.findMarkerRoot(file, List.of("pom.xml"), true, repo),
                "the boundary itself is still looked at");
        assertEquals(
                repo,
                RootResolver.findMarkerRoot(file, List.of("pom.xml"), true, dir.resolve("elsewhere")),
                "a boundary the file is not inside does not bound");
    }

    @Test
    void findsMarkerRootsBelowAProjectBoundedAndSkippingOutputFolders(@TempDir Path dir) throws IOException {
        Files.writeString(Files.createDirectories(dir.resolve("backend")).resolve("pom.xml"), "<project/>");
        Files.writeString(
                Files.createDirectories(dir.resolve("services").resolve("billing"))
                        .resolve("pom.xml"),
                "<project/>");
        Files.writeString(
                Files.createDirectories(dir.resolve("too").resolve("deep").resolve("down"))
                        .resolve("pom.xml"),
                "<project/>");
        Files.writeString(
                Files.createDirectories(dir.resolve("target").resolve("gen")).resolve("pom.xml"), "<project/>");
        Files.writeString(Files.createDirectories(dir.resolve(".hidden")).resolve("pom.xml"), "<project/>");
        Files.createDirectories(dir.resolve("frontend"));

        List<Path> found = RootResolver.findMarkerRootsBelow(dir, List.of("pom.xml"), 2, 100);
        assertEquals(List.of(dir.resolve("backend"), dir.resolve("services").resolve("billing")), found);

        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertEquals(
                dir,
                RootResolver.findMarkerRootsBelow(dir, List.of("pom.xml"), 2, 100)
                        .get(0),
                "the root itself first");
        assertEquals(
                List.of(dir), RootResolver.findMarkerRootsBelow(dir, List.of("pom.xml"), 2, 1), "the visit cap holds");
        assertEquals(List.of(), RootResolver.findMarkerRootsBelow(dir.resolve("missing"), List.of("pom.xml"), 2, 100));
    }
}

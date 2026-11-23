package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * More of {@link RootResolver}: missing inputs, tiers, the Python sub-project and library rules, and the walks up a
 * reactor or a Gradle build, bounded by the project and the home folder.
 */
class RootResolverMoreTest {

    private static Path touch(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "");
    }

    @Test
    void missingInputsResolveToNothing(@TempDir Path dir) {
        assertFalse(RootResolver.hasAstroConfig(null));
        assertNull(RootResolver.findMarkerRoot(null, List.of("pom.xml")));
        assertNull(RootResolver.findMarkerRoot(dir.resolve("a.txt"), List.of()));
        assertNull(RootResolver.findMarkerRoot(dir.resolve("a.txt"), null, true));
        assertEquals(List.of(), RootResolver.findMarkerRootsBelow(null, List.of("pom.xml"), 2, 10));
        assertEquals(List.of(), RootResolver.findMarkerRootsBelow(dir, List.of(), 2, 10));
        assertEquals(List.of(), RootResolver.findMarkerRootsBelow(dir.resolve("none"), List.of("pom.xml"), 2, 10));
        assertFalse(RootResolver.inInstalledPackages(null));
        assertFalse(RootResolver.isBroadRoot(null));
        assertTrue(RootResolver.isBroadRoot(Path.of("/")), "a filesystem root");
        assertFalse(RootResolver.isLoose(null, null, List.of("pom.xml"), null));
        assertNull(RootResolver.resolve(null, null, List.of("pom.xml"), null), "no file and no project");
        assertEquals(dir.getRoot(), RootResolver.resolve(null, dir.getRoot(), List.of(), null), "a root has no parent");
        assertNull(RootResolver.javaBuildRoot(null, null, null));
        assertNull(RootResolver.outermostContiguous(null, "pom.xml", null, null));
        assertEquals(dir, RootResolver.outermostContiguous(dir, null, null, null));
    }

    @Test
    void tiersFallBackToTheFirstTierAndThenToTheFolder(@TempDir Path dir) throws Exception {
        Path file = touch(dir.resolve("app/src/main.ts"));
        touch(dir.resolve("app/package.json"));
        assertEquals(
                dir.resolve("app/src"), RootResolver.resolveTiered(null, file, null, null), "no tiers: the folder");
        assertEquals(
                dir.resolve("app"),
                RootResolver.resolveTiered(null, file, List.of(List.of("package.json")), null),
                "one tier is a plain resolve");
        assertEquals(
                dir.resolve("app/src"),
                RootResolver.resolveTiered(null, file, List.of(), null),
                "no markers: the file's folder");
        assertEquals(
                dir.resolve("app"),
                RootResolver.resolveTiered(null, file, List.of(List.of("deno.json"), List.of("package.json")), null),
                "the second tier when the first finds nothing");
        assertEquals(
                dir.resolve("app/src"),
                RootResolver.resolveTiered(
                        null, file, List.of(List.of("deno.json"), List.of("package.json")), dir.resolve("app")),
                "a marker in the home folder makes no workspace");
    }

    @Test
    void aPythonSubProjectGetsItsOwnServerUnlessItIsAWorkspaceMember(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("project");
        Path file = touch(project.resolve("backend/app/main.py"));
        touch(project.resolve("backend/pyproject.toml"));
        assertEquals(project.resolve("backend"), RootResolver.pythonProjectRoot(project, file, project));
        assertEquals(
                project.resolve("backend"),
                RootResolver.pythonProjectRoot(project, file, project),
                "asked again: the same answer");
        assertEquals(project, RootResolver.pythonProjectRoot(project, file, null), "no project window");
        assertEquals(
                dir.resolve("elsewhere"),
                RootResolver.pythonProjectRootUncached(dir.resolve("elsewhere"), file, project),
                "a root the markers did not choose as the project's");
        assertEquals(project, RootResolver.pythonProjectRootUncached(project, dir.resolve("outside/x.py"), project));

        Files.writeString(project.resolve("pyproject.toml"), "[tool.uv.workspace]\nmembers = [\"backend\"]\n");
        assertEquals(project, RootResolver.pythonProjectRootUncached(project, file, project), "the workspace's own");

        Path ws = dir.resolve("ws");
        Path member = touch(ws.resolve("libs/one/pkg/mod.py"));
        touch(ws.resolve("libs/one/pyproject.toml"));
        Files.writeString(touch(ws.resolve("libs/pyproject.toml")), "[tool.uv.workspace]\n");
        assertEquals(
                ws.resolve("libs"),
                RootResolver.pythonProjectRootUncached(ws, member, ws),
                "a member shares its workspace's server");
    }

    @Test
    void aJumpTargetIsALibraryOutsideTheRootsOrInInstalledPackages() {
        Path project = Path.of("/p");
        assertTrue(RootResolver.isLibraryTarget(Path.of("/p/.venv/lib/site-packages/x.py"), project, project));
        assertTrue(RootResolver.isLibraryTarget(Path.of("/usr/lib/python3/dist-packages/y.py"), null, null));
        assertFalse(RootResolver.isLibraryTarget(Path.of("/srv/app/a.py"), Path.of("/srv/app"), project));
        assertFalse(RootResolver.isLibraryTarget(Path.of("/p/a.py"), Path.of("/srv"), project));
        assertTrue(RootResolver.isLibraryTarget(Path.of("/usr/lib/python3.12/os.py"), Path.of("/srv"), project));
        assertTrue(RootResolver.isLibraryTarget(Path.of("/usr/lib/python3.12/os.py"), null, null));
    }

    @Test
    void aReactorIsWalkedUpWithinTheProjectAndBelowHome(@TempDir Path dir) throws Exception {
        touch(dir.resolve("pom.xml"));
        touch(dir.resolve("reactor/pom.xml"));
        touch(dir.resolve("reactor/mod/pom.xml"));
        Path mod = dir.resolve("reactor/mod");
        assertEquals(dir, RootResolver.outermostContiguous(mod, "pom.xml", null, null));
        assertEquals(
                dir.resolve("reactor"),
                RootResolver.outermostContiguous(mod, "pom.xml", dir.resolve("reactor"), null),
                "not above the project");
        assertEquals(
                dir.resolve("reactor"), RootResolver.outermostContiguous(mod, "pom.xml", null, dir), "nor at home");
        assertEquals(
                dir,
                RootResolver.outermostContiguous(mod, "pom.xml", dir.resolve("other"), null),
                "a project the root is not in does not bound it");
        assertEquals(dir, RootResolver.javaBuildRoot(mod, null, null), "a reactor's root");
    }

    @Test
    void aGradleBuildsRootIsItsSettingsFolder(@TempDir Path dir) throws Exception {
        touch(dir.resolve("build/settings.gradle.kts"));
        Path app = dir.resolve("build/app");
        touch(app.resolve("build.gradle.kts"));
        assertEquals(dir.resolve("build"), RootResolver.javaBuildRoot(app, null, null));
        assertEquals(app, RootResolver.javaBuildRoot(app, app, null), "not above the project");
        assertEquals(app, RootResolver.javaBuildRoot(app, null, dir.resolve("build")), "not at home");
        Path loose = dir.resolve("loose");
        Files.createDirectories(loose);
        assertEquals(loose, RootResolver.javaBuildRoot(loose, null, dir), "no build above it");
    }

    @Test
    void aTypeScriptRootWidensToTheMonorepoButNotPastHomeOrTheProject(@TempDir Path dir) throws Exception {
        Files.writeString(touch(dir.resolve("mono/package.json")), "{\"workspaces\": [\"packages/*\"]}");
        Path pkg = dir.resolve("mono/packages/a");
        touch(pkg.resolve("tsconfig.json"));
        assertEquals(dir.resolve("mono"), RootResolver.typeScriptWorkspaceRoot(pkg, null, null));
        assertEquals(pkg, RootResolver.typeScriptWorkspaceRoot(pkg, pkg, null), "the project bounds it");
        assertEquals(pkg, RootResolver.typeScriptWorkspaceRoot(pkg, null, dir.resolve("mono/packages")), "home");
        Files.writeString(touch(dir.resolve("refs/tsconfig.json")), "{\"references\": [{\"path\": \"a\"}]}");
        assertEquals(dir.resolve("refs"), RootResolver.typeScriptWorkspaceRoot(dir.resolve("refs/a"), null, null));
        touch(dir.resolve("pnpm/pnpm-workspace.yaml"));
        assertEquals(dir.resolve("pnpm"), RootResolver.typeScriptWorkspaceRoot(dir.resolve("pnpm/x"), null, null));
    }

    @Test
    void aFileOutsideTheProjectWithoutMarkersIsLoose(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path inside = touch(project.resolve("a.py"));
        Path outside = touch(dir.resolve("scratch/b.py"));
        assertFalse(RootResolver.isLoose(project, inside, List.of("pyproject.toml"), null));
        assertTrue(RootResolver.isLoose(project, outside, List.of("pyproject.toml"), null));
        touch(dir.resolve("scratch/pyproject.toml"));
        assertFalse(RootResolver.isLoose(project, outside, List.of("pyproject.toml"), null));
        assertTrue(
                RootResolver.isLoose(project, outside, List.of("pyproject.toml"), dir.resolve("scratch")),
                "a marker at home is none");
    }

    @Test
    void aMarkerSearchStopsAtItsBoundary(@TempDir Path dir) throws Exception {
        touch(dir.resolve("pom.xml"));
        Path file = touch(dir.resolve("sub/inner/A.java"));
        assertEquals(dir, RootResolver.findMarkerRoot(file, List.of("pom.xml"), true, null));
        assertNull(RootResolver.findMarkerRoot(file, List.of("pom.xml"), true, dir.resolve("sub")));
        assertEquals(
                dir,
                RootResolver.findMarkerRoot(file, List.of("pom.xml"), true, dir.resolve("other")),
                "a boundary the file is not under is none");
        Files.createDirectories(dir.resolve("sub/.git"));
        assertEquals(dir.resolve("sub"), RootResolver.findMarkerRoot(file, List.of(".git"), false, null));
        assertEquals(dir, RootResolver.findMarkerRoot(file, List.of(".git", "pom.xml"), true, null), "files only");
        assertEquals(
                dir.resolve("sub/inner"), RootResolver.findMarkerRoot(dir.resolve("sub/inner"), List.of("A.java")));
    }
}

package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which env files a request file uses (A9 wiring, F4/S5: never above the project, the repository, or — outside
 * both — the file's own folder; never the home folder or the root), which names it offers, which choice it keeps.
 */
class HttpEnvironmentsTest {

    private static final Path HOME = Path.of("/home/user").toAbsolutePath();
    private static final Path ROOT = HOME.resolve("work/project");
    private static final String PUB = "http-client.env.json";
    private static final String PRIV = "http-client.private.env.json";

    private static Path envDir(Path dir, Path project, Set<Path> files) {
        return HttpEnvironments.envDir(dir, project, HOME, files::contains);
    }

    @Test
    void insideAProjectTheNearestFolderUpToTheRootIsUsed() {
        Path api = ROOT.resolve("services/api");
        assertEquals(ROOT, envDir(api, ROOT, Set.of(ROOT.resolve(PUB))), "found two folders up");
        assertEquals(ROOT.resolve("services"), envDir(api, ROOT, Set.of(ROOT.resolve("services/" + PRIV))));
        assertEquals(
                api, envDir(api, ROOT, Set.of(api.resolve(PUB), ROOT.resolve(PUB))), "the file's own folder first");
        assertEquals(api, envDir(api, ROOT, Set.of(ROOT.getParent().resolve(PUB))), "nothing above the project");
    }

    @Test
    void outsideAProjectTheWalkStopsAtTheRepositoryRoot() {
        Path repo = HOME.resolve("src/repo");
        Path api = repo.resolve("api");
        Set<Path> files = Set.of(
                repo.resolve(".git"), repo.resolve(PUB), HOME.resolve("src").resolve(PRIV));
        assertEquals(repo, envDir(api, null, files), "up to the repository root");
        assertEquals(repo, envDir(api, HOME.resolve("other-project"), files), "a file outside the open project");
        assertEquals(
                api,
                envDir(
                        api,
                        null,
                        Set.of(repo.resolve(".git"), HOME.resolve("src").resolve(PRIV))),
                "a private env file above the repository is never used");
        assertEquals(repo, envDir(api, null, Set.of(repo.resolve(".hg"), repo.resolve(PRIV))), "Mercurial too");
    }

    @Test
    void outsideAnyRepositoryOnlyTheFilesOwnFolderIsUsed() {
        Path downloads = HOME.resolve("Downloads/x");
        assertEquals(downloads, envDir(downloads, null, Set.of(HOME.resolve(PRIV))), "the home folder is not used");
        assertEquals(
                downloads,
                envDir(downloads, null, Set.of(HOME.resolve("Downloads").resolve(PUB))));
        assertEquals(downloads, envDir(downloads, null, Set.of(downloads.resolve(PRIV))), "its own folder is");
        // A home folder kept under Git (dotfiles) is not a repository root for this purpose.
        assertEquals(downloads, envDir(downloads, null, Set.of(HOME.resolve(".git"), HOME.resolve(PRIV))));
        Path root = HOME.getRoot();
        assertEquals(
                downloads,
                envDir(downloads, null, Set.of(root.resolve(".git"), root.resolve(PUB))),
                "nor is the file-system root");
        assertEquals(HOME, envDir(HOME, null, Set.of(HOME.resolve(PUB))), "a file in the home folder itself uses it");
    }

    @Test
    void untitledBuffersUseTheProjectRootOrNothing() {
        assertEquals(ROOT, HttpEnvironments.forFile(null, ROOT));
        assertNull(HttpEnvironments.forFile(null, null));
    }

    @Test
    void readMergesBothFilesAndListsTheirNames(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve(PUB), "{\"$shared\":{\"h\":\"pub\"},\"dev\":{\"a\":\"1\"},\"prod\":{\"a\":\"2\"}}");
        Files.writeString(dir.resolve(PRIV), "{\"dev\":{\"t\":\"secret\"},\"local\":{}}");
        HttpEnvironments.EnvFiles env = HttpEnvironments.read(dir);
        assertEquals(List.of("dev", "prod", "local"), env.names());
        assertEquals(Map.of("h", "pub", "a", "1", "t", "secret"), env.variables("dev"));
        assertEquals("1\n2", env.everyValue().get("a"), "every environment's value, for the gate");
        assertEquals(List.of(), HttpEnvironments.read(null).names());
        assertEquals(Map.of(), HttpEnvironments.read(dir.resolve("none")).variables("dev"));
    }

    @Test
    void theRememberedChoiceIsKeptOnlyWhileOffered() {
        assertEquals("dev", HttpEnvironments.choose("dev", List.of("", "dev")));
        assertEquals("", HttpEnvironments.choose("dev", List.of("", "staging")));
        assertEquals("", HttpEnvironments.choose(null, List.of("", "dev")));
    }
}

package com.editora.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitService#recentChangesNow} on a small repository with real history (the Kubernetes fixture is a
 * {@code --depth 1} clone, which has none): the 90-day window, merges left out, a project in a subfolder, the cache
 * per HEAD, and what a shallow clone shows.
 */
public class GitServiceRecentChangesTest {

    @TempDir
    Path temp;

    private Path repo;
    private final GitService service = new GitService();

    /**
     * A history: an import 200 days ago, then work over the last weeks, a merge among it. Dated in order: {@code git log
     * --since} stops walking at the first commit older than the window, as it does on a real history.
     */
    public static Path history(Path repo) throws Exception {
        Files.createDirectories(repo);
        git(repo, null, "init", "-q", "-b", "main");
        write(repo, "pkg/hot/hot.go", "0");
        write(repo, "pkg/cold/cold.go", "0");
        write(repo, "README.md", "# p");
        write(repo, "pkg/hot/still.go", "never touched again");
        write(repo, "pkg/hot/quiet.go", "nor this");
        write(repo, ".github/CODEOWNERS", "* @core\n/pkg/hot/ @hot-team");
        commit(repo, 200, "Old Importer", "import");
        for (int day = 30; day >= 10; day -= 5) {
            write(repo, "pkg/hot/hot.go", "v" + day);
            commit(repo, day, "Ada Lovelace", "hot " + day);
        }
        git(repo, null, "checkout", "-q", "-b", "side");
        write(repo, "pkg/hot/warm.go", "w");
        commit(repo, 8, "Grace Hopper", "warm");
        git(repo, null, "checkout", "-q", "main");
        write(repo, "docs/guide.md", "g");
        commit(repo, 6, "Ada Lovelace", "docs");
        git(repo, dated(5, "Merger"), "merge", "-q", "--no-ff", "-m", "merge side", "side");
        write(repo, "pkg/hot/hot.go", "latest");
        commit(repo, 2, "Grace Hopper", "hot latest");
        return repo;
    }

    @BeforeEach
    void makeRepo() throws Exception {
        repo = history(temp.resolve("project").toAbsolutePath());
    }

    @AfterEach
    void stop() {
        service.shutdown();
    }

    @Test
    void theLast90DaysAreCountedWithoutMergesOrTheOldImport() {
        GitRecentChanges.Result result = service.recentChangesNow(repo);
        assertFalse(result.shallow());
        assertEquals(8, result.commits(), "five on hot.go, warm, docs, the latest; neither the import nor the merge");
        GitRecentChanges.Stat hot = result.stat("pkg/hot/hot.go", false);
        assertEquals(6, hot.commits());
        assertEquals("Grace Hopper", hot.lastAuthor());
        assertTrue(Instant.now().getEpochSecond() - hot.lastEpochSeconds()
                < Duration.ofDays(3).toSeconds());
        assertEquals(1, result.stat("pkg/hot/warm.go", false).commits());
        assertNull(result.stat("pkg/cold/cold.go", false), "changed 200 days ago only");
        assertNull(result.stat("README.md", false));
        assertEquals(7, result.stat("pkg/hot", true).commits());
        assertEquals(7, result.stat("pkg", true).commits());
        assertEquals(1, result.stat("docs", true).commits());
        assertNull(result.stat("pkg/cold", true));
        assertEquals("", result.problem());
    }

    @Test
    void aProjectInASubfolderSeesItsOwnPathsOnly() {
        GitRecentChanges.Result result = service.recentChangesNow(repo.resolve("pkg"));
        assertEquals(7, result.commits(), "the docs commit is outside");
        assertEquals(6, result.stat("hot/hot.go", false).commits());
        assertNull(result.stat("docs", true));
    }

    @Test
    void theAnswerIsKeptUntilTheHeadMoves() throws Exception {
        GitRecentChanges.Result first = service.recentChangesNow(repo);
        assertSame(first, service.recentChangesNow(repo), "the same HEAD: no second log");
        write(repo, "pkg/cold/cold.go", "warmed up");
        commit(repo, 0, "Ada Lovelace", "touch cold");
        GitRecentChanges.Result after = service.recentChangesNow(repo);
        assertNotSame(first, after);
        assertEquals(1, after.stat("pkg/cold/cold.go", false).commits());
    }

    @Test
    void aShallowCloneSaysWhereItsHistoryStops() throws Exception {
        Path one = temp.resolve("depth1");
        git(temp, null, "clone", "-q", "--depth", "1", repo.toUri().toString(), one.toString());
        GitRecentChanges.Result shallow = service.recentChangesNow(one);
        assertTrue(shallow.shallow());
        assertEquals(0, shallow.commits(), "the grafted commit lists every file: it is not a change");
        assertTrue(shallow.files().isEmpty());
        assertTrue(shallow.boundaryEpochSeconds() > 0);

        Path three = temp.resolve("depth3");
        git(temp, null, "clone", "-q", "--depth", "3", repo.toUri().toString(), three.toString());
        GitRecentChanges.Result deeper = service.recentChangesNow(three);
        assertTrue(deeper.shallow());
        assertTrue(deeper.commits() >= 1 && deeper.commits() < 8, "only what is above the cut: " + deeper.commits());
    }

    @Test
    void notARepositoryOrNoCommitIsNothing() throws Exception {
        Path plain = Files.createDirectories(temp.resolve("plain"));
        assertSame(GitRecentChanges.Result.NONE, service.recentChangesNow(plain));
        Path unborn = Files.createDirectories(temp.resolve("unborn"));
        git(unborn, null, "init", "-q");
        assertSame(GitRecentChanges.Result.NONE, service.recentChangesNow(unborn));
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    public static void write(Path repo, String rel, String text) throws Exception {
        Path file = repo.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text + "\n");
    }

    public static void commit(Path repo, int daysAgo, String author, String message) throws Exception {
        git(repo, null, "add", "-A");
        git(repo, dated(daysAgo, author), "commit", "-q", "-m", message);
    }

    private static Map<String, String> dated(int daysAgo, String author) {
        String date = Instant.now().minus(Duration.ofDays(daysAgo)).getEpochSecond() + " +0000";
        String email = author.toLowerCase(java.util.Locale.ROOT).replace(' ', '.') + "@example.com";
        return Map.of(
                "GIT_AUTHOR_DATE", date,
                "GIT_COMMITTER_DATE", date,
                "GIT_AUTHOR_NAME", author,
                "GIT_AUTHOR_EMAIL", email,
                "GIT_COMMITTER_NAME", author,
                "GIT_COMMITTER_EMAIL", email);
    }

    public static void git(Path dir, @Nullable Map<String, String> env, String... args) throws Exception {
        List<String> command =
                new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(args));
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_AUTHOR_NAME", "Test");
        builder.environment().put("GIT_AUTHOR_EMAIL", "test@example.com");
        builder.environment().put("GIT_COMMITTER_NAME", "Test");
        builder.environment().put("GIT_COMMITTER_EMAIL", "test@example.com");
        if (env != null) {
            builder.environment().putAll(env);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + ": " + output);
        }
    }
}

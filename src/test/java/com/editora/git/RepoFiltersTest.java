package com.editora.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.editora.config.TrustStore;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (HN dry run, 2026-11-27): a repository's own {@code .git/config} (an extracted archive, a USB stick)
 * can name a program as a filter driver, and {@code .gitattributes} selects it. The gutter diff of a changed file
 * ran it in a folder nobody had trusted. Background commands now empty the repository's own filters there; a
 * trusted folder's run as before.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in filter is a POSIX shell command")
class RepoFiltersTest {

    @TempDir
    Path dir;

    private Path repo;
    private Path marker;

    @BeforeEach
    void repoWithItsOwnFilter() throws Exception {
        repo = Files.createDirectories(dir.resolve("repo")).toRealPath();
        marker = dir.resolve("filter-ran.log");
        git("init", "-q");
        Files.writeString(repo.resolve(".gitattributes"), "*.txt filter=evil\n");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git("add", ".");
        git("commit", "-qm", "first");
        String program = "sh -c 'echo ran >> " + marker + "; cat'";
        git("config", "filter.evil.clean", program);
        git("config", "filter.evil.process", "sh -c 'echo ran >> " + marker + "; exit 1'");
        Files.writeString(repo.resolve("a.txt"), "two\n");
        TrustStore.publish(new TrustStore());
        RepoFilters.clearForTest();
    }

    @AfterEach
    void forget() {
        TrustStore.publish(new TrustStore());
        RepoFilters.clearForTest();
    }

    /** What the gutter diff and status run: a background command through {@link GitService#backgroundArgv}. */
    private ProcessRunner.Result backgroundDiff() {
        return ProcessRunner.run(
                repo,
                Duration.ofSeconds(30),
                GitService.backgroundArgv(repo, "diff", "--stat"),
                GitSafety.BACKGROUND_ENV);
    }

    @Test
    void anUntrustedFoldersOwnFilterDoesNotRun() {
        ProcessRunner.Result r = backgroundDiff();
        assertFalse(Files.exists(marker), "the repository's filter ran");
        assertTrue(r.ok() && r.out().contains("a.txt"), "the diff still works: " + r);
    }

    @Test
    void aTrustedFoldersFilterRuns() {
        TrustStore trust = new TrustStore();
        trust.trust(repo);
        TrustStore.publish(trust);
        backgroundDiff();
        assertTrue(Files.exists(marker), "trusted: its filters are its own business");
    }

    @Test
    void onlyTheRepositorysOwnFiltersAreNamed() {
        String out = "global\tfilter.lfs.clean\n"
                + "global\tfilter.lfs.process\n"
                + "local\tfilter.evil.clean\n"
                + "worktree\tfilter.with.dots.smudge\n"
                + "command\tfilter.x.clean\n";
        assertEquals(Set.of("evil", "with.dots"), GitSafety.localFilterNames(out), "Git LFS is the user's");
        assertEquals(
                List.of(
                        "-c", "filter.evil.clean=",
                        "-c", "filter.evil.smudge=",
                        "-c", "filter.evil.process=",
                        "-c", "filter.evil.required=false"),
                GitSafety.filterOverrides(List.of("evil")));
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "git", "-c", "commit.gpgsign=false", "-c", "user.name=Test", "-c", "user.email=test@example.com"));
        command.addAll(List.of(args));
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + ": " + output);
        }
    }
}

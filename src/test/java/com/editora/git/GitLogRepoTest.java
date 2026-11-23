package com.editora.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GitLog#logArgs} and {@link GitLog#parse} against a real repository: a stash, notes, a tab in a name. */
class GitLogRepoTest {

    @TempDir
    Path temp;

    @Test
    void allBranchesListsBranchesAndTagsButNotTheStashOrNotes() throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git(repo, "add", "a.txt");
        git(repo, "-c", "user.name=Tab\tName", "commit", "-q", "-m", "first");
        git(repo, "tag", "v1");
        git(repo, "checkout", "-q", "-b", "side");
        Files.writeString(repo.resolve("a.txt"), "side\n");
        git(repo, "commit", "-q", "-am", "on side");
        git(repo, "checkout", "-q", "main");
        git(repo, "notes", "add", "-m", "a note", "HEAD");
        Files.writeString(repo.resolve("a.txt"), "dirty\n");
        Files.writeString(repo.resolve("new.txt"), "untracked\n");
        git(repo, "stash", "push", "-q", "--include-untracked", "-m", "work in progress");

        String out = git(repo, GitLog.logArgs(new GitLog.Request(true, null, GitLogQuery.NONE, 0, 50)));
        GitLog.Page page = GitLog.parse(out, 50);

        List<String> subjects =
                page.entries().stream().map(GitLog.Entry::subject).toList();
        assertEquals(2, subjects.size(), "the two commits of the branches, and nothing else: " + subjects);
        assertTrue(subjects.containsAll(List.of("on side", "first")), "" + subjects);
        for (String subject : subjects) {
            assertFalse(subject.contains("index on") || subject.contains("untracked files on"), subject);
            assertFalse(subject.startsWith("On main") || subject.contains("Notes added"), subject);
        }
        GitLog.Entry first = page.entries().stream()
                .filter(e -> e.subject().equals("first"))
                .findFirst()
                .orElseThrow();
        assertEquals("Tab\tName", first.author(), "a tab in a name stays in the name");
        assertTrue(first.epochSeconds() > 0, "…and the fields after it are where they belong");
        assertTrue(first.parents().isEmpty());
        assertTrue(first.tags().contains("v1"));
    }

    private static String git(Path dir, String... args) throws Exception {
        return git(dir, List.of(args));
    }

    private static String git(Path dir, List<String> args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "git",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "core.hooksPath=/dev/null",
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.com"));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + ": " + output);
        }
        return output;
    }
}

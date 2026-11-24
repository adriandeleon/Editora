package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.editora.git.GitService;
import com.editora.git.GitWorktrees;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4-B3: choosing a remote branch never switches to a local branch that another work tree has checked out — git
 * refuses ("already used by worktree"). Read from a real repository with a real {@code git worktree add}.
 */
class GitRemoteCheckoutWorktreeTest {

    @Test
    void aTrackingBranchCheckedOutInAnotherWorktreeIsPassedOver(@TempDir Path dir) throws Exception {
        Path origin = dir.resolve("origin");
        Path seed = dir.resolve("seed");
        Path clone = dir.resolve("clone");
        Files.createDirectories(seed);
        git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        git(seed, "init", "-q", "-b", "main");
        identity(seed);
        Files.writeString(seed.resolve("a.txt"), "a\n");
        git(seed, "add", "a.txt");
        git(seed, "commit", "-q", "-m", "first");
        git(seed, "branch", "feature");
        git(seed, "push", "-q", origin.toString(), "main", "feature");
        git(dir, "clone", "-q", origin.toString(), clone.toString());
        identity(clone);
        git(clone, "branch", "--track", "feature", "origin/feature");
        git(clone, "branch", "--track", "alias", "origin/feature");
        git(clone, "worktree", "add", "-q", dir.resolve("wt").toString(), "feature");

        // The same-named tracking branch is held by the other work tree: the other tracking branch is chosen.
        GitWindowCoordinator.RemoteCheckout choice = decide(clone, "origin/feature");
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(GitWindowCoordinator.RemoteCheckout.Kind.SWITCH, "alias"),
                choice);
        git(clone, "checkout", "-q", choice.name()); // git agrees: the switch works
        git(clone, "checkout", "-q", "main");

        // Without a free tracking branch, the held one still holds its name: a new name is asked for.
        git(clone, "branch", "-D", "alias");
        choice = decide(clone, "origin/feature");
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(GitWindowCoordinator.RemoteCheckout.Kind.ASK, "origin-feature"),
                choice);
        git(clone, "checkout", "-q", "-b", choice.name(), "--track", "origin/feature");
        assertTrue(git(clone, "branch", "--show-current").strip().equals("origin-feature"));

        // Checked out right here (not elsewhere), the same-named branch is switched to as before.
        git(clone, "checkout", "-q", "main");
        git(clone, "worktree", "remove", dir.resolve("wt").toString());
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(GitWindowCoordinator.RemoteCheckout.Kind.SWITCH, "feature"),
                decide(clone, "origin/feature"));
    }

    @Test
    void theDecisionPrefersAFreeBranchAndFallsBack() {
        List<GitService.BranchInfo> local = List.of(
                new GitService.BranchInfo("feature", "origin/feature", 0, 0, false),
                new GitService.BranchInfo("alias", "origin/feature", 0, 0, false));
        List<String> remotes = List.of("origin");
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(GitWindowCoordinator.RemoteCheckout.Kind.SWITCH, "feature"),
                GitWindowCoordinator.remoteCheckout("origin/feature", local, remotes, Set.of("alias")),
                "the same name first, among the free ones");
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(GitWindowCoordinator.RemoteCheckout.Kind.ASK, "origin-feature"),
                GitWindowCoordinator.remoteCheckout("origin/feature", local, remotes, Set.of("alias", "feature")),
                "every tracking branch is held: the taken name is asked about");
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(GitWindowCoordinator.RemoteCheckout.Kind.TRACK, "feature"),
                GitWindowCoordinator.remoteCheckout("origin/feature", List.of(local.get(1)), remotes, Set.of("alias")),
                "the held branch has another name: the remote branch's own name is free to track");
    }

    private static GitWindowCoordinator.RemoteCheckout decide(Path clone, String remoteBranch) throws Exception {
        List<GitService.BranchInfo> local = new ArrayList<>();
        for (String line : git(clone, "for-each-ref", "--format=%(refname:short) %(upstream:short)", "refs/heads")
                .lines()
                .toList()) {
            String[] parts = line.split(" ", -1);
            local.add(new GitService.BranchInfo(parts[0], parts.length > 1 ? parts[1] : "", 0, 0, false));
        }
        List<GitWorktrees.Worktree> trees = GitWorktrees.parse(git(clone, "worktree", "list", "--porcelain"));
        Set<String> held = GitBranchCoordinator.checkedOutElsewhere(clone.toRealPath(), trees)
                .keySet();
        return GitWindowCoordinator.remoteCheckout(remoteBranch, local, List.of("origin"), held);
    }

    private static void identity(Path repo) throws Exception {
        git(repo, "config", "user.email", "editora-test@example.invalid");
        git(repo, "config", "user.name", "Editora Test");
        git(repo, "config", "commit.gpgsign", "false");
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}

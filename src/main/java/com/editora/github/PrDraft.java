package com.editora.github;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * What the create-pull-request form starts with, and the checks made before it opens: a title and body
 * derived from the branch's commits (GitHub's own rule — one commit gives its subject and body, several give
 * the branch name and a list), the repository's pull-request template, and whether the branch is one a pull
 * request can be opened from. Pure apart from {@link #template}, which reads one small file — unit-tested.
 */
public final class PrDraft {

    private PrDraft() {}

    /** One commit of the branch: its subject line and the rest of its message. */
    public record CommitMessage(String subject, String body) {
        public CommitMessage {
            subject = subject == null ? "" : subject.strip();
            body = body == null ? "" : body.strip();
        }
    }

    /** The form's starting title and body. */
    public record Draft(String title, String body) {}

    /** The largest template read into the form. */
    static final long TEMPLATE_MAX_BYTES = 64 * 1024;

    /**
     * The draft for {@code branch} whose commits not on the base are {@code commits} (newest first, as
     * {@code git log} lists them). {@code template} — the repository's pull-request template, {@code ""} when
     * it has none — is the body when present: it is what the repository asks every pull request to follow, and
     * passing a body to {@code gh} would otherwise bypass it.
     */
    public static Draft of(String branch, List<CommitMessage> commits, String template) {
        List<CommitMessage> list = commits == null ? List.of() : commits;
        String title;
        String body;
        if (list.size() == 1) {
            title = list.get(0).subject();
            body = list.get(0).body();
        } else {
            title = humanise(branch);
            StringBuilder bullets = new StringBuilder();
            for (int i = list.size() - 1; i >= 0; i--) { // oldest first, the order they were made in
                if (!list.get(i).subject().isEmpty()) {
                    bullets.append("- ").append(list.get(i).subject()).append('\n');
                }
            }
            body = bullets.toString().stripTrailing();
        }
        String wanted = template == null ? "" : template.strip();
        return new Draft(title, wanted.isEmpty() ? body : wanted);
    }

    /**
     * A branch name as a title: the last path segment ({@code fix/github-ui} → {@code github-ui}), separators
     * as spaces, first letter capitalised — {@code "Github ui"}.
     */
    public static String humanise(String branch) {
        String b = branch == null ? "" : branch.strip();
        int slash = b.lastIndexOf('/');
        if (slash >= 0 && slash < b.length() - 1) {
            b = b.substring(slash + 1);
        }
        b = b.replace('-', ' ').replace('_', ' ').strip().replaceAll("\\s+", " ");
        return b.isEmpty() ? "" : b.substring(0, 1).toUpperCase(Locale.ROOT) + b.substring(1);
    }

    /** Whether {@code branch} is the repository's default branch — there is nothing to open a pull request for. */
    public static boolean onDefaultBranch(String branch, String defaultBranch) {
        return branch != null && !branch.isBlank() && branch.equals(defaultBranch);
    }

    /**
     * Whether the branch must be pushed before {@code gh pr create} can describe it: it has no upstream, or
     * it has commits its upstream does not.
     */
    public static boolean needsPush(String upstream, int ahead) {
        return upstream == null || upstream.isBlank() || ahead > 0;
    }

    /**
     * The ref the branch's commits are counted against: the base branch on the branch's own remote when
     * there is such a remote-tracking branch, else on any remote, else the local branch, else {@code ""}.
     *
     * @param remoteBranches remote-tracking short names ({@code origin/main})
     * @param localBranches local branch names
     * @param upstream the current branch's upstream ({@code origin/feature}), or {@code ""}
     */
    public static String baseRef(
            String base, List<String> remoteBranches, List<String> localBranches, String upstream) {
        if (base == null || base.isBlank()) {
            return "";
        }
        int slash = upstream == null ? -1 : upstream.indexOf('/');
        String own = (slash > 0 ? upstream.substring(0, slash) : "origin") + "/" + base;
        if (remoteBranches.contains(own)) {
            return own;
        }
        for (String remote : remoteBranches) {
            if (remote.endsWith("/" + base)) {
                return remote;
            }
        }
        return localBranches.contains(base) ? base : "";
    }

    /**
     * The branch names a pull request can target: every remote-tracking branch without its remote
     * ({@code origin/main} → {@code main}), no {@code HEAD} pointers, no duplicates, the default branch first.
     */
    public static List<String> baseChoices(List<String> remoteBranches, String defaultBranch) {
        List<String> out = new ArrayList<>();
        if (defaultBranch != null && !defaultBranch.isBlank()) {
            out.add(defaultBranch);
        }
        for (String remote : remoteBranches) {
            int slash = remote.indexOf('/');
            String name = slash >= 0 ? remote.substring(slash + 1) : remote;
            if (!name.isEmpty() && !name.equals("HEAD") && !name.startsWith("HEAD ") && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * The repository's pull-request template: {@code pull_request_template.md} (any letter case) in
     * {@code .github/}, the repository root or {@code docs/} — the places GitHub itself looks, in that order.
     * {@code ""} when there is none, it is not readable, or it is implausibly large. A
     * {@code PULL_REQUEST_TEMPLATE/} directory of several templates is not chosen from.
     */
    public static String template(Path root) {
        if (root == null) {
            return "";
        }
        for (String dir : List.of(".github", "", "docs")) {
            Path folder = dir.isEmpty() ? root : root.resolve(dir);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(folder)) {
                Path found = entries.filter(p -> p.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT)
                                .equals("pull_request_template.md"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    return Files.size(found) > TEMPLATE_MAX_BYTES
                            ? ""
                            : Files.readString(found, StandardCharsets.UTF_8).strip();
                }
            } catch (IOException | RuntimeException unreadable) {
                // try the next place
            }
        }
        return "";
    }
}

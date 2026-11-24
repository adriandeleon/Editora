package com.editora.github;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@code gh} call for a top-level PR review (approve / request-changes / comment). Pure — the decision is
 * unit-tested even though the invocation isn't. A body is optional for {@link ReviewAction#APPROVE} (approve with no
 * comment) but required for the other two (GitHub rejects an empty request-changes/comment review). The body is
 * never an argument: it goes to gh's standard input, where its size does not matter.
 *
 * <p>When the commit the reviewer saw is known (the review tab loaded the pull request's head), the review is
 * pinned to it: {@code gh api --hostname <host> repos/<owner>/<repo>/pulls/<n>/reviews --input -} with {@code
 * commit_id} — host and repository from the pull request's own address — so commits pushed while the reviewer read
 * are not approved with them. Without it, {@code gh pr review <n> --body-file -}.
 */
public final class PrReviewArgs {

    private PrReviewArgs() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The kind of review to submit. */
    public enum ReviewAction {
        APPROVE,
        REQUEST_CHANGES,
        COMMENT;

        /** The REST API's {@code event} for this action. */
        String event() {
            return name();
        }
    }

    /** Whether {@code action} requires a non-blank body (everything except a bare approve). */
    public static boolean bodyRequired(ReviewAction action) {
        return action != ReviewAction.APPROVE;
    }

    /**
     * Whether the form may be submitted: an action was chosen and the body it needs is there. An approve counts only
     * when the reviewer picked it ({@code approveChosen}) — the form never starts on Approve, and a stray submit with
     * an empty body must not approve a pull request in public.
     */
    public static boolean canSubmit(@Nullable ReviewAction action, @Nullable String body, boolean approveChosen) {
        if (action == null) {
            return false;
        }
        if (action == ReviewAction.APPROVE) {
            return approveChosen;
        }
        return body != null && !body.isBlank();
    }

    /** The {@code gh pr review} sub-args (without the leading {@code "gh"}); the body goes on standard input. */
    public static List<String> build(int number, ReviewAction action, @Nullable String body) {
        List<String> args = new ArrayList<>();
        args.add("pr");
        args.add("review");
        args.add(String.valueOf(number));
        args.add(
                switch (action) {
                    case APPROVE -> "--approve";
                    case REQUEST_CHANGES -> "--request-changes";
                    case COMMENT -> "--comment";
                });
        if (action != ReviewAction.APPROVE || (body != null && !body.isBlank())) {
            args.add("--body-file");
            args.add("-");
        }
        return args;
    }

    /**
     * The whole call: pinned to {@code commitId} through the REST API when it is known, else {@code gh pr review}.
     * The body (when there is one) is the standard input either way. The pinned call names the pull request's own
     * host and repository, read from its web address {@code prUrl}: {@code gh api} would otherwise send it to
     * {@code github.com} whatever the remote (a GitHub Enterprise pull request), and fill {@code {owner}/{repo}}
     * from a remote that may not be the pull request's. Without a readable {@code prUrl} the review is not pinned
     * ({@code gh pr review} finds the host itself).
     */
    public static GhInvocation invocation(
            int number, ReviewAction action, @Nullable String body, @Nullable String commitId, @Nullable String prUrl) {
        String b = body == null ? "" : body;
        Target target = commitId == null || commitId.isBlank() ? null : target(prUrl, number);
        if (target == null || commitId == null) {
            List<String> args = build(number, action, b);
            return new GhInvocation(args, args.contains("--body-file") ? b : null);
        }
        ObjectNode json = MAPPER.createObjectNode();
        json.put("commit_id", commitId.strip());
        json.put("event", action.event());
        if (!b.isBlank()) {
            json.put("body", b);
        }
        return new GhInvocation(
                List.of(
                        "api",
                        "--hostname",
                        target.host(),
                        "--method",
                        "POST",
                        "repos/" + target.repo() + "/pulls/" + number + "/reviews",
                        "--input",
                        "-"),
                json.toString());
    }

    /** A pull request's host ({@code github.com}, {@code ghe.example.com[:port]}) and {@code owner/name}. */
    record Target(String host, String repo) {}

    /** Safe in a host, an owner or a repository name: nothing gh or a URL path would read otherwise. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * The host and repository of pull request {@code number}'s web address ({@code https://host/owner/repo/pull/n}),
     * or {@code null} when {@code prUrl} is not one. Pure.
     */
    static @Nullable Target target(@Nullable String prUrl, int number) {
        if (prUrl == null || prUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(prUrl.strip());
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null
                    || path == null
                    || !"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            String[] parts = path.replaceAll("^/+|/+$", "").split("/");
            if (parts.length != 4
                    || !parts[2].equals("pull")
                    || !parts[3].equals(String.valueOf(number))
                    || !NAME.matcher(host).matches()
                    || !NAME.matcher(parts[0]).matches()
                    || !NAME.matcher(parts[1]).matches()) {
                return null;
            }
            String h = host.toLowerCase(Locale.ROOT) + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            return new Target(h, parts[0] + "/" + parts[1]);
        } catch (URISyntaxException bad) {
            return null;
        }
    }
}

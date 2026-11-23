package com.editora.github;

import java.util.ArrayList;
import java.util.List;

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
 * pinned to it: {@code gh api repos/{owner}/{repo}/pulls/<n>/reviews --input -} with {@code commit_id}, so commits
 * pushed while the reviewer read are not approved with them. Without it, {@code gh pr review <n> --body-file -}.
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
     * The body (when there is one) is the standard input either way.
     */
    public static GhInvocation invocation(
            int number, ReviewAction action, @Nullable String body, @Nullable String commitId) {
        String b = body == null ? "" : body;
        if (commitId == null || commitId.isBlank()) {
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
                List.of("api", "--method", "POST", "repos/{owner}/{repo}/pulls/" + number + "/reviews", "--input", "-"),
                json.toString());
    }
}

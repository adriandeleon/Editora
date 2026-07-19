package com.editora.github;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code gh pr review} argument list for a top-level PR review (approve / request-changes /
 * comment). Pure — the argv decision is unit-tested even though the invocation isn't. A body is optional for
 * {@link ReviewAction#APPROVE} (approve with no comment) but required for the other two (gh rejects an empty
 * request-changes/comment review). Like {@link PrCreateArgs}, the body is passed as {@code --body <text>} so
 * gh never drops into an interactive editor.
 */
public final class PrReviewArgs {

    private PrReviewArgs() {}

    /** The kind of review to submit. */
    public enum ReviewAction {
        APPROVE,
        REQUEST_CHANGES,
        COMMENT
    }

    /** Whether {@code action} requires a non-blank body (everything except a bare approve). */
    public static boolean bodyRequired(ReviewAction action) {
        return action != ReviewAction.APPROVE;
    }

    /** The {@code gh} sub-args (without the leading {@code "gh"}) for {@code gh pr review}. */
    public static List<String> build(int number, ReviewAction action, String body) {
        List<String> args = new ArrayList<>();
        args.add("pr");
        args.add("review");
        args.add(String.valueOf(number));
        String b = body == null ? "" : body;
        switch (action) {
            case APPROVE -> {
                args.add("--approve");
                if (!b.isBlank()) { // an approve may carry an optional comment
                    args.add("--body");
                    args.add(b);
                }
            }
            case REQUEST_CHANGES -> {
                args.add("--request-changes");
                args.add("--body");
                args.add(b);
            }
            case COMMENT -> {
                args.add("--comment");
                args.add("--body");
                args.add(b);
            }
        }
        return args;
    }
}

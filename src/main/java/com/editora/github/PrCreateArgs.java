package com.editora.github;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the {@code gh pr create} argument list from the create-PR form fields. The argv <em>decision</em> is
 * pure and unit-tested even though the invocation itself isn't: a blank body still passes {@code --body ""} so
 * {@code gh} never drops into an interactive editor (which would hang, since {@link com.editora.process.ProcessRunner}
 * closes the child's stdin), a blank base is omitted (gh uses the repo's default branch), and {@code --draft}
 * is added only when requested.
 *
 * <p>Reviewers / assignees / labels arrive as raw comma-separated form fields and are emitted as one repeated
 * flag per value ({@code --reviewer a --reviewer b}) rather than one comma-joined flag — gh accepts both, but
 * the repeated form can't be confused by a value that itself contains a comma. Pure.
 */
public final class PrCreateArgs {

    private PrCreateArgs() {}

    /** The {@code gh} sub-args (i.e. without the leading {@code "gh"}) for creating a PR. */
    public static List<String> build(String title, String body, String base, boolean draft) {
        return build(title, body, base, draft, null, null, null);
    }

    /**
     * As {@link #build(String, String, String, boolean)}, plus the three people/label fields. Each is a raw
     * comma-separated field straight from the form ({@code null}/blank ⇒ the flag is omitted entirely).
     */
    public static List<String> build(
            String title, String body, String base, boolean draft, String reviewers, String assignees, String labels) {
        List<String> args = new ArrayList<>();
        args.add("pr");
        args.add("create");
        args.add("--title");
        args.add(title == null ? "" : title);
        args.add("--body");
        args.add(body == null ? "" : body);
        if (base != null && !base.isBlank()) {
            args.add("--base");
            args.add(base.strip());
        }
        if (draft) {
            args.add("--draft");
        }
        addRepeated(args, "--reviewer", handles(reviewers));
        addRepeated(args, "--assignee", handles(assignees));
        addRepeated(args, "--label", split(labels));
        return args;
    }

    private static void addRepeated(List<String> args, String flag, List<String> values) {
        for (String v : values) {
            args.add(flag);
            args.add(v);
        }
    }

    /**
     * Splits a comma-separated field into trimmed, non-blank, de-duplicated (case-insensitively) values,
     * preserving the order they were typed in.
     */
    static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String v = part.strip();
            if (!v.isEmpty() && seen.add(v.toLowerCase(Locale.ROOT))) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * {@link #split} for a user/team field, additionally dropping a leading {@code @} that a user naturally
     * types ({@code @octocat} → {@code octocat}) — gh wants the bare handle (or {@code owner/team}) and errors
     * on the {@code @} form. The one exception is the literal {@code @me}, which gh defines as the
     * authenticated user, so it must survive verbatim.
     */
    static List<String> handles(String raw) {
        List<String> out = new ArrayList<>();
        for (String v : split(raw)) {
            out.add(v.length() > 1 && v.charAt(0) == '@' && !"@me".equalsIgnoreCase(v) ? v.substring(1) : v);
        }
        return out;
    }
}

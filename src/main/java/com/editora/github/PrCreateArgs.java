package com.editora.github;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code gh pr create} argument list from the create-PR form fields. The argv <em>decision</em> is
 * pure and unit-tested even though the invocation itself isn't: a blank body still passes {@code --body ""} so
 * {@code gh} never drops into an interactive editor (which would hang, since {@link com.editora.process.ProcessRunner}
 * closes the child's stdin), a blank base is omitted (gh uses the repo's default branch), and {@code --draft}
 * is added only when requested. Pure.
 */
public final class PrCreateArgs {

    private PrCreateArgs() {}

    /** The {@code gh} sub-args (i.e. without the leading {@code "gh"}) for creating a PR. */
    public static List<String> build(String title, String body, String base, boolean draft) {
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
        return args;
    }
}

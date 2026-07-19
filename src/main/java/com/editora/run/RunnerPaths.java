package com.editora.run;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a path token from a <em>CI runner's</em> log onto the local checkout. A GitHub Actions log prints
 * stack frames with the runner's own paths — absolute
 * ({@code /home/runner/work/<repo>/<repo>/src/main/java/X.java}, or {@code D:\a\repo\repo\src\x.ts} on a
 * Windows runner) or repo-relative ({@code src/lib/x.ts}) — neither of which exists locally at that path. This
 * produces the progressively shorter repo-relative <em>suffixes</em> to try under the local project root,
 * longest first, so the first hit is the most specific match:
 *
 * <pre>{@code
 * "a/b/c/d.ts" -> ["a/b/c/d.ts", "b/c/d.ts", "c/d.ts", "d.ts"]
 * }</pre>
 *
 * <p>The runner's {@code /home/runner/work/<repo>/<repo>/} prefix simply falls out of the suffix walk — no
 * pattern-matching on runner layouts is needed, so it works for GitHub-hosted, self-hosted, and other CI alike.
 * A token containing a {@code ..} segment is rejected outright so a log line can never resolve outside the
 * project root. Pure — unit-tested.
 */
public final class RunnerPaths {

    private RunnerPaths() {}

    /**
     * Cap on the trailing segments that participate, bounding the candidate count for a deeply nested path.
     * Must comfortably exceed a realistic repo-relative depth: a runner prefix is ~5 segments
     * ({@code /home/runner/work/<repo>/<repo>/}) and a Java path like
     * {@code src/main/java/com/editora/config/migration/ConfigSchema.java} is already 8 on its own — too small
     * a cap would never emit the suffix that actually resolves, and the link would silently do nothing. The
     * cost of a larger cap is only a few extra {@code isRegularFile} stats per (user-initiated) click.
     */
    private static final int MAX_SEGMENTS = 12;

    /** Repo-relative candidate suffixes for {@code token}, longest first; empty when it can't be used. */
    public static List<String> candidates(String token) {
        if (token == null || token.isBlank()) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        for (String s : token.replace('\\', '/').strip().split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                return List.of(); // never let a log line escape the project root
            }
            // Drop a leading Windows drive prefix ("D:") so the rest is a clean relative walk.
            if (segments.isEmpty() && s.length() == 2 && s.charAt(1) == ':') {
                continue;
            }
            segments.add(s);
        }
        if (segments.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, segments.size() - MAX_SEGMENTS);
        List<String> out = new ArrayList<>();
        for (int i = from; i < segments.size(); i++) {
            out.add(String.join("/", segments.subList(i, segments.size())));
        }
        return out;
    }
}

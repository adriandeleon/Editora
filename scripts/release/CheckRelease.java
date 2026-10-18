package release;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lib.Json;
import lib.Proc;

/**
 * Release gates run by {@code .github/workflows/release.yml}.
 *
 * <pre>
 *   java scripts/release/CheckRelease.java version &lt;ref-type&gt; &lt;ref-name&gt; &lt;pom.xml&gt;
 * </pre>
 * Preflight, before any build leg starts: on a tag build the tag must be a version ({@code vX.Y.Z} or
 * {@code vX.Y.Z-rcN}) and, unless it is an {@code -rc} tag, equal the pom's {@code <version>}.
 *
 * <pre>
 *   java scripts/release/CheckRelease.java assets &lt;artifacts-dir&gt; &lt;version&gt; --pom &lt;pom.xml&gt; [--mode enforce|report]
 * </pre>
 * Before JReleaser publishes: every installer the build matrix is supposed to produce must be present and
 * non-empty. GitHub releases are immutable once published, and JReleaser's file globs happily match nothing,
 * so a release that lost its .deb to a failed wrap would otherwise publish without it and could never be
 * completed.
 *
 * <pre>
 *   java scripts/release/CheckRelease.java ci &lt;owner/repo&gt; &lt;commit-sha&gt; [--mode enforce|report] [--wait-minutes N]
 * </pre>
 * Preflight: every check that the master branch's ruleset requires must have passed on the commit being
 * released. A tag can be pushed on any commit, and the build legs run no tests, so without this a release
 * could be cut from code CI never passed (or failed). When CI is still running on the commit (a tag pushed
 * straight after a merge), it waits up to N minutes. Asks GitHub with {@code gh}.
 *
 * <p>All exit 0 when the release may proceed and 1 with a list of what is wrong otherwise.
 */
public final class CheckRelease {

    /**
     * What each build leg stages (the "Stage artifacts" step of release.yml), by target. Linux arm64
     * deliberately ships only the tarball and the fat jar; macOS ships only the .dmg. Keep this in step with
     * that step and with docs/release.md — CheckReleaseTest pins the full list.
     */
    static final Map<String, List<String>> EXPECTED = ordered(
            "linux-x64", List.of("deb", "rpm", "AppImage", "tar.gz", "jar"),
            "linux-arm64", List.of("tar.gz", "jar"),
            "macos-x64", List.of("dmg"),
            "macos-arm64", List.of("dmg"),
            "windows-x64", List.of("msi", "jar"));

    /** Best-effort archives from the native-experimental matrix: reported, never required. */
    static final Map<String, String> OPTIONAL = ordered(
            "linux-x64", "tar.gz",
            "macos-x64", "tar.gz",
            "macos-arm64", "tar.gz",
            "windows-x64", "zip");

    private static final Pattern TAG = Pattern.compile("v(\\d+\\.\\d+\\.\\d+)(-rc[0-9A-Za-z.]*)?");
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");

    /** One check run on a commit, as GitHub's check-runs API reports it. */
    record CheckRun(String name, String status, String conclusion, String startedAt) {}

    /** {@code pass}, {@code pending} or {@code fail}, and one line per required check. */
    record Verdict(String verdict, List<String> lines) {}

    private CheckRelease() {}

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> ordered(Object... pairs) {
        var map = new LinkedHashMap<String, V>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (V) pairs[i + 1]);
        }
        return map;
    }

    /** The project's own {@code <version>}: the first one in the file, as the workflow's {@code grep -m1} reads it. */
    static String pomVersion(Path pom) {
        Matcher match = VERSION.matcher(read(pom));
        if (!match.find()) {
            throw new IllegalArgumentException("no <version> found in " + pom);
        }
        return match.group(1);
    }

    /** Returns a list of problems (empty when the build may proceed). */
    static List<String> checkVersion(String refType, String refName, Path pom) {
        if (!refType.equals("tag")) {
            return List.of(); // a manual dry run from a branch: nothing to compare
        }
        Matcher match = TAG.matcher(refName);
        if (!match.matches()) {
            return List.of("tag '" + refName + "' is not a release tag: expected vX.Y.Z or vX.Y.Z-rcN");
        }
        if (match.group(2) != null) {
            return List.of(); // an -rc is cut from the -SNAPSHOT line; the pom is not expected to match it
        }
        String tagged = match.group(1);
        String declared = pomVersion(pom);
        if (!declared.equals(tagged)) {
            String hint = declared.endsWith("-SNAPSHOT")
                    ? " — drop the -SNAPSHOT suffix before tagging (docs/release.md, step 1)"
                    : "";
            return List.of("tag '" + refName + "' does not match the pom version '" + declared + "'" + hint);
        }
        return List.of();
    }

    /** Every required file name. Fat jars carry the POM version, everything else the release version. */
    static List<String> expectedAssets(String version, String jarVersion) {
        var names = new ArrayList<String>();
        EXPECTED.forEach((target, extensions) -> {
            for (String extension : extensions) {
                String stem = extension.equals("jar") ? jarVersion : version;
                names.add("Editora-" + stem + "-" + target + "." + extension);
            }
        });
        return names;
    }

    static List<String> optionalAssets(String version) {
        var names = new ArrayList<String>();
        OPTIONAL.forEach((target, extension) ->
                names.add("Editora-" + version + "-" + target + "-native-experimental." + extension));
        return names;
    }

    /** Names of the non-empty files anywhere under the downloaded-artifacts directory. */
    static Set<String> present(Path root) {
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> size(p) > 0)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> missingAssets(Path root, String version, String jarVersion) {
        var found = present(root);
        return expectedAssets(version, jarVersion).stream().filter(n -> !found.contains(n)).toList();
    }

    /** The check names the default branch's rulesets require, as GitHub reports them for {@code master}. */
    static List<String> requiredChecks(String repo, Function<List<String>, String> gh) {
        String out = gh.apply(List.of("api", "repos/" + repo + "/rules/branches/master",
                "--jq", ".[] | select(.type == \"required_status_checks\")"
                        + " | .parameters.required_status_checks[].context"));
        return out.lines().filter(line -> !line.isBlank()).toList();
    }

    /** Every check run on {@code sha}. */
    static List<CheckRun> checkRuns(String repo, String sha, Function<List<String>, String> gh) {
        String out = gh.apply(List.of("api", "--paginate", "repos/" + repo + "/commits/" + sha + "/check-runs?per_page=100",
                "--jq", ".check_runs[] | {name, status, conclusion, started_at}"));
        return out.lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    var run = Json.object(Json.parse(line));
                    return new CheckRun((String) run.get("name"), (String) run.get("status"),
                            (String) run.get("conclusion"), (String) run.get("started_at"));
                })
                .toList();
    }

    /**
     * The verdict on the required checks.
     *
     * <p>A check can have run more than once on a commit (a re-run, or a push and a dispatched run): the
     * newest run of each name decides, so re-running a check that failed for an unrelated reason counts. Only
     * {@code success} passes. A {@code skipped} or {@code neutral} required check passes GitHub's merge rule,
     * but it is not evidence that the code was tested.
     */
    static Verdict ciVerdict(List<CheckRun> runs, List<String> required) {
        var newest = new HashMap<String, CheckRun>();
        for (CheckRun run : runs) {
            if (required.contains(run.name())) {
                CheckRun previous = newest.get(run.name());
                String previousStart = previous == null || previous.startedAt() == null ? "" : previous.startedAt();
                String start = run.startedAt() == null ? "" : run.startedAt();
                if (start.compareTo(previousStart) >= 0) {
                    newest.put(run.name(), run);
                }
            }
        }
        boolean failed = false;
        boolean pending = false;
        var lines = new ArrayList<String>();
        for (String name : required) {
            CheckRun run = newest.get(name);
            if (run == null) {
                pending = true;
                lines.add("  waiting  " + name + ": has not started on this commit");
            } else if (!"completed".equals(run.status())) {
                pending = true;
                lines.add("  waiting  " + name + ": " + run.status());
            } else if ("success".equals(run.conclusion())) {
                lines.add("  ok       " + name);
            } else {
                failed = true;
                lines.add("  FAILED   " + name + ": " + run.conclusion());
            }
        }
        return new Verdict(failed ? "fail" : pending ? "pending" : "pass", lines);
    }

    /** Polls until the required checks on {@code sha} have a verdict or the wait is over. Returns the exit code. */
    static int checkCi(String repo, String sha, String mode, int waitMinutes, Function<List<String>, String> gh,
            IntConsumer sleepSeconds, DoubleSupplier clockSeconds, PrintStream out) {
        var required = requiredChecks(repo, gh);
        String level = mode.equals("enforce") ? "error" : "warning";
        if (required.isEmpty()) {
            out.println("::" + level + " title=Release preflight::the master ruleset requires no checks, so there is "
                    + "nothing to tell whether CI passed on this commit");
            return mode.equals("enforce") ? 1 : 0;
        }
        double deadline = clockSeconds.getAsDouble() + waitMinutes * 60;
        Verdict verdict;
        while (true) {
            verdict = ciVerdict(checkRuns(repo, sha, gh), required);
            out.println("CI on " + sha.substring(0, Math.min(12, sha.length())) + ": " + verdict.verdict());
            out.println(String.join("\n", verdict.lines()));
            out.flush();
            if (verdict.verdict().equals("pass")) {
                return 0;
            }
            if (verdict.verdict().equals("fail") || clockSeconds.getAsDouble() >= deadline) {
                break;
            }
            sleepSeconds.accept(30);
        }
        String problem = verdict.verdict().equals("fail")
                ? "a check that master requires failed on this commit; fix it, or re-run it if it was unrelated"
                : "CI has not finished on this commit after " + waitMinutes + " minute(s)";
        out.println("::" + level + " title=Release preflight::" + problem);
        return mode.equals("enforce") ? 1 : 0;
    }

    private static String gh(List<String> args) {
        var command = new ArrayList<String>();
        command.add("gh");
        command.addAll(args);
        return Proc.check(command);
    }

    static int run(String[] argv, PrintStream out, PrintStream err) {
        if (argv.length == 0) {
            return usage(err, "a command is required");
        }
        var positional = new ArrayList<String>();
        var options = new HashMap<String, String>();
        for (int i = 1; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.startsWith("--")) {
                String name = arg;
                String value;
                int equals = arg.indexOf('=');
                if (equals > 0) {
                    name = arg.substring(0, equals);
                    value = arg.substring(equals + 1);
                } else if (i + 1 < argv.length) {
                    value = argv[++i];
                } else {
                    return usage(err, name + " needs a value");
                }
                options.put(name, value);
            } else {
                positional.add(arg);
            }
        }
        String mode = options.getOrDefault("--mode", "enforce");
        if (!mode.equals("enforce") && !mode.equals("report")) {
            return usage(err, "--mode must be enforce or report");
        }
        switch (argv[0]) {
            case "ci" -> {
                if (positional.size() != 2 || !Set.of("--mode", "--wait-minutes").containsAll(options.keySet())) {
                    return usage(err, "ci takes <owner/repo> <commit-sha> [--mode enforce|report] [--wait-minutes N]");
                }
                int wait;
                try {
                    wait = Integer.parseInt(options.getOrDefault("--wait-minutes", "0"));
                } catch (NumberFormatException e) {
                    return usage(err, "--wait-minutes must be a number");
                }
                return checkCi(positional.get(0), positional.get(1), mode, wait, CheckRelease::gh,
                        CheckRelease::sleep, () -> System.nanoTime() / 1e9, out);
            }
            case "version" -> {
                if (positional.size() != 3 || !options.isEmpty()) {
                    return usage(err, "version takes <ref-type> <ref-name> <pom.xml>");
                }
                Path pom = Path.of(positional.get(2));
                var problems = checkVersion(positional.get(0), positional.get(1), pom);
                for (String problem : problems) {
                    out.println("::error title=Release preflight::" + problem);
                }
                if (problems.isEmpty()) {
                    out.println("preflight ok: " + positional.get(0) + " '" + positional.get(1) + "', pom "
                            + pomVersion(pom));
                }
                return problems.isEmpty() ? 0 : 1;
            }
            case "assets" -> {
                if (positional.size() != 2 || !options.containsKey("--pom")
                        || !Set.of("--pom", "--mode").containsAll(options.keySet())) {
                    return usage(err, "assets takes <artifacts-dir> <version> --pom <pom.xml> [--mode enforce|report]");
                }
                return assets(Path.of(positional.get(0)), positional.get(1), Path.of(options.get("--pom")), mode, out, err);
            }
            default -> {
                return usage(err, "unknown command '" + argv[0] + "'");
            }
        }
    }

    private static int assets(Path artifacts, String version, Path pom, String mode, PrintStream out, PrintStream err) {
        var found = present(artifacts);
        String jarVersion = pomVersion(pom);
        var missing = missingAssets(artifacts, version, jarVersion);
        for (String name : expectedAssets(version, jarVersion)) {
            out.println("  " + (missing.contains(name) ? "MISSING" : "ok     ") + "  " + name);
        }
        for (String name : optionalAssets(version)) {
            out.println("  " + (found.contains(name) ? "ok     " : "absent ") + "  " + name + " (experimental, optional)");
        }
        if (missing.isEmpty()) {
            out.println("all expected release assets are present");
            return 0;
        }
        String level = mode.equals("enforce") ? "error" : "warning";
        out.println("::" + level + " title=Release assets missing::" + missing.size() + " expected asset(s) missing: "
                + String.join(", ", missing));
        if (mode.equals("enforce")) {
            err.println("refusing to publish: a GitHub release is immutable, so these could never be added later.");
            return 1;
        }
        return 0;
    }

    private static int usage(PrintStream err, String problem) {
        err.println("usage: java scripts/release/CheckRelease.java {version,assets,ci} ...");
        err.println("error: " + problem);
        return 2;
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}

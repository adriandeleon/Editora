package com.editora.github;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.diff.PatchParser;
import com.editora.process.ProcessRunner;

/**
 * The native-{@code gh} facade — a structural clone of {@code GitService}. Every GitHub command shells out via
 * {@link ProcessRunner} on a single daemon executor thread and posts results back on the JavaFX thread, so the
 * UI thread is never blocked. Availability ({@code gh} on PATH + {@code gh auth status} succeeding) is probed
 * once and cached; list refreshes carry a generation guard so a stale background result is dropped.
 *
 * <p>Every editor read (owner/repo resolution) is delegated to {@code gh} itself: commands run with a
 * working directory inside the repo, so {@code gh} resolves the host + owner/repo from the git remote — no
 * host config lives in Editora. Security: {@code gh} holds the user's GitHub credentials; Editora never sees,
 * stores, or transmits a token — it only shells out, exactly like {@code GitService} does for {@code git}.
 */
public final class GitHubService {

    private static final Duration QUICK = Duration.ofSeconds(10);
    private static final Duration NETWORK = Duration.ofSeconds(120);

    /**
     * The child environment for every {@code gh} call: never prompt, never page, never colorize, never phone
     * home for update notifications — so output parses cleanly and nothing can block waiting for a TTY.
     * ({@link ProcessRunner} closes the child's stdin, so a would-be prompt EOFs immediately.)
     */
    private static final Map<String, String> GH_ENV = Map.of(
            "GH_PROMPT_DISABLED", "1",
            "GH_NO_UPDATE_NOTIFIER", "1",
            "GH_PAGER", "cat",
            "CLICOLOR", "0",
            "NO_COLOR", "1");

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "github-service");
        t.setDaemon(true);
        return t;
    });

    /** The configured {@code gh} command tokens (default {@code ["gh"]}); a blank override resets to gh. */
    private volatile List<String> command = List.of("gh");

    /** null = not yet probed; cached after the first {@link #detect}. */
    private volatile Availability availability;

    private final AtomicLong listGen = new AtomicLong();

    /** Whether {@code gh} is on PATH, whether it reports an authenticated host, and its version line. */
    public record Availability(boolean found, boolean authenticated, String version) {
        public static final Availability UNKNOWN = new Availability(false, false, "");

        /** Both present and authenticated — GitHub commands can actually run. */
        public boolean ready() {
            return found && authenticated;
        }
    }

    /** Sets the {@code gh} command/path (whitespace-tokenized); blank ⇒ resolve {@code gh} on PATH. */
    public void setCommand(String configured) {
        if (configured == null || configured.isBlank()) {
            command = List.of("gh");
        } else {
            List<String> tokens = new ArrayList<>();
            for (String t : configured.strip().split("\\s+")) {
                if (!t.isBlank()) {
                    tokens.add(t);
                }
            }
            command = tokens.isEmpty() ? List.of("gh") : List.copyOf(tokens);
        }
        availability = null; // re-probe with the new command
    }

    /** The last probed availability (may be {@code null} before the first {@link #detect}). */
    public Availability availability() {
        return availability;
    }

    // --- detection -------------------------------------------------------------------------------

    /**
     * Probes {@code gh --version} + {@code gh auth status} off the FX thread and posts an {@link Availability}
     * on the FX thread (also caching it). {@code gh auth status} is gated on the exit code only — its human
     * text has moved between stdout/stderr across gh versions, so it must never be parsed.
     */
    public void detect(Consumer<Availability> onResult) {
        exec.submit(() -> {
            boolean found = false;
            boolean auth = false;
            String version = "";
            try {
                ProcessRunner.Result v = gh(null, QUICK, "--version");
                found = v.ok();
                if (found) {
                    version = firstLine(v.out());
                }
            } catch (RuntimeException ignored) {
                // gh not launchable → not found
            }
            if (found) {
                try {
                    auth = gh(null, QUICK, "auth", "status").ok();
                } catch (RuntimeException ignored) {
                    // treat a failed probe as unauthenticated
                }
            }
            Availability a = new Availability(found, auth, version);
            availability = a;
            Platform.runLater(() -> onResult.accept(a));
        });
    }

    // --- pull requests ---------------------------------------------------------------------------

    /** Result of a PR list: {@code ok} distinguishes a failed {@code gh} call from a genuinely empty list. */
    public record PrListResult(boolean ok, List<PrListParser.PullRequest> prs, String error) {}

    /** Lists open PRs ({@code gh pr list --json …}); generation-guarded so a stale refresh is dropped. */
    public void prList(Path dir, Consumer<PrListResult> onResult) {
        long gen = listGen.incrementAndGet();
        exec.submit(() -> {
            ProcessRunner.Result r = gh(
                    dir,
                    NETWORK,
                    "pr",
                    "list",
                    "--limit",
                    "50",
                    "--json",
                    "number,title,author,headRefName,baseRefName,state,isDraft,updatedAt,url");
            PrListResult res = r.ok()
                    ? new PrListResult(true, PrListParser.parse(r.out()), "")
                    : new PrListResult(false, List.of(), r.message());
            if (gen == listGen.get()) {
                Platform.runLater(() -> onResult.accept(res));
            }
        });
    }

    /** A PR's detail ({@code gh pr view <n> --json …}); posts {@code null} on failure. */
    public void prView(Path dir, int number, Consumer<PrViewParser.PrDetail> onResult) {
        exec.submit(() -> {
            ProcessRunner.Result r = gh(
                    dir,
                    NETWORK,
                    "pr",
                    "view",
                    String.valueOf(number),
                    "--json",
                    "number,title,body,author,baseRefName,headRefName,state,url,additions,deletions");
            PrViewParser.PrDetail d = r.ok() ? PrViewParser.parse(r.out()) : null;
            Platform.runLater(() -> onResult.accept(d));
        });
    }

    /** Result of a PR diff: the parsed per-file patches, or an error message. */
    public record DiffResult(boolean ok, List<PatchParser.FilePatch> files, String error) {}

    /** Fetches a PR's whole unified diff ({@code gh pr diff <n>}) and parses it off-thread into file patches. */
    public void prDiff(Path dir, int number, Consumer<DiffResult> onResult) {
        exec.submit(() -> {
            ProcessRunner.Result r = gh(dir, NETWORK, "pr", "diff", String.valueOf(number));
            DiffResult res = r.ok()
                    ? new DiffResult(true, PatchParser.parse(r.out()), "")
                    : new DiffResult(false, List.of(), r.message());
            Platform.runLater(() -> onResult.accept(res));
        });
    }

    /** Checks out a PR branch ({@code gh pr checkout <n>}); posts the raw result for status/error reporting. */
    public void prCheckout(Path dir, int number, Consumer<ProcessRunner.Result> onResult) {
        run(dir, NETWORK, onResult, "pr", "checkout", String.valueOf(number));
    }

    /** Creates a PR ({@code gh pr create …}); posts the raw result (the created PR URL is on stdout). */
    public void prCreate(Path dir, List<String> ghArgs, Consumer<ProcessRunner.Result> onResult) {
        run(dir, NETWORK, onResult, ghArgs.toArray(new String[0]));
    }

    /** Result of a PR checks query: the runs, or an error. Parsed regardless of exit code (see {@link ChecksParser}). */
    public record ChecksResult(boolean ok, List<ChecksParser.CheckRun> runs, String error) {}

    /**
     * A PR's CI checks ({@code gh pr checks --json …}); the JSON is parsed even on a non-zero exit. A
     * {@code number <= 0} omits the PR argument, so {@code gh} resolves the checks for the current branch's PR.
     */
    public void prChecks(Path dir, int number, Consumer<ChecksResult> onResult) {
        exec.submit(() -> {
            List<String> args = new ArrayList<>(List.of("pr", "checks"));
            if (number > 0) {
                args.add(String.valueOf(number));
            }
            args.add("--json");
            args.add("name,state,bucket,link,workflow");
            ProcessRunner.Result r = gh(dir, NETWORK, args.toArray(new String[0]));
            // gh pr checks exits 1 (failing) / 8 (pending) with the JSON still on stdout — parse it regardless.
            List<ChecksParser.CheckRun> runs = ChecksParser.parse(r.out());
            ChecksResult res = !runs.isEmpty() || r.ok()
                    ? new ChecksResult(true, runs, "")
                    : new ChecksResult(false, List.of(), r.message());
            Platform.runLater(() -> onResult.accept(res));
        });
    }

    // --- issues ----------------------------------------------------------------------------------

    /** Result of an issue list. */
    public record IssueListResult(boolean ok, List<IssueListParser.Issue> issues, String error) {}

    /** Lists open issues ({@code gh issue list --json …}); generation-guarded. */
    public void issueList(Path dir, Consumer<IssueListResult> onResult) {
        long gen = listGen.incrementAndGet();
        exec.submit(() -> {
            ProcessRunner.Result r = gh(
                    dir,
                    NETWORK,
                    "issue",
                    "list",
                    "--limit",
                    "50",
                    "--json",
                    "number,title,author,state,labels,updatedAt,url");
            IssueListResult res = r.ok()
                    ? new IssueListResult(true, IssueListParser.parse(r.out()), "")
                    : new IssueListResult(false, List.of(), r.message());
            if (gen == listGen.get()) {
                Platform.runLater(() -> onResult.accept(res));
            }
        });
    }

    // --- open on github --------------------------------------------------------------------------

    /**
     * Resolves the canonical GitHub URL for {@code repoRel} at {@code line} on {@code branch} via
     * {@code gh browse --no-browser <rel>:<line> --branch <branch>} (which prints the URL to stdout). Routing
     * through gh gets GHES hosts, renamed default branches, and permalink escaping right for free.
     */
    public void browse(Path dir, String repoRelWithLine, String branch, Consumer<ProcessRunner.Result> onResult) {
        List<String> args = new ArrayList<>(List.of("browse", "--no-browser", repoRelWithLine));
        if (branch != null && !branch.isBlank()) {
            args.add("--branch");
            args.add(branch);
        }
        run(dir, QUICK, onResult, args.toArray(new String[0]));
    }

    // --- internals -------------------------------------------------------------------------------

    private void run(Path dir, Duration timeout, Consumer<ProcessRunner.Result> onResult, String... args) {
        exec.submit(() -> {
            ProcessRunner.Result r = gh(dir, timeout, args);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    private ProcessRunner.Result gh(Path dir, Duration timeout, String... args) {
        List<String> cmd = new ArrayList<>(command);
        for (String a : args) {
            cmd.add(a);
        }
        return ProcessRunner.run(dir, timeout, cmd, GH_ENV);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).strip();
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}

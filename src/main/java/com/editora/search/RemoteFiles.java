package com.editora.search;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;

/**
 * Asking a remote host about the files of a folder it holds, instead of walking the folder over SFTP one
 * directory listing at a time: its own {@code rg} lists them, else its {@code git}, in one command each.
 * Blocking; never on the FX thread.
 */
public final class RemoteFiles {

    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    /** How long "this host has no rg" is believed before it is asked again (it may be installed meanwhile). */
    private static final long ABSENT_TTL_NANOS = Duration.ofMinutes(5).toNanos();

    private record Probe(boolean present, long atNanos) {}

    /** By host id and command: whether {@code rg} answers there. */
    private static final Map<String, Probe> RIPGREP = new ConcurrentHashMap<>();

    private RemoteFiles() {}

    /** The ripgrep command for the host {@code root} is on: its configured {@code rg} tool, else {@code rg}. */
    public static List<String> ripgrepCommand(Path root) {
        String configured = ExecTargets.remoteTool(root, "rg");
        return configured == null ? List.of(Ripgrep.DEFAULT_COMMAND) : Ripgrep.command(configured);
    }

    /**
     * Whether ripgrep can be used for {@code root} on its host: the host can run commands and its {@code rg}
     * answers {@code --version}. An exit status of 0 is required, not merely a launch: on a host the command
     * is started by a shell, which reports a missing program as 127.
     */
    public static boolean hasRipgrep(Path root) {
        var target = ExecTargets.forPath(root);
        if (target.isLocal() || !target.isAvailable()) {
            return false;
        }
        List<String> cmd = ripgrepCommand(root);
        String key = target.id() + "\u0000" + String.join(" ", cmd);
        Probe known = RIPGREP.get(key);
        long now = System.nanoTime();
        if (known != null && (known.present() || now - known.atNanos() < ABSENT_TTL_NANOS)) {
            return known.present();
        }
        List<String> argv = new ArrayList<>(cmd);
        argv.add("--version");
        boolean present = ProcessRunner.run(root.toAbsolutePath().getRoot(), PROBE_TIMEOUT, argv)
                .ok();
        RIPGREP.put(key, new Probe(present, now));
        return present;
    }

    /**
     * The files under {@code root}, relative to it with {@code /} separators, as its host lists them: by
     * {@code rg --files}, else {@code git ls-files} (inside a repository), honouring {@code .gitignore} when
     * {@code respectGitignore}. Stops at {@code max} and says so. {@code null} when neither could list.
     */
    public static Listing list(Path root, boolean respectGitignore, int max) {
        if (hasRipgrep(root)) {
            List<String> argv = new ArrayList<>(ripgrepCommand(root));
            argv.add("--files");
            if (!respectGitignore) {
                argv.add("--no-ignore");
            }
            Listing listed = run(root, argv, max, '\n');
            if (listed != null) {
                return listed;
            }
        }
        List<String> git = new ArrayList<>(List.of("git", "ls-files", "-z", "--cached", "--others"));
        if (respectGitignore) {
            git.add("--exclude-standard");
        }
        return run(root, git, max, '\0');
    }

    /** What a host listed, and whether {@code max} cut it short. */
    public record Listing(List<String> paths, boolean truncated) {}

    private static Listing run(Path root, List<String> argv, int max, char separator) {
        List<String> paths = new ArrayList<>();
        boolean[] truncated = {false};
        ProcessRunner.Result r;
        if (separator == '\n') {
            r = ProcessRunner.runLines(root, LIST_TIMEOUT, argv, line -> {
                if (!line.isEmpty()) {
                    paths.add(line);
                }
                truncated[0] = paths.size() > max;
                return truncated[0];
            });
        } else {
            r = ProcessRunner.run(root, LIST_TIMEOUT, argv);
            for (String path : r.out().split(String.valueOf(separator))) {
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            }
            truncated[0] = paths.size() > max || r.outTruncated();
        }
        if (!truncated[0] && !r.ok()) {
            return null;
        }
        List<String> kept = paths.size() > max ? paths.subList(0, max) : paths;
        return new Listing(List.copyOf(kept), truncated[0]);
    }
}

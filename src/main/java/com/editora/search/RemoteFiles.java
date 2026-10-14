package com.editora.search;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;

/**
 * Asking a remote host about the files of a folder it holds, instead of walking the folder over SFTP one
 * directory listing at a time: its own {@code rg} lists them, else its {@code git}, else its {@code find}, in one
 * command each. Blocking; never on the FX thread.
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
     * {@code rg --files}, else {@code git ls-files} (inside a repository), else {@code find}, honouring
     * {@code .gitignore} when {@code respectGitignore}. Stops at {@code max} and says so. {@code null} when
     * none of them could list (the host cannot run commands).
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
        Listing listed = run(root, git, max, '\0');
        return listed != null ? listed : find(root, respectGitignore, max);
    }

    /**
     * Upper bound on the lines {@code find} may print before it is stopped: what it lists is filtered here, so it
     * may print more than {@code max} that are then dropped; past this many the listing is called cut.
     */
    static int findCeiling(int max) {
        return (int) Math.min(Integer.MAX_VALUE, (long) max * 4 + 1_000);
    }

    /**
     * The last lister, for a folder that is not a Git repository on a host without {@code rg}: every host has a
     * POSIX {@code find}. It applies the same rules as the walk of a local project: hidden folders are not
     * entered and hidden files are not listed, and with {@code respectGitignore} the {@code .gitignore} files
     * of the folder and of each folder below it are honoured (only those {@code find} listed are read, so this
     * costs a read per such file, not a request per folder). {@code node_modules} is not entered while
     * ignoring: it is ignored in nearly every project, and listing it first would fill the ceiling.
     */
    private static Listing find(Path root, boolean respectGitignore, int max) {
        // POSIX find only (no -mindepth, no -print0): `! -name .` keeps the start folder from matching ".*".
        List<String> argv = new ArrayList<>(List.of("find", ".", "-type", "d", "!", "-name", ".", "("));
        argv.addAll(List.of("-name", ".*"));
        if (respectGitignore) {
            argv.addAll(List.of("-o", "-name", "node_modules"));
        }
        argv.addAll(List.of(")", "-prune", "-o", "-type", "f", "-print"));
        int ceiling = findCeiling(max);
        List<String> printed = new ArrayList<>();
        boolean[] cut = {false};
        ProcessRunner.Result r = ProcessRunner.runLines(root, LIST_TIMEOUT, argv, line -> {
            String rel = line.startsWith("./") ? line.substring(2) : line;
            if (!rel.isEmpty()) {
                printed.add(rel);
            }
            cut[0] = printed.size() >= ceiling;
            return cut[0];
        });
        // find exits 1 when some folder could not be read, after listing the rest: that is still a listing.
        if (!cut[0] && !r.ok() && printed.isEmpty()) {
            return null;
        }
        List<String> kept = filtered(printed, respectGitignore ? root : null);
        boolean truncated = cut[0] || kept.size() > max;
        return new Listing(List.copyOf(kept.size() > max ? kept.subList(0, max) : kept), truncated);
    }

    /**
     * What a local project walk would keep of {@code printed} (root-relative file paths): no hidden files, none
     * inside a hidden folder, and with a {@code root} to read them from, none that a {@code .gitignore} of the
     * folder or of a folder on the way down excludes. Sorted, as a walk delivers them.
     */
    static List<String> filtered(List<String> printed, Path root) {
        Set<String> withRules = new HashSet<>();
        for (String rel : printed) {
            if (rel.equals(".gitignore") || rel.endsWith("/.gitignore")) {
                withRules.add(rel.length() == ".gitignore".length() ? "" : rel.substring(0, rel.length() - 11));
            }
        }
        Map<String, GitignoreFilter> byDir = new HashMap<>();
        byDir.put("", root == null ? GitignoreFilter.NONE : GitignoreFilter.load(root));
        Map<String, Boolean> dirKept = new HashMap<>();
        List<String> kept = new ArrayList<>();
        for (String rel : printed.stream().sorted().toList()) {
            int slash = rel.lastIndexOf('/');
            String dir = slash < 0 ? "" : rel.substring(0, slash);
            String name = rel.substring(slash + 1);
            if (name.startsWith(".") || !dirKept(dir, root, withRules, byDir, dirKept)) {
                continue;
            }
            if (!filterFor(dir, root, withRules, byDir).ignored(rel, false)) {
                kept.add(rel);
            }
        }
        return kept;
    }

    /** Whether nothing on the way down to {@code dir} is hidden or ignored. */
    private static boolean dirKept(
            String dir,
            Path root,
            Set<String> withRules,
            Map<String, GitignoreFilter> byDir,
            Map<String, Boolean> memo) {
        if (dir.isEmpty()) {
            return true;
        }
        Boolean known = memo.get(dir);
        if (known != null) {
            return known;
        }
        int slash = dir.lastIndexOf('/');
        String parent = slash < 0 ? "" : dir.substring(0, slash);
        boolean kept = dirKept(parent, root, withRules, byDir, memo)
                && !dir.substring(slash + 1).startsWith(".")
                && !filterFor(parent, root, withRules, byDir).ignored(dir, true);
        memo.put(dir, kept);
        return kept;
    }

    /** The rules in force inside {@code dir}: its parent's, plus its own {@code .gitignore} when it has one. */
    private static GitignoreFilter filterFor(
            String dir, Path root, Set<String> withRules, Map<String, GitignoreFilter> byDir) {
        GitignoreFilter known = byDir.get(dir);
        if (known != null) {
            return known;
        }
        int slash = dir.lastIndexOf('/');
        GitignoreFilter parent = filterFor(slash < 0 ? "" : dir.substring(0, slash), root, withRules, byDir);
        GitignoreFilter here = root != null && parent.nests() && withRules.contains(dir)
                ? parent.nested(root.resolve(dir), dir)
                : parent;
        byDir.put(dir, here);
        return here;
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

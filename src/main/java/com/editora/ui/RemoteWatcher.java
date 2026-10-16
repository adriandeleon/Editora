package com.editora.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.process.ExecSpec;
import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;
import com.editora.process.ProcessRegistry;

/**
 * Watches a folder on a remote host for changes made by something other than Editora (a {@code git pull} in a
 * terminal there, a build writing files) — what a {@link java.nio.file.WatchService} does for a local folder,
 * which SFTP cannot. One long-lived command on the host reports changed paths, one per line: {@code inotifywait}
 * when the host has it, else a poll with {@code find -newer} every few seconds (a directory whose entries
 * changed is reported as itself, which is how a deletion shows). Lines are gathered into short batches.
 */
final class RemoteWatcher {

    private static final Logger LOG = Logger.getLogger(RemoteWatcher.class.getName());

    /** How long lines are gathered before a batch is handed on: a checkout reports hundreds at once. */
    static final long BATCH_MS = 300;

    /** Seconds between two polls on a host without inotifywait. */
    static final int POLL_SECONDS = 4;

    /**
     * The command run on the host; {@code $1} is the folder. Each line is {@code KIND|path} with an absolute
     * path. {@code .git} and {@code node_modules} are left out: their churn is not something a user is
     * waiting to see, and the Git refresh learns about a checkout from the files it changes.
     */
    static final String SCRIPT =
            """
            root=$1
            if [ -z "$EDITORA_WATCH_POLL" ] && command -v inotifywait >/dev/null 2>&1; then
              exec inotifywait -m -r -q --format '%e|%w%f' \\
                -e create -e delete -e modify -e moved_from -e moved_to -e close_write \\
                --exclude '/(\\.git|node_modules)(/|$)' "$root"
            fi
            stamp=$(mktemp "${TMPDIR:-/tmp}/editora-watch.XXXXXX") || exit 1
            next="$stamp.next"
            trap 'rm -f "$stamp" "$next"' EXIT
            while :; do
              sleep @POLL@
              : > "$next"
              find -H "$root" \\( -name .git -o -name node_modules \\) -prune -o -newer "$stamp" -print \\
                | sed 's/^/CHANGED|/'
              mv -f "$next" "$stamp"
            done
            """.replace("@POLL@", String.valueOf(POLL_SECONDS)); // not formatted(): %e %w %f are inotify's

    /** TEST SEAM: watch by polling even on a host that has inotifywait (the fallback is otherwise untestable). */
    static volatile boolean pollForTest;

    private final Process process;
    private volatile boolean stopped;

    private RemoteWatcher(Process process) {
        this.process = process;
    }

    /**
     * Starts watching {@code root} on its host, handing each batch to {@code onChanges} on the reader thread.
     * Null when the host cannot run commands now (the watch is then simply absent, as before).
     */
    static RemoteWatcher start(Path root, Consumer<List<ProjectPanel.FsChange>> onChanges) {
        ExecTarget target = ExecTargets.forPath(root);
        if (target.isLocal() || !target.isAvailable()) {
            return null;
        }
        Process process;
        try {
            process = target.start(ExecSpec.of(
                            List.of("sh", "-c", SCRIPT, "editora-watch", target.toTargetPath(root)),
                            null,
                            pollForTest ? Map.of("EDITORA_WATCH_POLL", "1") : Map.of(),
                            ExecSpec.Locale.PARSE_STABLE)
                    .withLongLived(true) // ended with the connection, like everything else started there
                    .withDiscardedOutput(false, true));
        } catch (IOException e) {
            LOG.log(Level.FINE, "remote watch could not start", e);
            return null;
        }
        ProcessRegistry.track(process);
        RemoteWatcher watcher = new RemoteWatcher(process);
        Thread reader = new Thread(() -> watcher.read(target, onChanges), "remote-fs-watch");
        reader.setDaemon(true);
        reader.start();
        return watcher;
    }

    void stop() {
        stopped = true;
        ProcessRegistry.killTree(process);
    }

    boolean isRunning() {
        return !stopped && process.isAlive();
    }

    private void read(ExecTarget target, Consumer<List<ProjectPanel.FsChange>> onChanges) {
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!stopped && (line = in.readLine()) != null) {
                List<ProjectPanel.FsChange> batch = new ArrayList<>();
                add(batch, parse(target, line));
                // Whatever follows within BATCH_MS joins this batch: a checkout reports hundreds of paths.
                boolean open = true;
                while (open && !stopped) {
                    if (in.ready()) {
                        String next = in.readLine();
                        open = next != null;
                        if (open) {
                            add(batch, parse(target, next));
                        }
                    } else {
                        Thread.sleep(BATCH_MS);
                        open = in.ready();
                    }
                }
                if (!batch.isEmpty() && !stopped) {
                    onChanges.accept(List.copyOf(batch));
                }
            }
        } catch (IOException | InterruptedException ended) {
            // the command or the connection ended: no more changes from there
        }
    }

    private static void add(List<ProjectPanel.FsChange> batch, ProjectPanel.FsChange change) {
        if (change != null) {
            batch.add(change);
        }
    }

    /** One line of the host's report as a change, or null for a line that is not one. Pure. */
    static ProjectPanel.FsChange parse(ExecTarget target, String line) {
        int bar = line.indexOf('|');
        if (bar <= 0 || bar == line.length() - 1) {
            return null;
        }
        String events = line.substring(0, bar);
        String path = line.substring(bar + 1);
        if (!path.startsWith("/") || ProjectWatchChanges.isInternalName(name(path))) {
            return null;
        }
        return new ProjectPanel.FsChange(target.toPath(path), kind(events));
    }

    private static String name(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** inotifywait's event names ({@code CREATE,ISDIR}, {@code MOVED_TO}, …) as the tree's kinds. */
    static ProjectPanel.FsKind kind(String events) {
        for (String event : events.split(",")) {
            switch (event) {
                case "CREATE", "MOVED_TO" -> {
                    return ProjectPanel.FsKind.CREATED;
                }
                case "DELETE", "MOVED_FROM" -> {
                    return ProjectPanel.FsKind.DELETED;
                }
                default -> {
                    // CHANGED, MODIFY, CLOSE_WRITE, ISDIR: read on
                }
            }
        }
        return ProjectPanel.FsKind.CHANGED;
    }
}

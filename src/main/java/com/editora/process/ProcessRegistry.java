package com.editora.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central registry of long-lived <em>managed external processes</em> — the LSP language servers and
 * DAP debug adapters Editora spawns. It exists so those servers never outlive the app:
 *
 * <ol>
 *   <li><b>JVM shutdown hook</b> ({@link #installShutdownHook}) force-kills every live process tree on
 *       exit. A shutdown hook runs on a normal quit <em>and on SIGTERM</em> (a plain {@code kill}, the OS
 *       asking the app to quit, most crashes) — so the close-handler teardown is no longer the only path
 *       that reaps servers. (Nothing can catch SIGKILL / {@code kill -9}; that's what the ledger below
 *       is for.)
 *   <li><b>Escalating kill</b> ({@link #killTree}) — destroy the descendant tree (SIGTERM, children
 *       first so a wrapper script like {@code jdtls → python → java} can't orphan the real server), then,
 *       after a grace period, force-kill anything still alive. Non-blocking, so it's safe to call from the
 *       FX thread during a window close.
 *   <li><b>Startup reaping</b> ({@link #reapOrphans}) — a small on-disk ledger of spawned root PIDs (with
 *       each process's start time + executable, to be safe against PID reuse) lets a <em>fresh</em> launch
 *       kill any server leaked by a previous run that died hard (SIGKILL / power loss), and clears the
 *       stale entries.
 * </ol>
 *
 * <p>Only processes that genuinely outlive their spawn call need tracking (servers/adapters); short-lived
 * probes that are started and immediately destroyed do not. Uses only {@code java.base}
 * ({@link Process}/{@link ProcessHandle}) — no dependency, no {@code module-info} change.
 */
public final class ProcessRegistry {

    private static final Logger LOG = Logger.getLogger(ProcessRegistry.class.getName());

    /** Grace period after SIGTERM before a still-alive tree is force-killed (see {@link #killTree}). */
    static final long GRACE_MS = 1500;

    /** Live tracked roots (the {@link Process} we started). */
    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();

    /** Metadata for the on-disk ledger, keyed by pid (so a crash-leaked server can be reaped next run). */
    private static final ConcurrentHashMap<Long, LedgerEntry> LEDGER = new ConcurrentHashMap<>();

    private static final Object LEDGER_FILE_LOCK = new Object();
    private static volatile Path ledgerFile;

    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean();

    /** A single daemon scheduler for the delayed force-kill in {@link #killTree}. */
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "editora-process-reaper");
        t.setDaemon(true);
        return t;
    });

    private ProcessRegistry() {}

    /**
     * Registers the JVM shutdown hook that force-kills every live tracked process tree on exit. Idempotent;
     * call once from {@code App.main}. Cheap and safe even on the {@code --help}/{@code --version} paths
     * (nothing is tracked there, so the hook is a no-op).
     */
    public static void installShutdownHook() {
        if (HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(ProcessRegistry::killAll, "editora-shutdown-reaper"));
        }
    }

    /** Points the ledger at {@code <configDir>/spawned-servers.txt}. Call once at startup before any spawn. */
    public static void setLedgerFile(Path file) {
        ledgerFile = file;
    }

    /**
     * Tracks a freshly-started long-lived process: it joins the live set (for the shutdown hook), is
     * recorded in the ledger (for next-run reaping), and is auto-removed when it exits on its own.
     */
    public static void track(Process p) {
        if (p == null) {
            return;
        }
        LIVE.add(p);
        LedgerEntry entry = LedgerEntry.of(p);
        LEDGER.put(p.pid(), entry);
        writeLedger();
        // Self-clean if the process dies on its own (crash, server self-exit) so we don't try to kill a
        // reused PID later, and the live set stays accurate.
        p.onExit().thenRun(() -> untrack(p));
    }

    /** Stops tracking a process (called after it's been killed, or when it exits). */
    public static void untrack(Process p) {
        if (p == null) {
            return;
        }
        LIVE.remove(p);
        if (LEDGER.remove(p.pid()) != null) {
            writeLedger();
        }
    }

    /**
     * Kills a process tree with escalation, then untracks it. Sends SIGTERM to the descendants (children
     * first) and the root now, then schedules a force-kill of any survivor after {@link #GRACE_MS}.
     * <b>Non-blocking</b> — safe on the FX thread. If the process exits from the SIGTERM, the scheduled
     * step is a harmless no-op; if the JVM exits before it runs, the shutdown hook force-kills it.
     */
    public static void killTree(Process p) {
        if (p == null) {
            return;
        }
        destroyTree(p, false);
        try {
            SCHEDULER.schedule(
                    () -> {
                        if (p.isAlive() || p.descendants().findAny().isPresent()) {
                            destroyTree(p, true);
                        }
                        untrack(p);
                    },
                    GRACE_MS,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            // Scheduler rejected (JVM shutting down): force-kill now and move on.
            destroyTree(p, true);
            untrack(p);
        }
    }

    /** SIGTERM (force=false) or SIGKILL (force=true) the whole tree, children before the root so a wrapper
     *  script can't reparent-orphan its real child. Snapshots descendants before touching the root. */
    private static void destroyTree(Process p, boolean force) {
        try {
            List<ProcessHandle> tree = p.descendants().toList();
            for (ProcessHandle h : tree) {
                if (force) {
                    h.destroyForcibly();
                } else {
                    h.destroy();
                }
            }
            if (force) {
                p.destroyForcibly();
            } else {
                p.destroy();
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** The shutdown hook: force-kill every live tree. Synchronous (the JVM is exiting) and fast. */
    private static void killAll() {
        for (Process p : LIVE) {
            destroyTree(p, true);
        }
        LIVE.clear();
        // The killed PIDs are now dead, so the next launch's reapOrphans would no-op on them anyway; still,
        // clear the ledger we can account for (a hard crash that skips this hook is what reaping handles).
        LEDGER.clear();
        writeLedger();
    }

    /**
     * Reaps any server leaked by a previous run that died too hard for the shutdown hook to run (SIGKILL,
     * power loss). Reads the ledger, and for each recorded pid still alive whose start time + executable
     * still match what we recorded (guarding against PID reuse killing an innocent process), force-kills its
     * tree. Then rewrites the ledger empty. Call once at startup, before any new server starts.
     */
    public static void reapOrphans() {
        Path file = ledgerFile;
        if (file == null) {
            return;
        }
        List<LedgerEntry> entries;
        synchronized (LEDGER_FILE_LOCK) {
            if (!Files.exists(file)) {
                return;
            }
            try {
                entries = parseLedger(Files.readAllLines(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not read process ledger", e);
                return;
            }
        }
        int reaped = 0;
        for (LedgerEntry e : entries) {
            Optional<ProcessHandle> handle = ProcessHandle.of(e.pid());
            if (handle.isEmpty()) {
                continue; // already gone
            }
            ProcessHandle h = handle.get();
            ProcessHandle.Info info = h.info();
            boolean reap = h.isAlive() && shouldReap(e, info.startInstant().map(Instant::toEpochMilli), info.command());
            if (reap) {
                h.descendants().forEach(ProcessHandle::destroyForcibly);
                h.destroyForcibly();
                reaped++;
            }
        }
        if (reaped > 0) {
            LOG.info("Reaped " + reaped + " orphaned language/debug server process(es) from a previous run");
        }
        // Clear the ledger: survivors were killed, dead ones are irrelevant. This run repopulates it.
        synchronized (LEDGER_FILE_LOCK) {
            try {
                Files.write(file, List.of(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    // --- pure, unit-tested helpers -------------------------------------------------------------------

    /** A ledger row: the spawned root pid plus enough identity to avoid killing a reused PID next run. */
    record LedgerEntry(long pid, long startEpochMillis, String command) {

        static LedgerEntry of(Process p) {
            ProcessHandle.Info info = p.info();
            long start = info.startInstant().map(Instant::toEpochMilli).orElse(0L);
            String cmd = info.command().orElse("");
            return new LedgerEntry(p.pid(), start, cmd);
        }

        /** Tab-separated: {@code pid \t startEpochMillis \t command} (command is last so tabs in it are
         *  impossible — an executable path has no tabs — but we still strip any defensively). */
        String format() {
            return pid + "\t" + startEpochMillis + "\t"
                    + command.replace('\t', ' ').replace('\n', ' ');
        }

        static Optional<LedgerEntry> parse(String line) {
            if (line == null || line.isBlank()) {
                return Optional.empty();
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 2) {
                return Optional.empty();
            }
            try {
                long pid = Long.parseLong(parts[0].trim());
                long start = Long.parseLong(parts[1].trim());
                String cmd = parts.length == 3 ? parts[2] : "";
                return Optional.of(new LedgerEntry(pid, start, cmd));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    static List<LedgerEntry> parseLedger(List<String> lines) {
        List<LedgerEntry> out = new ArrayList<>();
        for (String line : lines) {
            LedgerEntry.parse(line).ifPresent(out::add);
        }
        return out;
    }

    /**
     * Whether a still-alive process with the given actual identity is the same server we recorded — i.e.
     * safe to reap. Requires the executable to match, and (when both the recorded and actual start times
     * are known) the start instants to be equal. Conservative: if we recorded a start time but the live
     * process reports none, we don't reap (can't prove identity, so leave it alone). Pure → unit-tested.
     */
    static boolean shouldReap(LedgerEntry recorded, Optional<Long> actualStartMillis, Optional<String> actualCommand) {
        // Executable must match (and be non-blank); this alone rejects an unrelated reused PID.
        if (recorded.command().isBlank()
                || actualCommand.isEmpty()
                || !recorded.command().equals(actualCommand.get())) {
            return false;
        }
        // If we recorded a real start time, the live process must report the same one.
        if (recorded.startEpochMillis() > 0) {
            return actualStartMillis.isPresent() && actualStartMillis.get() == recorded.startEpochMillis();
        }
        // No recorded start time (rare — the OS didn't report one at spawn): command match is all we have.
        return true;
    }

    private static void writeLedger() {
        Path file = ledgerFile;
        if (file == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (LedgerEntry e : LEDGER.values()) {
            lines.add(e.format());
        }
        synchronized (LEDGER_FILE_LOCK) {
            try {
                Files.write(file, lines, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // best effort — the shutdown hook + in-memory live set still cover the common cases
            }
        }
    }
}

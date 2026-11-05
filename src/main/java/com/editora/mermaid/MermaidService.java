package com.editora.mermaid;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * UI-facing façade for the Mermaid CLIs, mirroring {@code GitService}: work runs on a single daemon
 * executor and results are posted back on the JavaFX thread via {@link Platform#runLater}. Owns the
 * configured executable paths (mmdc/maid) and a cached availability probe; used by {@code MainController}
 * for the export command and the Settings tool-detection status. Diagram <em>preview</em> rendering
 * lives separately in {@code editor/MermaidImages} (with its own cache).
 */
public final class MermaidService {

    /**
     * Which CLIs are present (resolved from the configured paths or PATH).
     *
     * @param maidInstall where the default {@code npx -y @probelabs/maid} found maid installed (null when it did
     *     not, or when the maid command is the user's own and was probed by running it)
     */
    public record Availability(boolean mmdc, boolean maid, MaidLocator.@Nullable Found maidInstall) {
        public Availability(boolean mmdc, boolean maid) {
            this(mmdc, maid, null);
        }
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mermaid-service");
        t.setDaemon(true);
        return t;
    });

    /** Default commands when the user leaves the path blank: mmdc as a bare binary, maid via npx. */
    public static final String DEFAULT_MMDC = "mmdc";

    public static final String DEFAULT_MAID = "npx -y @probelabs/maid";

    private final java.util.concurrent.atomic.AtomicLong validateGen = new java.util.concurrent.atomic.AtomicLong();
    /** The latest validate request of each requester; weak keys, so a closed buffer is not kept alive. */
    private final java.util.Map<Object, Long> latestValidate =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private volatile String mmdcPath = "";
    private volatile String maidPath = "";
    private volatile String nodePath = "";
    private volatile @Nullable Availability cached;
    /** Finds an installed maid for the default npx command by its files (the Settings Node.js in, files only). */
    private volatile Function<String, Optional<MaidLocator.Found>> maidLocator = MaidLocator::locateLocal;

    /** Forgets the cached availability, so the next {@link #detect} probes again (Settings' Re-check). */
    public void invalidate() {
        this.cached = null;
    }

    /**
     * Updates the configured executable paths/commands. Clears the cached availability <b>only when they
     * actually change</b>, so the next detect re-probes.
     *
     * <p>Clearing unconditionally meant every settings apply re-probed — and Settings is live-apply, so that
     * is once per control you touch, plus every theme apply. A probe runs mmdc (and a maid command of the user's
     * own) for seconds, on the same single thread that serves live {@code .mmd} linting and exports: toggling a
     * checkbox parked a multi-second job in front of them. (The default {@code npx -y @probelabs/maid} is found
     * by its files instead — see {@link MaidLocator}.)
     */
    public void setPaths(String mmdcPath, String maidPath) {
        String mmdc = mmdcPath == null ? "" : mmdcPath;
        String maid = maidPath == null ? "" : maidPath;
        if (!mmdc.equals(this.mmdcPath) || !maid.equals(this.maidPath)) {
            this.cached = null;
        }
        this.mmdcPath = mmdc;
        this.maidPath = maid;
    }

    /**
     * The Settings Node.js, whose global {@code node_modules} is one place the default maid is looked for. A
     * change clears the cached availability, as a path change does.
     */
    public void setNodePath(String nodePath) {
        String node = nodePath == null ? "" : nodePath.strip();
        if (!node.equals(this.nodePath)) {
            this.cached = null;
        }
        this.nodePath = node;
    }

    /** Replaces how an installed maid is found for the default command — a test's seam (no real home or PATH). */
    public void setMaidLocator(Function<String, Optional<MaidLocator.Found>> locator) {
        this.maidLocator = locator;
        this.cached = null;
    }

    /** Whether the maid command is the default {@code npx -y @probelabs/maid} (see {@link MaidLocator}). */
    public boolean maidIsDefault() {
        return MaidLocator.isDefault(maidCommand());
    }

    /**
     * Where an installed maid is for the default command, found by its files — never by running npx, which
     * would fetch the package from the npm registry to answer. Empty for a user's own command (run that to
     * learn), or when none is installed. Blocking file reads; call off the FX thread.
     */
    public Optional<MaidLocator.Found> locateDefaultMaid() {
        return maidIsDefault() ? maidLocator.apply(nodePath) : Optional.empty();
    }

    /**
     * Whether maid is there: the default npx command by {@link #locateDefaultMaid() its files}, a user's own
     * command by running it ({@link Mermaid#detect}). Blocking; call off the FX thread.
     */
    Availability probe() {
        boolean mmdc = Mermaid.detect(mmdcCommand());
        if (maidIsDefault()) {
            MaidLocator.Found found = locateDefaultMaid().orElse(null);
            return new Availability(mmdc, found != null, found);
        }
        return new Availability(mmdc, Mermaid.detect(maidCommand()));
    }

    /** The mmdc command (configured value or {@link #DEFAULT_MMDC}), tokenized. */
    public List<String> mmdcCommand() {
        return Mermaid.command(mmdcPath, DEFAULT_MMDC);
    }

    /** The maid command (configured value or {@link #DEFAULT_MAID}), tokenized. */
    public List<String> maidCommand() {
        return Mermaid.command(maidPath, DEFAULT_MAID);
    }

    /** Probes mmdc + maid presence off-thread (cached), posting {@link Availability} on the FX thread. */
    public void detect(Consumer<Availability> onResult) {
        Availability hit = cached;
        if (hit != null) {
            Platform.runLater(() -> onResult.accept(hit));
            return;
        }
        exec.submit(() -> {
            Availability a = probe();
            cached = a;
            Platform.runLater(() -> onResult.accept(a));
        });
    }

    /** Renders {@code source} to {@code dest} (format by extension) via mmdc off-thread; posts the result. */
    public void export(String source, Path dest, boolean dark, Consumer<ProcessRunner.Result> onResult) {
        exec.submit(() -> {
            ProcessRunner.Result r = Mermaid.exportTo(mmdcCommand(), source, dest, dark);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Lints {@code source} via maid off-thread, posting diagnostics on the FX thread. A generation guard
     * drops stale results so only the latest in-flight validation wins (live linting while typing).
     */
    public void validate(String source, Consumer<List<MaidOutput.Diagnostic>> onResult) {
        validate(this, source, onResult);
    }

    /**
     * As {@link #validate(String, Consumer)}, with the guard scoped to {@code requester} (a buffer): a
     * request only supersedes an earlier one from the same requester. With one counter for everyone,
     * validating several diagram buffers in one tick delivered only the last one's diagnostics.
     */
    public void validate(Object requester, String source, Consumer<List<MaidOutput.Diagnostic>> onResult) {
        Object key = requester == null ? this : requester;
        long gen = validateGen.incrementAndGet();
        latestValidate.put(key, gen);
        exec.submit(() -> {
            if (!isLatestValidate(key, gen)) {
                return; // superseded while queued: don't spawn maid for text nobody is waiting on
            }
            List<MaidOutput.Diagnostic> diagnostics = Mermaid.validate(maidCommand(), source);
            if (isLatestValidate(key, gen)) {
                Platform.runLater(() -> {
                    if (isLatestValidate(key, gen)) { // re-checked where requests are made: no stale delivery
                        onResult.accept(diagnostics);
                    }
                });
            }
        });
    }

    private boolean isLatestValidate(Object key, long request) {
        Long current = latestValidate.get(key);
        return current != null && current == request;
    }

    /** Stops the background render/validate thread (called when the owning window closes). */
    public void shutdown() {
        exec.shutdownNow();
    }
}

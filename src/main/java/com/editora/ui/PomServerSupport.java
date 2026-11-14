package com.editora.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspServerRegistry;
import com.editora.maven.MalformedPoms;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the window does around the Maven pom server beyond starting it: where it may run ({@link
 * #pomServerRunsFor}), and keeping its coordinate completion alive when a pom elsewhere in its workspace is malformed
 * (review 2, E3; review 3, E21–E25). lemminx-maven answers the first {@code groupId}/{@code artifactId}/{@code
 * version} completion, then only {@code <!--} and {@code <![CDATA[} for every pom while its workspace holds a pom
 * whose text <em>on disk</em> was not well-formed XML when it first built it: it keeps the failed build's null project,
 * and every coordinate completion throws on it (lemminx-maven 0.12.0, {@code getCurrentWorkspaceProjects}). Nothing
 * the server is told later (a didSave of the fix, a watched-file change) rebuilds it.
 *
 * <p>The proof is the server's own log ({@code xml.logs.client}): a coordinate answer it logged that failure for
 * has the root walked as lemminx-maven walks it ({@link MalformedPoms}, off the FX thread, at most once every few
 * seconds per root), the malformed poms are taken out of the server's workspace and the completion is asked again.
 * An empty answer with no failure logged is an honest no-match, delivered at once (review 4, E33). A pom left out
 * and well-formed on disk again (saved here, or fixed by a checkout) restarts that root's Maven server alone:
 * lemminx-maven cannot take a pom back in the same session.
 *
 * <p>lemminx-maven also keeps a null project for a well-formed pom whose effective model Maven cannot build (an
 * unresolvable parent) and whose strict re-read fails (a duplicated or unknown tag, a DOCTYPE with entities). A
 * failure with nothing malformed left to leave out restarts the root only when one of its poms changed on disk since
 * that server started, and never twice for the same files; otherwise it is said once per root, naming the likely
 * pom when {@link MalformedPoms#unbuildable} finds one (review 4, E31).
 */
final class PomServerSupport {

    /** The Maven pom server, as the leave-out needs it ({@link LspManager}'s, or a test's). */
    interface MavenWorkspace {
        /** The root of the Maven server serving {@code file}, or null when none does. */
        @Nullable
        Path root(Path file);

        /** Every pom left out of that server's workspace. */
        List<Path> leftOut(Path file);

        /** Takes {@code poms} out of that server's workspace; returns the ones newly left out. */
        List<Path> leaveOut(Path file, List<Path> poms);

        /** How many coordinate completions that server failed on its workspace so far. */
        int completionFailures(Path file);

        /** When that server was launched (epoch millis), or -1 when none serves {@code file}. */
        long startedMillis(Path file);

        /** Stops the Maven server on {@code root} only. */
        void shutdown(Path root);

        static MavenWorkspace of(LspManager lsp) {
            return new MavenWorkspace() {
                @Override
                public @Nullable Path root(Path file) {
                    return lsp.mavenRoot(file);
                }

                @Override
                public List<Path> leftOut(Path file) {
                    return lsp.mavenLeftOut(file);
                }

                @Override
                public List<Path> leaveOut(Path file, List<Path> poms) {
                    return lsp.leaveOutOfMavenWorkspace(file, poms);
                }

                @Override
                public int completionFailures(Path file) {
                    return lsp.mavenCompletionFailures(file);
                }

                @Override
                public long startedMillis(Path file) {
                    return lsp.mavenStartedMillis(file);
                }

                @Override
                public void shutdown(Path root) {
                    lsp.shutdownServerAt(LspServerRegistry.MAVEN_POM_SERVER_ID, root);
                }
            };
        }
    }

    /** The elements whose text the Maven server completes from the local repository. */
    private static final Set<String> COORDINATES = Set.of("groupId", "artifactId", "version");

    /** The element whose text the caret is in: the last start tag before it on the line, with no tag after it. */
    private static final Pattern OPEN_ELEMENT = Pattern.compile("<([A-Za-z][\\w.-]*)>[^<]*$");

    /** How long one search's answer stands for a root before a completion searches again. */
    static final long RECHECK_MILLIS = 5_000;

    /** What a completion's search decided ({@link #decide}). */
    enum Act {
        /** Hand the answer over as it came. */
        DELIVER,
        /** Leave the malformed poms out of the workspace and ask again. */
        LEAVE_OUT,
        /** Restart the root's Maven server: a left-out pom is well-formed again. */
        TAKE_BACK,
        /**
         * Restart the root's Maven server: it failed with nothing malformed left to leave out, and a pom changed on
         * disk since it started (a stale failure).
         */
        RESTART_STALE,
        /** Say once that Maven completion is off: a pom it cannot build, which a restart would build no better. */
        COMPLETION_OFF
    }

    private final Consumer<String> status;
    private final MavenWorkspace maven;
    private final Consumer<Consumer<EditorBuffer>> forEachBuffer;
    private final Function<EditorBuffer, @Nullable Path> lspPath;
    private final Consumer<EditorBuffer> sync;

    /** When each root was last searched (per root: one window may hold several Maven roots). */
    private final Map<Path, Long> lastCheck = new HashMap<>();

    /** The workspace poms' modified times each root was last restarted for (a stale failure): never twice for one. */
    private final Map<Path, Map<Path, Long>> restartedFor = new HashMap<>();

    /** The roots told that Maven completion is off, until their server restarts. */
    private final Set<Path> saidOff = new java.util.HashSet<>();

    PomServerSupport(
            Consumer<String> status,
            MavenWorkspace maven,
            Consumer<Consumer<EditorBuffer>> forEachBuffer,
            Function<EditorBuffer, @Nullable Path> lspPath,
            Consumer<EditorBuffer> sync) {
        this.status = status;
        this.maven = maven;
        this.forEachBuffer = forEachBuffer;
        this.lspPath = lspPath;
        this.sync = sync;
    }

    /**
     * The coordinate element ({@code groupId}, {@code artifactId}, {@code version}) whose text holds the caret at
     * {@code column} of {@code line}, or null. Pure.
     */
    static @Nullable String coordinateElement(@Nullable String line, int column) {
        if (line == null) {
            return null;
        }
        String before = line.substring(0, Math.max(0, Math.min(column, line.length())));
        Matcher m = OPEN_ELEMENT.matcher(before);
        return m.find() && COORDINATES.contains(m.group(1)) ? m.group(1) : null;
    }

    /**
     * What to do about one completion, given its search. {@code failed}: the server logged its workspace failure for
     * it, the only proof that an answer is the broken one (an empty one alone is an honest no-match, E33); {@code
     * malformed}: the poms malformed on disk now; {@code leftOut}: the poms already out; {@code backAgain}: left-out
     * poms well-formed on disk again; {@code restartable}: a pom changed on disk since the server started, not into
     * files a restart was already tried for, and none is left that it cannot build ({@link #restartable}). Pure.
     */
    static Act decide(
            boolean failed, List<Path> malformed, Set<Path> leftOut, List<Path> backAgain, boolean restartable) {
        if (!backAgain.isEmpty()) {
            return Act.TAKE_BACK;
        }
        if (!failed) {
            return Act.DELIVER;
        }
        if (malformed.stream().anyMatch(p -> !leftOut.contains(p))) {
            return Act.LEAVE_OUT;
        }
        return restartable ? Act.RESTART_STALE : Act.COMPLETION_OFF;
    }

    /**
     * Whether restarting a failing root's server may help (E31): {@code scan} saw a pom modified after the server
     * started ({@code startedMillis}, -1 when unknown), the poms are not as they were at the last restart for a
     * stale failure ({@code lastRestart}, null when none), and no pom is left that Maven's strict reader refuses (a
     * new server would build it no better). Pure.
     */
    static boolean restartable(MalformedPoms.Scan scan, long startedMillis, @Nullable Map<Path, Long> lastRestart) {
        return startedMillis >= 0
                && scan.unbuildable().isEmpty()
                && scan.changedAfter(startedMillis)
                && !scan.modified().equals(lastRestart);
    }

    /**
     * The pom to name when completion is off: one whose DOCTYPE declares entities first (Maven refuses it whatever
     * else holds), then one with a parent (the unresolvable parent is what sends lemminx-maven to the strict
     * reader), then any; null when none. Pure.
     */
    static MalformedPoms.@Nullable Unbuildable likelyCulprit(List<MalformedPoms.Unbuildable> unbuildable) {
        return unbuildable.stream()
                .min(java.util.Comparator.comparingInt(
                        u -> u.why() == MalformedPoms.Why.ENTITIES ? 0 : u.hasParent() ? 1 : 2))
                .orElse(null);
    }

    /** The status saying Maven completion is off on {@code root}, naming {@code culprit} when known. */
    static String completionOffStatus(Path root, MalformedPoms.@Nullable Unbuildable culprit) {
        if (culprit == null) {
            return tr("status.maven.completionOff", folderName(root));
        }
        int line = Math.max(1, culprit.line());
        String why = switch (culprit.why()) {
            case ENTITIES -> tr("status.maven.whyEntities", line);
            case DUPLICATE -> tr("status.maven.whyDuplicate", "<" + culprit.element() + ">", line);
            case UNKNOWN -> tr("status.maven.whyUnknown", "<" + culprit.element() + ">", line);
        };
        return tr("status.maven.completionOffPom", folderName(root), displayPath(root, culprit.file()), why);
    }

    /** {@code file}'s server's failure count: read when the completion is sent, given back to {@link #onCompletion}. */
    int failuresBefore(@Nullable Path file) {
        return file == null ? 0 : maven.completionFailures(file);
    }

    /**
     * Hears one Maven-server completion of {@code file} answered at a coordinate ({@code element} non-null, else
     * nothing is done). {@code failuresBefore} is {@link #failuresBefore} as the request was sent. Searches the root
     * when the server logged its workspace failure for this answer, or the root has poms left out, then leaves out,
     * restarts, says completion is off, or runs {@code askAgain} / {@code deliver} (the answer as it came). Returns
     * whether it took the answer over; when false the caller delivers it.
     */
    boolean onCompletion(
            @Nullable String element,
            List<String> labels,
            Path file,
            int failuresBefore,
            Runnable askAgain,
            Runnable deliver) {
        Path root = element == null ? null : maven.root(file);
        if (root == null) {
            return false;
        }
        boolean failed = maven.completionFailures(file) > failuresBefore; // the proof: an empty answer alone is not
        List<Path> out = maven.leftOut(file);
        if (!failed && out.isEmpty()) {
            return false; // a real answer or an honest no-match, nothing to take back: delivered at once (E33)
        }
        long now = System.currentTimeMillis();
        Long last = lastCheck.get(root);
        if (last != null && now - last < RECHECK_MILLIS) {
            return false;
        }
        lastCheck.put(root, now);
        long started = maven.startedMillis(file);
        Map<Path, Long> lastRestart = restartedFor.get(root);
        Thread.ofVirtual().name("pom-wellformed").start(() -> {
            MalformedPoms.Scan scan = failed ? MalformedPoms.scan(root) : null;
            List<Path> back = out.stream()
                    .filter(p -> java.nio.file.Files.isRegularFile(p) && MalformedPoms.check(p, null) == null)
                    .toList();
            Act act = decide(
                    failed,
                    scan == null
                            ? List.of()
                            : scan.malformed().stream()
                                    .map(MalformedPoms.Problem::file)
                                    .toList(),
                    Set.copyOf(out),
                    back,
                    scan != null && restartable(scan, started, lastRestart));
            Runnable none = () -> {};
            // an answer without a logged failure went out at once (below); only a proven broken one waits for the walk
            Platform.runLater(
                    () -> act(act, file, root, scan, back, failed ? askAgain : none, failed ? deliver : none));
        });
        return failed; // otherwise the answer is delivered now; the left-out poms are re-checked behind it (E24)
    }

    private void act(
            Act act,
            Path file,
            Path root,
            MalformedPoms.@Nullable Scan scan,
            List<Path> back,
            Runnable askAgain,
            Runnable deliver) {
        switch (act) {
            case TAKE_BACK -> {
                deliver.run();
                restart(root, tr("status.maven.pomBack", displayPath(root, back.get(0))));
            }
            case LEAVE_OUT -> {
                Set<Path> already = Set.copyOf(maven.leftOut(file));
                List<MalformedPoms.Problem> fresh = scan == null
                        ? List.of()
                        : scan.malformed().stream()
                                .filter(p -> !already.contains(p.file()))
                                .toList();
                List<Path> out = fresh.isEmpty()
                        ? List.of()
                        : maven.leaveOut(
                                file,
                                fresh.stream().map(MalformedPoms.Problem::file).toList());
                if (out.isEmpty()) {
                    deliver.run(); // the server could not be told (not ready): say nothing it did not do
                    return;
                }
                MalformedPoms.Problem first = fresh.stream()
                        .filter(p -> out.contains(p.file()))
                        .findFirst()
                        .orElse(fresh.get(0));
                String where = displayPath(root, first.file());
                int line = Math.max(1, first.line());
                status.accept(
                        out.size() == 1
                                ? tr("status.maven.completionLeftOut", where, line)
                                : tr("status.maven.completionLeftOutMore", where, line, out.size() - 1));
                // the retry is searched at once: a pom it cannot build may still break it (E31, broken + dup)
                lastCheck.remove(root);
                askAgain.run();
            }
            case RESTART_STALE -> {
                deliver.run();
                if (scan != null) {
                    restartedFor.put(root, scan.modified());
                }
                restart(root, tr("status.maven.restartStale", folderName(root)));
            }
            case COMPLETION_OFF -> {
                deliver.run();
                if (saidOff.add(root)) {
                    status.accept(completionOffStatus(root, scan == null ? null : likelyCulprit(scan.unbuildable())));
                }
            }
            case DELIVER -> deliver.run();
        }
    }

    /**
     * Saved {@code pom} as {@code savedText} (the bytes now on disk): when it was left out of its Maven server's
     * workspace and is well-formed now, restarts that root's server, which takes it back (lemminx-maven cannot in
     * the same session). Returns whether it did. FX thread.
     */
    boolean saved(Path pom, String savedText) {
        Path root = maven.root(pom);
        if (root == null || !wellFormedAgain(holds(maven.leftOut(pom), pom), pom, savedText)) {
            return false;
        }
        restart(root, tr("status.maven.pomBack", displayPath(root, pom)));
        return true;
    }

    /**
     * Files changed on disk (the project watcher's or the server root's own watcher's batch): when one is a pom left
     * out of a Maven server's workspace and well-formed now (a checkout, another editor), restarts that root's server
     * (review 3, E24; review 4, E32). A pom changed under a root whose completion was said to be off restarts it when
     * a restart may help ({@link #restartable}: the pom it could not build fixed), without waiting for a completion.
     * FX thread; the files are read off it.
     */
    void filesChanged(List<Path> changed) {
        Map<Path, Path> leftOutByRoot = new HashMap<>();
        Map<Path, Path> offByRoot = new HashMap<>();
        for (Path p : changed) {
            if (MalformedPoms.isWorkspacePom(p)) {
                Path root = maven.root(p);
                if (root != null && holds(maven.leftOut(p), p)) {
                    leftOutByRoot.putIfAbsent(root, p);
                } else if (root != null && saidOff.contains(root)) {
                    offByRoot.putIfAbsent(root, p);
                }
            }
        }
        offByRoot.keySet().removeAll(leftOutByRoot.keySet()); // taking a pom back restarts it anyway
        offByRoot.forEach((root, pom) -> {
            long started = maven.startedMillis(pom);
            Map<Path, Long> lastRestart = restartedFor.get(root);
            Thread.ofVirtual().name("pom-wellformed").start(() -> {
                MalformedPoms.Scan scan = MalformedPoms.scan(root);
                if (scan.malformed().isEmpty() && restartable(scan, started, lastRestart)) {
                    Platform.runLater(() -> {
                        if (saidOff.contains(root)) { // not restarted meanwhile
                            restartedFor.put(root, scan.modified());
                            restart(root, tr("status.maven.restartStale", folderName(root)));
                        }
                    });
                }
            });
        });
        leftOutByRoot.forEach((root, pom) -> Thread.ofVirtual()
                .name("pom-wellformed")
                .start(() -> {
                    if (java.nio.file.Files.isRegularFile(pom) && MalformedPoms.check(pom, null) == null) {
                        Platform.runLater(() -> {
                            if (holds(maven.leftOut(pom), pom)) { // not restarted meanwhile
                                restart(root, tr("status.maven.pomBack", displayPath(root, pom)));
                            }
                        });
                    }
                }));
    }

    /**
     * Restarts the Maven server on {@code root} alone (review 3, E23: not every root's) and opens that root's poms on
     * a fresh one at once, then says {@code message}: after the save's own status and the server's "Starting…".
     */
    void restart(Path root, String message) {
        List<EditorBuffer> poms = new java.util.ArrayList<>();
        forEachBuffer.accept(b -> {
            Path p = lspPath.apply(b);
            if (p != null && root.equals(maven.root(p))) {
                poms.add(b);
            }
        });
        maven.shutdown(root);
        lastCheck.remove(root); // the fresh server's first degraded answer is searched at once (E Gaps)
        saidOff.remove(root); // and if it fails again, that is said again
        poms.forEach(sync);
        status.accept(message);
        Platform.runLater(() -> status.accept(message));
    }

    /**
     * Whether saving {@code pom} as {@code text} should restart its Maven server: it was left out of the server's
     * workspace ({@code leftOut}) and is well-formed now. lemminx-maven cannot take it back in the same session.
     */
    static boolean wellFormedAgain(boolean leftOut, Path pom, String text) {
        return leftOut && MalformedPoms.check(pom, text) == null;
    }

    /** Whether {@code poms} holds {@code pom}, compared as absolute normalized paths. Pure. */
    static boolean holds(List<Path> poms, Path pom) {
        Path at = pom.toAbsolutePath().normalize();
        return poms.stream().anyMatch(p -> p.toAbsolutePath().normalize().equals(at));
    }

    private static String folderName(Path root) {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    /** {@code file} relative to {@code root} when it lies under it, else its full path. Pure. */
    static String displayPath(Path root, Path file) {
        try {
            Path rel = root.toAbsolutePath()
                    .normalize()
                    .relativize(file.toAbsolutePath().normalize());
            return rel.toString().isEmpty() || rel.startsWith("..") ? file.toString() : rel.toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /**
     * Whether the Maven pom server may serve {@code file}: always here; on a remote host (SSH, WSL, a container) only
     * with a command that host can run — {@code lsp.maven-pom} in its tools, or a bare command loading nothing from
     * this machine. The local {@code java -cp "<config>/plugins/lsp/maven/*"} ran there with a classpath that exists
     * only here and crash-looped (review 2, A5); without one the host's plain lemminx serves the pom.
     */
    static boolean pomServerRunsFor(@Nullable Path file, @Nullable String localCommand) {
        String pom = com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID;
        return com.editora.process.ExecTargets.runsHere(file)
                || !com.editora.lsp.LspServerRegistry.remoteCommandFor(
                                pom, localCommand, com.editora.process.ExecTargets.remoteTool(file, "lsp." + pom))
                        .isEmpty();
    }
}

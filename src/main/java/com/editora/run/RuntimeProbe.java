package com.editora.run;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * Asks a launch's runtime for its version ({@link RuntimeVersion}) off the FX thread, beside the launch rather
 * than before it: the program never waits for the answer, and the Run or Debug tool window fills in its chip
 * when it comes. Not cached — a pyenv shim, a re-pointed {@code JAVA_HOME} or an upgraded {@code node}
 * answers differently from one run to the next, and the chip must say what this run got.
 */
public final class RuntimeProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private RuntimeProbe() {}

    /**
     * Probes the runtime of {@code launch} (the argv about to start) in {@code where} — its working folder,
     * which also picks the host for a remote one; {@code null} is this machine — with the launch's own
     * {@code env} on top. Delivers the result, or {@code null} when there is no version to show, on the FX
     * thread. Delivers {@code null} at once (on the caller's thread) for a launch whose runtime is unknown.
     */
    public static void probe(
            List<String> launch, @Nullable Path where, Map<String, String> env, Consumer<RuntimeVersion> onResult) {
        RuntimeVersion.Probe probe = RuntimeVersion.probeFor(launch);
        if (probe == null) {
            onResult.accept(null);
            return;
        }
        Map<String, String> extraEnv = setOnly(env);
        Thread t = new Thread(
                () -> {
                    RuntimeVersion version = null;
                    try {
                        ProcessRunner.Result r = ProcessRunner.run(where, TIMEOUT, probe.argv(), extraEnv);
                        if (r != null) {
                            version = RuntimeVersion.parse(probe, r.err() + "\n" + r.out());
                        }
                    } catch (RuntimeException ignored) {
                        // A probe that cannot run shows no chip; the launch itself reports its own failure.
                    }
                    RuntimeVersion found = version;
                    Platform.runLater(() -> onResult.accept(found));
                },
                "runtime-probe");
        t.setDaemon(true);
        t.start();
    }

    /** {@code env} without the variables it unsets (a {@code null} value): the probe only adds. */
    private static Map<String, String> setOnly(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return Map.of();
        }
        Map<String, String> set = new java.util.LinkedHashMap<>(env);
        set.values().removeIf(java.util.Objects::isNull);
        return set;
    }
}

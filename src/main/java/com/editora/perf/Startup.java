package com.editora.perf;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup timing instrumentation: phase marks from <em>process start</em> to the first painted frame.
 *
 * <p>Off unless {@code -Deditora.perf} / {@code EDITORA_PERF=1} is set, and when off every {@link #mark}
 * is a single static boolean test — so this is inert in a normal launch and can stay in the shipped code.
 *
 * <p>Timing starts at the <b>process</b> start ({@link ProcessHandle}), not at {@code main}, because the
 * jpackage launcher, JVM boot, and AOT-cache mapping all land before any app code runs and are a real part
 * of what the user waits for. Everything is measured against that one origin, so phases are directly
 * comparable and the last mark <em>is</em> the wall-clock time to that point.
 *
 * <p>The mark that matters is {@link #FIRST_PAINT}: the first rendered frame that actually shows the
 * requested file's content, not merely a window. Anything earlier (window shown, buffer loaded) can be
 * true while the user is still looking at blank space.
 */
public final class Startup {

    /** Phase: {@code App.main} entered — everything before it is launcher + JVM + AOT mapping. */
    public static final String MAIN = "main";
    /** Phase: {@code App.start} entered — the FX toolkit is up. */
    public static final String FX_START = "fx-start";
    /** Phase: shared config (settings, session index) loaded. */
    public static final String CONFIG_LOADED = "config-loaded";
    /** Phase: {@code main.fxml} loaded (its controller constructed, not yet initialised). */
    public static final String FXML_LOADED = "fxml-loaded";
    /** Phase: {@code MainController.init} returned — tool windows, coordinators and plugins are wired. */
    public static final String CONTROLLER_INIT = "controller-init";
    /** Phase: the first window is built (scene graph constructed, not yet shown). */
    public static final String WINDOW_BUILT = "window-built";
    /** Phase: {@code stage.show()} returned. The window exists; it may still be blank. */
    public static final String WINDOW_SHOWN = "window-shown";
    /** Phase: the requested file's text has been put into its buffer (not necessarily painted). */
    public static final String FILE_LOADED = "file-loaded";
    /** Phase: the first frame carrying that file's content has been rendered. The headline number. */
    public static final String FIRST_PAINT = "first-paint";

    /** Enabled via {@code -Deditora.perf} or {@code EDITORA_PERF=1} (the env var suits a packaged app). */
    private static final boolean ENABLED =
            System.getProperty("editora.perf") != null || "1".equals(System.getenv("EDITORA_PERF"));

    /** Exit as soon as {@link #FIRST_PAINT} is marked — for a repeatable measurement loop. */
    private static final boolean EXIT_ON_FIRST_PAINT =
            System.getProperty("editora.perfExit") != null || "1".equals(System.getenv("EDITORA_PERF_EXIT"));

    /** Timing origin: the harness-supplied T0 if given, else the OS's process start (never null). */
    private static final Instant ORIGIN = origin();

    private static final List<Mark> MARKS = new ArrayList<>();
    private static boolean reported;

    /** One phase and how long after process start it happened. */
    public record Mark(String phase, long millis) {}

    private Startup() {}

    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Records {@code phase} at now. Only the first call for a phase counts, so a mark placed on a path that
     * runs once per window (or once per restored file) reports the first — which is the one the user waited
     * for. No-op unless enabled.
     */
    public static void mark(String phase) {
        if (!ENABLED) {
            return;
        }
        long ms = Duration.between(ORIGIN, Instant.now()).toMillis();
        synchronized (MARKS) {
            for (Mark m : MARKS) {
                if (m.phase().equals(phase)) {
                    return;
                }
            }
            MARKS.add(new Mark(phase, ms));
        }
        if (FIRST_PAINT.equals(phase)) {
            report();
            if (EXIT_ON_FIRST_PAINT) {
                Runtime.getRuntime().halt(0); // halt, not exit: don't run shutdown hooks into the timing
            }
        }
    }

    /** The marks recorded so far, in the order they happened. */
    public static List<Mark> marks() {
        synchronized (MARKS) {
            return List.copyOf(MARKS);
        }
    }

    /** Prints the report to stderr (once). Also mirrored into the Debug Log by the caller if it wants. */
    public static void report() {
        if (!ENABLED) {
            return;
        }
        List<Mark> snapshot;
        synchronized (MARKS) {
            if (reported) {
                return;
            }
            reported = true;
            snapshot = List.copyOf(MARKS);
        }
        System.err.print(format(snapshot));
    }

    /**
     * Renders marks as a table of cumulative-since-process-start and per-phase deltas. Pure — the unit test
     * drives this rather than a real launch.
     */
    public static String format(List<Mark> marks) {
        StringBuilder sb = new StringBuilder("[perf] startup (ms since process start"
                + (exactOrigin() ? "" : ", APPROXIMATE origin — see Startup.origin()") + ")\n");
        long prev = 0;
        for (Mark m : marks) {
            sb.append(String.format("[perf] %-14s %6d  (+%d)%n", m.phase(), m.millis(), m.millis() - prev));
            prev = m.millis();
        }
        for (Mark m : marks) {
            if (FIRST_PAINT.equals(m.phase())) {
                sb.append(String.format("[perf] TIME-TO-FIRST-PAINT %d ms%n", m.millis()));
            }
        }
        return sb.toString();
    }

    /**
     * The timing origin, in preference order:
     *
     * <ol>
     *   <li>{@code EDITORA_PERF_T0} — epoch millis stamped by the measuring harness immediately before it
     *       exec's the launcher. <b>Exact</b>, and it's the instant the user's double-click really starts.</li>
     *   <li>{@link ProcessHandle}'s process start — convenient but only <b>approximate</b>: on Linux the
     *       kernel reports start time as boot-time plus clock ticks, and the boot-time reference drifts, so
     *       this over-reported the elapsed time by ~500 ms on the machine this was written on (caught by
     *       cross-checking a reported number against externally measured wall-clock). Fine for comparing
     *       phases within one run; do not quote it as an absolute.</li>
     *   <li>Now — this class loads early in {@code main}, so this just undercounts the pre-main portion.</li>
     * </ol>
     */
    private static Instant origin() {
        String t0 = System.getenv("EDITORA_PERF_T0");
        if (t0 != null && !t0.isBlank()) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(t0.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to the OS value
            }
        }
        try {
            return ProcessHandle.current().info().startInstant().orElseGet(Instant::now);
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    /** True when the origin is the exact harness-supplied T0 rather than the approximate OS value. */
    public static boolean exactOrigin() {
        String t0 = System.getenv("EDITORA_PERF_T0");
        return t0 != null && !t0.isBlank();
    }
}

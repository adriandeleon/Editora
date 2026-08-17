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

    /** Sample the FX thread's stack between marks; see {@link #startSampler}. Needs {@code EDITORA_PERF}. */
    private static final boolean SAMPLE =
            System.getProperty("editora.perfSample") != null || "1".equals(System.getenv("EDITORA_PERF_SAMPLE"));

    /** Fine enough to attribute a ~100 ms stall, coarse enough not to perturb what it measures. */
    private static final int SAMPLE_INTERVAL_MS = 5;

    private static final List<Mark> MARKS = new ArrayList<>();
    private static final List<String> SAMPLES = new ArrayList<>();
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

    /**
     * Starts sampling the FX application thread's stack, so a long gap between two marks can be attributed
     * rather than guessed at. Opt-in via {@code EDITORA_PERF_SAMPLE=1} on top of {@code EDITORA_PERF}.
     *
     * <p>This exists because the interesting gap is <em>not</em> rendering: the last stretch before
     * {@link #FIRST_PAINT} is bounded by two animation-timer ticks (~33 ms of real frames), so anything
     * longer means the FX thread was busy and pulses were not running at all. A sampler names the method
     * that held it; reasoning from the call graph repeatedly names the wrong one.
     *
     * <p>Sampling a thread's stack is safe (no suspension, no instrumentation) and the sampler is a daemon,
     * so it can never hold the JVM open. Frames are aggregated by their deepest {@code com.editora} frame —
     * the app method responsible — with the raw leaf kept for context.
     */
    public static void startSampler(Thread fxThread) {
        if (!ENABLED || !SAMPLE || fxThread == null) {
            return;
        }
        Thread t = new Thread(
                () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        StackTraceElement[] stack = fxThread.getStackTrace();
                        if (stack.length > 0) {
                            // Read the phase before taking the SAMPLES lock: report() holds MARKS and then
                            // formats samples, so acquiring them in the other order here would invert the
                            // lock order between the two threads.
                            String phase;
                            synchronized (MARKS) {
                                phase = MARKS.isEmpty()
                                        ? "?"
                                        : MARKS.get(MARKS.size() - 1).phase();
                            }
                            String frame = attribute(stack);
                            synchronized (SAMPLES) {
                                SAMPLES.add(phase + " | " + frame);
                            }
                        }
                        try {
                            Thread.sleep(SAMPLE_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                },
                "editora-perf-sampler");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The frame a sample is blamed on: the deepest {@code com.editora} frame, since a JDK/JavaFX leaf
     * ({@code Object.wait}, a CSS lookup) says what is running but not which app code asked for it. Falls
     * back to the leaf when no app frame is on the stack, which is itself the useful answer — it means the
     * toolkit, not Editora, was busy.
     */
    private static String attribute(StackTraceElement[] stack) {
        for (StackTraceElement e : stack) {
            if (e.getClassName().startsWith("com.editora.")) {
                return e.getClassName().substring("com.editora.".length()) + "." + e.getMethodName();
            }
        }
        StackTraceElement leaf = stack[0];
        return "(toolkit) " + leaf.getClassName() + "." + leaf.getMethodName();
    }

    /** Renders the sampler's tally, hottest first. Empty when sampling is off. */
    public static String sampleReport() {
        List<String> snapshot;
        synchronized (SAMPLES) {
            if (SAMPLES.isEmpty()) {
                return "";
            }
            snapshot = List.copyOf(SAMPLES);
        }
        java.util.Map<String, Integer> tally = new java.util.HashMap<>();
        for (String s : snapshot) {
            tally.merge(s, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder(
                "[perf] FX-thread samples (" + snapshot.size() + " @ " + SAMPLE_INTERVAL_MS + "ms)\n");
        tally.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(40)
                .forEach(e -> sb.append(
                        String.format("[perf]   %5d ms  %s%n", e.getValue() * SAMPLE_INTERVAL_MS, e.getKey())));
        return sb.toString();
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
        System.err.print(sampleReport());
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

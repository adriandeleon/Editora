package com.editora.dap;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The time limit on an evaluation that runs the program — a watch, the Debug console, a hover that calls a function —
 * told to each debugger that keeps to one, so a call that never returns is stopped by the debugger itself and the
 * program stays paused where it was (Settings → Debugging → Evaluation time limit, {@code
 * Settings.debugEvaluationTimeLimit}).
 *
 * <p>Probed against the real debuggers ({@code EvaluationTimeLimitProbeFxTest}):
 *
 * <ul>
 *   <li>GDB: {@code set direct-call-timeout} (a watch, the console) and {@code set indirect-call-timeout} (a
 *       breakpoint's condition), with {@code set unwind-on-timeout on} so the frame is put back as it was. Given on
 *       its command line ({@code -iex}), before its DAP mode starts.
 *   <li>debugpy: pydevd's {@code PYDEVD_INTERRUPT_THREAD_TIMEOUT} in the program's environment, which interrupts the
 *       thread evaluating ({@code KeyboardInterrupt}): a busy loop, a {@code sleep}, a long comprehension. A launch
 *       only: a program attached to was started without it.
 * </ul>
 *
 * <p>CodeLLDB stops a call in a watch or a hover by itself, in under a second, whatever its {@code evaluationTimeout}
 * says (and its Debug console takes LLDB commands, whose {@code expr} waits for good unless given {@code -t}).
 * java-debug, js-debug and Delve have no limit at all. DAP's {@code cancel} is no way out either: of the debuggers here
 * only GDB and CodeLLDB offer it, GDB ignores it during a call, and after CodeLLDB's the frame no longer evaluates.
 * There Editora stops waiting after {@link #wait} and says the program is still running the expression.
 */
public final class EvaluationLimits {

    /** The limit by default, in seconds. */
    public static final int DEFAULT_SECONDS = 10;

    /** How long Editora waits for an evaluation's answer at the least, limit or none. */
    static final Duration MINIMUM_WAIT = Duration.ofSeconds(30);

    /** pydevd's setting, read from the program's environment when it starts. */
    static final String PYDEVD_TIMEOUT = "PYDEVD_INTERRUPT_THREAD_TIMEOUT";

    private EvaluationLimits() {}

    /** GDB's arguments that set the limit, put before {@code -i dap}: none when {@code seconds} is 0 (no limit). */
    public static List<String> gdbArguments(int seconds) {
        if (seconds <= 0) {
            return List.of();
        }
        return List.of(
                "-iex",
                "set direct-call-timeout " + seconds,
                "-iex",
                "set indirect-call-timeout " + seconds,
                "-iex",
                "set unwind-on-timeout on");
    }

    /**
     * {@code args}, a launch or attach request, with the limit of {@code seconds} added for the debugger it goes to
     * (its {@code type}): debugpy's launch gets it in the program's environment. What the request already says (a
     * launch.json's own {@code env}) is kept; {@code seconds} 0 adds nothing.
     */
    public static Map<String, Object> decorate(Map<String, Object> args, int seconds) {
        if (seconds <= 0 || args == null) {
            return args;
        }
        Object type = args.get("type");
        if (("python".equals(type) || "debugpy".equals(type)) && "launch".equals(args.get("request"))) {
            Map<String, Object> out = new LinkedHashMap<>(args);
            Map<String, Object> env = new LinkedHashMap<>();
            if (args.get("env") instanceof Map<?, ?> own) {
                own.forEach((k, v) -> env.put(String.valueOf(k), v));
            }
            env.putIfAbsent(PYDEVD_TIMEOUT, String.valueOf(seconds));
            out.put("env", env);
            return out;
        }
        return args;
    }

    /** How long Editora waits for an evaluation's answer: past the debugger's own limit, and never under 30 s. */
    public static Duration wait(int seconds) {
        Duration past = Duration.ofSeconds(Math.max(0, seconds) + 5L);
        return past.compareTo(MINIMUM_WAIT) > 0 ? past : MINIMUM_WAIT;
    }
}

package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.editora.config.ExceptionBreakpoint;
import com.editora.dap.DapModels;
import com.editora.dap.ExceptionFilterCatalog;
import org.jspecify.annotations.Nullable;

/**
 * Pure: how exception breakpoints ({@link ExceptionBreakpoint}) decide an exception stop. Editora has the adapter stop
 * on every thrown exception of a language that has some (its "caught" / "raised" filter, added to the user's own
 * choice: {@link #extras}) — or, where the adapter can be told types (debugpy), on those types alone
 * ({@link #adapterTypes}) — and at each such stop asks which exception it is and lets the program go on unless one
 * matches: its type pattern, then its condition.
 *
 * <p>An uncaught exception's stop ({@code breakMode} {@code unhandled}, as debugpy and java-debug mark it) comes from
 * the user's own Uncaught choice and is always shown. A raised exception passing up through the frames it leaves
 * stops again in each (debugpy does): the stop after a shown one, of the same exception, one frame further out, is
 * passed over ({@link #propagating}).
 */
final class ExceptionBreakpoints {

    private ExceptionBreakpoints() {}

    /** The enabled exception breakpoints of {@code language} whose type pattern names {@code type}. */
    static List<ExceptionBreakpoint> matching(List<ExceptionBreakpoint> all, String language, String type) {
        List<String> names = typeNames(type);
        List<ExceptionBreakpoint> out = new ArrayList<>();
        for (ExceptionBreakpoint b : all) {
            if (b.enabled() && b.language().equals(language) && StopFilterCheck.matches(b.type(), names)) {
                out.add(b);
            }
        }
        return out;
    }

    /** The enabled exception breakpoints of {@code language}. */
    static List<ExceptionBreakpoint> enabled(List<ExceptionBreakpoint> all, String language) {
        return all.stream()
                .filter(b -> b.enabled() && b.language().equals(language))
                .toList();
    }

    /**
     * The names a type pattern is matched against: the type as the debugger gives it ({@code java.lang.KeyError},
     * {@code builtins.KeyError}) and its last part ({@code KeyError}), after a {@code .}, {@code ::} or {@code /}.
     */
    static List<String> typeNames(@Nullable String type) {
        String t = type == null ? "" : type.strip();
        int colons = t.lastIndexOf("::");
        int cut = Math.max(Math.max(t.lastIndexOf('.'), colons < 0 ? -1 : colons + 1), t.lastIndexOf('/'));
        String last = cut >= 0 && cut < t.length() - 1 ? t.substring(cut + 1) : t;
        return last.equals(t) ? List.of(t) : List.of(t, last);
    }

    /** Whether a stop's {@code breakMode} is an uncaught exception's ({@code unhandled}, {@code userUnhandled}). */
    static boolean uncaught(@Nullable String breakMode) {
        return "unhandled".equals(breakMode) || "userUnhandled".equals(breakMode);
    }

    /** Values a condition evaluates to that mean it does not hold, in the debugged languages' words. */
    private static final Set<String> FALSE =
            Set.of("false", "0", "none", "null", "nil", "undefined", "", "0.0", "''", "\"\"");

    /** Whether a condition's evaluated value holds (anything but false, zero, nothing). */
    static boolean holds(@Nullable String value) {
        return value != null && !FALSE.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether {@code now} is the stack of the exception shown at {@code before}, one or more frames further out: the
     * same exception passing up, which debugpy stops on again in each frame it leaves.
     */
    static boolean propagating(List<DapModels.StackFrameInfo> before, List<DapModels.StackFrameInfo> now) {
        return propagating(before, before.size(), now, now.size());
    }

    /**
     * As above, for the top frames of stacks {@code beforeDepth} and {@code nowDepth} frames deep (a stop fetches the
     * top of the stack only): the frames both have must match. When they have none in common it cannot be told, and the
     * exception is not taken to be passing up.
     */
    static boolean propagating(
            List<DapModels.StackFrameInfo> before, int beforeDepth, List<DapModels.StackFrameInfo> now, int nowDepth) {
        int out = beforeDepth - nowDepth; // frames left: now[i] is before[i + out]
        if (before.isEmpty() || now.isEmpty() || out <= 0) {
            return false;
        }
        int common = Math.min(now.size(), before.size() - out);
        if (common <= 0) {
            return false;
        }
        for (int i = 0; i < common; i++) {
            if (!ParallelStacks.key(before.get(i + out)).equals(ParallelStacks.key(now.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The exception filters each language's enabled exception breakpoints need besides the user's choice: its adapter's
     * filter for thrown (caught) exceptions. Languages whose adapter has none are left out.
     */
    static Map<String, List<String>> extras(List<ExceptionBreakpoint> all) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (ExceptionBreakpoint b : all) {
            if (!b.enabled() || out.containsKey(b.language())) {
                continue;
            }
            String caught = ExceptionFilterCatalog.caughtFilter(b.language());
            if (caught != null) {
                out.put(b.language(), List.of(caught));
            }
        }
        return out;
    }

    /**
     * The exception types each language's adapter can be told to stop on in place of its thrown-exception filter
     * ({@code language → types}), so that it does not stop on every exception the program throws for Editora to check
     * (perf review 2026-11-23, D1): debugpy's {@code exceptionOptions}, when every enabled Python exception breakpoint
     * names builtin types only ({@link com.editora.dap.PythonExceptionTypes#typesOf}). The check after each stop stays
     * (an exact type, a condition, an excluded type). Other adapters cannot be told a type: java-debug reads no
     * {@code exceptionOptions}, and its own type list (a global debug setting) narrows the Uncaught choice too.
     */
    static Map<String, List<String>> adapterTypes(List<ExceptionBreakpoint> all) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Set<String> cannot = new java.util.HashSet<>();
        for (ExceptionBreakpoint b : all) {
            if (!b.enabled() || !PYTHON.equals(b.language()) || cannot.contains(b.language())) {
                continue;
            }
            List<String> types = com.editora.dap.PythonExceptionTypes.typesOf(b.type());
            if (types == null) {
                cannot.add(b.language());
                out.remove(b.language());
                continue;
            }
            List<String> named = new ArrayList<>(out.getOrDefault(b.language(), List.of()));
            types.stream().filter(t -> !named.contains(t)).forEach(named::add);
            out.put(b.language(), List.copyOf(named));
        }
        return out;
    }

    /** The language whose adapter (debugpy) is told exception types. */
    private static final String PYTHON = com.editora.dap.DebugLanguages.PYTHON.id();

    /** A picker row's text: the type (any, when blank) and the condition, if there is one. */
    static String label(ExceptionBreakpoint b, String anyType) {
        String type = b.type().isEmpty() ? anyType : b.type();
        return b.condition().isEmpty() ? type : type + "  if " + b.condition();
    }
}

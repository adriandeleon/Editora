package com.editora.dap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.debug.ExceptionBreakMode;
import org.eclipse.lsp4j.debug.ExceptionOptions;
import org.eclipse.lsp4j.debug.ExceptionPathSegment;
import org.jspecify.annotations.Nullable;

/**
 * Pure: exception breakpoints of Python types, told to debugpy as DAP {@code exceptionOptions} (perf review
 * 2026-11-23, D1). Without them Editora turned on debugpy's "raised" filter for one exception breakpoint, and every
 * exception the program raised — {@code StopIteration}, a library's {@code KeyError} probing — stopped it for Editora
 * to check the type and resume. With them debugpy stops only on the types named (and their subclasses); Editora still
 * checks each stop (an exact type, a condition), so the types told are a pre-filter that must let through every
 * exception a breakpoint names.
 *
 * <p>debugpy (pydevd's {@code on_setexceptionbreakpoints_request}) reads {@code exceptionOptions} <em>instead of</em>
 * the filters when there are any, so the user's own choice (Uncaught, User Uncaught, Raised) is told as options too.
 * It resolves a type name by {@code eval} and else by importing it: only the builtin exception types every Python 3
 * has are told, never a name it would have to import (a user module imported early) or could not resolve (the
 * breakpoint silently dropped).
 */
public final class PythonExceptionTypes {

    private PythonExceptionTypes() {}

    /** The path segment debugpy reads as "the Python exceptions" (pydevd's {@code "Python Exceptions"}). */
    static final String ROOT = "Python Exceptions";

    /** Python 3's builtin exception types, by lower-case name: every version debugpy runs on has them. */
    private static final Map<String, String> BUILTIN = List.of(
                    "BaseException",
                    "GeneratorExit",
                    "KeyboardInterrupt",
                    "SystemExit",
                    "Exception",
                    "ArithmeticError",
                    "FloatingPointError",
                    "OverflowError",
                    "ZeroDivisionError",
                    "AssertionError",
                    "AttributeError",
                    "BufferError",
                    "EOFError",
                    "ImportError",
                    "ModuleNotFoundError",
                    "LookupError",
                    "IndexError",
                    "KeyError",
                    "MemoryError",
                    "NameError",
                    "UnboundLocalError",
                    "OSError",
                    "EnvironmentError",
                    "IOError",
                    "BlockingIOError",
                    "ChildProcessError",
                    "ConnectionError",
                    "BrokenPipeError",
                    "ConnectionAbortedError",
                    "ConnectionRefusedError",
                    "ConnectionResetError",
                    "FileExistsError",
                    "FileNotFoundError",
                    "InterruptedError",
                    "IsADirectoryError",
                    "NotADirectoryError",
                    "PermissionError",
                    "ProcessLookupError",
                    "TimeoutError",
                    "ReferenceError",
                    "RuntimeError",
                    "NotImplementedError",
                    "RecursionError",
                    "StopAsyncIteration",
                    "StopIteration",
                    "SyntaxError",
                    "IndentationError",
                    "TabError",
                    "SystemError",
                    "TypeError",
                    "ValueError",
                    "UnicodeError",
                    "UnicodeDecodeError",
                    "UnicodeEncodeError",
                    "UnicodeTranslateError",
                    "Warning",
                    "BytesWarning",
                    "DeprecationWarning",
                    "FutureWarning",
                    "ImportWarning",
                    "PendingDeprecationWarning",
                    "ResourceWarning",
                    "RuntimeWarning",
                    "SyntaxWarning",
                    "UnicodeWarning",
                    "UserWarning")
            .stream()
            .collect(Collectors.toUnmodifiableMap(n -> n.toLowerCase(Locale.ROOT), Function.identity()));

    /**
     * The builtin types a breakpoint's type pattern names ({@code ValueError, builtins.KeyError, !UnicodeError}: the
     * patterns Editora matches case-ignored), or null when debugpy could not be told it: a blank pattern (any type), a
     * glob, a type that is not builtin. Patterns that exclude are left out (the check after the stop applies them).
     */
    public static @Nullable List<String> typesOf(@Nullable String patterns) {
        if (patterns == null || patterns.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String raw : patterns.split(",")) {
            String p = raw.strip();
            if (p.isEmpty() || p.startsWith("!")) {
                continue;
            }
            if (p.regionMatches(true, 0, "builtins.", 0, "builtins.".length())) {
                p = p.substring("builtins.".length());
            }
            String builtin = BUILTIN.get(p.toLowerCase(Locale.ROOT));
            if (builtin == null) {
                return null;
            }
            out.add(builtin);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * The {@code exceptionOptions} for the filters chosen ({@code raised}, {@code uncaught}, {@code userUnhandled})
     * and the exception breakpoints' {@code types}, stopped on wherever they are raised.
     */
    public static ExceptionOptions[] options(List<String> filters, List<String> types) {
        List<ExceptionOptions> out = new ArrayList<>();
        if (filters.contains("raised")) {
            out.add(option(ExceptionBreakMode.ALWAYS, List.of()));
        }
        if (filters.contains("uncaught")) {
            out.add(option(ExceptionBreakMode.UNHANDLED, List.of()));
        }
        if (filters.contains("userUnhandled")) {
            out.add(option(ExceptionBreakMode.USER_UNHANDLED, List.of()));
        }
        Set<String> named = new LinkedHashSet<>(types);
        if (!named.isEmpty() && !filters.contains("raised")) {
            out.add(option(ExceptionBreakMode.ALWAYS, List.copyOf(named)));
        }
        return out.toArray(new ExceptionOptions[0]);
    }

    private static ExceptionOptions option(ExceptionBreakMode mode, List<String> types) {
        ExceptionPathSegment root = new ExceptionPathSegment();
        root.setNames(new String[] {ROOT});
        ExceptionOptions o = new ExceptionOptions();
        if (types.isEmpty()) {
            o.setPath(new ExceptionPathSegment[] {root});
        } else {
            ExceptionPathSegment named = new ExceptionPathSegment();
            named.setNames(types.toArray(new String[0]));
            o.setPath(new ExceptionPathSegment[] {root, named});
        }
        o.setBreakMode(mode);
        return o;
    }
}

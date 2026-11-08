package com.editora.dap;

import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Toolkit- and lsp4j.debug-free value types exposed by {@link DapManager} to the {@code ui}/{@code editor}
 * layers, so they never depend on the DAP wire types. {@link DapClient} maps the raw lsp4j.debug objects
 * into these.
 */
public final class DapModels {

    private DapModels() {}

    /** A thread of the debuggee. */
    public record ThreadInfo(int id, String name) {}

    /**
     * A thread as the Threads view shows it: whether it is suspended, and why ({@code breakpoint}, {@code step},
     * {@code pause}, {@code exception}… as the adapter said; {@code ""} when it did not). {@code shown} is the thread
     * whose stack and variables the Debug window shows.
     */
    public record ThreadStatus(int id, String name, boolean paused, String reason, boolean shown) {}

    /**
     * One frame of a thread's call stack. {@code file} may be null for frames with no source. {@code sourceUri}
     * is the adapter's own name for a source that is not a file on this machine — java-debug's
     * {@code jdt://contents/…} for a JDK or library class, whose text jdtls can supply
     * ({@code java/classFileContents}) — and null for an ordinary file ({@code file}).
     */
    public record StackFrameInfo(
            int id,
            String name,
            @Nullable Path file,
            int line,
            int column,
            @Nullable String sourceUri,
            String hint) {
        /**
         * {@code hint}: {@link #HINT_SUBTLE} for a frame the adapter de-emphasizes (js-debug's {@code skipFiles}:
         * Node's own modules), {@link #HINT_LABEL} for a row that only labels the stack (an {@code await} between
         * async frames), {@link #HINT_FOLD} for the row standing for frames folded away; "" otherwise.
         */
        public StackFrameInfo {
            hint = hint == null ? "" : hint;
        }

        public StackFrameInfo(
                int id, String name, @Nullable Path file, int line, int column, @Nullable String sourceUri) {
            this(id, name, file, line, column, sourceUri, "");
        }

        public StackFrameInfo(int id, String name, Path file, int line, int column) {
            this(id, name, file, line, column, null);
        }

        /** This frame at another (0-based) line — the line it is shown on in an edited document. */
        public StackFrameInfo atLine(int newLine) {
            return new StackFrameInfo(id, name, file, newLine, column, sourceUri, hint);
        }
    }

    /** {@link StackFrameInfo#hint} of a de-emphasized frame (library or runtime code the user does not step into). */
    public static final String HINT_SUBTLE = "subtle";

    /** {@link StackFrameInfo#hint} of a label row, such as js-debug's {@code await} between async frames. */
    public static final String HINT_LABEL = "label";

    /** {@link StackFrameInfo#hint} of the row standing for folded frames; its {@code line} is how many. */
    public static final String HINT_FOLD = "fold";

    /** A variable scope of a stack frame (e.g. "Local", "Static"); {@code variablesReference} fetches its
     *  variables. */
    public record ScopeInfo(String name, int variablesReference, boolean expensive) {}

    /**
     * A variable (or child). A non-zero {@code variablesReference} means it's expandable (object/array).
     * {@code namedVariables} / {@code indexedVariables} are the child counts an adapter reports when the client
     * declared {@code supportsVariablePaging} (0 = not reported): a large {@code indexedVariables} is what lets
     * the children be fetched a page at a time instead of all at once.
     *
     * <p>{@code lazy} is DAP's {@code presentationHint.lazy}: the value is not computed until asked for, and the
     * one child of {@code variablesReference} holds it. java-debug 0.53.2 sends every object whose class has its
     * own {@code toString()} this way ({@code Integer}, enums, records, dates): the row reads {@code Integer@62}
     * and its value hides one level down. {@link LazyValues} resolves such a row before anyone shows it.
     *
     * <p>{@code note} says why a row shows less than its value ({@code null}: nothing to say).
     */
    public record VariableInfo(
            String name,
            String value,
            String type,
            int variablesReference,
            int namedVariables,
            int indexedVariables,
            boolean lazy,
            @Nullable ValueNote note) {

        public VariableInfo(
                String name,
                String value,
                String type,
                int variablesReference,
                int namedVariables,
                int indexedVariables,
                boolean lazy) {
            this(name, value, type, variablesReference, namedVariables, indexedVariables, lazy, null);
        }

        public VariableInfo(
                String name,
                String value,
                String type,
                int variablesReference,
                int namedVariables,
                int indexedVariables) {
            this(name, value, type, variablesReference, namedVariables, indexedVariables, false);
        }

        public VariableInfo(String name, String value, String type, int variablesReference) {
            this(name, value, type, variablesReference, 0, 0);
        }
    }

    /**
     * Why a variable shows only {@code Type@id} (Java debugging review 3, B201/B202): its {@code toString()} was
     * not run within the stop's time budget (a click loads it), cannot run after Pause, or failed.
     */
    public enum ValueNote {
        /** Not loaded within the stop's budget: still lazy, loaded on click or expand. */
        NOT_LOADED,
        /** The thread was stopped by Pause, and java-debug runs methods only on a thread stopped by a step or breakpoint. */
        NO_TO_STRING_AFTER_PAUSE,
        /** {@code toString()} threw (or gave nothing). */
        TO_STRING_FAILED,
        /** A click asked for a {@link #NOT_LOADED} row and the answer has not come yet (the Variables tree only). */
        LOADING
    }

    /** Where execution is currently suspended (top frame), used to highlight + jump the editor. */
    public record StopLocation(int threadId, String reason, Path file, int line) {}

    /**
     * A full {@code evaluate} response: the rendered result, an expandable children reference (0 = leaf),
     * and the value's type when the adapter reports one. Used by watches and the hover value popup.
     * {@code failed} marks a request the adapter refused; {@code result} is then its message, not a value.
     */
    public record EvalResult(
            String result,
            int variablesReference,
            @Nullable String type,
            int namedVariables,
            int indexedVariables,
            boolean failed) {

        public EvalResult(String result, int variablesReference, @Nullable String type) {
            this(result, variablesReference, type, 0, 0, false);
        }

        public EvalResult(
                String result, int variablesReference, String type, int namedVariables, int indexedVariables) {
            this(result, variablesReference, type, namedVariables, indexedVariables, false);
        }

        /** An evaluation the adapter refused, carrying its message. */
        public static EvalResult failure(String message) {
            return new EvalResult(message == null ? "" : message, 0, null, 0, 0, true);
        }
    }

    /**
     * A breakpoint to send to the adapter for one file: 0-based {@code line} + optional condition/log, and the 1-based
     * {@code column} a lambda breakpoint stops at (the lambda's start), or 0 for the line itself.
     */
    public record LineBreakpoint(
            int line,
            @Nullable String condition,
            @Nullable String logMessage,
            @Nullable String hitCondition,
            int column) {
        public LineBreakpoint(
                int line, @Nullable String condition, @Nullable String logMessage, @Nullable String hitCondition) {
            this(line, condition, logMessage, hitCondition, 0);
        }

        public LineBreakpoint(int line, @Nullable String condition, @Nullable String logMessage) {
            this(line, condition, logMessage, null);
        }
    }

    /** All breakpoints for one source file. */
    public record FileBreakpoints(Path file, List<LineBreakpoint> breakpoints) {}

    /**
     * A method breakpoint ({@code field == false}: a DAP function breakpoint) or a field watchpoint (a DAP data
     * breakpoint) to send to the adapter. {@code name} is what the adapter takes — java-debug's {@code pkg.Class#member}
     * for both; {@code access} ({@code write}, {@code read}, {@code readWrite}) applies to a field only.
     */
    public record MemberBreakpoint(
            boolean field,
            String name,
            String access,
            @Nullable String condition,
            @Nullable String hitCondition) {}

    /**
     * What the adapter's {@code dataBreakpointInfo} says of a variable: the {@code dataId} a data breakpoint on it
     * takes (java-debug: {@code pkg.Class#field}), how it describes it, and the accesses it can watch.
     */
    public record DataBreakpointTarget(String dataId, String description, List<String> accessTypes) {}

    /**
     * What the adapter says about one breakpoint it was sent, from a {@code setBreakpoints} response or a
     * later {@code breakpoint} event. {@code line} is the 0-based line that was <em>requested</em>;
     * {@code actualLine} the 0-based line the adapter bound it to (-1 when it named none).
     *
     * <p>{@code verified == false} alone means "not bound yet": java-debug binds a breakpoint only when its
     * class is loaded, and js-debug and debugpy say the same with a {@code message} ("Unbound breakpoint",
     * "Waiting for code to be loaded…"). So a message does not make a breakpoint a rejected one — only
     * {@code failed} does: the adapter's own {@code reason: "failed"}, an error answer to the whole request,
     * or (see {@link DapManager.Listener#onNotice}) a condition it could not evaluate.
     */
    public record BreakpointStatus(int line, boolean verified, boolean failed, String message, int actualLine) {

        public BreakpointStatus {
            message = message == null ? "" : message.strip();
        }

        /** Unverified and not known to have failed: the adapter may still bind it. */
        public boolean pending() {
            return !verified && !failed;
        }
    }

    /** The exception a thread is stopped on: its type name and message (either may be empty). */
    public record ExceptionInfo(
            String type, String message, String breakMode, String stackTrace, List<ExceptionInfo> causes) {

        public ExceptionInfo {
            type = type == null ? "" : type.strip();
            message = message == null ? "" : message.strip();
            breakMode = breakMode == null ? "" : breakMode.strip();
            stackTrace = stackTrace == null ? "" : stackTrace.strip();
            causes = causes == null ? List.of() : List.copyOf(causes);
        }

        /** An exception named by its type and message alone. */
        public ExceptionInfo(String type, String message) {
            this(type, message, "", "", List.of());
        }

        /**
         * Whether the program would not catch it ({@code unhandled}, {@code userUnhandled}): java-debug says
         * {@code userUnhandled} for an uncaught exception and {@code always} for a caught one.
         */
        public boolean uncaught() {
            return "unhandled".equals(breakMode) || "userUnhandled".equals(breakMode);
        }

        /** Whether the adapter said the program catches it ({@code always}). */
        public boolean caught() {
            return "always".equals(breakMode);
        }

        public boolean isEmpty() {
            return type.isEmpty() && message.isEmpty();
        }
    }

    /** An exception filter an adapter offers ({@code exceptionBreakpointFilters}): its id, label and default. */
    public record ExceptionFilterOption(String filter, String label, boolean byDefault) {}

    /**
     * What java-debug's {@code redefineClasses} did: the classes it replaced in the running program, or the
     * reason it could not ({@code error}, empty when it worked). No classes and no error: nothing had changed.
     */
    public record CodeReplace(List<String> changedClasses, String error) {

        public CodeReplace {
            changedClasses = changedClasses == null ? List.of() : List.copyOf(changedClasses);
            error = error == null ? "" : error.strip();
        }

        public boolean failed() {
            return !error.isEmpty();
        }

        /**
         * Whether the error is java-debug's "JVM contains obsolete methods": the classes were replaced, but methods
         * that were running when the code changed go on with their old code until they return.
         */
        public boolean obsoleteMethods() {
            return error.toLowerCase(java.util.Locale.ROOT).contains("obsolete methods");
        }
    }

    /**
     * One answer of DAP {@code completions}: {@code label} shown, {@code text} inserted (null: the label), its
     * {@code type} ("method", "field", "variable", … lower case, "" when not said), and the 0-based {@code start}
     * and {@code length} of the text it replaces (-1: the word before the caret).
     */
    public record CompletionTarget(
            String label, String text, String type, String detail, String sortText, int start, int length) {

        /** What accepting it inserts. */
        public String insert() {
            return text == null || text.isEmpty() ? label : text;
        }
    }

    /** One call the debugger can step into on the current line ({@code stepInTargets}): its id and label. */
    public record StepTarget(int id, String label, int line) {

        public StepTarget {
            label = label == null ? "" : label.strip();
        }
    }
}

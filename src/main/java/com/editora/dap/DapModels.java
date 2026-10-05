package com.editora.dap;

import java.nio.file.Path;
import java.util.List;

/**
 * Toolkit- and lsp4j.debug-free value types exposed by {@link DapManager} to the {@code ui}/{@code editor}
 * layers, so they never depend on the DAP wire types. {@link DapClient} maps the raw lsp4j.debug objects
 * into these (see {@code DapMappers}).
 */
public final class DapModels {

    private DapModels() {}

    /** A thread of the debuggee. */
    public record ThreadInfo(int id, String name) {}

    /** One frame of a thread's call stack. {@code file} may be null for frames with no source. */
    public record StackFrameInfo(int id, String name, Path file, int line, int column) {}

    /** A variable scope of a stack frame (e.g. "Local", "Static"); {@code variablesReference} fetches its
     *  variables. */
    public record ScopeInfo(String name, int variablesReference, boolean expensive) {}

    /**
     * A variable (or child). A non-zero {@code variablesReference} means it's expandable (object/array).
     * {@code namedVariables} / {@code indexedVariables} are the child counts an adapter reports when the client
     * declared {@code supportsVariablePaging} (0 = not reported): a large {@code indexedVariables} is what lets
     * the children be fetched a page at a time instead of all at once.
     */
    public record VariableInfo(
            String name, String value, String type, int variablesReference, int namedVariables, int indexedVariables) {

        public VariableInfo(String name, String value, String type, int variablesReference) {
            this(name, value, type, variablesReference, 0, 0);
        }
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
            String type,
            int namedVariables,
            int indexedVariables,
            boolean failed) {

        public EvalResult(String result, int variablesReference, String type) {
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

    /** A breakpoint to send to the adapter for one file: 0-based {@code line} + optional condition/log. */
    public record LineBreakpoint(int line, String condition, String logMessage) {}

    /** All breakpoints for one source file. */
    public record FileBreakpoints(Path file, List<LineBreakpoint> breakpoints) {}

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
    public record ExceptionInfo(String type, String message) {

        public ExceptionInfo {
            type = type == null ? "" : type.strip();
            message = message == null ? "" : message.strip();
        }

        public boolean isEmpty() {
            return type.isEmpty() && message.isEmpty();
        }
    }
}

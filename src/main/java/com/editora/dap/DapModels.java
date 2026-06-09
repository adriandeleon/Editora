package com.editora.dap;

import java.nio.file.Path;
import java.util.List;

/**
 * Toolkit- and lsp4j.debug-free value types exposed by {@link DapManager} to the {@code ui}/{@code editor}
 * layers, so they never depend on the DAP wire types. {@link DapClient} maps the raw lsp4j.debug objects
 * into these (see {@code DapMappers}).
 */
public final class DapModels {

    private DapModels() {
    }

    /** A thread of the debuggee. */
    public record ThreadInfo(int id, String name) {
    }

    /** One frame of a thread's call stack. {@code file} may be null for frames with no source. */
    public record StackFrameInfo(int id, String name, Path file, int line, int column) {
    }

    /** A variable scope of a stack frame (e.g. "Local", "Static"); {@code variablesReference} fetches its
     *  variables. */
    public record ScopeInfo(String name, int variablesReference, boolean expensive) {
    }

    /** A variable (or child). A non-zero {@code variablesReference} means it's expandable (object/array). */
    public record VariableInfo(String name, String value, String type, int variablesReference) {
    }

    /** Where execution is currently suspended (top frame), used to highlight + jump the editor. */
    public record StopLocation(int threadId, String reason, Path file, int line) {
    }

    /** A breakpoint to send to the adapter for one file: 0-based {@code line} + optional condition/log. */
    public record LineBreakpoint(int line, String condition, String logMessage) {
    }

    /** All breakpoints for one source file. */
    public record FileBreakpoints(Path file, List<LineBreakpoint> breakpoints) {
    }
}

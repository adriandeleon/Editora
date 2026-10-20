package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.editora.config.Breakpoint;
import com.editora.dap.DapModels;
import com.editora.editor.BreakpointManager;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/**
 * The breakpoints of files with no open tab, as a debug session is told them at its start: read from the
 * breakpoint store and re-anchored against each file's text as it is on disk now. Pure apart from
 * {@link #readLinesOrNull}'s bounded read (moved out of {@link DebugCoordinator}, which caches the result).
 */
final class ClosedFileBreakpoints {

    /** Don't slurp a huge file just to re-anchor its breakpoints (fall back to the stored line indices). */
    private static final long MAX_BREAKPOINT_FILE_BYTES = 20L * 1024 * 1024;

    private ClosedFileBreakpoints() {}

    /** Reads a file's lines (over SFTP for a remote one; this runs off the FX thread) for re-anchoring, or {@code null} when it can't be used as-is (unreadable,
     *  too large, or non-UTF-8) — the caller then falls back to the stored line indices. */
    static @Nullable List<String> readLinesOrNull(Path p) {
        try {
            if (!Files.isReadable(p) || Files.size(p) > MAX_BREAKPOINT_FILE_BYTES) {
                return null;
            }
            return Files.readAllLines(p);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The DAP breakpoints for files that have no open tab, each re-anchored against the file's current text.
     * Pure/unit-testable: {@code isLocal} skips files whose machine cannot debug (a remote host that may not
     * run commands), {@code readLines} returns a file's lines (or
     * {@code null} to keep the stored indices). Open-tab files are excluded — their live buffer supplies them.
     * A file whose lines are read is re-anchored via {@link BreakpointManager#reanchor} (so an external edit
     * that shifted lines still hits the right code); an unreadable one keeps its persisted indices rather than
     * being dropped. Only enabled breakpoints are emitted.
     */
    static List<DapModels.FileBreakpoints> of(
            Map<String, List<Breakpoint>> map,
            Set<String> openPaths,
            Predicate<Path> isLocal,
            Function<Path, @Nullable List<String>> readLines) {
        List<DapModels.FileBreakpoints> out = new ArrayList<>();
        if (map == null) {
            return out;
        }
        for (Map.Entry<String, List<Breakpoint>> e : map.entrySet()) {
            String key = e.getKey();
            if (key == null || (openPaths != null && openPaths.contains(key))) {
                continue;
            }
            List<Breakpoint> bps = e.getValue();
            if (bps == null || bps.isEmpty()) {
                continue;
            }
            Path p;
            try {
                p = Vfs.parseStorable(key); // a remote file's key is its URI: null while its host is not connected
            } catch (RuntimeException ex) {
                continue;
            }
            if (p == null || !isLocal.test(p)) {
                continue; // a file whose host cannot run its debugger has nothing to send
            }
            List<String> lines = readLines.apply(p);
            List<DapModels.LineBreakpoint> out2 = new ArrayList<>();
            if (lines != null && !lines.isEmpty()) {
                var anchored = BreakpointManager.reanchor(
                        bps,
                        lines.size(),
                        i -> i >= 0 && i < lines.size() ? lines.get(i) : "",
                        BreakpointManager.MAX_REANCHOR_SCAN);
                for (Breakpoint bp : anchored.values()) {
                    if (bp.enabled()) {
                        out2.add(new DapModels.LineBreakpoint(
                                bp.line(), bp.condition(), bp.logMessage(), bp.hitCondition()));
                    }
                }
            } else {
                for (Breakpoint bp : bps) {
                    if (bp.enabled()) {
                        out2.add(new DapModels.LineBreakpoint(
                                bp.line(), bp.condition(), bp.logMessage(), bp.hitCondition()));
                    }
                }
            }
            if (!out2.isEmpty()) {
                out.add(new DapModels.FileBreakpoints(p, out2));
            }
        }
        return out;
    }
}

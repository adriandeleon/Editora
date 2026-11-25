package com.editora.ui;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.editora.config.Breakpoint;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DapServerRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * The debugger in the Project Map: the Map told of each stop the editor shows (and when nothing is paused any more), a
 * frame picked in the Map's call stack shown, and the breakpoint strip of a Map card's gutter — what a file's tab
 * gutter shows and does. Part of {@link DebugCoordinator}.
 */
final class MapDebugFollow {

    private final DebugCoordinator d;

    MapDebugFollow(DebugCoordinator d) {
        this.d = d;
    }

    // --- The Project Map follows the debugger (Map gaps round 3, item 2) ---

    /** Told each frame the editor shows, and when nothing is paused any more. */
    private Consumer<MapDebugStop> onMapStop = stop -> {};

    private MapDebugStop mapStop = MapDebugStop.RUNNING;

    /** Where the Project Map hears of each stop; told the current one at once. */
    void setOnMapStop(Consumer<MapDebugStop> listener) {
        onMapStop = listener;
        listener.accept(mapStop);
    }

    void mapStop(MapDebugStop stop) {
        mapStop = stop;
        onMapStop.accept(stop);
    }

    /** What the Map was last told. For tests. */
    MapDebugStop mapStop() {
        return mapStop;
    }

    /**
     * A frame picked in the Map's call stack: shown as a click on it in the Debug window would show it; when the stack
     * there no longer lists it (folded runtime frames, a newer stop), the status says so.
     */
    void selectFrameFromMap(DapModels.StackFrameInfo frame) {
        if (d.dap().state() != DapManager.State.SUSPENDED || d.s().panel == null) {
            return;
        }
        DapModels.StackFrameInfo shown = d.s().shownFrame;
        if (shown != null && shown.id() == frame.id()) {
            mapStop(mapStop); // the frame on show: the Map shows it again, as it was picked there
            return;
        }
        if (!d.panel().selectFrameById(frame.id())) {
            d.host.setStatus(tr("status.debug.frameNotListed", frame.name()));
        }
    }

    // --- Project Map cards' gutters (Map gaps round 3, item 1) ---

    /**
     * Whether a Map card of {@code file} has a breakpoint strip: debugging is on, and the file's language debugs where
     * the file's tools run — as its tab's gutter would have one.
     */
    boolean cardBreakpointsOn(Path file) {
        if (!d.debugSupportEnabled()) {
            return false;
        }
        EditorBuffer open = d.ops.bufferForPath(file);
        if (open != null) {
            return d.isDebuggableBuffer(open);
        }
        String language = com.editora.editor.debug.LineFunctions.languageOf(file);
        if (language == null || !DapServerRegistry.isDebuggable(language)) {
            return false;
        }
        return Vfs.isLocal(file) && com.editora.process.ExecTargets.runsHere(file)
                || DapManager.debuggableOnHost(language) && RemoteExec.runsFor(file);
    }

    /**
     * {@code file}'s breakpoint glyphs by 0-based line, for a Map card's gutter: its tab's, with what a live session
     * says of them, or the stored ones of a file with no tab (or a narrowed one).
     */
    Map<Integer, MapCardGutter.Breakpoint> cardBreakpoints(Path file) {
        Map<Integer, MapCardGutter.Breakpoint> out = new java.util.HashMap<>();
        EditorBuffer open = d.ops.bufferForPath(file);
        if (open != null && !open.isNarrowed()) {
            var manager = open.getBreakpointManager();
            Set<Integer> lines = new HashSet<>(manager.memberMarks().keySet());
            manager.snapshot().forEach(bp -> lines.add(bp.line()));
            for (int line : lines) {
                if (manager.hasGlyph(line)) {
                    String tip = manager.glyphTooltip(line);
                    out.put(line, new MapCardGutter.Breakpoint(manager.glyphClasses(line), tip == null ? "" : tip));
                }
            }
            return out;
        }
        String muted = d.breakpointsMuted() ? tr("debug.breakpoint.muted") : null;
        for (Breakpoint bp : d.ops.breakpointMap().getOrDefault(d.key(file), List.of())) {
            boolean waits = bp.isTriggered() && d.triggered.labelOf(bp.triggeredBy()) != null;
            String classes = com.editora.editor.debug.BreakpointManager.kindClasses(bp, waits);
            if (muted != null) {
                classes = classes == null ? "muted" : classes + " muted";
            }
            String tip = com.editora.editor.debug.BreakpointDescription.tooltip(bp, null, d.triggered::labelOf);
            tip = muted == null ? tip : tip == null || tip.isBlank() ? muted : muted + "\n" + tip;
            out.put(bp.line(), new MapCardGutter.Breakpoint(classes, tip == null ? "" : tip));
        }
        return out;
    }

    /**
     * A click in a Map card's breakpoint strip on {@code line} of {@code file}, whose text is {@code text}: what a
     * click in its tab's gutter does — through that tab when it has one — else the stored breakpoints of the file are
     * changed and a live session is told. A line with no code to stop on gets none, and the status says so.
     */
    void toggleCardBreakpoint(Path file, int line, String text) {
        if (!cardBreakpointsOn(file)) {
            return;
        }
        EditorBuffer open = d.ops.bufferForPath(file);
        if (open != null && !open.isNarrowed()) {
            var manager = open.getBreakpointManager();
            var mark = manager.isBreakpoint(line) ? null : manager.memberMark(line);
            if (mark != null) {
                mark.members().forEach(d.memberBreakpoints::toggle); // a method breakpoint's mark: removes it
            } else if (d.caretBreakpoints.breakpointAllowed(open, line, () -> toggleCardBreakpoint(file, line, text))) {
                open.toggleBreakpoint(line);
            }
            return;
        }
        String key = d.key(file);
        boolean set =
                d.ops.breakpointMap().getOrDefault(key, List.of()).stream().anyMatch(bp -> bp.line() == line);
        String language = com.editora.editor.debug.LineFunctions.languageOf(file);
        if (!set && com.editora.dap.CodeLessLines.isCodeLess(text, line, language == null ? "" : language)) {
            d.host.setStatus(tr("status.debug.noCodeOnLine"));
            return;
        }
        d.breakpointMenus
                .breakpointsPicker()
                .rewriteClosed(file, key, line, set ? null : Breakpoint.plain(line, lineOf(text, line)));
    }

    /** Line {@code line} (0-based) of {@code text}, or "" past its end. */
    static String lineOf(String text, int line) {
        int start = 0;
        for (int i = 0; i < line; i++) {
            int next = text.indexOf('\n', start);
            if (next < 0) {
                return "";
            }
            start = next + 1;
        }
        int end = text.indexOf('\n', start);
        String found = end < 0 ? text.substring(start) : text.substring(start, end);
        return found.endsWith("\r") ? found.substring(0, found.length() - 1) : found;
    }
}

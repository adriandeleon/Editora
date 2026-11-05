package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import com.editora.config.Breakpoint;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import com.editora.editor.debug.BreakpointDescription;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debug: Breakpoints — every breakpoint of the window's files (open tabs and the saved ones of closed files) in a
 * palette-style list: Enter jumps to it, Alt+E enables or disables it, Alt+D removes it, Alt+M mutes them all.
 * There was no place that listed breakpoints at all (IntelliJ's View Breakpoints, VS Code's BREAKPOINTS view).
 */
final class BreakpointsPicker {

    /** One breakpoint: its file, its whole-file 0-based line, and the tab it is open in (null: closed). */
    record Entry(
            Path file,
            String key,
            int line,
            Breakpoint breakpoint,
            @Nullable EditorBuffer buffer) {}

    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;
    private final DapManager dapManager;
    private final Runnable toggleMute;
    private final java.util.function.BooleanSupplier muted;

    BreakpointsPicker(
            CoordinatorHost host,
            DebugCoordinator.Ops ops,
            DapManager dapManager,
            Runnable toggleMute,
            java.util.function.BooleanSupplier muted) {
        this.host = host;
        this.ops = ops;
        this.dapManager = dapManager;
        this.toggleMute = toggleMute;
        this.muted = muted;
    }

    void show() {
        Path root = ops.projectRoot();
        Path home = homeDir();
        QuickOpen<Entry> picker = new QuickOpen<>(
                tr("debug.breakpoints.title"),
                tr("debug.breakpoints.prompt"),
                this::entries,
                BreakpointsPicker::label,
                e -> detail(e, root, home),
                this::jump);
        // The kind in the glyph's shape and colour, as in the gutter: a logpoint is a diamond (review 2 C107).
        picker.setItemIcon(e -> glyph(e.breakpoint()));
        picker.addRowAction(
                new KeyCodeCombination(KeyCode.E, KeyCombination.ALT_DOWN),
                tr("debug.breakpoints.keyEnable", "Alt+E"),
                this::toggleEnabled);
        picker.addRowAction(
                new KeyCodeCombination(KeyCode.D, KeyCombination.ALT_DOWN),
                tr("debug.breakpoints.keyRemove", "Alt+D"),
                this::remove);
        picker.addRowAction(
                new KeyCodeCombination(KeyCode.M, KeyCombination.ALT_DOWN),
                tr("debug.breakpoints.keyMute", "Alt+M"),
                e -> toggleMute.run());
        picker.setOverlayHost(host.overlayHost());
        if (entries().isEmpty()) {
            // Said in the list itself: the status bar is far from where the user is looking (review 2 C107).
            picker.setPlaceholder(tr("status.debug.noBreakpoints"));
        }
        picker.show(host.window());
    }

    /** Every breakpoint: those of open tabs as they are now, then the saved ones of files with no tab. */
    List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        Set<String> open = new HashSet<>();
        host.forEachBuffer(b -> {
            if (b.getPath() == null) {
                return;
            }
            String key = DebugCoordinator.key(b.getPath());
            if (!open.add(key)) {
                return; // a second tab on the same file
            }
            for (Breakpoint bp : b.getBreakpointManager().documentSnapshot()) {
                out.add(new Entry(b.getPath(), key, bp.line(), bp, b));
            }
        });
        for (Map.Entry<String, List<Breakpoint>> e : ops.breakpointMap().entrySet()) {
            if (e.getKey() == null || open.contains(e.getKey()) || e.getValue() == null) {
                continue;
            }
            Path p;
            try {
                p = Vfs.parseStorable(e.getKey());
            } catch (RuntimeException ex) {
                continue;
            }
            if (p == null) {
                continue;
            }
            for (Breakpoint bp : e.getValue()) {
                out.add(new Entry(p, e.getKey(), bp.line(), bp, null));
            }
        }
        return out;
    }

    static String label(Entry e) {
        String name = e.file().getFileName() == null
                ? e.file().toString()
                : e.file().getFileName().toString();
        String text = e.breakpoint().lineText().strip();
        return name + ":" + (e.line() + 1) + (text.isEmpty() ? "" : "  " + text);
    }

    /** The gutter's mark for a breakpoint: ◆ a logpoint, ● any other; hollow when disabled. */
    static String mark(Breakpoint bp) {
        boolean log = bp.isLogpoint();
        return bp.enabled() ? (log ? "◆" : "●") : (log ? "◇" : "○");
    }

    /** The gutter glyph's style class for a breakpoint (its colour), as {@code BreakpointManager} picks it. */
    static String kindClass(Breakpoint bp) {
        if (!bp.enabled()) {
            return "breakpoint-disabled";
        }
        if (bp.isLogpoint()) {
            return "breakpoint-logpoint";
        }
        return bp.isConditional() ? "breakpoint-conditional" : "breakpoint-plain";
    }

    private static javafx.scene.Node glyph(Breakpoint bp) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(mark(bp) + " ");
        t.getStyleClass().addAll("breakpoint-marker", "breakpoint-list-mark", kindClass(bp));
        return t;
    }

    /**
     * The row's second line: what the breakpoint does (condition, log message…) first, then its folder — relative
     * to the project, or with the home folder as {@code ~}. The whole absolute folder took the row's width, and the
     * condition was cut off (review 2 C107).
     */
    static String detail(Entry e, @Nullable Path projectRoot, @Nullable Path home) {
        List<String> parts = new ArrayList<>(BreakpointDescription.lines(e.breakpoint()));
        String folder = folder(e.file().getParent(), projectRoot, home);
        if (!folder.isEmpty()) {
            parts.add(folder);
        }
        return String.join("  ·  ", parts);
    }

    /** {@code dir} as the list shows it: project-relative ("" at the project root), else {@code ~}-shortened. */
    static String folder(@Nullable Path dir, @Nullable Path projectRoot, @Nullable Path home) {
        if (dir == null) {
            return "";
        }
        if (projectRoot != null && dir.startsWith(projectRoot)) {
            return projectRoot.relativize(dir).toString().replace('\\', '/');
        }
        if (home != null && dir.startsWith(home) && !dir.equals(home)) {
            return "~/" + home.relativize(dir).toString().replace('\\', '/');
        }
        return dir.toString();
    }

    private static @Nullable Path homeDir() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home);
    }

    private void jump(Entry e) {
        ops.openPath(e.file());
        EditorBuffer b = ops.bufferForPath(e.file());
        if (b != null) {
            ops.afterBufferLoad(
                    b, () -> b.jumpToLine(e.line() - b.getBreakpointManager().regionFirstLine()));
        }
    }

    void toggleEnabled(Entry e) {
        if (e.buffer() != null) {
            int view = e.line() - e.buffer().getBreakpointManager().regionFirstLine();
            e.buffer().getBreakpointManager().setEnabled(view, !e.breakpoint().enabled());
            e.buffer().refreshGutterLine(view);
            return; // the manager's change persists it and tells a live session
        }
        rewriteClosed(e, e.breakpoint().withEnabled(!e.breakpoint().enabled()));
    }

    void remove(Entry e) {
        if (e.buffer() != null) {
            int view = e.line() - e.buffer().getBreakpointManager().regionFirstLine();
            if (e.buffer().getBreakpointManager().isBreakpoint(view)) {
                e.buffer().toggleBreakpoint(view);
            }
            return;
        }
        rewriteClosed(e, null);
    }

    /** A closed file's saved breakpoint replaced ({@code next}) or removed (null): saved, and sent to a session. */
    private void rewriteClosed(Entry e, @Nullable Breakpoint next) {
        Map<String, List<Breakpoint>> map = ops.breakpointMap();
        List<Breakpoint> list = new ArrayList<>(map.getOrDefault(e.key(), List.of()));
        list.removeIf(bp -> bp.line() == e.line());
        if (next != null) {
            list.add(next);
            list.sort(java.util.Comparator.comparingInt(Breakpoint::line));
        }
        if (list.isEmpty()) {
            map.remove(e.key());
        } else {
            map.put(e.key(), list);
        }
        ops.saveBreakpoints();
        ops.breakpointsStored(map, e.key());
        if (dapManager.isActive() && !muted.getAsBoolean()) {
            List<DapModels.LineBreakpoint> lines = new ArrayList<>();
            for (Breakpoint bp : list) {
                if (bp.enabled()) {
                    lines.add(new DapModels.LineBreakpoint(
                            bp.line(), bp.condition(), bp.logMessage(), bp.hitCondition()));
                }
            }
            dapManager.updateBreakpoints(new DapModels.FileBreakpoints(e.file(), lines));
        }
    }
}

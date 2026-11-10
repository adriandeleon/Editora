package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.PinnedLine;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import com.editora.editor.debug.PinManager;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Values pinned to lines while debugging (Visual Studio's pinned DataTips, IntelliJ's inline watches): an expression
 * pinned to a line is evaluated in the selected frame at every stop and painted at the end of that line, after the
 * values the editor shows by itself, until it is unpinned. Kept per file and project with the breakpoints.
 */
final class PinnedValues {

    /** Most pins evaluated at one stop, over every open file: each is a request to the debugger. */
    static final int MAX_EVALUATED = 40;

    /** Most characters of a value painted after its expression. */
    static final int MAX_VALUE_CHARS = 60;

    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;
    /** The focused debug session's manager. */
    private final java.util.function.Supplier<DapManager> dapManager;

    /** The frame the shown values were evaluated in, or -1 (nothing shown). */
    private int frameId = -1;

    /** Answers to an older refresh than this are dropped (the program moved on). */
    private int generation;

    /** The buffers painting pinned values now. */
    private final java.util.Set<EditorBuffer> painted =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    PinnedValues(CoordinatorHost host, DebugCoordinator.Ops ops, java.util.function.Supplier<DapManager> dapManager) {
        this.host = host;
        this.ops = ops;
        this.dapManager = dapManager;
    }

    /** {@code buffer} opened: its saved pins come back, and a change to them is saved and shown. */
    void restore(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        PinManager pins = buffer.getPinManager();
        if (pins.restore(ops.pins(DebugCoordinator.key(file)))) {
            persist(buffer);
        }
        pins.setOnChanged(() -> {
            persist(buffer);
            if (frameId >= 0) {
                refresh(frameId); // a pin added or removed while paused shows at once
            }
        });
    }

    private void persist(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null || buffer.isNarrowed()) {
            return; // region-relative lines while narrowed: written when it widens
        }
        ops.persistPins(DebugCoordinator.key(file), buffer.getPinManager().snapshot());
    }

    /**
     * Debug: Pin Value to Line — pins the selection, or the expression at the caret, to the caret's line; unpins it
     * when it is pinned there already. With neither, it asks for the expression.
     */
    void togglePinAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            host.setStatus(tr("status.debug.saveFirst"));
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        String selected = b.getFocusedArea().getSelectedText();
        String expression = selected != null && !selected.isBlank() && !selected.contains("\n")
                ? selected.strip()
                : com.editora.editor.debug.DebugExpressions.atCaret(b.getFocusedArea());
        if (expression == null || expression.isBlank()) {
            host.promptText(tr("dialog.debug.pinTitle"), tr("dialog.debug.pinLabel", line + 1), "", text -> {
                if (text != null && !text.isBlank()) {
                    toggle(b, line, text.strip());
                }
            });
            return;
        }
        toggle(b, line, expression);
    }

    /** Pins {@code expression} to {@code line} (0-based, as the buffer shows it), or unpins it; says which. */
    void toggle(EditorBuffer b, int line, String expression) {
        PinManager pins = b.getPinManager();
        boolean had = pins.expressions(line).contains(expression);
        if (!had && pins.expressions(line).size() >= PinManager.MAX_PER_LINE) {
            host.setStatus(tr("status.debug.pinsFull", PinManager.MAX_PER_LINE));
            return;
        }
        boolean pinned = pins.toggle(line, expression);
        host.setStatus(tr(pinned ? "status.debug.pinned" : "status.debug.unpinned", expression, line + 1));
    }

    /** Debug: Unpin Values on Line — removes every value pinned to the caret's line. */
    void unpinLine() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        if (b.getPinManager().clearLine(line)) {
            host.setStatus(tr("status.debug.unpinnedLine", line + 1));
        } else {
            host.setStatus(tr("status.debug.noPins", line + 1));
        }
    }

    /**
     * Evaluates every pinned value of the open files in frame {@code frameId} and paints the answers. Called on each
     * stop and frame change; values that fail paint their error, so a pin out of scope says so.
     */
    void refresh(int frameId) {
        this.frameId = frameId;
        int asked = ++generation;
        if (dapManager.get().state() != DapManager.State.SUSPENDED || frameId < 0) {
            return;
        }
        List<EditorBuffer> buffers = new ArrayList<>();
        host.forEachBuffer(b -> {
            if (!b.getPinManager().isEmpty() || painted.contains(b)) {
                buffers.add(b);
            }
        });
        int budget = MAX_EVALUATED;
        for (EditorBuffer b : buffers) {
            Map<Integer, PinnedLine> lines = b.getPinManager().byLine();
            if (lines.isEmpty()) {
                b.setPinnedValues(null);
                painted.remove(b);
                continue;
            }
            Map<Integer, Map<String, String>> answers = new HashMap<>();
            int[] pending = {0};
            List<Runnable> requests = new ArrayList<>();
            for (PinnedLine p : lines.values()) {
                for (String expr : p.expressions()) {
                    if (budget-- <= 0) {
                        break;
                    }
                    pending[0]++;
                    int line = p.line();
                    requests.add(() -> dapManager.get().evaluateFull(expr, frameId, "watch", r -> {
                        if (asked != generation) {
                            return; // a newer stop or frame took over
                        }
                        answers.computeIfAbsent(line, k -> new LinkedHashMap<>())
                                .put(expr, shown(r));
                        if (--pending[0] == 0) {
                            Map<Integer, String> shown = paint(lines, answers);
                            b.setPinnedValues(shown);
                            painted.add(b);
                            onPainted.accept(b, shown);
                        }
                    }));
                }
            }
            requests.forEach(Runnable::run);
        }
    }

    /** Hears each buffer's pinned values as they are painted at a stop (Debug History keeps them). */
    private java.util.function.BiConsumer<EditorBuffer, Map<Integer, String>> onPainted = (b, shown) -> {};

    void setOnPainted(java.util.function.BiConsumer<EditorBuffer, Map<Integer, String>> listener) {
        this.onPainted = listener;
    }

    /**
     * Paints the pinned values an earlier stop showed ({@code recorded}, by file then line) in the open files, in
     * place of the live ones; a file it showed none in shows none. Nothing is evaluated.
     */
    void showRecorded(Map<java.nio.file.Path, Map<Integer, String>> recorded) {
        clear();
        host.forEachBuffer(b -> {
            Map<Integer, String> shown = b.getPath() == null ? null : recorded.get(b.getPath());
            if (shown != null && !shown.isEmpty()) {
                b.setPinnedValues(shown);
                painted.add(b);
            }
        });
    }

    /** The program runs again, or the session ended: no pinned value is shown. */
    void clear() {
        frameId = -1;
        generation++;
        for (EditorBuffer b : new ArrayList<>(painted)) {
            b.setPinnedValues(null);
        }
        painted.clear();
    }

    /** An answer as painted: its value cut to {@link #MAX_VALUE_CHARS}, or what the debugger said went wrong. Pure. */
    static String shown(DapModels.@Nullable EvalResult r) {
        if (r == null) {
            return "?";
        }
        String text = r.failed() ? tr("debug.pin.failed", firstLine(r.result())) : firstLine(r.result());
        return text.length() > MAX_VALUE_CHARS ? text.substring(0, MAX_VALUE_CHARS) + "…" : text;
    }

    private static String firstLine(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).strip() + " …";
    }

    /** Each line's {@code expr = value  ·  expr2 = value2}, in the order the line's values were pinned. Pure. */
    static Map<Integer, String> paint(Map<Integer, PinnedLine> lines, Map<Integer, Map<String, String>> answers) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (PinnedLine p : lines.values()) {
            Map<String, String> got = answers.getOrDefault(p.line(), Map.of());
            List<String> parts = new ArrayList<>();
            for (String expr : p.expressions()) {
                String value = got.get(expr);
                if (value != null) {
                    parts.add(expr + " = " + value);
                }
            }
            if (!parts.isEmpty()) {
                out.put(p.line(), String.join("  ·  ", parts));
            }
        }
        return out;
    }
}

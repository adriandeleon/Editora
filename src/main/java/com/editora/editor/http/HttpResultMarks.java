package com.editora.editor.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import javafx.css.PseudoClass;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

import com.editora.editor.CodeLensShift;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.Inlay;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional;
import org.jspecify.annotations.Nullable;

/**
 * The results drawn after the method/URL lines of a {@code .http} buffer (U11), as inlays after the line's last
 * character ({@link Inlay}: never in the document, a copy, a search or a save), and the class each such line's ▶
 * takes. The window computes them when a run lands, when a saved history is restored and when History is cleared
 * ({@link HttpLineResult}); this keeps them on their lines in between:
 *
 * <ul>
 *   <li>an edit that adds or removes lines moves them with their requests at once ({@link CodeLensShift});
 *   <li>at each Run scan (a settled edit) a result whose line no longer reads as it did is dropped — the request
 *       was edited, and its last result would describe another URL. After a batch of changes (an undo of several
 *       steps) the line is looked for by its text, as the running ■ is ({@link HttpRunMark}).
 * </ul>
 *
 * <p>A click on the text runs the result's action; the labels are mouse-transparent, so it is found by its bounds,
 * as a code lens is. Allocates only when results exist: no work at all for a buffer without any. FX thread only.
 */
public final class HttpResultMarks {

    /** Every segment's class starts with this. */
    static final String STYLE_PREFIX = "http-result";

    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("result-hover");

    /** 0-based line → its result. */
    private Map<Integer, HttpLineResult> byLine = Map.of();
    /** A batch of changes moved lines in ways not replayed here: the next scan finds the lines by their text. */
    private boolean lost;

    private @Nullable Tooltip tooltip;
    private int hoveredLine = -1;
    private final List<Node> hovered = new ArrayList<>(3);
    private @Nullable Cursor cursorBefore;

    public boolean isEmpty() {
        return byLine.isEmpty();
    }

    /** The results, by line, as drawn now. */
    public List<HttpLineResult> all() {
        List<HttpLineResult> out = new ArrayList<>();
        byLine.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            int line = e.getKey();
            HttpLineResult r = e.getValue();
            out.add(line == r.line() ? r : withLine(r, line));
        });
        return out;
    }

    @Nullable
    HttpLineResult at(int line) {
        return byLine.get(line);
    }

    /**
     * Takes new results — each kept only when its line reads {@code anchor} now — and returns the lines whose look
     * changed (to repaint their gutter rows), or null when nothing that is drawn changed.
     */
    @Nullable
    public Set<Integer> set(List<HttpLineResult> results, IntFunction<String> textOf) {
        Map<Integer, HttpLineResult> next = new HashMap<>();
        if (results != null) {
            for (HttpLineResult r : results) {
                if (r != null && r.anchor().equals(textOf.apply(r.line()))) {
                    next.putIfAbsent(r.line(), r);
                }
            }
        }
        boolean same = next.size() == byLine.size();
        if (same) {
            for (Map.Entry<Integer, HttpLineResult> e : next.entrySet()) {
                HttpLineResult was = byLine.get(e.getKey());
                if (was == null || !e.getValue().looksLike(withLine(was, e.getKey()))) {
                    same = false;
                    break;
                }
            }
        }
        Set<Integer> changed = same ? null : new HashSet<>(byLine.keySet());
        if (changed != null) {
            changed.addAll(next.keySet());
        }
        byLine = next.isEmpty() ? Map.of() : next; // the new clicks, even when nothing looks different
        lost = false;
        return changed;
    }

    /** Moves the results with an edit that added or removed lines; true when any moved or went. */
    public boolean shift(List<PlainTextChange> changes, CodeArea area) {
        if (byLine.isEmpty()) {
            return false;
        }
        if (changes.size() > 1) {
            for (PlainTextChange c : changes) {
                if (c.getRemoved().indexOf('\n') >= 0 || c.getInserted().indexOf('\n') >= 0) {
                    lost = true; // each change is in the coordinates of its own step: the scan finds them by text
                    return false;
                }
            }
            return false;
        }
        Map<Integer, HttpLineResult> moved = CodeLensShift.afterChanges(byLine, changes, area);
        if (moved == null) {
            return false;
        }
        byLine = moved;
        return true;
    }

    /**
     * After a Run scan: keeps each result whose line is still a request line reading as it did, else drops it.
     * Returns the lines whose look changed, or null.
     */
    @Nullable
    public Set<Integer> reanchor(List<Integer> requestLines, IntFunction<String> textOf) {
        if (byLine.isEmpty()) {
            lost = false;
            return null;
        }
        boolean intact = !lost;
        if (intact) {
            for (Map.Entry<Integer, HttpLineResult> e : byLine.entrySet()) {
                if (!still(e.getKey(), e.getValue(), requestLines, textOf)) {
                    intact = false;
                    break;
                }
            }
        }
        if (intact) {
            return null;
        }
        Map<Integer, HttpLineResult> next = new HashMap<>();
        for (Map.Entry<Integer, HttpLineResult> e : byLine.entrySet()) {
            int line = e.getKey();
            HttpLineResult r = e.getValue();
            if (!still(line, r, requestLines, textOf)) {
                if (!lost) {
                    continue; // edited: its result goes
                }
                int i = HttpRunMark.find(requestLines, textOf, r.anchor(), nearest(requestLines, line));
                if (i < 0) {
                    continue;
                }
                line = requestLines.get(i);
            }
            next.putIfAbsent(line, r);
        }
        lost = false;
        Set<Integer> changed = new HashSet<>(byLine.keySet());
        changed.addAll(next.keySet());
        byLine = next.isEmpty() ? Map.of() : next;
        return changed;
    }

    private static boolean still(int line, HttpLineResult r, List<Integer> requestLines, IntFunction<String> textOf) {
        return requestLines.contains(line) && r.anchor().equals(textOf.apply(line));
    }

    /** The index in {@code lines} (ascending) of the first line at or after {@code line}. */
    private static int nearest(List<Integer> lines, int line) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) >= line) {
                return i;
            }
        }
        return Math.max(0, lines.size() - 1);
    }

    private static HttpLineResult withLine(HttpLineResult r, int line) {
        return line == r.line()
                ? r
                : new HttpLineResult(line, r.anchor(), r.segments(), r.glyphClass(), r.tooltip(), r.onClick());
    }

    /** The class {@code line}'s ▶ takes, or null. */
    @Nullable
    public String glyphClass(int line) {
        HttpLineResult r = byLine.isEmpty() ? null : byLine.get(line);
        return r == null ? null : r.glyphClass();
    }

    /**
     * {@code base} (the line's other inlays, may be null) followed by its result, drawn at {@code column} — the end of
     * the line. {@code base} itself when the line has none.
     */
    @Nullable
    public List<Inlay> inlays(int line, @Nullable List<Inlay> base, int column) {
        HttpLineResult r = byLine.isEmpty() ? null : byLine.get(line);
        if (r == null) {
            return base;
        }
        List<Inlay> out =
                new ArrayList<>((base == null ? 0 : base.size()) + r.segments().size());
        if (base != null) {
            out.addAll(base);
        }
        for (HttpLineResult.Segment s : r.segments()) {
            out.add(new Inlay(column, s.text(), s.styleClass()));
        }
        return out;
    }

    // --- the pointer -------------------------------------------------------------------------------------------

    /** A click on a result runs its action; hovering one lights it, shows the hand and the tooltip. */
    public void install(CodeArea a) {
        a.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress() || byLine.isEmpty()) {
                return;
            }
            int line = lineAt(a, e);
            if (resultNodeAt(a, e, line) == null) {
                return;
            }
            e.consume();
            hover(a, -1, null, e);
            activate(line);
        });
        a.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (byLine.isEmpty() && hoveredLine < 0) {
                return;
            }
            int line = byLine.isEmpty() ? -1 : lineAt(a, e);
            Node node = resultNodeAt(a, e, line);
            hover(a, node == null ? -1 : line, node, e);
        });
        a.addEventFilter(MouseEvent.MOUSE_EXITED, e -> hover(a, -1, null, e));
    }

    /** Runs the action of {@code line}'s result, as a click on it does; false when it has none. */
    public boolean activate(int line) {
        HttpLineResult r = byLine.get(line);
        if (r == null) {
            return false;
        }
        r.onClick().run();
        return true;
    }

    /** The line under the pointer: the character under it, else the insertion point before it (see code lenses). */
    private static int lineAt(CodeArea a, MouseEvent e) {
        var hit = a.hit(e.getX(), e.getY());
        int offset = hit.getCharacterIndex().orElse(hit.getInsertionIndex());
        return a.offsetToPosition(offset, TwoDimensional.Bias.Backward).getMajor();
    }

    /** The result label under the pointer on {@code line}, or null. Looks only on a line that has a result. */
    private @Nullable Node resultNodeAt(CodeArea a, MouseEvent e, int line) {
        if (line < 0 || !byLine.containsKey(line)) {
            return null;
        }
        for (Node n : a.lookupAll(".inlay")) {
            if (!isResult(n)) {
                continue;
            }
            Point2D at = n.sceneToLocal(e.getSceneX(), e.getSceneY());
            // Not the gap that sets the result apart from the request: a click there places the caret.
            double gap = n instanceof Region r ? r.snappedLeftInset() : 0;
            if (at != null && at.getX() >= gap && n.contains(at)) {
                return n;
            }
        }
        return null;
    }

    static boolean isResult(Node n) {
        for (String c : n.getStyleClass()) {
            if (c.startsWith(STYLE_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private void hover(CodeArea a, int line, @Nullable Node node, MouseEvent e) {
        if (line == hoveredLine) {
            return;
        }
        for (Node n : hovered) {
            n.pseudoClassStateChanged(HOVER, false);
        }
        hovered.clear();
        if (hoveredLine >= 0) {
            a.setCursor(cursorBefore);
        }
        if (tooltip != null) {
            tooltip.hide();
        }
        hoveredLine = line;
        HttpLineResult r = line < 0 ? null : byLine.get(line);
        if (r == null || node == null) {
            hoveredLine = -1;
            return;
        }
        Parent row = node.getParent(); // the line's segments are siblings: light them together
        for (Node n : row == null ? List.of(node) : row.getChildrenUnmodifiable()) {
            if (isResult(n)) {
                n.pseudoClassStateChanged(HOVER, true);
                hovered.add(n);
            }
        }
        cursorBefore = a.getCursor();
        a.setCursor(Cursor.HAND);
        if (r.tooltip() != null && !r.tooltip().isEmpty()) {
            if (tooltip == null) {
                tooltip = new Tooltip();
                tooltip.setWrapText(true);
                tooltip.setMaxWidth(420);
            }
            tooltip.setText(r.tooltip());
            tooltip.show(a, e.getScreenX() + 12, e.getScreenY() + 16);
        }
    }
}

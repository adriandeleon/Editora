package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;
import org.fxmisc.richtext.multi.MultiCaretManager;

/**
 * Alt+drag column selection while <b>Word Wrap is on</b>: one range per <em>visual row</em> the rectangle
 * crosses.
 *
 * <p>The editor component's own box selection hit-tests each paragraph once, at its vertical centre — its
 * documentation calls a wrapped paragraph "one line". So a rectangle dragged down across a paragraph that
 * wraps to seven rows produced a single range, on the middle row, and typing edited one spot where seven
 * were drawn inside the rectangle. This takes the gesture over only while the area wraps; with wrap off the
 * component's handling runs exactly as before.
 *
 * <p>The ranges are applied through the manager's public calls (collapse, then one caret per range), since
 * its in-place update is not exposed. That rebuilds the carets on a pulse that changes the rectangle's rows,
 * and a pulse that does not is skipped.
 */
final class WrapBoxSelection {

    /** The component's own cap on the rows one box may cover. */
    private static final int MAX_ROWS = 5000;

    private final CodeArea area;
    private final MultiCaretManager<?, ?, ?> manager;
    private final EventHandler<MouseEvent> onMouse = this::handle;

    private boolean active;
    private double anchorX;
    private double anchorY;
    private List<int[]> last;

    private WrapBoxSelection(CodeArea area, MultiCaretManager<?, ?, ?> manager) {
        this.area = area;
        this.manager = manager;
        // A filter: the component's bindings are handlers on the same node and would consume the press first.
        area.addEventFilter(MouseEvent.ANY, onMouse);
    }

    static WrapBoxSelection install(CodeArea area, MultiCaretManager<?, ?, ?> manager) {
        return new WrapBoxSelection(area, manager);
    }

    void dispose() {
        area.removeEventFilter(MouseEvent.ANY, onMouse);
    }

    private void handle(MouseEvent e) {
        var type = e.getEventType();
        if (type == MouseEvent.MOUSE_PRESSED) {
            active = e.getButton() == MouseButton.PRIMARY && e.isAltDown() && area.isWrapText();
            if (active) {
                anchorX = e.getX();
                anchorY = e.getY();
                last = null;
                area.requestFocus();
                e.consume();
            }
        } else if (!active) {
            return;
        } else if (type == MouseEvent.MOUSE_DRAGGED) {
            drag(e.getX(), e.getY());
            e.consume();
        } else if (type == MouseEvent.MOUSE_RELEASED) {
            active = false;
            last = null;
            manager.mergeCoincident();
            e.consume();
        } else if (type == MouseEvent.DRAG_DETECTED) {
            e.consume();
        }
    }

    private void drag(double x, double y) {
        List<int[]> ranges = ranges(x, y);
        if (same(last, ranges)) {
            return;
        }
        last = ranges;
        manager.collapseToPrimary();
        area.selectRange(ranges.get(0)[0], ranges.get(0)[1]);
        for (int i = 1; i < ranges.size(); i++) {
            manager.addCaretWithSelection(ranges.get(i)[0], ranges.get(i)[1]);
        }
    }

    private List<int[]> ranges(double x, double y) {
        double left = Math.min(anchorX, x);
        double right = Math.max(anchorX, x);
        double top = Math.min(anchorY, y);
        double bottom = Math.max(anchorY, y);
        int first = paragraphAt(left, top);
        int lastPar = paragraphAt(left, bottom);
        List<int[]> ranges = new ArrayList<>();
        for (int par = first; par <= lastPar && ranges.size() < MAX_ROWS; par++) {
            Optional<Bounds> screen = area.getParagraphBoundsOnScreen(par); // laid-out paragraphs only
            Bounds box = screen.isPresent() ? area.screenToLocal(screen.get()) : null;
            if (box == null) {
                continue;
            }
            for (double rowY :
                    rowCentres(box.getMinY(), box.getHeight(), area.getParagraphLinesCount(par), top, bottom)) {
                int s = area.hit(left, rowY).getInsertionIndex();
                int e = area.hit(right, rowY).getInsertionIndex();
                ranges.add(new int[] {Math.min(s, e), Math.max(s, e)});
            }
        }
        if (ranges.isEmpty()) {
            int p = area.hit(left, top).getInsertionIndex();
            ranges.add(new int[] {p, p});
        }
        return ranges;
    }

    private int paragraphAt(double x, double y) {
        return area.offsetToPosition(area.hit(x, y).getInsertionIndex(), TwoDimensional.Bias.Forward)
                .getMajor();
    }

    /**
     * The vertical centres of the visual rows a rectangle spanning {@code [top, bottom]} selects in a
     * paragraph laid out from {@code minY} over {@code height} in {@code rows} equal rows. An unwrapped
     * paragraph is always taken (the caller only asks about paragraphs the rectangle reaches); of a wrapped
     * one, only the rows the rectangle actually crosses.
     */
    static List<Double> rowCentres(double minY, double height, int rows, double top, double bottom) {
        if (rows <= 1) {
            return List.of(minY + height / 2);
        }
        double rowHeight = height / rows;
        List<Double> centres = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            double rowTop = minY + row * rowHeight;
            if (rowTop + rowHeight > top && rowTop < bottom) {
                centres.add(rowTop + rowHeight / 2);
            }
        }
        return centres;
    }

    private static boolean same(List<int[]> a, List<int[]> b) {
        if (a == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) {
                return false;
            }
        }
        return true;
    }
}

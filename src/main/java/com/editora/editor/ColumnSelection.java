package com.editora.editor;

import java.util.List;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.MultiChangeBuilder;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

import com.editora.editor.ColumnEdits.Range;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * Rectangular ("column"/"block") selection for a {@link CodeArea}. RichTextFX is single-caret, so this
 * is a self-owned block: an {@code anchor}/{@code caret} (line, column) pair drawn as a translucent
 * rectangle on a mouse-transparent {@link Canvas} overlay (mirroring {@link WhitespaceOverlay}), with
 * the actual edits applied per line through an atomic {@link MultiChangeBuilder}. The pure geometry +
 * text math lives in the unit-tested {@link ColumnEdits}.
 *
 * <p>Input: <b>Option/Alt-drag</b> to select; <b>Shift+Alt+arrows</b> to extend (starting from the
 * caret if none). While active, typing column-inserts on every selected line, Backspace/Delete remove a
 * column, and Esc / any normal caret move cancels. Copy/Cut act on the rectangle (routed from the
 * controller). v1 is the primary editor view only.
 */
public final class ColumnSelection extends Region {

    private static final Color FILL = Color.web("#3b82f6", 0.30);
    private static final Color CARET_FILL = Color.web("#3b82f6", 0.85);

    private final CodeArea area;
    private final Canvas canvas = new Canvas();

    private boolean active;
    private int anchorLine;
    private int anchorCol;
    private int caretLine;
    private int caretCol;
    /** Guards programmatic caret/text changes so the caret listener doesn't cancel our own edits. */
    private boolean applying;
    private boolean redrawPending;

    public ColumnSelection(CodeArea area) {
        this.area = area;
        getStyleClass().add("column-overlay");
        setMouseTransparent(true);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
        // A user-driven caret move (click, C-f, arrows…) ends the block; our own edits set `applying`.
        area.caretPositionProperty().addListener((o, a, b) -> {
            if (active && !applying) {
                cancel();
            }
        });
        installInput();
    }

    public boolean isActive() {
        return active;
    }

    private int top() {
        return Math.min(anchorLine, caretLine);
    }

    private int bottom() {
        return Math.max(anchorLine, caretLine);
    }

    private int left() {
        return Math.min(anchorCol, caretCol);
    }

    private int right() {
        return Math.max(anchorCol, caretCol);
    }

    // --- input ----------------------------------------------------------------------------------

    private void installInput() {
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isAltDown()) {
                beginAt(e.getX(), e.getY());
                e.consume();
            } else if (active) {
                cancel(); // a normal click ends the block (don't consume — let the click position the caret)
            }
        });
        area.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (active && e.isAltDown()) {
                updateCaretAt(e.getX(), e.getY());
                e.consume();
            }
        });
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        area.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
    }

    private void onKeyPressed(KeyEvent e) {
        KeyCode code = e.getCode();
        boolean arrow = code == KeyCode.LEFT || code == KeyCode.RIGHT
                || code == KeyCode.UP || code == KeyCode.DOWN;
        if (e.isAltDown() && e.isShiftDown() && arrow) {
            if (!active) {
                startAtCaret();
            }
            extend(code);
            e.consume();
            return;
        }
        if (!active) {
            return;
        }
        switch (code) {
            case ESCAPE -> {
                cancel();
                e.consume();
            }
            case BACK_SPACE -> {
                backspace();
                e.consume();
            }
            case DELETE -> {
                deleteForward();
                e.consume();
            }
            case SHIFT, CONTROL, ALT, META, COMMAND -> {
                // bare modifier press: keep the block (a chord may follow)
            }
            default -> {
                // Any other key press while active: if it's a printable char it's handled in
                // KEY_TYPED; otherwise (navigation, etc.) end the block and let normal handling run.
                if (e.isControlDown() || e.isMetaDown() || e.isAltDown() || code.isFunctionKey()
                        || code.isNavigationKey()) {
                    cancel();
                }
            }
        }
    }

    private void onKeyTyped(KeyEvent e) {
        if (!active) {
            return;
        }
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty() || e.isControlDown() || e.isMetaDown() || e.isAltDown()) {
            return;
        }
        char c = ch.charAt(0);
        if (c < 0x20 || c == 0x7f) {
            return; // control chars (incl. Enter/Tab handled elsewhere)
        }
        insertColumn(ch);
        e.consume();
    }

    // --- block lifecycle ------------------------------------------------------------------------

    /** Starts a zero-width block at the current caret (used by Shift+Alt+arrows and the command). */
    public void startAtCaret() {
        int[] lc = caretLineCol();
        anchorLine = caretLine = lc[0];
        anchorCol = caretCol = lc[1];
        active = true;
        scheduleRedraw();
    }

    private void beginAt(double x, double y) {
        int[] lc = hit(x, y);
        anchorLine = caretLine = lc[0];
        anchorCol = caretCol = lc[1];
        active = true;
        scheduleRedraw();
    }

    private void updateCaretAt(double x, double y) {
        int[] lc = hit(x, y);
        caretLine = lc[0];
        caretCol = lc[1];
        scheduleRedraw();
    }

    private void extend(KeyCode code) {
        int lastLine = area.getParagraphs().size() - 1;
        switch (code) {
            case LEFT -> caretCol = Math.max(0, caretCol - 1);
            case RIGHT -> caretCol = Math.min(caretCol + 1, area.getParagraphLength(clampLine(caretLine)));
            case UP -> caretLine = Math.max(0, caretLine - 1);
            case DOWN -> caretLine = Math.min(lastLine, caretLine + 1);
            default -> { }
        }
        scheduleRedraw();
    }

    public void cancel() {
        if (!active) {
            return;
        }
        active = false;
        clearCanvas();
    }

    private int clampLine(int line) {
        return Math.max(0, Math.min(line, area.getParagraphs().size() - 1));
    }

    private int[] caretLineCol() {
        var pos = area.offsetToPosition(area.getCaretPosition(), Bias.Forward);
        return new int[]{pos.getMajor(), pos.getMinor()};
    }

    /** Screen (x,y) → (line, column) via the area's hit-test (column clamps to the line's length). */
    private int[] hit(double x, double y) {
        try {
            int idx = area.hit(x, y).getInsertionIndex();
            var pos = area.offsetToPosition(idx, Bias.Forward);
            return new int[]{pos.getMajor(), pos.getMinor()};
        } catch (RuntimeException ex) {
            return new int[]{caretLine, caretCol};
        }
    }

    // --- edits ----------------------------------------------------------------------------------

    /** Copies the rectangle's text (per-line slices joined by newlines) to the system clipboard. */
    public void copy() {
        if (!active) {
            return;
        }
        String t = ColumnEdits.rectText(area.getText(), top(), bottom(), left(), right());
        ClipboardContent content = new ClipboardContent();
        content.putString(t);
        Clipboard.getSystemClipboard().setContent(content);
    }

    public void cut() {
        if (!active) {
            return;
        }
        copy();
        delete();
    }

    /** Deletes the rectangle and collapses to a single caret at its top-left. */
    public void delete() {
        if (!active) {
            return;
        }
        int line = top();
        int col = left();
        applyRanges(ColumnEdits.rectRanges(area.getText(), top(), bottom(), left(), right()), "");
        active = false;
        clearCanvas();
        moveCaret(line, col);
    }

    /** Column-insert {@code str} at the left edge of every selected line (replacing any width). */
    private void insertColumn(String str) {
        List<Range> ranges = ColumnEdits.rectRanges(area.getText(), top(), bottom(), left(), right());
        applyRanges(ranges, str);
        int newCol = left() + str.length();
        anchorLine = top();
        caretLine = bottom();
        anchorCol = caretCol = newCol; // zero-width column caret, so typing continues per line
        moveCaret(top(), newCol);
        scheduleRedraw();
    }

    private void backspace() {
        if (left() < right()) {
            // Non-empty block: delete it, leave a zero-width column for continued typing.
            int col = left();
            applyRanges(ColumnEdits.rectRanges(area.getText(), top(), bottom(), left(), right()), "");
            anchorLine = top();
            caretLine = bottom();
            anchorCol = caretCol = col;
            moveCaret(top(), col);
            scheduleRedraw();
            return;
        }
        int col = left();
        if (col == 0) {
            return;
        }
        applyRanges(ColumnEdits.backspaceRanges(area.getText(), top(), bottom(), col), "");
        anchorCol = caretCol = col - 1;
        moveCaret(top(), col - 1);
        scheduleRedraw();
    }

    private void deleteForward() {
        if (left() < right()) {
            delete();
            return;
        }
        // Zero-width column: delete the character at the column on each line.
        int col = left();
        applyRanges(ColumnEdits.rectRanges(area.getText(), top(), bottom(), col, col + 1), "");
        moveCaret(top(), col);
        scheduleRedraw();
    }

    /** Applies a set of per-line replacements atomically (one undo step) under the {@code applying} guard. */
    private void applyRanges(List<Range> ranges, String replacement) {
        if (ranges.isEmpty()) {
            return;
        }
        applying = true;
        try {
            MultiChangeBuilder<?, ?, ?> mc = area.createMultiChange(ranges.size());
            for (Range r : ranges) {
                mc.replaceTextAbsolutely(r.start(), r.end(), replacement);
            }
            mc.commit();
        } finally {
            applying = false;
        }
    }

    private void moveCaret(int line, int col) {
        applying = true;
        try {
            int l = clampLine(line);
            int c = Math.min(col, area.getParagraphLength(l));
            area.moveTo(area.getAbsolutePosition(l, c));
        } catch (RuntimeException ignored) {
            // viewport/state mid-update; ignore
        } finally {
            applying = false;
        }
    }

    // --- rendering ------------------------------------------------------------------------------

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        canvas.relocate(0, 0);
        scheduleRedraw();
    }

    private void scheduleRedraw() {
        if (redrawPending) {
            return;
        }
        redrawPending = true;
        Platform.runLater(() -> {
            redrawPending = false;
            redraw();
        });
    }

    private void clearCanvas() {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (!active || w <= 0 || h <= 0) {
            return;
        }
        try {
            int total = area.getParagraphs().size();
            int first = Math.max(top(), area.firstVisibleParToAllParIndex());
            int last = Math.min(bottom(), Math.min(total - 1, area.lastVisibleParToAllParIndex()));
            double contentLeftX = contentLeftX(first, last);
            for (int line = first; line <= last; line++) {
                if (area.isFolded(line)) {
                    continue;
                }
                drawLine(g, line, contentLeftX);
            }
        } catch (RuntimeException ignored) {
            // viewport mid-layout; a later event redraws
        }
    }

    private void drawLine(GraphicsContext g, int line, double contentLeftX) {
        Bounds vbounds = lineVBounds(line);
        if (vbounds == null) {
            return;
        }
        double y = vbounds.getMinY();
        double height = vbounds.getHeight();
        double lx = columnX(line, left(), contentLeftX);
        double rx = columnX(line, right(), contentLeftX);
        if (Double.isNaN(lx) || Double.isNaN(rx)) {
            return;
        }
        if (rx <= lx) {
            // zero/clamped width → a thin caret bar so empty/short lines are visible
            g.setFill(CARET_FILL);
            g.fillRect(lx, y, 2, height);
        } else {
            g.setFill(FILL);
            g.fillRect(lx, y, rx - lx, height);
        }
    }

    /** Local x of {@code col} on {@code line} (right edge of the preceding char; clamped to EOL). */
    private double columnX(int line, int col, double contentLeftX) {
        int len = area.getParagraphLength(line);
        int absStart = area.getAbsolutePosition(line, 0);
        if (len == 0) {
            return contentLeftX;
        }
        int c = Math.min(col, len);
        if (c == 0) {
            Bounds b = toLocal(area.getCharacterBoundsOnScreen(absStart, absStart + 1).orElse(null));
            return b == null ? Double.NaN : b.getMinX();
        }
        Bounds b = toLocal(area.getCharacterBoundsOnScreen(absStart + c - 1, absStart + c).orElse(null));
        return b == null ? Double.NaN : b.getMaxX();
    }

    /** Vertical bounds (local) of a line, from a character on it (or the paragraph for empty lines). */
    private Bounds lineVBounds(int line) {
        int len = area.getParagraphLength(line);
        if (len > 0) {
            int abs = area.getAbsolutePosition(line, 0);
            return toLocal(area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null));
        }
        return toLocal(area.getParagraphBoundsOnScreen(line).orElse(null));
    }

    private double contentLeftX(int first, int last) {
        for (int p = first; p <= last; p++) {
            if (area.isFolded(p) || area.getParagraph(p).getText().isEmpty()) {
                continue;
            }
            int abs = area.getAbsolutePosition(p, 0);
            Bounds b = toLocal(area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null));
            if (b != null) {
                return b.getMinX();
            }
        }
        return 0;
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}

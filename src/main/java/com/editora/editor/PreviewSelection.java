package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import org.jspecify.annotations.Nullable;

/**
 * Text selection in the rendered Markdown preview: one range from an anchor to a focus, across blocks.
 *
 * <p><b>What can be selected.</b> A <em>leaf</em> is a {@code TextFlow} (a paragraph, a heading, a list
 * item's text, a table cell, a highlighted code block, an alert's body) or a free-standing {@code Label} (a
 * plain code block, a front-matter cell, an alert's title). List markers, the language label of a code block,
 * task boxes, pictures and rules are not text. A position is a leaf and a <em>logical</em> offset in it: the
 * characters of its text runs, with an inline label (inline code, a Find mark) counting as its text. Logical
 * offsets survive Find-in-preview, which cuts a run into pieces without changing what the flow says.
 *
 * <p><b>What it costs.</b> Nothing is attached to the document's nodes and the model holds two positions, so
 * an unchanged block is still reused as it is and an edit still rebuilds one block. The highlight is a layer
 * of shapes above the column, drawn only for the leaves in (and just around) the viewport: its cost follows
 * the height of the pane, not the length of the document or of the selection. It is redrawn after a layout or
 * a scroll, once per pulse, and only while a selection exists — with none, scrolling and layout run no code of
 * this class. A re-render that removes the block an end of the selection is in clears the selection.
 *
 * <p>Mouse: drag selects, a double click a word, a triple click the block, Shift-click extends, a plain click
 * clears. A press on a task box or another control is left to it; a link is still followed by a click that
 * does not move. Keys (once the pane has the focus and something is selected): Shift with the arrows extends
 * by a character or a line, Escape clears, the platform's Select All and Copy chords do that.
 */
final class PreviewSelection {

    /** A place in the document: a leaf and a logical offset in it (0 … its length). */
    record Pos(Region leaf, int offset) {}

    /** Blocks this far outside the viewport (px) are drawn too, so a small scroll shows no bare text. */
    private static final double OVERSCAN = 240;

    private static final String NOT_TEXT_MARKER = "md-list-marker";
    private static final String NOT_TEXT_LANGUAGE = "md-code-lang";

    private final MarkdownPreview preview;
    private final Pane column;
    private final Pane layer = new Pane();
    /** Never shown: it takes the highlight's colour from the stylesheet, for shapes made after the CSS pass. */
    private final Rectangle swatch = new Rectangle();

    private @Nullable ScrollPane pane;
    private @Nullable Pos anchor;
    private @Nullable Pos focus;
    private boolean all;
    private boolean dirty;
    private boolean listening;
    private int painted;
    private final Runnable afterPulse = this::paintIfDirty;

    PreviewSelection(MarkdownPreview preview, Pane column, Pane root) {
        this.preview = preview;
        this.column = column;
        layer.setManaged(false);
        layer.setMouseTransparent(true);
        layer.getStyleClass().add("md-selection-layer");
        swatch.getStyleClass().add("md-selection-fill");
        swatch.setVisible(false);
        swatch.setManaged(false);
        layer.getChildren().add(swatch);
        root.getChildren().add(layer);
        swatch.fillProperty()
                .addListener(
                        o -> { // the theme changed
                            if (isActive()) {
                                invalidate();
                            }
                        });
        column.addEventHandler(MouseEvent.MOUSE_PRESSED, this::pressed);
        column.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
    }

    /** The scroll pane the document is shown in: it takes the keys, and its scrolling redraws the highlight. */
    void attach(ScrollPane scrollPane) {
        this.pane = scrollPane;
        scrollPane.vvalueProperty().addListener(o -> {
            if (isActive()) {
                invalidate();
            }
        });
        scrollPane.addEventHandler(KeyEvent.KEY_PRESSED, this::key);
    }

    // --- the model --------------------------------------------------------------------------------------

    /** Whether some text is selected. */
    boolean isActive() {
        return anchor != null && focus != null && !(anchor.leaf() == focus.leaf() && anchor.offset() == focus.offset());
    }

    /** Whether the selection is the whole document (Select All), whatever of it is built so far. */
    boolean isAll() {
        return all && isActive();
    }

    @Nullable
    Pos anchor() {
        return anchor;
    }

    @Nullable
    Pos focus() {
        return focus;
    }

    /** How many highlight shapes are drawn now (tests: it follows the viewport, not the document). */
    int paintedShapes() {
        return painted;
    }

    void clear() {
        boolean was = isActive();
        anchor = null;
        focus = null;
        all = false;
        if (was) {
            changed();
        }
    }

    void select(Pos from, Pos to) {
        anchor = from;
        focus = to;
        all = false;
        changed();
    }

    /** Selects everything; false when the document shows no text. */
    boolean selectAll() {
        Pos first = edge(true);
        Pos last = edge(false);
        if (first == null || last == null) {
            return false;
        }
        anchor = first;
        focus = last;
        all = true;
        changed();
        return isActive();
    }

    private @Nullable Pos edge(boolean start) {
        ObservableList<Node> blocks = column.getChildren();
        for (int k = 0; k < blocks.size(); k++) {
            List<Region> leaves = leaves(blocks.get(start ? k : blocks.size() - 1 - k));
            if (!leaves.isEmpty()) {
                Region leaf = start ? leaves.get(0) : leaves.get(leaves.size() - 1);
                return new Pos(leaf, start ? 0 : length(leaf));
            }
        }
        return null;
    }

    /**
     * After a render: a selection whose end is in a block that is gone is cleared (what it named no longer
     * exists); a whole-document one takes in what was built since.
     */
    void revalidate() {
        if (anchor == null) {
            return;
        }
        if (all) {
            selectAll();
        } else if (blockIndex(anchor.leaf()) < 0 || focus == null || blockIndex(focus.leaf()) < 0) {
            clear();
        } else {
            invalidate();
        }
    }

    private void changed() {
        if (pane != null) {
            // While something is selected, the editing and caret chords belong to the preview (Copy, Select
            // All, Shift-arrows) instead of the editor command they are bound to.
            if (isActive()) {
                pane.getProperties().put("editora.ownsKeys", Boolean.TRUE);
            } else {
                pane.getProperties().remove("editora.ownsKeys");
            }
        }
        invalidate();
    }

    /** Asks for the highlight to be redrawn once this pulse's layout is done. */
    void invalidate() {
        dirty = true;
        if (column.getScene() == null) {
            paintIfDirty();
            return;
        }
        if (!listening) {
            listening = true;
            column.getScene().addPostLayoutPulseListener(afterPulse);
        }
        javafx.application.Platform.requestNextPulse();
    }

    // --- leaves and order -------------------------------------------------------------------------------

    /** The selectable leaves of {@code block}, in reading order. */
    static List<Region> leaves(Node block) {
        List<Region> out = new ArrayList<>();
        collect(block, out);
        return out;
    }

    private static void collect(Node node, List<Region> out) {
        if (!node.isManaged() || !node.isVisible()) {
            return; // a folded <details>
        }
        if (node instanceof TextFlow flow) {
            out.add(flow);
        } else if (node instanceof Label label) {
            if (!label.isMouseTransparent()
                    && !label.getStyleClass().contains(NOT_TEXT_MARKER)
                    && !label.getStyleClass().contains(NOT_TEXT_LANGUAGE)
                    && label.getText() != null
                    && !label.getText().isEmpty()) {
                out.add(label);
            }
        } else if (node instanceof Parent parent && !(node instanceof Control)) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    /** The top-level block {@code node} is in, or null when it is not in the document (any more). */
    private Node blockOf(Node node) {
        Node n = node;
        while (n != null && n.getParent() != column) {
            n = n.getParent();
        }
        return n;
    }

    private int blockIndex(Node node) {
        Node block = blockOf(node);
        return block == null ? -1 : column.getChildren().indexOf(block);
    }

    /** Negative, zero or positive as {@code a} is before, at or after {@code b} in reading order. */
    int compare(Pos a, Pos b) {
        if (a.leaf() == b.leaf()) {
            return Integer.compare(a.offset(), b.offset());
        }
        int ba = blockIndex(a.leaf());
        int bb = blockIndex(b.leaf());
        if (ba != bb) {
            return Integer.compare(ba, bb);
        }
        List<Region> leaves = leaves(blockOf(a.leaf()));
        return Integer.compare(leaves.indexOf(a.leaf()), leaves.indexOf(b.leaf()));
    }

    // --- a leaf's text ----------------------------------------------------------------------------------

    /** What an inline child of a flow says: a run's text, an inline label's, a ticked or empty task box. */
    private static String say(Node child) {
        if (child instanceof Text t) {
            return t.getText() == null ? "" : t.getText();
        }
        if (child instanceof Label l) {
            return l.getText() == null ? "" : l.getText();
        }
        return "￼"; // a picture, a formula: one place in the line, no text
    }

    /** The logical length of {@code leaf}. */
    static int length(Region leaf) {
        if (leaf instanceof Label label) {
            return label.getText().length();
        }
        int n = 0;
        for (Node child : ((TextFlow) leaf).getChildren()) {
            n += say(child).length();
        }
        return n;
    }

    /** The text of {@code leaf}, a picture or formula as U+FFFC. */
    static String textOf(Region leaf) {
        if (leaf instanceof Label label) {
            return label.getText();
        }
        StringBuilder sb = new StringBuilder();
        for (Node child : ((TextFlow) leaf).getChildren()) {
            sb.append(say(child));
        }
        return sb.toString();
    }

    /** The text layout's index for a logical offset: there, an inline label is one character. */
    private static int toLayout(TextFlow flow, int logical) {
        int layout = 0;
        int seen = 0;
        for (Node child : flow.getChildren()) {
            int len = say(child).length();
            if (child instanceof Text) {
                if (logical <= seen + len) {
                    return layout + (logical - seen);
                }
                layout += len;
            } else {
                if (logical <= seen) {
                    return layout;
                }
                if (logical < seen + len) {
                    return layout + 1; // inside an inline label: it is highlighted whole
                }
                layout += 1;
            }
            seen += len;
        }
        return layout;
    }

    private static int toLogical(TextFlow flow, int layoutIndex) {
        int layout = 0;
        int logical = 0;
        for (Node child : flow.getChildren()) {
            int len = say(child).length();
            int width = child instanceof Text ? len : 1;
            if (layoutIndex <= layout + width) {
                if (child instanceof Text) {
                    return logical + (layoutIndex - layout);
                }
                return layoutIndex <= layout ? logical : logical + len;
            }
            layout += width;
            logical += len;
        }
        return logical;
    }

    // --- pointing ---------------------------------------------------------------------------------------

    /** The index of the block at document offset {@code y} (column coordinates), the nearest when in a gap. */
    private int blockAt(double y) {
        ObservableList<Node> blocks = column.getChildren();
        int lo = 0;
        int hi = blocks.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (blocks.get(mid).getBoundsInParent().getMaxY() < y) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return blocks.isEmpty() ? -1 : lo;
    }

    /** The position nearest to a point of the scene, or null when the document shows no text. */
    @Nullable
    Pos at(double sceneX, double sceneY) {
        Point2D inColumn = column.sceneToLocal(sceneX, sceneY);
        int index = blockAt(inColumn.getY());
        ObservableList<Node> blocks = column.getChildren();
        // The block under the point, else the nearest one that has text (a rule, a picture have none).
        for (int d = 0; index >= 0 && d < blocks.size(); d++) {
            for (int i : new int[] {index + d, index - d}) {
                if (i >= 0 && i < blocks.size()) {
                    Pos found = nearest(leaves(blocks.get(i)), sceneX, sceneY);
                    if (found != null) {
                        return found;
                    }
                }
                if (d == 0) {
                    break;
                }
            }
        }
        return null;
    }

    private static @Nullable Pos nearest(List<Region> leaves, double sceneX, double sceneY) {
        Region best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Region leaf : leaves) {
            Bounds b = leaf.localToScene(leaf.getBoundsInLocal());
            double dy = sceneY < b.getMinY() ? b.getMinY() - sceneY : sceneY > b.getMaxY() ? sceneY - b.getMaxY() : 0;
            double dx = sceneX < b.getMinX() ? b.getMinX() - sceneX : sceneX > b.getMaxX() ? sceneX - b.getMaxX() : 0;
            double distance = dy * 4096 + dx; // the line first, then along it
            if (distance < bestDistance) {
                bestDistance = distance;
                best = leaf;
            }
        }
        if (best == null) {
            return null;
        }
        Point2D local = best.sceneToLocal(sceneX, sceneY);
        if (best instanceof TextFlow flow) {
            double x = Math.max(0, Math.min(flow.getWidth(), local.getX()));
            double y = Math.max(0, Math.min(Math.max(0, flow.getHeight() - 1), local.getY()));
            return new Pos(flow, toLogical(flow, flow.hitTest(new Point2D(x, y)).getInsertionIndex()));
        }
        boolean after = local.getY() > best.getHeight() || (local.getY() >= 0 && local.getX() > best.getWidth() / 2);
        return new Pos(best, after ? length(best) : 0);
    }

    private static boolean onControl(MouseEvent e, Node stop) {
        Node n = e.getPickResult() == null ? null : e.getPickResult().getIntersectedNode();
        for (; n != null && n != stop; n = n.getParent()) {
            if (n instanceof javafx.scene.control.ButtonBase
                    || n instanceof javafx.scene.control.ScrollBar
                    || (n.hasProperties() && n.getProperties().containsKey(MarkdownRenderer.ENLARGE_KEY))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the pointer of {@code e} is on text (a leaf), not on a margin, a picture or a rule. */
    boolean onText(MouseEvent e) {
        Node n = e.getPickResult() == null ? null : e.getPickResult().getIntersectedNode();
        for (; n != null && n != column; n = n.getParent()) {
            if (n instanceof TextFlow || n instanceof Label) {
                return true;
            }
        }
        return false;
    }

    private void pressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || onControl(e, column)) {
            return;
        }
        Pos hit = at(e.getSceneX(), e.getSceneY());
        if (hit == null) {
            return;
        }
        if (pane != null && !pane.isFocused()) {
            pane.requestFocus();
        }
        if (e.isShiftDown() && anchor != null) {
            focus = hit;
            all = false;
            changed();
        } else if (e.getClickCount() == 2 && !e.isShortcutDown() && onText(e)) {
            selectWord(hit);
        } else if (e.getClickCount() >= 3 && onText(e)) {
            selectBlock(hit);
        } else {
            select(hit, hit); // a caret: nothing selected, and where a drag or a Shift-click starts from
        }
    }

    private void dragged(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || anchor == null || e.isStillSincePress()) {
            return;
        }
        if (pane != null) { // past the top or bottom edge, the document follows the pointer
            Bounds view = pane.localToScene(pane.getBoundsInLocal());
            if (e.getSceneY() < view.getMinY()) {
                preview.setScrollY(Math.max(0, preview.scrollY() - (view.getMinY() - e.getSceneY())));
            } else if (e.getSceneY() > view.getMaxY()) {
                preview.setScrollY(preview.scrollY() + (e.getSceneY() - view.getMaxY()));
            }
        }
        Pos hit = at(e.getSceneX(), e.getSceneY());
        Pos was = focus;
        if (hit != null && (was == null || hit.leaf() != was.leaf() || hit.offset() != was.offset())) {
            focus = hit;
            all = false;
            changed();
        }
    }

    /** The bounds of the word at {@code offset} of {@code text}: letters, digits and {@code _}; else the one
     *  character there. Pure. */
    static int[] wordAt(String text, int offset) {
        if (text.isEmpty()) {
            return new int[] {0, 0};
        }
        int at = Math.max(0, Math.min(text.length() - 1, offset));
        if (!isWord(text.charAt(at)) && at > 0 && isWord(text.charAt(at - 1)) && offset >= text.length()) {
            at--;
        }
        if (!isWord(text.charAt(at))) {
            return new int[] {at, at + 1};
        }
        int from = at;
        int to = at + 1;
        while (from > 0 && isWord(text.charAt(from - 1))) {
            from--;
        }
        while (to < text.length() && isWord(text.charAt(to))) {
            to++;
        }
        return new int[] {from, to};
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '’';
    }

    void selectWord(Pos at) {
        int[] word = wordAt(textOf(at.leaf()), at.offset());
        select(new Pos(at.leaf(), word[0]), new Pos(at.leaf(), word[1]));
    }

    /** Selects the whole top-level block {@code at} is in (a paragraph, a list, a table, a code block). */
    void selectBlock(Pos at) {
        Node block = blockOf(at.leaf());
        List<Region> leaves = block == null ? List.of() : leaves(block);
        if (!leaves.isEmpty()) {
            Region last = leaves.get(leaves.size() - 1);
            select(new Pos(leaves.get(0), 0), new Pos(last, length(last)));
        }
    }

    // --- keys -------------------------------------------------------------------------------------------

    private void key(KeyEvent e) {
        if (e.getCode() == KeyCode.A && e.isShortcutDown() && !e.isShiftDown() && !e.isAltDown()) {
            if (isActive() && selectAll()) {
                e.consume();
            }
            return;
        }
        Pos from = focus;
        if (from == null || !isActive()) {
            return;
        }
        if (e.getCode() == KeyCode.C && e.isShortcutDown() && !e.isAltDown()) {
            if (preview.copySelection()) {
                e.consume();
            }
        } else if (e.getCode() == KeyCode.ESCAPE) {
            clear();
            e.consume();
        } else if (e.isShiftDown() && !e.isShortcutDown() && !e.isAltDown()) {
            Pos to = switch (e.getCode()) {
                case LEFT, KP_LEFT -> step(from, false);
                case RIGHT, KP_RIGHT -> step(from, true);
                case UP, KP_UP -> line(from, false);
                case DOWN, KP_DOWN -> line(from, true);
                default -> null;
            };
            if (to != null) {
                focus = to;
                all = false;
                changed();
                reveal(to);
                e.consume();
            }
        }
    }

    /** One character on from {@code p}, into the next (or previous) leaf at an end; {@code p} at the ends of
     *  the document. */
    Pos step(Pos p, boolean forward) {
        if (forward && p.offset() < length(p.leaf())) {
            return new Pos(p.leaf(), p.offset() + 1);
        }
        if (!forward && p.offset() > 0) {
            return new Pos(p.leaf(), p.offset() - 1);
        }
        Region next = neighbour(p.leaf(), forward);
        return next == null ? p : new Pos(next, forward ? 0 : length(next));
    }

    private @Nullable Region neighbour(Region leaf, boolean forward) {
        Node block = blockOf(leaf);
        if (block == null) {
            return null;
        }
        List<Region> leaves = leaves(block);
        int at = leaves.indexOf(leaf) + (forward ? 1 : -1);
        if (at >= 0 && at < leaves.size()) {
            return leaves.get(at);
        }
        ObservableList<Node> blocks = column.getChildren();
        for (int i = blocks.indexOf(block) + (forward ? 1 : -1); i >= 0 && i < blocks.size(); i += forward ? 1 : -1) {
            List<Region> other = leaves(blocks.get(i));
            if (!other.isEmpty()) {
                return forward ? other.get(0) : other.get(other.size() - 1);
            }
        }
        return null;
    }

    /** Where the caret of {@code p} is, as a box in scene coordinates. */
    private Bounds caretBox(Pos p) {
        if (p.leaf() instanceof TextFlow flow) {
            Path caret = new Path(flow.caretShape(toLayout(flow, p.offset()), true));
            Bounds b = caret.getBoundsInLocal();
            if (!b.isEmpty()) {
                return flow.localToScene(b);
            }
        }
        Bounds b = p.leaf().localToScene(p.leaf().getBoundsInLocal());
        double x = p.offset() == 0 ? b.getMinX() : b.getMaxX();
        return new javafx.geometry.BoundingBox(x, b.getMinY(), 0, b.getHeight());
    }

    /** The position one line above or below {@code p}, at the same place across the line. */
    @Nullable
    Pos line(Pos p, boolean down) {
        Bounds caret = caretBox(p);
        double x = caret.getMinX();
        double y = down ? caret.getMaxY() + 3 : caret.getMinY() - 3;
        Pos to = at(x, y);
        if (to == null) {
            return p;
        }
        // In a gap between blocks the nearest leaf may be the one we are in: then go to its neighbour.
        if (to.leaf() == p.leaf() && (down ? to.offset() <= p.offset() : to.offset() >= p.offset())) {
            Region next = neighbour(p.leaf(), down);
            if (next == null) {
                return new Pos(p.leaf(), down ? length(p.leaf()) : 0);
            }
            Bounds b = next.localToScene(next.getBoundsInLocal());
            Pos in = nearest(List.of(next), x, down ? b.getMinY() + 1 : b.getMaxY() - 1);
            return in == null ? p : in;
        }
        return to;
    }

    private void reveal(Pos p) {
        if (pane == null) {
            return;
        }
        Bounds caret = caretBox(p);
        Bounds view = pane.localToScene(pane.getBoundsInLocal());
        if (caret.getMinY() < view.getMinY()) {
            preview.setScrollY(Math.max(0, preview.scrollY() - (view.getMinY() - caret.getMinY()) - 24));
        } else if (caret.getMaxY() > view.getMaxY()) {
            preview.setScrollY(preview.scrollY() + (caret.getMaxY() - view.getMaxY()) + 24);
        }
    }

    // --- the selected leaves ----------------------------------------------------------------------------

    /** A leaf of the selection and the logical range of it that is selected. */
    record Part(Region leaf, int from, int to, int block) {}

    /**
     * The selected parts of the blocks {@code firstBlock}…{@code lastBlock} (indices in the column; clipped to
     * the selection), in reading order.
     */
    List<Part> parts(int firstBlock, int lastBlock) {
        List<Part> out = new ArrayList<>();
        Pos anchorAt = anchor;
        Pos focusAt = focus;
        if (anchorAt == null || focusAt == null || !isActive()) {
            return out;
        }
        boolean forward = compare(anchorAt, focusAt) <= 0;
        Pos start = forward ? anchorAt : focusAt;
        Pos end = forward ? focusAt : anchorAt;
        int sb = blockIndex(start.leaf());
        int eb = blockIndex(end.leaf());
        if (sb < 0 || eb < 0) {
            return out;
        }
        ObservableList<Node> blocks = column.getChildren();
        for (int i = Math.max(sb, firstBlock); i <= Math.min(eb, lastBlock); i++) {
            List<Region> leaves = leaves(blocks.get(i));
            int from = i == sb ? leaves.indexOf(start.leaf()) : 0;
            int to = i == eb ? leaves.indexOf(end.leaf()) : leaves.size() - 1;
            for (int k = Math.max(0, from); k <= to && k < leaves.size(); k++) {
                Region leaf = leaves.get(k);
                int a = leaf == start.leaf() ? start.offset() : 0;
                int b = leaf == end.leaf() ? end.offset() : length(leaf);
                if (b > a) {
                    out.add(new Part(leaf, a, b, i));
                }
            }
        }
        return out;
    }

    /** Every selected part. */
    List<Part> parts() {
        return parts(0, Integer.MAX_VALUE - 1);
    }

    /** The first and last block of the selection (indices in the column) when it is exactly whole blocks,
     *  else null. */
    int @Nullable [] wholeBlocks() {
        List<Part> parts = parts();
        if (parts.isEmpty()) {
            return null;
        }
        Part first = parts.get(0);
        Part last = parts.get(parts.size() - 1);
        ObservableList<Node> blocks = column.getChildren();
        List<Region> head = leaves(blocks.get(first.block()));
        List<Region> tail = leaves(blocks.get(last.block()));
        boolean whole = first.from() == 0
                && head.get(0) == first.leaf()
                && last.to() == length(last.leaf())
                && tail.get(tail.size() - 1) == last.leaf();
        return whole ? new int[] {first.block(), last.block()} : null;
    }

    // --- painting ---------------------------------------------------------------------------------------

    private void paintIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        paint();
        if (!isActive() && listening && column.getScene() != null) {
            column.getScene().removePostLayoutPulseListener(afterPulse);
            listening = false;
        }
    }

    private void paint() {
        layer.getChildren().retainAll(swatch);
        painted = 0;
        if (!isActive() || column.getScene() == null) {
            return;
        }
        double top = 0;
        double bottom = Double.MAX_VALUE;
        if (pane != null && pane.getContent() != null) {
            Point2D origin = column.sceneToLocal(pane.localToScene(0, 0));
            top = origin.getY() - OVERSCAN;
            bottom = origin.getY() + pane.getHeight() + OVERSCAN;
        }
        int first = Math.max(0, blockAt(top));
        int last = Math.max(first, blockAt(bottom));
        for (Part part : parts(first, last)) {
            Shape shape = shapeOf(part);
            if (shape != null) {
                shape.setFill(swatch.getFill());
                shape.setStroke(null);
                shape.setManaged(false);
                Point2D at = layer.sceneToLocal(part.leaf().localToScene(0, 0));
                shape.setLayoutX(at.getX());
                shape.setLayoutY(at.getY());
                layer.getChildren().add(shape);
                painted++;
            }
        }
    }

    private static @Nullable Shape shapeOf(Part part) {
        if (part.leaf() instanceof TextFlow flow) {
            javafx.scene.shape.PathElement[] range =
                    flow.rangeShape(toLayout(flow, part.from()), toLayout(flow, part.to()));
            return range.length == 0 ? null : new Path(range);
        }
        return new Rectangle(part.leaf().getWidth(), part.leaf().getHeight()); // a label: all of it
    }

    // --- what is selected, as text ----------------------------------------------------------------------

    /** The selected text: a tab between the cells of a table row, a line break between the leaves of a
     *  block, a blank line between blocks. */
    String text() {
        StringBuilder sb = new StringBuilder();
        Part before = null;
        for (Part part : parts()) {
            if (before != null) {
                sb.append(separator(before, part));
            }
            String text = textOf(part.leaf());
            sb.append(text, Math.min(part.from(), text.length()), Math.min(part.to(), text.length()));
            before = part;
        }
        return sb.toString().replace("￼", "");
    }

    static String separator(Part a, Part b) {
        if (a.block() != b.block()) {
            return "\n\n";
        }
        return sameTableRow(a.leaf(), b.leaf()) ? "\t" : "\n";
    }

    static boolean sameTableRow(Node a, Node b) {
        return a.getParent() instanceof GridPane grid
                && b.getParent() == grid
                && java.util.Objects.equals(GridPane.getRowIndex(a), GridPane.getRowIndex(b));
    }
}

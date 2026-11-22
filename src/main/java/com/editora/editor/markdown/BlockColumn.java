package com.editora.editor.markdown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import org.jspecify.annotations.Nullable;

/**
 * The live preview's column of blocks, virtualized: it knows every block of the document but builds, and puts
 * in the scene, only the blocks in and just around the viewport (PERF-08, Pauta 6.2.0).
 *
 * <p><b>The model.</b> Each block is a {@link Slot}: its node once built, the height it was measured at, and how
 * many source lines it has. A block not measured yet counts at an estimate: its lines times the height per line
 * of the blocks measured so far. The heights are summed in a Fenwick tree, so the offset of a block, the block
 * at an offset and the total height each cost O(log n): a scroll pulse does no work in the length of the
 * document.
 *
 * <p><b>A layout pass</b> takes the viewport of the scroll pane the column is in, widened by {@link #OVERSCAN}
 * on each side; builds the blocks in it that have no node; puts them in the scene in document order; styles and
 * measures the new ones; and takes out the blocks that left it. Those stay built, out of the scene, up to
 * {@link #KEEP} of them, so a scroll back does not build them again. {@linkplain #setPinned Pinned} blocks (the
 * ends of a text selection) stay in the scene wherever they are. When blocks measured above the top of the
 * viewport turn out taller or shorter than their estimate, or the total height changes, the content would
 * jump: the column says where the viewport should now start ({@link #onReflow}) so that the block at the top
 * stays where it was, as {@code VirtualFlow} does.
 *
 * <p><b>Measuring.</b> A child's height at a given width can only change when the child asks for layout (that
 * is the JavaFX contract: {@code requestLayout} is what clears a size). So a child that is not marked as needing
 * layout, at a width that has not changed, keeps the height measured last time and is not measured again.
 */
final class BlockColumn extends VBox {

    /** One block of the document: built lazily, measured once it has been in the scene. */
    abstract static class Slot {
        /** The block's node; null when not built, or when it renders to nothing ({@link #built}). */
        @Nullable
        Node node;

        /** Whether {@link #node} is the block's rendering (null then means: it shows nothing). */
        boolean built;

        /** Its height, without the spacing below it, measured at width {@link #measuredAt}; -1 when never. */
        double height = -1;

        double measuredAt = -1;

        /** The layout pass it was last measured in: once per pass is enough, however often a pass asks. */
        long measuredIn = -1;

        /** The block's position in the column's list (set by {@link #setSlots}). */
        int index = -1;

        /** How many source lines the block has: what its height is estimated from before it is measured. */
        abstract int lines();

        /** Whether it is likely to show nothing (a comment, a link definition): estimated at no height. */
        abstract boolean likelyEmpty();

        /** Renders the block; null when it shows nothing. On the FX thread. */
        abstract @Nullable Node build();
    }

    /** Node property of a built block: its {@link Slot}. */
    static final String SLOT = "editora.md.slot";

    /** How far beyond the viewport (px, on each side) blocks are built and put in the scene. */
    static final double OVERSCAN = 240;

    /** Built blocks kept out of the scene before the least recently shown are let go. */
    static final int KEEP = 120;

    /** The window when there is no viewport to read (no scroll pane, or not in it): the first screens. */
    private static final double NO_VIEWPORT = 800;

    /** The height per source line of a block not measured, before any block has been. */
    private static final double DEFAULT_LINE_HEIGHT = 22;

    private List<? extends Slot> slots = List.of();
    private double[] tree = new double[1];
    private double total;
    private double perLine = DEFAULT_LINE_HEIGHT;
    private double measuredHeight;
    private double measuredLines;

    private final Set<Slot> pinned = new LinkedHashSet<>();
    /** Built blocks out of the scene, least recently shown first. */
    private final LinkedHashSet<Slot> kept = new LinkedHashSet<>();

    /** Where each child was last put: {@code {y, width, height}}. */
    private final Map<Node, double[]> placed = new WeakHashMap<>();

    private @Nullable ScrollPane pane;
    private long measured;
    private long pass;
    private boolean holding;
    private long built;
    private Runnable afterLayout = () -> {};
    private Consumer<Slot> onAttach = s -> {};
    private DoubleConsumer onReflow = y -> {};
    private Consumer<Node> onBuilt = n -> {};

    /** Runs {@code hook} at the end of every layout pass (something drawn beside a block follows it). */
    void afterLayout(Runnable hook) {
        this.afterLayout = hook == null ? () -> {} : hook;
    }

    /** Told each block that enters the scene (Find marks its matches). */
    void onAttach(Consumer<Slot> hook) {
        this.onAttach = hook == null ? s -> {} : hook;
    }

    /**
     * Told, after a pass that measured blocks and so changed heights, how far (px) the top of the viewport
     * should move for the block at the top to stay put: the change in height of what is above it. Told 0 when
     * only the total height changed (the pane then keeps its offset in px, not its fraction).
     */
    void onReflow(DoubleConsumer hook) {
        this.onReflow = hook == null ? y -> {} : hook;
    }

    /** Told each node a slot builds (it is sized for the zoom). */
    void onBuilt(Consumer<Node> hook) {
        this.onBuilt = hook == null ? n -> {} : hook;
    }

    /** The scroll pane whose viewport decides which blocks are in the scene; its scrolling lays them out. */
    void attach(ScrollPane scrollPane) {
        this.pane = scrollPane;
        scrollPane.vvalueProperty().addListener(o -> requestLayout());
        scrollPane.viewportBoundsProperty().addListener(o -> requestLayout());
    }

    /** How many times a child has been measured so far (the others kept their remembered height). */
    long measured() {
        return measured;
    }

    /** How many blocks have been built so far. */
    long built() {
        return built;
    }

    /** The blocks, in document order. */
    List<? extends Slot> slots() {
        return slots;
    }

    /**
     * Makes {@code next} the column's blocks. A slot that has a node keeps it; the children that belong to no
     * slot of {@code next} leave the scene at once. O(n): once per render, never per pulse.
     */
    void setSlots(List<? extends Slot> next) {
        Set<Slot> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < next.size(); i++) {
            Slot s = next.get(i);
            s.index = i;
            keep.add(s);
            if (s.node != null) {
                s.node.getProperties().put(SLOT, s);
            }
        }
        getChildren().removeIf(c -> !(slotOf(c) instanceof Slot s) || !keep.contains(s));
        kept.removeIf(s -> !keep.contains(s));
        pinned.removeIf(s -> !keep.contains(s));
        this.slots = next;
        if (measuredLines > 0) {
            perLine = Math.max(4, measuredHeight / measuredLines);
        }
        rebuildTree();
        requestLayout();
    }

    /** Blocks that stay in the scene wherever they are (the ends of a selection). */
    void setPinned(Collection<? extends Slot> pins) {
        Set<Slot> now = new LinkedHashSet<>(pins);
        if (!pinned.equals(now)) {
            pinned.clear();
            pinned.addAll(now);
            requestLayout();
        }
    }

    /** The slot of a block's node, or null when {@code node} is not (or no longer) a block. */
    static @Nullable Slot slotOf(@Nullable Node node) {
        return node != null && node.hasProperties() && node.getProperties().get(SLOT) instanceof Slot s ? s : null;
    }

    /** The node of block {@code i}, built now (and kept out of the scene) when it has none yet. */
    @Nullable
    Node node(int i) {
        Slot s = slots.get(i);
        if (!s.built) {
            build(s);
            if (s.node != null) {
                kept.add(s);
            }
        }
        return s.node;
    }

    private void build(Slot s) {
        Node n = s.build();
        s.node = n;
        s.built = true;
        built++;
        if (n != null) {
            n.getProperties().put(SLOT, s);
            onBuilt.accept(n);
        } else {
            s.height = 0;
            s.measuredAt = -1;
            if (s.index >= 0 && s.index < slots.size() && slots.get(s.index) == s) {
                set(s.index, 0);
            }
        }
    }

    /** Whether block {@code i} is in the scene now. */
    boolean attached(int i) {
        Node n = slots.get(i).node;
        return n != null && n.getParent() == this;
    }

    // --- heights -----------------------------------------------------------------------------------------

    private double spacing() {
        return snapSpaceY(getSpacing());
    }

    /** What block {@code s} takes in the column, the spacing below it included. */
    private double extent(Slot s) {
        if (s.built && s.node == null) {
            return 0;
        }
        double h = s.height >= 0 ? s.height : s.likelyEmpty() ? 0 : Math.max(1, s.lines()) * perLine;
        return h + spacing();
    }

    private void rebuildTree() {
        int n = slots.size();
        tree = new double[n + 1];
        total = 0;
        for (int i = 0; i < n; i++) {
            double e = extent(slots.get(i));
            total += e;
            tree[i + 1] += e;
            int parent = (i + 1) + ((i + 1) & -(i + 1));
            if (parent <= n) {
                tree[parent] += tree[i + 1];
            }
        }
    }

    private void set(int i, double extent) {
        double delta = extent - (prefix(i + 1) - prefix(i));
        if (delta == 0) {
            return;
        }
        total += delta;
        for (int k = i + 1; k < tree.length; k += k & -k) {
            tree[k] += delta;
        }
    }

    /** The sum of the extents of blocks {@code 0 … count-1}. */
    private double prefix(int count) {
        double sum = 0;
        for (int k = Math.min(count, tree.length - 1); k > 0; k -= k & -k) {
            sum += tree[k];
        }
        return sum;
    }

    /** The offset of block {@code i}'s top in the column (its padding included). */
    double topOf(int i) {
        return snappedTopInset() + prefix(i);
    }

    /** Block {@code i}'s height (measured, or estimated), without the spacing below it. */
    double heightOf(int i) {
        Slot s = slots.get(i);
        return s.built && s.node == null ? 0 : Math.max(0, extent(s) - spacing());
    }

    /** The block at offset {@code y} of the column (the last when below them all); -1 when there is none. */
    int indexAt(double y) {
        int n = slots.size();
        if (n == 0) {
            return -1;
        }
        double rest = y - snappedTopInset();
        int pos = 0;
        for (int step = Integer.highestOneBit(n); step > 0; step >>= 1) {
            int next = pos + step;
            if (next <= n && tree[next] <= rest) {
                pos = next;
                rest -= tree[next];
            }
        }
        return Math.min(pos, n - 1);
    }

    /** The height of all blocks, measured or estimated, padding included. */
    double totalHeight() {
        return snappedTopInset() + snappedBottomInset() + Math.max(0, total - (total > 0 ? spacing() : 0));
    }

    @Override
    protected double computePrefHeight(double width) {
        return totalHeight();
    }

    @Override
    protected double computeMinHeight(double width) {
        return totalHeight();
    }

    // --- the window --------------------------------------------------------------------------------------

    /** The column's offset in the scroll pane's content (the wrapper may put a bar above it). */
    private double offsetInContent() {
        double y = 0;
        ScrollPane p = pane;
        for (Node n = this; p != null && n != null && n != p.getContent(); n = n.getParent()) {
            y += n.getBoundsInParent().getMinY();
        }
        return y;
    }

    private boolean inPane() {
        ScrollPane p = pane;
        if (p == null || p.getContent() == null) {
            return false;
        }
        for (Node n = this; n != null; n = n.getParent()) {
            if (n == p.getContent()) {
                return true;
            }
        }
        return false;
    }

    /** {@code {top, bottom}} of what the viewport shows, in column coordinates. */
    private double[] viewport() {
        ScrollPane p = pane;
        if (p == null || !inPane()) {
            return new double[] {0, NO_VIEWPORT};
        }
        double viewHeight = p.getViewportBounds().getHeight();
        double contentHeight = p.getContent().getLayoutBounds().getHeight();
        double range = p.getVmax() - p.getVmin();
        double scrollable = Math.max(0, contentHeight - viewHeight);
        double y = range <= 0 ? 0 : scrollable * (p.getVvalue() - p.getVmin()) / range;
        double top = y - offsetInContent();
        return new double[] {top, top + Math.max(viewHeight, 1)};
    }

    private double contentWidth(double width) {
        Insets in = getInsets();
        return Math.max(0, width - snapSpaceX(in.getLeft()) - snapSpaceX(in.getRight()));
    }

    private static boolean dirty(Node child) {
        return !(child instanceof Parent p) || p.isNeedsLayout();
    }

    /** Measures a block in the scene when it asked for layout or the width changed; true when its height did. */
    private boolean measure(Slot s, double w) {
        Node n = s.node;
        if (n == null
                || n.getParent() != this
                || (s.measuredAt == w && s.height >= 0 && (!dirty(n) || s.measuredIn == pass))) {
            return false;
        }
        s.measuredIn = pass;
        double h = snapSizeY(n.prefHeight(w));
        measured++;
        double was = s.height;
        if (was < 0) {
            measuredHeight += h;
            measuredLines += Math.max(1, s.lines());
        }
        s.height = h;
        s.measuredAt = w;
        if (was != h) {
            set(s.index, extent(s));
            return true;
        }
        return false;
    }

    /** The nodes of blocks {@code first…last} and of the pinned blocks, in document order; builds what it must. */
    private List<Node> wanted(int first, int last) {
        List<Slot> pins = new ArrayList<>(pinned);
        pins.sort(java.util.Comparator.comparingInt(s -> s.index));
        List<Node> out = new ArrayList<>();
        int p = 0;
        for (int i = first; i <= last; i++) {
            while (p < pins.size() && pins.get(p).index < i) {
                add(pins.get(p++), out);
            }
            while (p < pins.size() && pins.get(p).index == i) {
                p++;
            }
            add(slots.get(i), out);
        }
        while (p < pins.size()) {
            add(pins.get(p++), out);
        }
        return out;
    }

    private void add(Slot s, List<Node> out) {
        if (!s.built) {
            build(s);
        }
        if (s.node != null) {
            out.add(s.node);
        }
    }

    /** Makes the children exactly {@code want} (in document order), moving as few nodes as it can. */
    private void attachInOrder(List<Node> want) {
        List<Node> children = getChildren();
        Set<Node> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        wanted.addAll(want);
        List<Node> leaving = new ArrayList<>();
        for (Node c : children) {
            if (!wanted.contains(c)) {
                leaving.add(c);
            }
        }
        if (!leaving.isEmpty()) {
            children.removeAll(leaving);
            for (Node c : leaving) {
                Slot s = slotOf(c);
                if (s != null && s.index >= 0 && s.index < slots.size() && slots.get(s.index) == s) {
                    kept.remove(s);
                    kept.add(s); // the most recently shown last
                }
            }
        }
        List<Node> entering = new ArrayList<>();
        for (int k = 0; k < want.size(); k++) {
            Node n = want.get(k);
            if (k < children.size() && children.get(k) == n) {
                continue;
            }
            boolean was = n.getParent() == this;
            if (was) {
                children.remove(n); // out of order: put back where it belongs
            }
            children.add(Math.min(k, children.size()), n);
            if (!was) {
                entering.add(n);
            }
        }
        for (Node n : entering) {
            Slot s = slotOf(n);
            if (s != null) {
                kept.remove(s);
            }
            n.applyCss(); // the new block by itself: never the whole column again (PERF-10)
            if (s != null) {
                onAttach.accept(s);
            }
        }
        while (kept.size() > KEEP) {
            Slot oldest = kept.iterator().next();
            kept.remove(oldest);
            if (oldest.node != null && oldest.node.getParent() != this) {
                oldest.node.getProperties().remove(SLOT);
                oldest.node = null;
                oldest.built = false; // its measured height stays: the best estimate there is
            }
        }
    }

    /**
     * Builds and attaches what the viewport shows now, without waiting for a pulse (a jump, a render). A column
     * that has no width yet (not in a window) gets the first screens, unmeasured, so it is not empty.
     */
    void fillNow() {
        if (getWidth() > 0) {
            layoutChildren();
        } else if (!slots.isEmpty() && !holding) {
            attachInOrder(wanted(0, Math.max(0, indexAt(NO_VIEWPORT))));
        }
    }

    /**
     * While {@code hold} is true a layout pass builds nothing and keeps the children it has: the pane can learn
     * the document's new height, and be scrolled to the place a render goes to, before anything is built there.
     */
    void holdBuild(boolean hold) {
        this.holding = hold;
        if (!hold) {
            requestLayout();
        }
    }

    @Override
    protected void layoutChildren() {
        if (slots.isEmpty()) {
            getChildren().clear();
            afterLayout.run();
            return;
        }
        double w = contentWidth(getWidth());
        if (w <= 0 || holding) {
            return;
        }
        pass++;
        double[] view = viewport();
        double startTop = view[0];
        int anchor = Math.max(0, indexAt(Math.max(0, view[0])));
        double anchorOffset = topOf(anchor) - view[0];
        double totalBefore = totalHeight();
        for (int round = 0; round < 4; round++) {
            int first = Math.max(0, indexAt(view[0] - OVERSCAN));
            int last = Math.max(first, indexAt(view[1] + OVERSCAN));
            attachInOrder(wanted(first, last));
            boolean changed = false;
            for (Node child : getChildren()) {
                Slot s = slotOf(child);
                if (s != null) {
                    changed |= measure(s, w);
                }
            }
            if (!changed) {
                break;
            }
            double top = view[0] <= 0 && anchorOffset >= 0 ? view[0] : topOf(anchor) - anchorOffset;
            view = new double[] {top, top + (view[1] - view[0])};
        }
        double x = snapSpaceX(getInsets().getLeft());
        for (Node child : getChildren()) {
            Slot s = slotOf(child);
            if (s == null || !child.isManaged()) {
                continue;
            }
            double y = topOf(s.index);
            double h = Math.max(0, s.height);
            double[] at = placed.get(child);
            if (dirty(child) || at == null || at[1] != w || at[2] != h) {
                layoutInArea(child, x, y, w, h, 0, HPos.LEFT, VPos.TOP);
                placed.put(child, new double[] {y, w, h});
            } else if (at[0] != y) {
                child.setLayoutY(y - child.getLayoutBounds().getMinY()); // only moved: nothing to measure
                at[0] = y;
            }
        }
        if (inPane() && (view[0] != startTop || totalHeight() != totalBefore)) {
            onReflow.accept(view[0] - startTop);
        }
        afterLayout.run();
    }
}

package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.WorkspaceState;

/**
 * Lays out IntelliJ-style left/right/bottom stripes around the editor area, manages registered
 * tool windows (one open per side, toggled via stripe buttons), and persists open state +
 * divider positions to workspace-state.json.
 */
public class ToolWindowManager {

    private static final PseudoClass OPEN = PseudoClass.getPseudoClass("open");

    private final ConfigManager config;
    private final KeymapManager keymap;

    private final VBox leftStripe = new VBox();
    private final VBox rightStripe = new VBox();
    private final HBox bottomStripe = new HBox();

    private final SplitPane hSplit = new SplitPane();
    private final SplitPane vSplit = new SplitPane();

    private final Map<String, ToolWindow> byId = new LinkedHashMap<>();
    private final Map<ToolWindow.Side, ToolWindow> openBySide = new HashMap<>();
    private final Map<ToolWindow, Button> stripeButtons = new HashMap<>();
    private final Map<ToolWindow, Region> panels = new HashMap<>();
    /** Tool windows hidden by context rather than user preference (e.g. the Commit window outside a Git
     *  repo). Transient — never persisted, so it doesn't clobber the user's show/hide setting. */
    private final java.util.Set<ToolWindow> unavailable = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** When true (Zen mode), all side stripes are force-hidden regardless of their buttons. */
    private boolean zenHidesStripes;
    /** User setting: when false the tool stripes are hidden (UI only — windows still open via keys/palette). */
    private boolean stripesEnabled = true;

    public ToolWindowManager(BorderPane workspace, Node editorArea, ConfigManager config, KeymapManager keymap) {
        this.config = config;
        this.keymap = keymap;

        leftStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-vertical", "tool-stripe-left");
        rightStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-vertical", "tool-stripe-right");
        bottomStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-horizontal", "tool-stripe-bottom");
        leftStripe.setAlignment(Pos.TOP_CENTER);
        rightStripe.setAlignment(Pos.TOP_CENTER);
        bottomStripe.setAlignment(Pos.CENTER_LEFT);

        hSplit.setOrientation(Orientation.HORIZONTAL);
        hSplit.getItems().add(editorArea);

        vSplit.setOrientation(Orientation.VERTICAL);
        vSplit.getItems().add(hSplit);

        workspace.setLeft(leftStripe);
        workspace.setRight(rightStripe);
        workspace.setBottom(bottomStripe);
        workspace.setCenter(vSplit);
        updateStripeVisibility();

        // Highlight whichever tool window holds keyboard focus. Tracked centrally via the scene's single
        // focus owner — more reliable than per-node focusWithin for deeply nested controls (e.g. the
        // tree panels), whose ancestor focusWithin didn't always clear on blur.
        vSplit.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) {
                scene.focusOwnerProperty().addListener((o, oldOwner, owner) -> updateActivePanel(owner));
                updateActivePanel(scene.getFocusOwner());
            }
        });
    }

    /** Marks the tool window panel that contains the scene's focus owner as active (others inactive). */
    private void updateActivePanel(Node focusOwner) {
        for (Region panel : panels.values()) {
            if (panel instanceof ToolWindowPanel p) {
                p.setActive(focusOwner != null && isDescendant(focusOwner, p));
            }
        }
    }

    /** Whether {@code node} is {@code ancestor} or sits somewhere beneath it in the scene graph. */
    private static boolean isDescendant(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    public void register(ToolWindow tw) {
        byId.put(tw.getId(), tw);
        ensureInOrder(tw.getId());
        Button button = new Button();
        button.setGraphic(tw.createIcon());
        button.getStyleClass().addAll("tool-stripe-button", "flat");
        button.setTooltip(new Tooltip(tooltipFor(tw)));
        button.setOnAction(e -> toggle(tw));
        enableReorderDrag(tw, button);
        stripeButtons.put(tw, button);
        if (shouldShowButton(tw)) {
            addButtonOrdered(tw, button);
        }
        updateStripeVisibility();
    }

    /**
     * Drag-and-drop reorder of a stripe button among its same-side neighbours. Mirrors the editor
     * tab-strip reordering UI: a translucent drag snapshot follows the cursor, the source dims while
     * dragging, and the target shows an accent insertion line on the side the icon would land.
     */
    private void enableReorderDrag(ToolWindow tw, Button button) {
        button.setOnDragDetected(e -> {
            Dragboard db = button.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(tw.getId());
            db.setContent(content);
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            db.setDragView(button.snapshot(params, null), e.getX(), e.getY());
            button.getStyleClass().add("tool-stripe-button-dragging");
            e.consume();
        });
        button.setOnDragOver(e -> {
            ToolWindow src = dragSource(e.getDragboard());
            if (src != null && src != tw && currentSide(src) == currentSide(tw)) {
                e.acceptTransferModes(TransferMode.MOVE);
                showDropMarker(button, currentSide(tw) != ToolWindow.Side.BOTTOM, dropAfter(button, tw, e));
            }
            e.consume();
        });
        button.setOnDragExited(e -> clearDropMarkers(button));
        button.setOnDragDropped(e -> {
            clearDropMarkers(button);
            ToolWindow src = dragSource(e.getDragboard());
            boolean ok = false;
            if (src != null && src != tw && currentSide(src) == currentSide(tw)) {
                reorderOnto(src, tw, dropAfter(button, tw, e));
                ok = true;
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        button.setOnDragDone(e -> {
            button.getStyleClass().remove("tool-stripe-button-dragging");
            clearDropMarkers(button);
        });
    }

    /** The tool window being dragged, if the dragboard carries a known tool-window id. */
    private ToolWindow dragSource(Dragboard db) {
        return db.hasString() ? byId.get(db.getString()) : null;
    }

    /** Whether a drop lands after the target: past the midpoint along the stripe's axis. */
    private boolean dropAfter(Button target, ToolWindow tw, javafx.scene.input.DragEvent e) {
        boolean vertical = currentSide(tw) != ToolWindow.Side.BOTTOM;
        double pos = vertical ? e.getY() : e.getX();
        double extent = vertical ? target.getHeight() : target.getWidth();
        return pos > extent / 2;
    }

    private static void showDropMarker(Node button, boolean vertical, boolean after) {
        clearDropMarkers(button);
        String cls = vertical
                ? (after ? "tool-drop-bottom" : "tool-drop-top")
                : (after ? "tool-drop-right" : "tool-drop-left");
        button.getStyleClass().add(cls);
    }

    private static void clearDropMarkers(Node button) {
        button.getStyleClass().removeAll("tool-drop-top", "tool-drop-bottom", "tool-drop-left", "tool-drop-right");
    }

    /** True if this tool window's stripe button should be shown. Defaults to visible. */
    public boolean isVisible(ToolWindow tw) {
        Boolean v = config.getWorkspaceState().getToolWindowVisible().get(tw.getId());
        return v == null || v;
    }

    /**
     * Hide/show the tool window's stripe button (when hidden, also closes it if open). Reconciles the
     * actual stripe membership rather than trusting the persisted flag — the visibility map can desync
     * from the real children (e.g. {@code register} runs against the default state, then the session
     * file is swapped for a project), so an early "no change" return would leave a stale button shown
     * or throw on a duplicate add.
     */
    public void setVisible(ToolWindow tw, boolean visible) {
        Button button = stripeButtons.get(tw);
        var stripe = stripeFor(currentSide(tw)).getChildren();
        if (!visible) {
            if (openBySide.get(currentSide(tw)) == tw) {
                close(tw);
            }
            if (button != null) {
                stripe.remove(button); // no-op if absent
            }
        } else if (button != null && !unavailable.contains(tw) && !stripe.contains(button)) {
            addButtonOrdered(tw, button); // contains-guard above: adding a duplicate child throws
        }
        Boolean prev = config.getWorkspaceState().getToolWindowVisible().get(tw.getId());
        config.getWorkspaceState().getToolWindowVisible().put(tw.getId(), visible);
        updateStripeVisibility();
        if (prev == null || prev != visible) {
            config.save(); // persist only on an actual change
        }
    }

    /** Whether the stripe button should be present: the user keeps it visible AND it isn't context-hidden. */
    private boolean shouldShowButton(ToolWindow tw) {
        return isVisible(tw) && !unavailable.contains(tw);
    }

    /**
     * Context-driven availability (NOT persisted, unlike {@link #setVisible}): hides the tool window's
     * stripe button + closes it when {@code available} is false, restoring it (subject to the user's
     * {@link #isVisible} preference) when true. Used to hide the Commit window outside a Git repo without
     * disturbing the user's show/hide setting.
     */
    public void setAvailable(ToolWindow tw, boolean available) {
        boolean wasAvailable = !unavailable.contains(tw);
        if (available == wasAvailable) {
            return;
        }
        if (available) {
            unavailable.remove(tw);
        } else {
            unavailable.add(tw);
        }
        Button button = stripeButtons.get(tw);
        var stripe = stripeFor(currentSide(tw)).getChildren();
        if (!available) {
            if (openBySide.get(currentSide(tw)) == tw) {
                close(tw);
            }
            if (button != null) {
                stripe.remove(button); // no-op if absent
            }
        } else if (button != null && isVisible(tw) && !stripe.contains(button)) {
            addButtonOrdered(tw, button);
        }
        updateStripeVisibility();
    }

    public Collection<ToolWindow> getRegisteredToolWindows() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** True if this tool window is the one currently open on its side. */
    public boolean isOpen(ToolWindow tw) {
        return tw != null && openBySide.get(currentSide(tw)) == tw;
    }

    /** The currently open tool windows, ordered by side (left, bottom, right), for focus cycling. */
    public java.util.List<ToolWindow> getOpenToolWindows() {
        java.util.List<ToolWindow> open = new java.util.ArrayList<>();
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            ToolWindow tw = openBySide.get(side);
            if (tw != null) {
                open.add(tw);
            }
        }
        return open;
    }

    /** The registered tool window whose content node contains {@code target} (focus), or null. */
    public ToolWindow toolWindowOf(javafx.event.EventTarget target) {
        Node n = target instanceof Node node ? node : null;
        while (n != null) {
            for (ToolWindow tw : byId.values()) {
                if (tw.getContent() == n) {
                    return tw;
                }
            }
            n = n.getParent();
        }
        return null;
    }

    /** Re-applies every stripe button's tooltip from the current keymap (after a live keymap switch). */
    public void refreshTooltips() {
        for (Map.Entry<ToolWindow, Button> e : stripeButtons.entrySet()) {
            e.getValue().setTooltip(new Tooltip(tooltipFor(e.getKey())));
        }
    }

    /** Tooltip text: title plus the chord for the tool window's command, if one is bound. */
    private String tooltipFor(ToolWindow tw) {
        String cmd = tw.getCommandId();
        if (cmd == null) {
            return tw.getTitle();
        }
        for (Map.Entry<String, String> e : keymap.bindings().entrySet()) {
            if (cmd.equals(e.getValue())) {
                return tw.getTitle() + " (" + e.getKey() + ")";
            }
        }
        return tw.getTitle();
    }

    /** The side this tool window is currently assigned to (settings override, falling back to the registered default). */
    public ToolWindow.Side currentSide(ToolWindow tw) {
        String stored = config.getWorkspaceState().getToolWindowSides().get(tw.getId());
        if (stored != null) {
            try {
                return ToolWindow.Side.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return tw.getSide();
    }

    /** Moves the tool window's stripe button to a different side; closes it first if it was open. */
    public void setSide(ToolWindow tw, ToolWindow.Side newSide) {
        ToolWindow.Side oldSide = currentSide(tw);
        if (oldSide == newSide) {
            return;
        }
        if (openBySide.get(oldSide) == tw) {
            close(tw);
        }
        Button button = stripeButtons.get(tw);
        config.getWorkspaceState().getToolWindowSides().put(tw.getId(), newSide.name());
        if (button != null && shouldShowButton(tw)) {
            stripeFor(oldSide).getChildren().remove(button);
            addButtonOrdered(tw, button);
        }
        updateStripeVisibility();
        config.save();
    }

    private void updateStripeVisibility() {
        setStripeShown(leftStripe);
        setStripeShown(rightStripe);
        setStripeShown(bottomStripe);
    }

    private void setStripeShown(Pane stripe) {
        boolean shown =
                stripesEnabled && !zenHidesStripes && !stripe.getChildren().isEmpty();
        stripe.setVisible(shown);
        stripe.setManaged(shown);
    }

    /** Zen mode: hide all three side stripes (without touching per-window visibility) or restore them. */
    public void setZenStripesHidden(boolean hidden) {
        zenHidesStripes = hidden;
        updateStripeVisibility();
    }

    /**
     * User setting (Settings → Tool Windows): show or hide the tool stripes. This is UI-only and takes
     * precedence over each tool window's individual visibility — tool windows still open via their
     * keybinding (e.g. {@code M-1}) or the command palette while the stripes are hidden.
     */
    public void setStripesEnabled(boolean enabled) {
        stripesEnabled = enabled;
        updateStripeVisibility();
    }

    /** Closes every open tool window and returns their ids (most recent layout order), for Zen restore. */
    public java.util.List<String> closeAllOpen() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (ToolWindow tw : getOpenToolWindows()) {
            ids.add(tw.getId());
        }
        for (String id : ids) {
            close(byId.get(id));
        }
        return ids;
    }

    /** Reopens the given tool windows by id (used when leaving Zen mode). */
    public void openByIds(java.util.List<String> ids) {
        for (String id : ids) {
            openById(id);
        }
    }

    /** Opens any tool windows the settings file says were open last time. */
    public void restore() {
        WorkspaceState s = config.getWorkspaceState();
        openById(s.getOpenLeftToolWindow());
        openById(s.getOpenRightToolWindow());
        openById(s.getOpenBottomToolWindow());
    }

    public void toggle(ToolWindow tw) {
        ToolWindow.Side side = currentSide(tw);
        if (openBySide.get(side) == tw) {
            close(tw);
        } else {
            open(tw);
        }
    }

    public void open(ToolWindow tw) {
        open(tw, true); // a direct/user open focuses the panel
    }

    /** Opens {@code tw}; when {@code focus}, moves keyboard focus into it and selects its first item. */
    public void open(ToolWindow tw, boolean focus) {
        ToolWindow.Side side = currentSide(tw);
        ToolWindow current = openBySide.get(side);
        if (current == tw) {
            return;
        }
        if (current != null) {
            close(current);
        }
        ToolWindowPanel panel = new ToolWindowPanel(tw, () -> close(tw));
        panels.put(tw, panel);
        openBySide.put(side, tw);
        switch (side) {
            case LEFT -> {
                hSplit.getItems().add(0, panel);
                double pos = config.getWorkspaceState().getLeftDividerPosition();
                Platform.runLater(() -> hSplit.setDividerPosition(0, pos));
            }
            case RIGHT -> {
                hSplit.getItems().add(panel);
                double pos = config.getWorkspaceState().getRightDividerPosition();
                int dividerIdx = hSplit.getItems().size() - 2;
                Platform.runLater(() -> hSplit.setDividerPosition(dividerIdx, pos));
            }
            case BOTTOM -> {
                vSplit.getItems().add(panel);
                double pos = config.getWorkspaceState().getBottomDividerPosition();
                Platform.runLater(() -> vSplit.setDividerPosition(0, pos));
            }
        }
        stripeButtons.get(tw).pseudoClassStateChanged(OPEN, true);
        persist();
        // Move focus into the freshly shown panel and select its first item (deferred until it's laid
        // out). Skipped on session restore so a restored-open tool window doesn't steal startup focus.
        if (focus && tw.getContent() instanceof ToolWindowContent content) {
            Platform.runLater(content::focusFirstItem);
        }
    }

    public void close(ToolWindow tw) {
        Region panel = panels.remove(tw);
        if (panel == null) {
            return;
        }
        ToolWindow.Side side = currentSide(tw);
        WorkspaceState settings = config.getWorkspaceState();
        switch (side) {
            case LEFT -> {
                int idx = hSplit.getItems().indexOf(panel);
                if (idx >= 0 && idx < hSplit.getDividers().size()) {
                    settings.setLeftDividerPosition(
                            hSplit.getDividers().get(idx).getPosition());
                }
                hSplit.getItems().remove(panel);
            }
            case RIGHT -> {
                int idx = hSplit.getItems().indexOf(panel);
                if (idx > 0 && idx - 1 < hSplit.getDividers().size()) {
                    settings.setRightDividerPosition(
                            hSplit.getDividers().get(idx - 1).getPosition());
                }
                hSplit.getItems().remove(panel);
            }
            case BOTTOM -> {
                if (!vSplit.getDividers().isEmpty()) {
                    settings.setBottomDividerPosition(
                            vSplit.getDividers().get(0).getPosition());
                }
                vSplit.getItems().remove(panel);
            }
        }
        openBySide.remove(side, tw);
        stripeButtons.get(tw).pseudoClassStateChanged(OPEN, false);
        persist();
    }

    private void openById(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        ToolWindow tw = byId.get(id);
        if (tw != null && shouldShowButton(tw)) {
            open(tw, false); // restore (incl. session + Zen exit) must not steal focus from the editor
        }
    }

    private Pane stripeFor(ToolWindow.Side side) {
        return switch (side) {
            case LEFT -> leftStripe;
            case RIGHT -> rightStripe;
            case BOTTOM -> bottomStripe;
        };
    }

    // --- Stripe ordering -------------------------------------------------------------------------

    /** The tool window owning a given stripe button, or null. */
    private ToolWindow toolWindowFor(Node button) {
        for (Map.Entry<ToolWindow, Button> e : stripeButtons.entrySet()) {
            if (e.getValue() == button) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Display order rank for a tool window (its index in the persisted order list; absent = last). */
    private int orderIndex(ToolWindow tw) {
        if (tw == null) {
            return Integer.MAX_VALUE;
        }
        int i = config.getWorkspaceState().getToolWindowOrder().indexOf(tw.getId());
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private void ensureInOrder(String id) {
        List<String> order = config.getWorkspaceState().getToolWindowOrder();
        if (!order.contains(id)) {
            order.add(id);
        }
    }

    /** Adds the button to its current side's stripe at the position dictated by the order list. */
    private void addButtonOrdered(ToolWindow tw, Button button) {
        Pane stripe = stripeFor(currentSide(tw));
        int rank = orderIndex(tw);
        int insert = 0;
        for (Node child : stripe.getChildren()) {
            ToolWindow other = toolWindowFor(child);
            if (other != null && other != tw && orderIndex(other) < rank) {
                insert++;
            }
        }
        stripe.getChildren().add(insert, button);
    }

    /** Re-sorts the buttons already in a stripe to match the order list. */
    private void relayoutStripe(ToolWindow.Side side) {
        Pane stripe = stripeFor(side);
        List<Node> buttons = new ArrayList<>(stripe.getChildren());
        buttons.sort(Comparator.comparingInt(b -> orderIndex(toolWindowFor(b))));
        stripe.getChildren().setAll(buttons);
    }

    /** All registered tool windows currently assigned to a side, in display order. */
    public List<ToolWindow> orderedOnSide(ToolWindow.Side side) {
        List<ToolWindow> list = new ArrayList<>();
        for (ToolWindow tw : byId.values()) {
            if (currentSide(tw) == side) {
                list.add(tw);
            }
        }
        list.sort(Comparator.comparingInt(this::orderIndex));
        return list;
    }

    /** Whether {@code tw} can move by {@code delta} (-1 earlier / +1 later) among its same-side peers. */
    public boolean canMove(ToolWindow tw, int delta) {
        List<ToolWindow> peers = orderedOnSide(currentSide(tw));
        int idx = peers.indexOf(tw);
        int target = idx + delta;
        return idx >= 0 && target >= 0 && target < peers.size();
    }

    /** Moves {@code tw} one slot earlier (-1) or later (+1) among its same-side peers; persists. */
    public boolean move(ToolWindow tw, int delta) {
        if (!canMove(tw, delta)) {
            return false;
        }
        List<ToolWindow> peers = orderedOnSide(currentSide(tw));
        ToolWindow other = peers.get(peers.indexOf(tw) + delta);
        List<String> order = config.getWorkspaceState().getToolWindowOrder();
        ensureInOrder(tw.getId());
        ensureInOrder(other.getId());
        Collections.swap(order, order.indexOf(tw.getId()), order.indexOf(other.getId()));
        relayoutStripe(currentSide(tw));
        config.save();
        return true;
    }

    /** Drops {@code src} immediately before/after {@code target} (same side); persists. */
    private void reorderOnto(ToolWindow src, ToolWindow target, boolean after) {
        List<String> order = config.getWorkspaceState().getToolWindowOrder();
        for (String id : byId.keySet()) {
            ensureInOrder(id); // make every id present so indices are meaningful
        }
        order.remove(src.getId());
        int ti = order.indexOf(target.getId());
        order.add(after ? ti + 1 : ti, src.getId());
        relayoutStripe(currentSide(target));
        config.save();
    }

    private void persist() {
        WorkspaceState s = config.getWorkspaceState();
        s.setOpenLeftToolWindow(idOf(openBySide.get(ToolWindow.Side.LEFT)));
        s.setOpenRightToolWindow(idOf(openBySide.get(ToolWindow.Side.RIGHT)));
        s.setOpenBottomToolWindow(idOf(openBySide.get(ToolWindow.Side.BOTTOM)));
        config.save();
    }

    private static String idOf(ToolWindow tw) {
        return tw == null ? "" : tw.getId();
    }
}

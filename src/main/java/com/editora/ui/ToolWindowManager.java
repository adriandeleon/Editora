package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
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

import static com.editora.i18n.Messages.tr;

/**
 * Lays out IntelliJ-style left/right/bottom stripes around the editor area, manages registered
 * tool windows (one open per side, toggled via stripe buttons), and persists open state +
 * divider positions to workspace-state.json.
 */
public class ToolWindowManager {

    private static final String MODE_DOCKED = "DOCKED";
    private static final String MODE_MAXIMIZED = "MAXIMIZED";
    private static final String MODE_FLOATING = "FLOATING";

    private static final PseudoClass OPEN = PseudoClass.getPseudoClass("open");
    /** Set on the stripe button of the tool window that currently holds keyboard focus (IntelliJ-style
     *  "active" highlight, stronger than the merely-{@link #OPEN} tint). Custom name to avoid colliding
     *  with JavaFX's built-in {@code :active}/{@code :focused} button pseudo-classes. */
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("tw-active");

    private final ConfigManager config;
    private final KeymapManager keymap;

    private final VBox leftStripe = new VBox();
    private final VBox rightStripe = new VBox();
    private final HBox bottomStripe = new HBox();

    private final SplitPane hSplit = new SplitPane();
    private final SplitPane vSplit = new SplitPane();

    private final Map<String, ToolWindow> byId = new LinkedHashMap<>();
    /** Tool windows whose stripe button is hidden by default (until the user shows them), absent any saved
     *  visibility preference — e.g. Remote, which is niche so it stays off until enabled. */
    private final Set<String> defaultHidden = new HashSet<>();

    /**
     * A side can hold two tool windows, stacked — Project over Structure, or two consoles side by side
     * along the bottom. Two rather than N because the value is having a second thing visible at all;
     * past that a side stops being a dock and becomes a list of slivers.
     */
    static final int MAX_PER_SIDE = 2;

    /** The tool windows open on each side, in stacking order. The first is the side's primary. */
    private final Map<ToolWindow.Side, List<ToolWindow>> openBySide = new java.util.EnumMap<>(ToolWindow.Side.class);
    /**
     * The node each side currently contributes to the outer split: the lone panel when one window is open,
     * an inner {@link SplitPane} of both when two are. Tracked rather than derived so a rebuild can swap it
     * <em>in place</em> in the outer split, which is what keeps the outer divider (and every index taken
     * against it) undisturbed while a side splits and unsplits.
     */
    private final Map<ToolWindow.Side, Region> sideContainers = new java.util.EnumMap<>(ToolWindow.Side.class);

    private final Map<ToolWindow.Side, SplitPane> innerSplits = new java.util.EnumMap<>(ToolWindow.Side.class);
    /** Tool windows detached into their own stage, each keyed to the stage holding it. */
    private final Map<ToolWindow, javafx.stage.Stage> floatingStages = new LinkedHashMap<>();

    private final Map<ToolWindow, Button> stripeButtons = new HashMap<>();
    private final Map<ToolWindow, Region> panels = new HashMap<>();
    /** Tool windows hidden by context rather than user preference (e.g. the Commit window outside a Git
     *  repo). Transient — never persisted, so it doesn't clobber the user's show/hide setting. */
    private final java.util.Set<ToolWindow> unavailable = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Optional observer notified after a tool window opens ({@code true}) or closes ({@code false}); lets a
     *  controller distinguish a user toggle from its own programmatic open/close (e.g. HTTP auto-show). */
    private java.util.function.BiConsumer<ToolWindow, Boolean> stateListener;

    /** When true (Zen mode), all side stripes are force-hidden regardless of their buttons. */
    private boolean zenHidesStripes;
    /** User setting: when false the tool stripes are hidden (UI only — windows still open via keys/palette). */
    private boolean stripesEnabled = true;
    /** Pulses to keep re-looking for the tab header's node before giving up (see the binder below). */
    private static final int SKIN_LOOKUP_ATTEMPTS = 20;
    /** True once the vertical stripes' top inset tracks the tab-header height, so the binder runs once. */
    private boolean stripeInsetBound;

    /** The tool window currently maximized over its split, or null. At most one at a time. */
    private ToolWindow maximized;
    /** The split {@link #maximized} took over, and the divider positions to hand back on restore. */
    private SplitPane maximizedSplit;

    private double[] maximizedRestorePositions;
    /** Min sizes zeroed to let the maximized panel actually take the whole split, and their old values. */
    private final Map<Region, Double> collapsedMinSizes = new java.util.IdentityHashMap<>();
    /** The open tool window holding keyboard focus, or null — the default target of a maximize request. */
    private ToolWindow activeToolWindow;
    /** True while a stripe button is being dragged, which reveals the empty stripes as drop targets. */
    private boolean draggingStripeButton;

    public ToolWindowManager(BorderPane workspace, Node editorArea, ConfigManager config, KeymapManager keymap) {
        this.config = config;
        this.keymap = keymap;

        leftStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-vertical", "tool-stripe-left");
        rightStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-vertical", "tool-stripe-right");
        bottomStripe.getStyleClass().addAll("tool-stripe", "tool-stripe-horizontal", "tool-stripe-bottom");
        leftStripe.setAlignment(Pos.TOP_CENTER);
        rightStripe.setAlignment(Pos.TOP_CENTER);
        bottomStripe.setAlignment(Pos.CENTER_LEFT);
        enableStripeDropTarget(leftStripe, ToolWindow.Side.LEFT);
        enableStripeDropTarget(rightStripe, ToolWindow.Side.RIGHT);
        enableStripeDropTarget(bottomStripe, ToolWindow.Side.BOTTOM);

        hSplit.setOrientation(Orientation.HORIZONTAL);
        hSplit.getItems().add(editorArea);

        vSplit.setOrientation(Orientation.VERTICAL);
        vSplit.getItems().add(hSplit);

        workspace.setLeft(leftStripe);
        workspace.setRight(rightStripe);
        workspace.setBottom(bottomStripe);
        workspace.setCenter(vSplit);
        updateStripeVisibility();
        alignVerticalStripesWithEditorText(editorArea);

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

    /**
     * Lines the vertical stripes' topmost icon up with the editor's first line of text instead of with the
     * tab strip above it. The stripes are {@link BorderPane} siblings of the whole editor area, so they
     * begin at the tab strip's top edge; insetting them by the live tab-header height drops the first icon
     * to where the code starts.
     *
     * <p>Read from the header rather than hardcoded because the tab strip is hideable at runtime
     * ({@code view.toggleTabBar}, Zen/Expert), which takes its height to zero — a constant would then leave
     * the icons a tab-strip's height below the text they are meant to sit beside.
     *
     * <p>The skin (and so the {@code .tab-header-area} node) is built during the first CSS pass, which has
     * not necessarily happened when the editor area first joins a scene — hence the bounded retry.
     */
    private void alignVerticalStripesWithEditorText(Node editorArea) {
        editorArea.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                bindStripeTopInset(editorArea, SKIN_LOOKUP_ATTEMPTS);
            }
        });
        if (editorArea.getScene() != null) {
            bindStripeTopInset(editorArea, SKIN_LOOKUP_ATTEMPTS);
        }
    }

    private void bindStripeTopInset(Node editorArea, int attemptsLeft) {
        if (stripeInsetBound || attemptsLeft <= 0 || editorArea.getScene() == null) {
            return;
        }
        editorArea.applyCss();
        if (!(editorArea.lookup(".tab-header-area") instanceof Region header)) {
            Platform.runLater(() -> bindStripeTopInset(editorArea, attemptsLeft - 1));
            return;
        }
        stripeInsetBound = true;
        header.heightProperty().addListener((o, a, h) -> applyStripeTopInset(h.doubleValue()));
        applyStripeTopInset(header.getHeight());
    }

    /**
     * Applies the computed top inset to both vertical stripes.
     *
     * <p>Set as an inline style rather than through {@code setPadding}: {@code app.css} is an author
     * stylesheet, and an author stylesheet overrides a programmatically set property — so
     * {@code .tool-stripe-vertical}'s own {@code -fx-padding} would win it back on the next CSS pass. That
     * forces the other three insets to be restated here; keep them in step with that rule.
     */
    private void applyStripeTopInset(double tabHeaderHeight) {
        // Less the button's own top inset: what should line up with the first line of code is the GLYPH,
        // and the button pads above it (`.tool-stripe-button` in app.css) to separate the icons on the rail.
        // Without this subtraction, giving the buttons breathing room silently walks the first icon down
        // past the text it is supposed to sit beside.
        long top = Math.max(0, Math.round(tabHeaderHeight) - STRIPE_BUTTON_TOP_INSET);
        verticalStripeTopInset = top;
        String style = "-fx-padding: " + top + "px 0 8px 0;";
        leftStripe.setStyle(style);
        rightStripe.setStyle(style);
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            applyPanelInsets(side);
        }
    }

    /** The inset the vertical stripes carry, so a panel opened later starts at the same line. */
    private double verticalStripeTopInset;

    /**
     * Drops a left/right tool window's header to the first stripe button.
     *
     * <p>The stripes and the split holding the panels are siblings of one {@link BorderPane}, so they share
     * a top edge — and the stripes are deliberately inset to line their first glyph up with the editor's
     * first line of code. Without the same inset the panel's header sits a tab-strip's height above the
     * stripe button that opened it, which reads as two competing top edges.
     *
     * <p>Bottom panels live below the horizontal split and are never inset. The inset is an inline style
     * for the reason given on {@link #applyStripeTopInset}, and is safe to overwrite wholesale because
     * {@code ToolWindowPanel} sets no inline style of its own.
     */
    private void applyPanelInsets(ToolWindow.Side side) {
        List<ToolWindow> open = openOn(side);
        for (int i = 0; i < open.size(); i++) {
            Region panel = panels.get(open.get(i));
            if (panel != null) {
                // Only the topmost panel on a side is inset: the one below it sits against its neighbour,
                // not against the tab strip, so padding it would open a gap in the middle of the side.
                boolean inset = side != ToolWindow.Side.BOTTOM && i == 0;
                panel.setStyle(inset ? "-fx-padding: " + Math.round(verticalStripeTopInset) + "px 0 0 0;" : "");
            }
        }
    }

    /** Mirrors {@code .tool-stripe-button}'s top inset in app.css — keep the two in step. */
    private static final int STRIPE_BUTTON_TOP_INSET = 9;

    /**
     * Marks the tool window that contains the scene's focus owner as active (others inactive): its panel
     * gets the active border and its stripe button gets the {@link #ACTIVE} highlight. Iterates every
     * registered window so a window that just lost focus is cleared too (closed windows have no panel and
     * so are never active).
     */
    private void updateActivePanel(Node focusOwner) {
        activeToolWindow = null;
        for (ToolWindow tw : byId.values()) {
            Region panel = panels.get(tw); // non-null only while the window is open
            boolean active = panel != null && focusOwner != null && isDescendant(focusOwner, panel);
            if (active) {
                activeToolWindow = tw;
            }
            if (panel instanceof ToolWindowPanel p) {
                p.setActive(active);
            }
            Button button = stripeButtons.get(tw);
            if (button != null) {
                button.pseudoClassStateChanged(ACTIVE, active);
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

    /** Registers an observer notified after any tool window opens (true) / closes (false). */
    public void setStateListener(java.util.function.BiConsumer<ToolWindow, Boolean> listener) {
        this.stateListener = listener;
    }

    /** Registers a tool window that defaults to hidden (no stripe button until the user enables it),
     *  unless a saved visibility preference says otherwise. */
    public void register(ToolWindow tw, boolean defaultVisible) {
        if (!defaultVisible) {
            defaultHidden.add(tw.getId());
        }
        register(tw);
    }

    public void register(ToolWindow tw) {
        byId.put(tw.getId(), tw);
        ensureInOrder(tw.getId());
        Button button = new Button();
        button.setGraphic(tw.createIcon());
        button.getStyleClass().addAll("tool-stripe-button", "flat");
        button.setTooltip(new Tooltip(tooltipFor(tw)));
        button.setOnAction(e -> toggle(tw));
        // Right-click → Hide the icon (persisted; re-show from Settings → Tool Windows).
        MenuItem hide = new MenuItem(tr("toolwindow.hide"), Icons.closeSmall());
        hide.setOnAction(e -> setVisible(tw, false));
        MenuItem split = new MenuItem(tr("toolwindow.openInSplit"), Icons.splitHorizontal());
        split.setOnAction(e -> openInSplit(tw));
        ContextMenu menu = new ContextMenu(split, hide);
        // Enablement is decided as the menu opens, not when it is built: whether a side can take a second
        // window depends on what is open there, which changes constantly after this runs.
        menu.setOnShowing(e -> split.setDisable(!canSplitWith(tw)));
        button.setContextMenu(menu);
        enableReorderDrag(tw, button);
        // A retitle (the Project window's "Current Folder" swap) must reach a labeled bottom button.
        tw.titleProperty().addListener((o, was, now) -> applyStripeLabel(tw, button));
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
            setDraggingStripeButton(true);
            e.consume();
        });
        button.setOnDragOver(e -> {
            ToolWindow src = dragSource(e.getDragboard());
            if (src != null && src != tw) {
                e.acceptTransferModes(TransferMode.MOVE);
                showDropMarker(button, currentSide(tw) != ToolWindow.Side.BOTTOM, dropAfter(button, tw, e));
            }
            // Consumed unconditionally, so a drag over a button never reaches the stripe's own handler
            // below — the button has an insertion point to offer and the stripe does not.
            e.consume();
        });
        button.setOnDragExited(e -> clearDropMarkers(button));
        button.setOnDragDropped(e -> {
            clearDropMarkers(button);
            ToolWindow src = dragSource(e.getDragboard());
            boolean ok = false;
            if (src != null && src != tw) {
                dockOnto(src, tw, dropAfter(button, tw, e));
                ok = true;
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        button.setOnDragDone(e -> {
            button.getStyleClass().remove("tool-stripe-button-dragging");
            clearDropMarkers(button);
            setDraggingStripeButton(false);
        });
    }

    /**
     * Makes a stripe itself a drop target, so a button can be dragged to a side that is <em>empty</em> —
     * otherwise a stripe with no buttons would be unreachable, and re-docking would only ever work between
     * sides that already had something on them.
     *
     * <p>Also catches a drop below/past the last button, where there is no neighbour to insert against.
     * Button handlers consume the event first, so this only ever sees empty stripe space.
     */
    private void enableStripeDropTarget(Pane stripe, ToolWindow.Side side) {
        stripe.setOnDragOver(e -> {
            ToolWindow src = dragSource(e.getDragboard());
            if (src != null) {
                e.acceptTransferModes(TransferMode.MOVE);
                setStripeDropTarget(stripe, true);
            }
            e.consume();
        });
        stripe.setOnDragExited(e -> setStripeDropTarget(stripe, false));
        stripe.setOnDragDropped(e -> {
            setStripeDropTarget(stripe, false);
            ToolWindow src = dragSource(e.getDragboard());
            if (src != null) {
                dockToSideEnd(src, side);
            }
            e.setDropCompleted(src != null);
            e.consume();
        });
    }

    /** Highlights a stripe while a button is hovering over its empty space. */
    private static final String STRIPE_DROP_TARGET = "tool-stripe-drop-target";

    /**
     * Adds or removes the drop-target highlight, idempotently.
     *
     * <p>Both halves matter. {@code DRAG_OVER} fires continuously while the cursor is over the stripe — tens
     * of times a second — so an unguarded {@code add} stacks the class up dozens deep; and
     * {@code ObservableList.remove} drops only the <em>first</em> occurrence, so one clear then leaves the
     * stripe painted for the rest of the session.
     */
    private static void setStripeDropTarget(Pane stripe, boolean on) {
        if (on) {
            if (!stripe.getStyleClass().contains(STRIPE_DROP_TARGET)) {
                stripe.getStyleClass().add(STRIPE_DROP_TARGET);
            }
        } else {
            stripe.getStyleClass().removeAll(STRIPE_DROP_TARGET);
        }
    }

    /**
     * Reveals the empty stripes for the duration of a drag, so a window can be dropped on a side that has
     * nothing on it yet — see {@link ToolWindowVisibility#stripeShown(boolean, boolean, boolean, boolean)}
     * for why an empty stripe is otherwise not merely invisible but unable to receive the drop at all.
     */
    private void setDraggingStripeButton(boolean dragging) {
        // Cleared on ANY end-of-drag signal, ahead of the unchanged-state guard below: the end of the
        // gesture is the one moment that always happens, so the highlight is not left to the stripe's own
        // handlers. A drop onto a BUTTON is consumed by that button, so the stripe's DRAG_DROPPED never
        // runs — and moving the cursor from stripe space onto one of the stripe's own buttons is not an
        // exit from the stripe, so DRAG_EXITED does not run either. Reordering by dropping on a neighbour
        // hit exactly that gap and left the dashed drop zone painted for the rest of the session.
        if (!dragging) {
            for (Pane stripe : List.of(leftStripe, rightStripe, bottomStripe)) {
                setStripeDropTarget(stripe, false);
            }
        }
        if (draggingStripeButton == dragging) {
            return;
        }
        draggingStripeButton = dragging;
        updateStripeVisibility();
    }

    /**
     * Drops {@code src} onto {@code target}'s slot — moving it to that side first when the drag crossed
     * stripes. One save for the whole gesture: the side and the order are two halves of one move.
     */
    void dockOnto(ToolWindow src, ToolWindow target, boolean after) {
        if (src == null || target == null || src == target) {
            return;
        }
        ToolWindow.Side side = currentSide(target);
        redockForDrop(src, side);
        applyOrder(ToolWindowDock.dropOnto(orderWithEveryId(), src.getId(), target.getId(), after), side);
        config.save();
    }

    /** Drops {@code src} on empty stripe space: onto that side, last in its order. */
    void dockToSideEnd(ToolWindow src, ToolWindow.Side side) {
        if (src == null) {
            return;
        }
        redockForDrop(src, side);
        applyOrder(ToolWindowDock.dropAtEnd(orderWithEveryId(), src.getId()), side);
        config.save();
    }

    /**
     * Moves a dragged window to another side, <em>keeping it open if it was</em>.
     *
     * <p>The side change closes it (its panel belongs to the old side's split), and a drag that silently
     * shut the window you were just looking at reads as having lost it. Reopened without focus, so the
     * drop doesn't yank the caret out of the editor.
     */
    private void redockForDrop(ToolWindow src, ToolWindow.Side side) {
        if (currentSide(src) == side) {
            return;
        }
        boolean wasOpen = isOpen(src);
        setSide(src, side, false); // one save per gesture — the caller does it
        if (wasOpen) {
            open(src, false);
        }
    }

    /** The persisted order with every registered id present, so indices into it are meaningful. */
    private List<String> orderWithEveryId() {
        for (String id : byId.keySet()) {
            ensureInOrder(id);
        }
        return config.getWorkspaceState().getToolWindowOrder();
    }

    /** Commits a computed order into the session's own list and re-sorts the affected stripe. */
    private void applyOrder(List<String> next, ToolWindow.Side side) {
        List<String> order = config.getWorkspaceState().getToolWindowOrder();
        order.clear();
        order.addAll(next);
        relayoutStripe(side);
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
        if (v != null) {
            return v;
        }
        return !defaultHidden.contains(tw.getId()); // default-hidden windows stay off until enabled
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
            if (isOpen(tw)) {
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

    /**
     * Seeds a context-dependent visibility default without overriding a choice the user already made.
     * Used when a tool's sensible default cannot be known at registration time (for example, GitHub after
     * the asynchronous {@code gh} probe completes).
     */
    public void setVisibleIfUnset(ToolWindow tw, boolean visible) {
        if (!config.getWorkspaceState().getToolWindowVisible().containsKey(tw.getId())) {
            setVisible(tw, visible);
        }
    }

    /** Whether the stripe button should be present: the user keeps it visible AND it isn't context-hidden. */
    private boolean shouldShowButton(ToolWindow tw) {
        return ToolWindowVisibility.buttonShown(isVisible(tw), unavailable.contains(tw));
    }

    /**
     * Context-driven availability (NOT persisted, unlike {@link #setVisible}): hides the tool window's
     * stripe button + closes it when {@code available} is false, restoring it (subject to the user's
     * {@link #isVisible} preference) when true. Used to hide the Commit window outside a Git repo without
     * disturbing the user's show/hide setting. Always reconciles the actual open/closed + button state to
     * match {@code available} — it does NOT short-circuit when the tracked flag already matches, because
     * {@link #open}/{@link #restore} can force a window open while it's still marked unavailable (a
     * previously-open tool window is restored at startup before the first real buffer loads, when every
     * buffer-gated window is provisionally unavailable): without reconciling every call, that divergence
     * between "tracked unavailable" and "actually open" would make the very next {@code setAvailable(tw,
     * false)} look like a no-op and skip the {@link #close} it should perform.
     */
    public void setAvailable(ToolWindow tw, boolean available) {
        if (available) {
            unavailable.remove(tw);
        } else {
            unavailable.add(tw);
        }
        Button button = stripeButtons.get(tw);
        var stripe = stripeFor(currentSide(tw)).getChildren();
        if (!available) {
            if (isOpen(tw)) {
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
        return tw != null && (openOn(currentSide(tw)).contains(tw) || isFloating(tw));
    }

    /** The currently open tool windows, ordered by side (left, bottom, right), for focus cycling. */
    public java.util.List<ToolWindow> getOpenToolWindows() {
        java.util.List<ToolWindow> open = new java.util.ArrayList<>();
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            open.addAll(openOn(side));
        }
        open.addAll(floatingStages.keySet()); // detached, but open — Zen must close these too
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
        setSide(tw, newSide, true);
    }

    /**
     * @param save false for a drag, which changes the side and the order together and saves once at the end
     */
    private void setSide(ToolWindow tw, ToolWindow.Side newSide, boolean save) {
        ToolWindow.Side oldSide = currentSide(tw);
        if (oldSide == newSide) {
            return;
        }
        if (isOpen(tw)) {
            close(tw);
        }
        Button button = stripeButtons.get(tw);
        config.getWorkspaceState().getToolWindowSides().put(tw.getId(), newSide.name());
        if (button != null && shouldShowButton(tw)) {
            stripeFor(oldSide).getChildren().remove(button);
            addButtonOrdered(tw, button);
        }
        updateStripeVisibility();
        if (save) {
            config.save();
        }
    }

    private void updateStripeVisibility() {
        setStripeShown(leftStripe);
        setStripeShown(rightStripe);
        setStripeShown(bottomStripe);
    }

    private void setStripeShown(Pane stripe) {
        boolean shown = ToolWindowVisibility.stripeShown(
                stripesEnabled, zenHidesStripes, stripe.getChildren().isEmpty(), draggingStripeButton);
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
        ToolWindow maximizeAfterOpen = null;
        for (String id : ids) {
            ToolWindow tw = visibleById(id);
            if (tw == null) {
                continue;
            }
            if (MODE_MAXIMIZED.equals(presentationMode(tw))) {
                maximizeAfterOpen = tw;
                openOnSide(tw, false, true);
            } else {
                openRemembered(tw, false, true);
            }
        }
        // Opening another side invalidates a live maximize, so apply it only after the whole batch exists.
        if (maximizeAfterOpen != null) {
            maximize(maximizeAfterOpen);
        }
    }

    /** Opens any tool windows the settings file says were open last time. */
    /** Reopens each side's windows in their stacked order — the first replaces, the rest join it. */
    public void restore() {
        // Read FIRST, before a single window is opened: every open persists, and persisting rewrites the
        // floating set from the live one — which is empty until the deferred pass below runs. Reading it
        // after the docked sides restore hands back a list this method has just cleared itself.
        List<String> floating = List.copyOf(config.getWorkspaceState().getFloatingToolWindows());
        Map<ToolWindow.Side, List<String>> docked = new java.util.EnumMap<>(ToolWindow.Side.class);
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            docked.put(
                    side,
                    List.copyOf(config.getWorkspaceState().getOpenToolWindows().getOrDefault(side.name(), List.of())));
        }
        ToolWindow maximizeAfterOpen = null;
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            boolean first = true;
            for (String id : docked.get(side)) {
                ToolWindow tw = visibleById(id);
                if (tw != null) {
                    // Session restoration rebuilds the complete dock first. Applying maximize per-window
                    // would have the next side's open cancel it as a stale layout.
                    openOnSide(tw, false, first);
                    if (MODE_MAXIMIZED.equals(presentationMode(tw))) {
                        maximizeAfterOpen = tw;
                    }
                }
                first = false;
            }
        }
        if (maximizeAfterOpen != null) {
            maximize(maximizeAfterOpen);
        }
        if (!floating.isEmpty()) {
            // Deferred: restore() runs during window construction, before there is a Scene — and so before
            // there is an owner to attach a floating stage to. By the next pulse the window is showing.
            Platform.runLater(() -> {
                for (String id : floating) {
                    ToolWindow tw = byId.get(id);
                    if (tw != null && shouldShowButton(tw)) {
                        openFloating(tw);
                    }
                }
            });
        }
    }

    /** Opens a tool window straight into its own stage, without it passing through the dock on the way. */
    private void openFloating(ToolWindow tw) {
        if (panels.containsKey(tw)) {
            floatOut(tw); // already open somewhere — just detach it
            return;
        }
        ToolWindowPanel panel =
                new ToolWindowPanel(tw, () -> close(tw), () -> toggleMaximized(tw), () -> toggleFloating(tw));
        panels.put(tw, panel);
        stripeButtons.get(tw).pseudoClassStateChanged(OPEN, true);
        floatOut(tw);
        if (stateListener != null) {
            stateListener.accept(tw, true);
        }
    }

    public void toggle(ToolWindow tw) {
        if (isOpen(tw)) {
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
        openRemembered(tw, focus, true);
    }

    /** Opens a closed tool window in the presentation mode in which the user last left it. */
    private void openRemembered(ToolWindow tw, boolean focus, boolean replace) {
        String mode = presentationMode(tw);
        if (MODE_FLOATING.equals(mode) && vSplit.getScene() != null) {
            openFloating(tw);
            return;
        }
        openOnSide(tw, focus, replace);
        if (MODE_MAXIMIZED.equals(mode)) {
            maximize(tw);
        }
    }

    /**
     * Opens {@code tw} <em>alongside</em> whatever already occupies its side, splitting it in two.
     *
     * <p>Deliberately a separate entry point rather than a mode: a plain open (stripe button, keybinding,
     * palette) keeps replacing, as it always has, so splitting only ever happens when it is asked for.
     */
    public void openInSplit(ToolWindow tw) {
        rememberPresentation(tw, MODE_DOCKED);
        openOnSide(tw, true, false);
    }

    /** True when {@code tw} could join its side's current occupant rather than replace it. */
    public boolean canSplitWith(ToolWindow tw) {
        List<ToolWindow> open = openOn(currentSide(tw));
        return !open.isEmpty() && !open.contains(tw) && open.size() < MAX_PER_SIDE;
    }

    /**
     * @param replace true to make {@code tw} the side's only window (the historic behaviour), false to add
     *     it beside the current one
     */
    private void openOnSide(ToolWindow tw, boolean focus, boolean replace) {
        ToolWindow.Side side = currentSide(tw);
        List<ToolWindow> open = openOn(side);
        if (open.contains(tw)) {
            return;
        }
        // Any change to a split's contents invalidates a maximize: the panel about to be added shifts the
        // divider indices the saved positions were captured against.
        restoreMaximized();
        if (replace) {
            for (ToolWindow other : new ArrayList<>(open)) {
                close(other);
            }
        } else if (open.size() >= MAX_PER_SIDE) {
            close(open.get(open.size() - 1)); // the companion makes way; the primary stays put
        }
        boolean wasEmpty = open.isEmpty();
        ToolWindowPanel panel =
                new ToolWindowPanel(tw, () -> close(tw), () -> toggleMaximized(tw), () -> toggleFloating(tw));
        panels.put(tw, panel);
        open.add(tw);
        rebuildSide(side);
        if (wasEmpty) {
            applyOuterDivider(side, tw, panel);
        }
        stripeButtons.get(tw).pseudoClassStateChanged(OPEN, true);
        persist();
        if (stateListener != null) {
            stateListener.accept(tw, true);
        }
        // Move focus into the freshly shown panel and select its first item (deferred until it's laid
        // out). Skipped on session restore so a restored-open tool window doesn't steal startup focus.
        if (focus && tw.getContent() instanceof ToolWindowContent content) {
            Platform.runLater(content::focusFirstItem);
        }
    }

    // --- Float / detach --------------------------------------------------------------------------

    /** True while this tool window lives in its own stage rather than docked to a side. */
    public boolean isFloating(ToolWindow tw) {
        return tw != null && floatingStages.containsKey(tw);
    }

    /** Detaches an open tool window into its own stage, or puts a floating one back on its side. */
    public void toggleFloating(ToolWindow tw) {
        if (isFloating(tw)) {
            dockBack(tw);
        } else {
            floatOut(tw);
        }
    }

    /**
     * Moves an open tool window out of the dock and into its own stage.
     *
     * <p>The stage is <em>owned</em> by the editor window, which is what makes it behave like part of the
     * app rather than a second application: it floats above its owner, minimises with it, and — the reason
     * there is no teardown hook anywhere for these — is closed by JavaFX when the owner closes.
     */
    private void floatOut(ToolWindow tw) {
        Region panel = panels.get(tw);
        javafx.stage.Window owner =
                vSplit.getScene() == null ? null : vSplit.getScene().getWindow();
        if (panel == null || owner == null || isFloating(tw)) {
            return;
        }
        restoreMaximized(); // the side is about to lose a panel
        ToolWindow.Side side = currentSide(tw);
        openOn(side).remove(tw);
        rebuildSide(side);
        panel.setStyle(""); // drop the tab-strip top inset — there is no tab strip over a floating stage

        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.initOwner(owner);
        stage.titleProperty().bind(tw.titleProperty());
        javafx.scene.Scene scene = new javafx.scene.Scene(new javafx.scene.layout.StackPane(panel));
        // A fresh Scene carries no stylesheets, and the app's look is an author stylesheet rather than the
        // user-agent one — without copying these the detached panel renders as unstyled default controls.
        if (owner.getScene() != null) {
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        applyFloatingBounds(tw, stage, owner);
        // Closing the stage closes the tool window: floating is a state of an open window, not a mode it
        // keeps, so it can never be reopened into a stage the user has forgotten is out there.
        stage.setOnCloseRequest(e -> {
            e.consume();
            close(tw);
        });
        rememberBoundsAsTheyChange(tw, stage);
        floatingStages.put(tw, stage);
        stage.show();
        setPanelFloating(tw, true);
        rememberPresentation(tw, MODE_FLOATING);
        persist();
        if (tw.getContent() instanceof ToolWindowContent content) {
            Platform.runLater(content::focusFirstItem);
        }
    }

    /** Returns a floating tool window to its side, where it lands as that side's primary. */
    private void dockBack(ToolWindow tw) {
        javafx.stage.Stage stage = floatingStages.remove(tw);
        if (stage == null) {
            return;
        }
        rememberFloatingBounds(tw, stage);
        detachPanelFrom(stage);
        stage.close();
        setPanelFloating(tw, false);
        ToolWindow.Side side = currentSide(tw);
        List<ToolWindow> open = openOn(side);
        if (open.size() >= MAX_PER_SIDE) {
            close(open.get(open.size() - 1)); // make room the same way an over-full split does
        }
        boolean wasEmpty = open.isEmpty();
        open.add(tw);
        rebuildSide(side);
        if (wasEmpty) {
            applyOuterDivider(side, tw, panels.get(tw));
        }
        rememberPresentation(tw, MODE_DOCKED);
        persist();
    }

    /**
     * Takes the panel out of a floating scene so it can be re-parented.
     *
     * <p>A JavaFX node has exactly one parent, so handing the panel straight to the split while the scene
     * still holds it throws — the scene has to be given something else to own first.
     */
    private void detachPanelFrom(javafx.stage.Stage stage) {
        if (stage.getScene() != null) {
            stage.getScene().setRoot(new javafx.scene.layout.StackPane());
        }
    }

    /** Positions a floating stage: its remembered bounds when those are still reachable, else on the owner. */
    private void applyFloatingBounds(ToolWindow tw, javafx.stage.Stage stage, javafx.stage.Window owner) {
        ToolWindowFloat.Bounds b = ToolWindowFloat.fromList(
                config.getWorkspaceState().getFloatingToolWindowBounds().get(tw.getId()));
        List<javafx.geometry.Rectangle2D> screens = new ArrayList<>();
        for (javafx.stage.Screen s : javafx.stage.Screen.getScreens()) {
            screens.add(s.getVisualBounds());
        }
        if (b != null && ToolWindowFloat.isReachable(b, screens)) {
            stage.setX(b.x());
            stage.setY(b.y());
            stage.setWidth(b.width());
            stage.setHeight(b.height());
            return;
        }
        // No usable memory (or the screen it was on is gone): a default size, centred on the editor window.
        stage.setWidth(b == null ? ToolWindowFloat.DEFAULT_WIDTH : b.width());
        stage.setHeight(b == null ? ToolWindowFloat.DEFAULT_HEIGHT : b.height());
        stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
        stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
    }

    /**
     * Keeps the stored bounds current as the user moves and resizes the stage.
     *
     * <p>Written into the session object on every change but never saved from here — a {@code config.save()}
     * per pixel of a drag would be a blocking disk write on the FX thread. The next ordinary save flushes
     * it, and there always is one, because closing or docking the window persists.
     */
    private void rememberBoundsAsTheyChange(ToolWindow tw, javafx.stage.Stage stage) {
        javafx.beans.value.ChangeListener<Number> onMove = (o, was, now) -> rememberFloatingBounds(tw, stage);
        stage.xProperty().addListener(onMove);
        stage.yProperty().addListener(onMove);
        stage.widthProperty().addListener(onMove);
        stage.heightProperty().addListener(onMove);
    }

    private void rememberFloatingBounds(ToolWindow tw, javafx.stage.Stage stage) {
        if (stage.getWidth() < ToolWindowFloat.MIN_USABLE || stage.getHeight() < ToolWindowFloat.MIN_USABLE) {
            return; // mid-construction, before the stage has been sized
        }
        config.getWorkspaceState()
                .getFloatingToolWindowBounds()
                .put(
                        tw.getId(),
                        ToolWindowFloat.toList(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
    }

    private void setPanelFloating(ToolWindow tw, boolean floating) {
        if (panels.get(tw) instanceof ToolWindowPanel p) {
            p.setFloating(floating);
        }
    }

    /** The tool windows open on a side, in stacking order (the live list — mutated by open/close). */
    private List<ToolWindow> openOn(ToolWindow.Side side) {
        return openBySide.computeIfAbsent(side, s -> new ArrayList<>());
    }

    /** The outer split a side contributes to: the bottom stacks under everything, the two sides beside it. */
    private SplitPane outerSplitFor(ToolWindow.Side side) {
        return side == ToolWindow.Side.BOTTOM ? vSplit : hSplit;
    }

    /**
     * Rebuilds a side's contribution to the outer split after its open set changed, swapping the container
     * in place so the outer divider survives.
     */
    private void rebuildSide(ToolWindow.Side side) {
        rememberInnerDivider(side); // before any inner split is discarded
        List<ToolWindow> open = openOn(side);
        Region next = null;
        if (open.size() == 1) {
            next = panels.get(open.get(0));
            innerSplits.remove(side);
        } else if (open.size() >= 2) {
            SplitPane inner = new SplitPane();
            inner.getStyleClass().add("tool-window-split");
            // Sides stack vertically (Project over Structure); the bottom strip divides left-to-right,
            // which is the only way two consoles down there are each wide enough to read.
            inner.setOrientation(side == ToolWindow.Side.BOTTOM ? Orientation.HORIZONTAL : Orientation.VERTICAL);
            for (ToolWindow t : open) {
                inner.getItems().add(panels.get(t));
            }
            innerSplits.put(side, inner);
            double pos = config.getWorkspaceState()
                    .getToolWindowSplitDividers()
                    .getOrDefault(side.name(), DEFAULT_SPLIT_DIVIDER);
            Platform.runLater(() -> inner.setDividerPosition(0, pos));
            next = inner;
        } else {
            innerSplits.remove(side);
        }
        applyPanelInsets(side);
        placeContainer(side, next);
    }

    /** An even split until the user drags it — neither window on a side has a claim to more room. */
    private static final double DEFAULT_SPLIT_DIVIDER = 0.5;

    /** Puts a side's new container into the outer split, replacing/removing/inserting as needed. */
    private void placeContainer(ToolWindow.Side side, Region next) {
        Region old = sideContainers.get(side);
        if (old == next) {
            return;
        }
        SplitPane outer = outerSplitFor(side);
        if (old != null && next != null) {
            // Swapped in place so the container keeps its slot in the outer split — an add-then-remove
            // would renumber every divider after it. The POSITION still has to be put back by hand: a
            // SplitPane rebuilds its dividers on any items-list change, a replace included, so a side that
            // split or unsplit would otherwise spring back to an even share with the editor.
            Double keep = outerDividerOf(side);
            outer.getItems().set(outer.getItems().indexOf(old), next);
            sideContainers.put(side, next); // so the re-apply measures against the node now in the split
            if (keep != null) {
                applyOuterDividerPosition(side, keep);
            }
        } else if (old != null) {
            outer.getItems().remove(old);
        } else {
            switch (side) {
                case LEFT -> outer.getItems().add(0, next);
                case RIGHT, BOTTOM -> outer.getItems().add(next);
            }
        }
        if (next == null) {
            sideContainers.remove(side);
        } else {
            sideContainers.put(side, next);
        }
    }

    /**
     * Puts a side's outer divider back to {@code pos}, now and again on the next pulse.
     *
     * <p>Both: the immediate set is what holds within the current gesture, while the deferred one survives
     * the layout pass the items-list change schedules, which re-runs the skin's own divider setup.
     */
    private void applyOuterDividerPosition(ToolWindow.Side side, double pos) {
        SplitPane outer = outerSplitFor(side);
        Region container = sideContainers.get(side);
        int idx = outer.getItems().indexOf(container);
        int divider = side == ToolWindow.Side.LEFT ? idx : idx - 1;
        if (idx < 0 || divider < 0 || divider >= outer.getDividers().size()) {
            return;
        }
        outer.setDividerPosition(divider, pos);
        Platform.runLater(() -> {
            if (sideContainers.get(side) == container
                    && divider < outer.getDividers().size()) {
                outer.setDividerPosition(divider, pos);
            }
        });
    }

    /** Sets the outer divider for a side that just went from empty to holding its first window. */
    private void applyOuterDivider(ToolWindow.Side side, ToolWindow tw, Region panel) {
        boolean firstOpen = !config.getWorkspaceState().getToolWindowSizes().containsKey(tw.getId());
        double pos = dividerFor(tw, side);
        SplitPane outer = outerSplitFor(side);
        int dividerIdx = side == ToolWindow.Side.LEFT ? 0 : outer.getItems().size() - 2;
        Platform.runLater(() -> {
            outer.setDividerPosition(dividerIdx, pos);
            // A pulse later, so the fit sees the panel at its remembered width rather than the previous
            // layout's — a SplitPane only settles a position on a layout pass.
            if (firstOpen && side != ToolWindow.Side.BOTTOM) {
                Platform.runLater(() -> fitToContent(panel, dividerIdx, side == ToolWindow.Side.LEFT));
            }
        });
    }

    /** Records where the user left the divider inside a split side, so the pairing reopens as they left it. */
    private void rememberInnerDivider(ToolWindow.Side side) {
        SplitPane inner = innerSplits.get(side);
        if (inner != null && !inner.getDividers().isEmpty()) {
            config.getWorkspaceState()
                    .getToolWindowSplitDividers()
                    .put(side.name(), inner.getDividers().get(0).getPosition());
        }
    }

    // --- Maximize --------------------------------------------------------------------------------

    /**
     * The tool window a maximize request should act on: the focused one, else the only open one.
     *
     * @return the target, or null when there is nothing (or nothing unambiguous) to maximize
     */
    public ToolWindow maximizeTarget() {
        return ToolWindowMaximize.pick(isOpen(activeToolWindow) ? activeToolWindow : null, getOpenToolWindows());
    }

    /** True while this tool window is the one expanded over its split. */
    public boolean isMaximized(ToolWindow tw) {
        return tw != null && maximized == tw;
    }

    /** Expands the tool window over its whole split, or hands the space back if it already holds it. */
    public void toggleMaximized(ToolWindow tw) {
        if (isMaximized(tw)) {
            restoreMaximized();
            rememberPresentation(tw, MODE_DOCKED);
        } else {
            maximize(tw);
            if (isMaximized(tw)) {
                rememberPresentation(tw, MODE_MAXIMIZED);
            }
        }
        persist();
    }

    /**
     * Gives an open tool window its split's entire space by pushing every divider to one end.
     *
     * <p>Scoped to the panel's <em>own</em> split, so maximizing a left/right window leaves an open bottom
     * window alone — the bottom panel is a sibling of the whole horizontal split, not of the editor.
     *
     * <p>The other items' min sizes are zeroed for the duration: a {@code SplitPane} honours them, and the
     * editor area's computed minimum (its tab header) is wide enough that without this a "maximize" only
     * shrinks the editor part-way and reads as a bug.
     */
    private void maximize(ToolWindow tw) {
        restoreMaximized(); // only ever one at a time
        if (isFloating(tw)) {
            return; // a detached stage has no split to take over — the OS window manager sizes it
        }
        ToolWindow.Side side = currentSide(tw);
        // The side's container, not the panel: on a split side the panel is inside an inner split and is
        // not an item of the outer one, so maximizing gives the whole side the space — both panes with it.
        Region container = sideContainers.get(side);
        if (container == null || !isOpen(tw)) {
            return; // not open — nothing to maximize
        }
        SplitPane split = outerSplitFor(side);
        boolean horizontal = split == hSplit;
        double[] positions = ToolWindowMaximize.positions(
                split.getItems().size(), split.getItems().indexOf(container));
        if (positions.length == 0) {
            return;
        }
        maximized = tw;
        maximizedSplit = split;
        maximizedRestorePositions = split.getDividerPositions();
        for (Node item : split.getItems()) {
            if (item != container && item instanceof Region r) {
                collapsedMinSizes.put(r, horizontal ? r.getMinWidth() : r.getMinHeight());
                if (horizontal) {
                    r.setMinWidth(0);
                } else {
                    r.setMinHeight(0);
                }
            }
        }
        split.setDividerPositions(positions);
        setPanelMaximized(tw, true);
    }

    /**
     * Hands the space back to the rest of the split. A no-op when nothing is maximized, so every caller
     * that is about to change a split's contents can call it unconditionally.
     */
    private void restoreMaximized() {
        restoreMaximized(true);
    }

    /** @param rememberDocked whether this visible transition should replace the sticky maximize mode */
    private void restoreMaximized(boolean rememberDocked) {
        if (maximized == null) {
            return;
        }
        ToolWindow tw = maximized;
        SplitPane split = maximizedSplit;
        double[] positions = maximizedRestorePositions;
        boolean horizontal = split == hSplit;
        maximized = null;
        maximizedSplit = null;
        maximizedRestorePositions = null;
        for (Map.Entry<Region, Double> e : collapsedMinSizes.entrySet()) {
            if (horizontal) {
                e.getKey().setMinWidth(e.getValue());
            } else {
                e.getKey().setMinHeight(e.getValue());
            }
        }
        collapsedMinSizes.clear();
        // Guarded on the divider count: an item could have been added or removed since (a second tool
        // window opening, say), and a stale array would then set the wrong dividers.
        if (split != null
                && positions != null
                && positions.length == split.getDividers().size()) {
            split.setDividerPositions(positions);
        }
        setPanelMaximized(tw, false);
        if (rememberDocked) {
            rememberPresentation(tw, MODE_DOCKED);
        }
    }

    private void setPanelMaximized(ToolWindow tw, boolean value) {
        if (panels.get(tw) instanceof ToolWindowPanel p) {
            p.setMaximized(value);
        }
    }

    /**
     * Captures the live divider positions of the currently-open tool windows into the session. Called on
     * quit/window-close so a divider the user dragged but left open is remembered — {@link #close} only
     * captures it when a tool window is actually hidden, so a quit-while-open would otherwise lose it.
     *
     * <p>Un-maximizes first: a maximized window's divider sits at 0 or 1, and persisting <em>that</em> as
     * the window's remembered size would have it reopen next session covering the editor.
     */
    public void persistDividers() {
        restoreMaximized(false);
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            ToolWindow primary = primaryOn(side);
            if (primary != null) {
                Double pos = outerDividerOf(side);
                if (pos != null) {
                    rememberDivider(primary, side, pos);
                }
            }
            rememberInnerDivider(side);
        }
    }

    /**
     * Where the outer divider for a side currently sits, or null when it has none.
     *
     * <p>Read against the side's <em>container</em>, not a panel: once a side splits, its panels live in an
     * inner split and are not items of the outer one at all, so an {@code indexOf(panel)} would answer -1
     * and the size would silently stop being remembered.
     */
    private Double outerDividerOf(ToolWindow.Side side) {
        Region container = sideContainers.get(side);
        if (container == null) {
            return null;
        }
        SplitPane outer = outerSplitFor(side);
        int idx = outer.getItems().indexOf(container);
        // The divider that sizes a side is the one on its inward edge: left of a right-hand panel, right
        // of a left-hand one. The bottom's container is last in the vertical split, so it is the same case.
        int divider = side == ToolWindow.Side.LEFT ? idx : idx - 1;
        if (idx < 0 || divider < 0 || divider >= outer.getDividers().size()) {
            return null;
        }
        return outer.getDividers().get(divider).getPosition();
    }

    public void close(ToolWindow tw) {
        if (panels.get(tw) == null) {
            return; // already closed — must not disturb a maximize belonging to some other window
        }
        if (isFloating(tw)) {
            javafx.stage.Stage stage = floatingStages.remove(tw);
            rememberFloatingBounds(tw, stage);
            detachPanelFrom(stage);
            stage.close();
            panels.remove(tw);
            afterClosed(tw);
            return;
        }
        // Before the dividers are read below: a maximized divider sits at 0 or 1, and remembering that as
        // this window's size would have it reopen covering the editor.
        restoreMaximized(!isMaximized(tw));
        ToolWindow.Side side = currentSide(tw);
        Double pos = outerDividerOf(side);
        if (pos != null) {
            rememberDivider(tw, side, pos);
        }
        rememberInnerDivider(side); // rebuildSide would too, but only while the inner split still exists
        panels.remove(tw);
        openOn(side).remove(tw);
        rebuildSide(side);
        afterClosed(tw);
    }

    /** The bookkeeping every close shares, whichever place the window was closed from. */
    private void afterClosed(ToolWindow tw) {
        stripeButtons.get(tw).pseudoClassStateChanged(OPEN, false);
        persist();
        if (stateListener != null) {
            stateListener.accept(tw, false);
        }
    }

    private ToolWindow visibleById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ToolWindow tw = byId.get(id);
        return tw != null && shouldShowButton(tw) ? tw : null;
    }

    private String presentationMode(ToolWindow tw) {
        String mode =
                config.getWorkspaceState().getToolWindowPresentationModes().get(tw.getId());
        return MODE_MAXIMIZED.equals(mode) || MODE_FLOATING.equals(mode) ? mode : MODE_DOCKED;
    }

    private void rememberPresentation(ToolWindow tw, String mode) {
        config.getWorkspaceState().getToolWindowPresentationModes().put(tw.getId(), mode);
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
        applyStripeLabel(tw, button);
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

    /**
     * Bottom-stripe buttons carry their title beside the icon (UI Kit: the bottom rail reads
     * "Problems  Run  Output  Git Log …"); the vertical rails stay icon-only — a rotated or truncated
     * label on a 30px rail helps nobody. Every placement funnels through {@link #addButtonOrdered}
     * (register, availability re-add, visibility re-add, side move), so a window dragged between sides
     * gains/loses its label automatically.
     */
    private void applyStripeLabel(ToolWindow tw, Button button) {
        button.setText(currentSide(tw) == ToolWindow.Side.BOTTOM ? tw.getTitle() : "");
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

    /**
     * The split fraction this window should open at: its own remembered size, else its side's.
     *
     * <p>Sizes used to be per SIDE only — three numbers for every window docked there — so opening the
     * Project tree at 22% and then the Structure outline left both at whatever the last one was dragged to.
     * The per-side value is still kept up to date and is what a never-opened window inherits, so a first
     * open is no worse than it was.
     */
    private double dividerFor(ToolWindow tw, ToolWindow.Side side) {
        WorkspaceState s = config.getWorkspaceState();
        Double own = s.getToolWindowSizes().get(tw.getId());
        if (own != null) {
            return own;
        }
        return switch (side) {
            case LEFT -> s.getLeftDividerPosition();
            case RIGHT -> s.getRightDividerPosition();
            case BOTTOM -> s.getBottomDividerPosition();
        };
    }

    /** Records a window's size against its own id, and as its side's default for the next new window. */
    private void rememberDivider(ToolWindow tw, ToolWindow.Side side, double pos) {
        WorkspaceState s = config.getWorkspaceState();
        s.getToolWindowSizes().put(tw.getId(), pos);
        switch (side) {
            case LEFT -> s.setLeftDividerPosition(pos);
            case RIGHT -> s.setRightDividerPosition(pos);
            case BOTTOM -> s.setBottomDividerPosition(pos);
        }
    }

    /**
     * On a window's FIRST open, widens it so its content stops needing a horizontal scrollbar.
     *
     * <p>Gated on a scrollbar actually being there rather than on the preferred width alone: the panels
     * that overflow are trees and lists, and a virtualized control's preferred width is a constant, not a
     * measure of its content — so asking it unprompted would answer confidently and wrongly. Seeing the
     * scrollbar first means the width it then reports is at worst a harmless over-estimate, and the fit
     * only ever widens, never shrinks.
     *
     * <p>Best-effort by nature, and only on a first open (afterwards the user's own size wins): the fit is
     * a snapshot of the rows visible at that moment, so expanding a deep tree node later can still bring
     * the scrollbar back.
     */
    private void fitToContent(Region panel, int dividerIdx, boolean leftSide) {
        if (dividerIdx < 0 || dividerIdx >= hSplit.getDividers().size() || !overflowsHorizontally(panel)) {
            return;
        }
        double pos = ToolWindowFit.fraction(panel.prefWidth(-1), panel.getWidth(), hSplit.getWidth(), leftSide);
        if (pos != ToolWindowFit.NO_CHANGE) {
            hSplit.setDividerPosition(dividerIdx, pos);
        }
    }

    /** True while anything inside the panel is showing a horizontal scrollbar. */
    private static boolean overflowsHorizontally(Region panel) {
        for (Node n : panel.lookupAll(".scroll-bar")) {
            if (n instanceof javafx.scene.control.ScrollBar sb
                    && sb.getOrientation() == Orientation.HORIZONTAL
                    && sb.isVisible()) {
                return true;
            }
        }
        return false;
    }

    private void persist() {
        WorkspaceState s = config.getWorkspaceState();
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            List<String> ids = new ArrayList<>();
            for (ToolWindow tw : openOn(side)) {
                ids.add(tw.getId());
            }
            s.getOpenToolWindows().put(side.name(), ids);
        }
        List<String> floating = new ArrayList<>();
        for (ToolWindow tw : floatingStages.keySet()) {
            floating.add(tw.getId());
        }
        s.setFloatingToolWindows(floating);
        // Still written, carrying each side's FIRST window: a build that predates split sides reads only
        // these, and would otherwise restore an empty layout from a session this one wrote.
        s.setOpenLeftToolWindow(idOf(primaryOn(ToolWindow.Side.LEFT)));
        s.setOpenRightToolWindow(idOf(primaryOn(ToolWindow.Side.RIGHT)));
        s.setOpenBottomToolWindow(idOf(primaryOn(ToolWindow.Side.BOTTOM)));
        config.save();
    }

    /** A side's primary (first-stacked) window, or null when nothing is open there. */
    private ToolWindow primaryOn(ToolWindow.Side side) {
        List<ToolWindow> open = openOn(side);
        return open.isEmpty() ? null : open.get(0);
    }

    private static String idOf(ToolWindow tw) {
        return tw == null ? "" : tw.getId();
    }
}

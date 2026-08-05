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

    private final Map<ToolWindow.Side, ToolWindow> openBySide = new HashMap<>();
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
        for (Map.Entry<ToolWindow, Region> e : panels.entrySet()) {
            applyPanelTopInset(e.getKey(), e.getValue());
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
    private void applyPanelTopInset(ToolWindow tw, Region panel) {
        boolean vertical = currentSide(tw) != ToolWindow.Side.BOTTOM;
        panel.setStyle(vertical ? "-fx-padding: " + Math.round(verticalStripeTopInset) + "px 0 0 0;" : "");
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
        for (ToolWindow tw : byId.values()) {
            Region panel = panels.get(tw); // non-null only while the window is open
            boolean active = panel != null && focusOwner != null && isDescendant(focusOwner, panel);
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
        button.setContextMenu(new ContextMenu(hide));
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
        boolean shown = ToolWindowVisibility.stripeShown(
                stripesEnabled, zenHidesStripes, stripe.getChildren().isEmpty());
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
        applyPanelTopInset(tw, panel); // match the stripe the button that opened it sits on
        boolean firstOpen = !config.getWorkspaceState().getToolWindowSizes().containsKey(tw.getId());
        double pos = dividerFor(tw, side);
        switch (side) {
            case LEFT -> {
                hSplit.getItems().add(0, panel);
                Platform.runLater(() -> {
                    hSplit.setDividerPosition(0, pos);
                    // A pulse later, so the fit sees the panel at its remembered width rather than the
                    // previous layout's — a SplitPane only settles a position on a layout pass.
                    if (firstOpen) {
                        Platform.runLater(() -> fitToContent(panel, 0, true));
                    }
                });
            }
            case RIGHT -> {
                hSplit.getItems().add(panel);
                int dividerIdx = hSplit.getItems().size() - 2;
                Platform.runLater(() -> {
                    hSplit.setDividerPosition(dividerIdx, pos);
                    if (firstOpen) {
                        Platform.runLater(() -> fitToContent(panel, dividerIdx, false));
                    }
                });
            }
            case BOTTOM -> {
                vSplit.getItems().add(panel);
                Platform.runLater(() -> vSplit.setDividerPosition(0, pos));
            }
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

    /**
     * Captures the live divider positions of the currently-open tool windows into the session. Called on
     * quit/window-close so a divider the user dragged but left open is remembered — {@link #close} only
     * captures it when a tool window is actually hidden, so a quit-while-open would otherwise lose it.
     */
    public void persistDividers() {
        ToolWindow left = openBySide.get(ToolWindow.Side.LEFT);
        if (left != null) {
            int idx = hSplit.getItems().indexOf(panels.get(left));
            if (idx >= 0 && idx < hSplit.getDividers().size()) {
                rememberDivider(
                        left,
                        ToolWindow.Side.LEFT,
                        hSplit.getDividers().get(idx).getPosition());
            }
        }
        ToolWindow right = openBySide.get(ToolWindow.Side.RIGHT);
        if (right != null) {
            int idx = hSplit.getItems().indexOf(panels.get(right));
            if (idx > 0 && idx - 1 < hSplit.getDividers().size()) {
                rememberDivider(
                        right,
                        ToolWindow.Side.RIGHT,
                        hSplit.getDividers().get(idx - 1).getPosition());
            }
        }
        ToolWindow bottom = openBySide.get(ToolWindow.Side.BOTTOM);
        if (bottom != null && !vSplit.getDividers().isEmpty()) {
            rememberDivider(
                    bottom, ToolWindow.Side.BOTTOM, vSplit.getDividers().get(0).getPosition());
        }
    }

    public void close(ToolWindow tw) {
        Region panel = panels.remove(tw);
        if (panel == null) {
            return;
        }
        ToolWindow.Side side = currentSide(tw);
        switch (side) {
            case LEFT -> {
                int idx = hSplit.getItems().indexOf(panel);
                if (idx >= 0 && idx < hSplit.getDividers().size()) {
                    rememberDivider(tw, side, hSplit.getDividers().get(idx).getPosition());
                }
                hSplit.getItems().remove(panel);
            }
            case RIGHT -> {
                int idx = hSplit.getItems().indexOf(panel);
                if (idx > 0 && idx - 1 < hSplit.getDividers().size()) {
                    rememberDivider(tw, side, hSplit.getDividers().get(idx - 1).getPosition());
                }
                hSplit.getItems().remove(panel);
            }
            case BOTTOM -> {
                if (!vSplit.getDividers().isEmpty()) {
                    rememberDivider(tw, side, vSplit.getDividers().get(0).getPosition());
                }
                vSplit.getItems().remove(panel);
            }
        }
        openBySide.remove(side, tw);
        stripeButtons.get(tw).pseudoClassStateChanged(OPEN, false);
        persist();
        if (stateListener != null) {
            stateListener.accept(tw, false);
        }
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
        s.setOpenLeftToolWindow(idOf(openBySide.get(ToolWindow.Side.LEFT)));
        s.setOpenRightToolWindow(idOf(openBySide.get(ToolWindow.Side.RIGHT)));
        s.setOpenBottomToolWindow(idOf(openBySide.get(ToolWindow.Side.BOTTOM)));
        config.save();
    }

    private static String idOf(ToolWindow tw) {
        return tw == null ? "" : tw.getId();
    }
}

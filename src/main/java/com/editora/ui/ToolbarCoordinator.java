package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.paint.Color;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.toolbar.ToolbarCatalog;
import com.editora.toolbar.ToolbarLayout;

import static com.editora.i18n.Messages.tr;
import static com.editora.toolbar.ToolbarCatalog.SEPARATOR;

/**
 * Owns the customizable main toolbar (the {@code toolbar} package's data-driven model): rebuilds the left
 * icon cluster from the persisted {@link com.editora.config.Settings#getToolbarLayout() layout}, hosts the
 * right-click "Customize Toolbar…" menu, and provides the on-bar customize mode (drag to reorder, drag off
 * to remove). The fixed tail (project-combo group, About, Quit) is re-appended by the host after each
 * rebuild via {@link Ops#appendFixedTail()} and stays pinned.
 *
 * <p>Follows the {@link CoordinatorHost} feature-coordinator pattern: shared window services come from the
 * host, the toolbar-specific hooks from {@link Ops}. Persisting a layout change broadcasts a settings-apply
 * so every window's toolbar rebuilds from the shared setting.
 */
final class ToolbarCoordinator {

    /** Toolbar-specific hooks the host ({@code MainController}) supplies. */
    interface Ops {
        ToolBar toolBar();

        /**
         * Id → the existing {@code @FXML} widget for default/special items (e.g. {@code "file.save"} →
         * saveButton, {@code "toolbar.recent"} → the Recent MenuButton). Extras not in this map are built as
         * fresh command buttons.
         */
        Map<String, Node> baseWidgets();

        /** Append the fixed, non-customizable tail to the (already-populated) toolbar. */
        void appendFixedTail();

        /** Re-run the chrome passes after a rebuild (Simple-mode hides + separator collapse). */
        void afterRebuild();

        /** Open the Settings window on the Toolbar page. */
        void openSettingsToolbarPage();

        /** Broadcast the layout change to every window so all toolbars rebuild from the shared setting. */
        void broadcastToolbarChanged();
    }

    private final CoordinatorHost host;
    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private final Ops ops;

    private boolean customizing;
    private boolean containerWired;
    private List<String> currentLayout = new ArrayList<>();
    private final List<Node> customNodes = new ArrayList<>();

    private Node dragSource;
    private int dragIndex = -1;

    private Scene escScene;
    private javafx.event.EventHandler<KeyEvent> escFilter;

    ToolbarCoordinator(CoordinatorHost host, CommandRegistry registry, KeymapManager keymap, Ops ops) {
        this.host = host;
        this.registry = registry;
        this.keymap = keymap;
        this.ops = ops;
    }

    // ---- Rebuild -------------------------------------------------------------------------------------

    /** Rebuilds the customizable icon cluster from the effective layout, then re-appends the fixed tail. */
    void rebuild() {
        ToolBar bar = ops.toolBar();
        if (bar == null) {
            return;
        }
        wireContainerOnce();
        currentLayout = effectiveLayout();
        customNodes.clear();
        bar.getItems().clear();
        for (String token : currentLayout) {
            Node n = buildNode(token);
            customNodes.add(n);
            bar.getItems().add(n);
        }
        ops.appendFixedTail();
        ops.afterRebuild();
        if (customizing) {
            installDragHandlers();
        }
    }

    /** The sanitized layout actually shown: the saved one, or the shipped default when unset. */
    List<String> effectiveLayout() {
        List<String> saved = host.settings().getToolbarLayout();
        List<String> base = (saved == null || saved.isEmpty()) ? ToolbarCatalog.defaultLayout() : saved;
        return ToolbarLayout.sanitize(base);
    }

    private Node buildNode(String token) {
        if (SEPARATOR.equals(token)) {
            return new Separator(Orientation.VERTICAL);
        }
        Node base = ops.baseWidgets().get(token);
        if (base != null) {
            return base;
        }
        return buildExtraButton(ToolbarCatalog.item(token));
    }

    /** A fresh command button for a catalog extra (not one of the default {@code @FXML} widgets). */
    private Button buildExtraButton(ToolbarCatalog.Item item) {
        Button b = new Button();
        b.setGraphic(ToolbarIcons.node(item.iconKey()));
        b.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        String base = tr("command." + item.commandId());
        String chord = keymap.bindings().entrySet().stream()
                .filter(e -> item.commandId().equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        b.setTooltip(new Tooltip(chord == null || chord.isEmpty() ? base : base + " (" + chord + ")"));
        b.setOnAction(e -> registry.run(item.commandId()));
        return b;
    }

    // ---- Layout mutation (persist + broadcast) -------------------------------------------------------

    /** Replaces the layout (from the Settings page or the palette), persisting + rebuilding every window. */
    void setLayout(List<String> tokens) {
        applyLayout(tokens);
    }

    /** Restores the shipped default arrangement. */
    void restoreDefault() {
        host.settings().setToolbarLayout(new ArrayList<>()); // empty ⇒ default
        host.requestSave();
        ops.broadcastToolbarChanged();
        host.syncSettingsWindow();
        host.setStatus(tr("status.toolbar.restored"));
    }

    private void applyLayout(List<String> tokens) {
        List<String> clean = ToolbarLayout.sanitize(tokens);
        host.settings().setToolbarLayout(clean);
        host.requestSave();
        // Defer the rebuild so we never mutate the scene graph mid drag-and-drop dispatch.
        Platform.runLater(ops::broadcastToolbarChanged);
    }

    // ---- Customize mode ------------------------------------------------------------------------------

    /** Palette command: toggle the on-bar customize mode. */
    void toggleCustomizeMode() {
        if (customizing) {
            exitCustomizeMode();
        } else {
            enterCustomizeMode();
        }
    }

    private void enterCustomizeMode() {
        customizing = true;
        ops.toolBar().getStyleClass().add("toolbar-customizing");
        installDragHandlers();
        installEscFilter();
        host.setStatus(tr("status.toolbar.customizeOn"));
    }

    private void exitCustomizeMode() {
        customizing = false;
        ops.toolBar().getStyleClass().remove("toolbar-customizing");
        clearDragHandlers();
        removeEscFilter();
        host.setStatus(tr("status.toolbar.customizeOff"));
    }

    private void wireContainerOnce() {
        if (containerWired) {
            return;
        }
        ToolBar bar = ops.toolBar();
        bar.setOnContextMenuRequested(e -> {
            showContextMenu(e.getScreenX(), e.getScreenY());
            e.consume();
        });
        // A drop anywhere inside the bar (a gap, or the fixed tail) counts as "keep, move to end" so it
        // never triggers the drag-off-to-remove path.
        bar.addEventHandler(DragEvent.DRAG_OVER, e -> {
            if (dragSource != null) {
                e.acceptTransferModes(TransferMode.MOVE);
            }
        });
        bar.addEventHandler(DragEvent.DRAG_DROPPED, e -> {
            if (dragSource != null) {
                reorderToEnd(customNodes.indexOf(dragSource));
                e.setDropCompleted(true);
            }
        });
        containerWired = true;
    }

    private void showContextMenu(double screenX, double screenY) {
        ContextMenu m = new ContextMenu();
        MenuItem toggle = new MenuItem(customizing ? tr("toolbar.menu.done") : tr("toolbar.menu.customize"));
        toggle.setGraphic(Icons.tools());
        toggle.setOnAction(e -> toggleCustomizeMode());
        MenuItem configure = new MenuItem(tr("toolbar.menu.configure"));
        configure.setGraphic(Icons.settings());
        configure.setOnAction(e -> ops.openSettingsToolbarPage());
        MenuItem restore = new MenuItem(tr("toolbar.menu.restoreDefault"));
        restore.setGraphic(Icons.refresh());
        restore.setOnAction(e -> restoreDefault());
        m.getItems().setAll(toggle, new SeparatorMenuItem(), configure, restore);
        m.show(ops.toolBar(), screenX, screenY);
    }

    // ---- Drag-and-drop (mirrors MainController.enableTabDrag) -----------------------------------------

    private void installDragHandlers() {
        for (Node n : customNodes) {
            n.setOnDragDetected(e -> onDragDetected(n, e));
            n.setOnDragOver(e -> onDragOver(n, e));
            n.setOnDragExited(e -> clearDrop(n));
            n.setOnDragDropped(e -> onDragDropped(n, e));
            n.setOnDragDone(this::onDragDone);
        }
    }

    private void clearDragHandlers() {
        for (Node n : customNodes) {
            n.setOnDragDetected(null);
            n.setOnDragOver(null);
            n.setOnDragExited(null);
            n.setOnDragDropped(null);
            n.setOnDragDone(null);
            clearDrop(n);
            n.getStyleClass().remove("toolbar-dragging");
        }
    }

    private void onDragDetected(Node n, MouseEvent e) {
        if (!customizing) {
            return;
        }
        Dragboard db = n.startDragAndDrop(TransferMode.MOVE);
        ClipboardContent cc = new ClipboardContent();
        cc.putString("editora-toolbar-item"); // real payload is dragSource; the string just satisfies the API
        db.setContent(cc);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        db.setDragView(n.snapshot(params, null), e.getX(), e.getY());
        dragSource = n;
        dragIndex = customNodes.indexOf(n);
        n.getStyleClass().add("toolbar-dragging");
        e.consume();
    }

    private void onDragOver(Node target, DragEvent e) {
        if (dragSource == null || target == dragSource) {
            return;
        }
        e.acceptTransferModes(TransferMode.MOVE);
        boolean after = e.getX() > target.getBoundsInLocal().getWidth() / 2;
        toggleDrop(target, after);
        e.consume();
    }

    private void onDragDropped(Node target, DragEvent e) {
        clearDrop(target);
        if (dragSource != null && target != dragSource) {
            boolean after = e.getX() > target.getBoundsInLocal().getWidth() / 2;
            reorder(customNodes.indexOf(dragSource), customNodes.indexOf(target), after);
            e.setDropCompleted(true);
            e.consume();
        }
    }

    private void onDragDone(DragEvent e) {
        Node src = dragSource;
        int from = dragIndex;
        if (src != null) {
            src.getStyleClass().remove("toolbar-dragging");
        }
        dragSource = null;
        dragIndex = -1;
        // Dropped outside the toolbar's bounds ⇒ remove the item (drag-off-to-remove). A drop on itself or
        // anywhere inside the bar keeps it (handled above), so this only fires for a true off-bar release.
        if (src != null && from >= 0 && isOutsideToolbar(e.getScreenX(), e.getScreenY())) {
            removeAt(from);
        }
        e.consume();
    }

    private boolean isOutsideToolbar(double screenX, double screenY) {
        ToolBar bar = ops.toolBar();
        Bounds b = bar.localToScreen(bar.getBoundsInLocal());
        return b == null || !b.contains(screenX, screenY);
    }

    // ---- Token-list edits (index-based, so duplicate separators stay unambiguous) --------------------

    private void reorder(int from, int to, boolean after) {
        if (from < 0 || from >= currentLayout.size() || to < 0 || to >= currentLayout.size()) {
            return;
        }
        List<String> l = new ArrayList<>(currentLayout);
        String token = l.remove(from);
        int t = to > from ? to - 1 : to; // target's index after the removal
        int insert = Math.max(0, Math.min(after ? t + 1 : t, l.size()));
        l.add(insert, token);
        applyLayout(l);
    }

    private void reorderToEnd(int from) {
        if (from < 0 || from >= currentLayout.size()) {
            return;
        }
        List<String> l = new ArrayList<>(currentLayout);
        l.add(l.remove(from));
        applyLayout(l);
    }

    private void removeAt(int index) {
        applyLayout(ToolbarLayout.remove(currentLayout, index));
    }

    // ---- Drop-marker + Esc helpers -------------------------------------------------------------------

    private void toggleDrop(Node n, boolean after) {
        n.getStyleClass().remove(after ? "toolbar-drop-before" : "toolbar-drop-after");
        String cls = after ? "toolbar-drop-after" : "toolbar-drop-before";
        if (!n.getStyleClass().contains(cls)) {
            n.getStyleClass().add(cls);
        }
    }

    private void clearDrop(Node n) {
        n.getStyleClass().removeAll("toolbar-drop-before", "toolbar-drop-after");
    }

    private void installEscFilter() {
        Scene sc = ops.toolBar().getScene();
        if (sc == null) {
            return;
        }
        escScene = sc;
        escFilter = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE && customizing) {
                exitCustomizeMode();
                ev.consume();
            }
        };
        sc.addEventFilter(KeyEvent.KEY_PRESSED, escFilter);
    }

    private void removeEscFilter() {
        if (escScene != null && escFilter != null) {
            escScene.removeEventFilter(KeyEvent.KEY_PRESSED, escFilter);
        }
        escScene = null;
        escFilter = null;
    }

    // ---- Display helpers shared with the Settings page -----------------------------------------------

    /** A fresh icon node for a layout token (null for a separator, which the page renders as a divider). */
    static Node iconFor(String token) {
        if (SEPARATOR.equals(token)) {
            return null;
        }
        ToolbarCatalog.Item it = ToolbarCatalog.item(token);
        return it == null ? null : ToolbarIcons.node(it.iconKey());
    }

    /** The human label for a layout token (command title, or a special key for the separator/recent). */
    static String labelFor(String token) {
        if (SEPARATOR.equals(token)) {
            return tr("toolbar.item.separator");
        }
        ToolbarCatalog.Item it = ToolbarCatalog.item(token);
        if (it == null) {
            return token;
        }
        if (it.commandId() == null) {
            return tr("toolbar.item.recent");
        }
        return tr("command." + it.commandId());
    }
}

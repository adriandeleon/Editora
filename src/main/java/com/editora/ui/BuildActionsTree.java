package com.editora.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.build.BuildAction;
import com.editora.build.BuildActionsProvider;

import static com.editora.i18n.Messages.tr;

/**
 * A build tool's IntelliJ-style tasks tool window: a mini icon toolbar (Run / Refresh / Stop / Run custom,
 * plus an optional tool-specific "Load all tasks…" for Gradle) over a {@link TreeView} of the sections
 * ({@code Lifecycle}, {@code Scripts}, {@code Targets}, …) produced by a {@link BuildActionsProvider}. A
 * {@link BuildAction.Task} is a runnable leaf (double-click / Enter / the Run button runs {@code executable +
 * task.args + provider.toggleArgs(active)}); a {@link BuildAction.Toggle} is a checkbox leaf (Maven's
 * {@code <profile>}s, Cargo's {@code --release}) that re-queries the provider so a checked toggle can reveal
 * extra rows. Complements the searchable {@link BuildActionsPopup} (kept for the command palette). One
 * instance per tool; the streaming output goes to the separate {@link BuildToolPanel} console window.
 */
public final class BuildActionsTree extends VBox implements ToolWindowContent {

    /** Runs the selected task's args plus the extra argv from the active toggles (mirrors the popup). */
    @FunctionalInterface
    public interface RunHandler {
        void run(List<String> taskArgs, List<String> toggleArgs);
    }

    private sealed interface Item permits SectionItem, TaskItem, ToggleItem {}

    private record SectionItem(String title) implements Item {}

    private record TaskItem(BuildAction.Task task) implements Item {}

    private record ToggleItem(BuildAction.Toggle toggle) implements Item {}

    private final TreeView<Item> tree = new TreeView<>();
    private final Label placeholder = new Label();
    private final StackPane body = new StackPane();
    private final HBox toolbar = new HBox(2);
    private final Button runButton;
    private final Button stopButton;
    private Button secondaryButton;

    private final Set<String> activeToggles = new LinkedHashSet<>();

    private BuildActionsProvider provider;
    private RunHandler onRun = (a, b) -> {};
    private Runnable onRefresh = () -> {};
    private Runnable onRunCustom = () -> {};
    private Runnable onStop = () -> {};

    public BuildActionsTree() {
        getStyleClass().add("build-tasks-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        runButton = iconButton(Icons.run(), tr("buildtree.run"), this::runSelected);
        Button refreshButton = iconButton(Icons.refresh(), tr("buildtree.refresh"), () -> onRefresh.run());
        stopButton = iconButton(Icons.stopSquare(), tr("buildtree.stop"), () -> onStop.run());
        Button customButton = iconButton(Icons.edit(), tr("buildtree.runCustom"), () -> onRunCustom.run());
        toolbar.getChildren().addAll(runButton, refreshButton, stopButton, customButton);
        toolbar.getStyleClass().add("build-tasks-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.getStyleClass().add("build-tasks-tree");
        tree.setCellFactory(t -> new ItemCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                runSelected();
            }
        });
        tree.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                runSelected();
                e.consume();
            }
        });

        placeholder.getStyleClass().add("tool-window-placeholder");
        body.getChildren().addAll(tree, placeholder);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(toolbar, body);

        setProvider(null);
    }

    /** Adds a tool-specific toolbar action (Gradle's "Load all tasks…"), appended to the toolbar. */
    public void setSecondaryAction(String label, Runnable action) {
        if (secondaryButton != null) {
            toolbar.getChildren().remove(secondaryButton);
        }
        secondaryButton = iconButton(Icons.tools(), label, action);
        toolbar.getChildren().add(secondaryButton);
    }

    public void setOnRun(RunHandler onRun) {
        this.onRun = onRun;
    }

    public void setOnRefresh(Runnable onRefresh) {
        this.onRefresh = onRefresh;
    }

    public void setOnRunCustom(Runnable onRunCustom) {
        this.onRunCustom = onRunCustom;
    }

    public void setOnStop(Runnable onStop) {
        this.onStop = onStop;
    }

    /** Enables/disables the Stop button to mirror whether a build is currently running. */
    public void setRunning(boolean running) {
        stopButton.setDisable(!running);
    }

    /**
     * Rebuilds the tree from {@code provider}'s current sections (given the active toggles). {@code null}
     * clears it to a placeholder (marker absent or malformed). Called on every detection change and after a
     * toggle flip / on-demand task load.
     */
    public void setProvider(BuildActionsProvider provider) {
        this.provider = provider;
        if (provider == null) {
            activeToggles.clear();
        }
        rebuild();
    }

    /** Re-renders the current provider in place (after a toggle flip or Gradle's loaded tasks land). */
    public void refreshFromProvider() {
        rebuild();
    }

    private void rebuild() {
        boolean has = provider != null;
        placeholder.setText(tr("buildtree.empty"));
        placeholder.setVisible(!has);
        tree.setVisible(has);
        if (!has) {
            tree.setRoot(null);
            return;
        }
        TreeItem<Item> root = new TreeItem<>(null);
        for (BuildAction.Section section : provider.sections(activeToggles)) {
            TreeItem<Item> sectionItem = new TreeItem<>(new SectionItem(section.title()));
            sectionItem.setExpanded(true);
            for (BuildAction.Row row : section.rows()) {
                Item item = row instanceof BuildAction.Task task
                        ? new TaskItem(task)
                        : new ToggleItem((BuildAction.Toggle) row);
                sectionItem.getChildren().add(new TreeItem<>(item));
            }
            root.getChildren().add(sectionItem);
        }
        tree.setRoot(root);
    }

    private void runSelected() {
        TreeItem<Item> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() instanceof TaskItem taskItem) {
            onRun.run(taskItem.task().args(), provider == null ? List.of() : provider.toggleArgs(activeToggles));
        }
    }

    private void toggle(BuildAction.Toggle toggle, boolean on) {
        if (on) {
            activeToggles.add(toggle.id());
        } else {
            activeToggles.remove(toggle.id());
        }
        rebuild(); // sections() is re-queried so a checked toggle can reveal extra rows
    }

    private Button iconButton(javafx.scene.Node icon, String tooltip, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon);
        b.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> action.run());
        return b;
    }

    @Override
    public void focusFirstItem() {
        tree.requestFocus();
        if (tree.getRoot() != null && !tree.getRoot().getChildren().isEmpty()) {
            tree.getSelectionModel().select(0);
        }
    }

    /** Tree cell: a bold section header, a runnable task (label + tooltip), or a checkbox toggle. */
    private final class ItemCell extends TreeCell<Item> {
        private final CheckBox check = new CheckBox();

        ItemCell() {
            check.setOnAction(e -> {
                if (getItem() instanceof ToggleItem t) {
                    toggle(t.toggle(), check.isSelected());
                }
            });
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("build-tasks-section", "build-tasks-task", "build-tasks-toggle");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            switch (item) {
                case SectionItem s -> {
                    getStyleClass().add("build-tasks-section");
                    setGraphic(null);
                    setText(s.title());
                    setTooltip(null);
                }
                case TaskItem t -> {
                    getStyleClass().add("build-tasks-task");
                    setGraphic(null);
                    setText(t.task().label());
                    String tip = t.task().tooltip();
                    setTooltip(tip == null || tip.isEmpty() ? null : new Tooltip(tip));
                }
                case ToggleItem tg -> {
                    getStyleClass().add("build-tasks-toggle");
                    setText(null);
                    setTooltip(null);
                    String note = tg.toggle().note();
                    check.setText(
                            note == null || note.isEmpty()
                                    ? tg.toggle().label()
                                    : tg.toggle().label() + "  " + note);
                    check.setSelected(activeToggles.contains(tg.toggle().id()));
                    setGraphic(check);
                }
            }
        }
    }
}

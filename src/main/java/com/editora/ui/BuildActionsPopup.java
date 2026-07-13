package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.editora.build.BuildAction;
import com.editora.build.BuildActionsProvider;

/**
 * A build tool's toolbar-button dropdown: a search field over a sectioned {@link ListView} of runnable
 * {@link BuildAction.Task}s and checkable {@link BuildAction.Toggle}s produced by a {@link BuildActionsProvider}
 * (plus a popup-owned "Run custom…" row). Flipping a toggle re-queries the provider (so Maven can reveal a
 * checked profile's nested goals) and doesn't close the popup; a task runs {@code executable + task.args +
 * provider.toggleArgs(active)} and closes. Modeled on {@link BranchPopup} (search + sectioned list, arrow-key
 * nav, Enter/click, Esc/C-g to close), anchored below the toolbar button via {@link OverlayHost#showBelow}.
 */
public final class BuildActionsPopup {

    /** Runs the selected task's args plus the extra argv from the active toggles. */
    @FunctionalInterface
    public interface RunHandler {
        void run(List<String> taskArgs, List<String> toggleArgs);
    }

    /** The tool-specific fixed strings (localized by the coordinator). */
    public record Labels(String title, String searchPrompt, String hint, String runCustom) {}

    private sealed interface Row permits Header, ActionRow, TaskRow, ToggleRow {}

    private record Header(String title) implements Row {}

    private record ActionRow(String label, Runnable run, boolean closeOnRun) implements Row {}

    private record TaskRow(BuildAction.Task task) implements Row {}

    private record ToggleRow(BuildAction.Toggle toggle) implements Row {}

    private final Labels labels;
    private final Label titleLabel;
    private final TextField search = new TextField();
    private final ListView<Row> list = new ListView<>();
    private final ObservableList<Row> items = FXCollections.observableArrayList();
    private List<Row> all = List.of();

    private OverlayHost overlayHost;
    private final VBox content;
    private boolean showing;
    private long lastHiddenAt;

    private BuildActionsProvider provider;
    private final Set<String> activeToggles = new LinkedHashSet<>();

    private Runnable onRunCustom = () -> {};
    private RunHandler onRun = (taskArgs, toggleArgs) -> {};
    private String secondaryLabel;
    private Runnable secondaryAction;

    public BuildActionsPopup(Labels labels) {
        this.labels = labels;
        this.titleLabel = new Label(labels.title());
        search.setPromptText(labels.searchPrompt());
        list.setItems(items);
        list.setPrefHeight(400);
        list.setFocusTraversable(false);
        list.setCellFactory(v -> new RowCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                activate(list.getSelectionModel().getSelectedItem());
            }
        });

        search.textProperty().addListener((o, a, b) -> filter(b));
        search.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        titleLabel.getStyleClass().add("palette-title");
        Label hint = new Label(labels.hint());
        hint.getStyleClass().add("palette-hint");
        content = new VBox(6, titleLabel, search, list, hint);
        content.getStyleClass().add("command-palette");
        content.setPrefWidth(500);
        content.setMaxSize(500, Region.USE_PREF_SIZE);
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE);
    }

    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    public void setOnRunCustom(Runnable onRunCustom) {
        this.onRunCustom = onRunCustom;
    }

    public void setOnRun(RunHandler onRun) {
        this.onRun = onRun;
    }

    /** Adds a second popup-owned action row below "Run custom…" that does <em>not</em> close the popup (e.g.
     *  Gradle's "Load all tasks…", which loads asynchronously and repopulates the list in place). */
    public void setSecondaryAction(String label, Runnable action) {
        this.secondaryLabel = label;
        this.secondaryAction = action;
    }

    /** Re-renders the open popup after the coordinator merged on-demand-loaded tasks into the shared provider
     *  (Gradle's "Load all tasks…"). No-op when the popup was never shown (its provider is still null). */
    public void rerender() {
        if (provider != null) {
            rebuildRows();
        }
    }

    public boolean isShown() {
        return showing;
    }

    /** True if the popup auto-hid within the last 250 ms (the same click that's reopening it). */
    public boolean justHidden() {
        return System.currentTimeMillis() - lastHiddenAt < 250;
    }

    public void hide() {
        if (overlayHost != null) {
            overlayHost.hide();
        }
    }

    /** Populates and shows the popup anchored below {@code anchor}, driven by {@code provider}. */
    public void show(Window owner, Node anchor, BuildActionsProvider provider) {
        prepare(provider);
        if (overlayHost == null) {
            return;
        }
        showing = true;
        overlayHost.showBelow(content, anchor, search::requestFocus, this::onHidden);
    }

    /** Populates and shows the popup centered (the command-palette entry point — no toolbar anchor). */
    public void show(Window owner, BuildActionsProvider provider) {
        prepare(provider);
        if (overlayHost == null) {
            return;
        }
        showing = true;
        overlayHost.show(content, true, search::requestFocus, this::onHidden);
    }

    private void prepare(BuildActionsProvider provider) {
        this.provider = provider;
        activeToggles.clear();
        rebuildRows();
        search.clear();
        filter("");
    }

    private void onHidden() {
        showing = false;
        lastHiddenAt = System.currentTimeMillis();
    }

    private void rebuildRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new ActionRow(labels.runCustom(), onRunCustom, true));
        if (secondaryAction != null && secondaryLabel != null) {
            rows.add(new ActionRow(secondaryLabel, secondaryAction, false));
        }
        for (BuildAction.Section section : provider.sections(activeToggles)) {
            if (section.rows().isEmpty()) {
                continue;
            }
            rows.add(new Header(section.title()));
            for (BuildAction.Row r : section.rows()) {
                if (r instanceof BuildAction.Task t) {
                    rows.add(new TaskRow(t));
                } else if (r instanceof BuildAction.Toggle tg) {
                    rows.add(new ToggleRow(tg));
                }
            }
        }
        all = rows;
        filter(search.getText());
    }

    private void toggle(String id) {
        if (!activeToggles.add(id)) {
            activeToggles.remove(id);
        }
        String selected = selectedLabel();
        rebuildRows();
        reselect(selected);
    }

    private String selectedLabel() {
        Row r = list.getSelectionModel().getSelectedItem();
        return r == null ? null : labelOf(r);
    }

    private void reselect(String label) {
        if (label != null) {
            for (int i = 0; i < items.size(); i++) {
                if (labelOf(items.get(i)).equals(label)) {
                    list.getSelectionModel().select(i);
                    list.scrollTo(i);
                    return;
                }
            }
        }
        selectFirstSelectable();
    }

    private static String labelOf(Row r) {
        if (r instanceof ActionRow a) {
            return a.label();
        }
        if (r instanceof TaskRow t) {
            return t.task().label();
        }
        if (r instanceof ToggleRow tg) {
            return tg.toggle().id();
        }
        return "";
    }

    private void filter(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Row> out = new ArrayList<>();
        Header pending = null;
        for (Row r : all) {
            if (r instanceof Header h) {
                pending = h;
                continue;
            }
            if (q.isEmpty() || CommandPalette.isSubsequence(q, labelOf(r).toLowerCase(Locale.ROOT))) {
                if (pending != null) {
                    out.add(pending);
                    pending = null;
                }
                out.add(r);
            }
        }
        items.setAll(out);
        selectFirstSelectable();
    }

    private void selectFirstSelectable() {
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Header)) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                return;
            }
        }
        list.getSelectionModel().clearSelection();
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                e.consume();
            }
            case ENTER -> {
                activate(list.getSelectionModel().getSelectedItem());
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            case N -> {
                if (e.isControlDown()) {
                    move(1);
                    e.consume();
                }
            }
            case P -> {
                if (e.isControlDown()) {
                    move(-1);
                    e.consume();
                }
            }
            case G -> {
                if (e.isControlDown()) {
                    hide();
                    e.consume();
                }
            }
            default -> {}
        }
    }

    private void move(int dir) {
        int n = items.size();
        if (n == 0) {
            return;
        }
        int idx = list.getSelectionModel().getSelectedIndex();
        for (int step = 0; step < n; step++) {
            idx = Math.floorMod(idx + dir, n);
            if (!(items.get(idx) instanceof Header)) {
                list.getSelectionModel().select(idx);
                list.scrollTo(idx);
                return;
            }
        }
    }

    private void activate(Row row) {
        if (row instanceof ActionRow a) {
            if (a.closeOnRun()) {
                hide();
            }
            a.run().run();
        } else if (row instanceof TaskRow t) {
            hide();
            onRun.run(t.task().args(), provider.toggleArgs(activeToggles));
        } else if (row instanceof ToggleRow tg) {
            toggle(tg.toggle().id()); // composes with a run rather than acting standalone — doesn't close
        }
    }

    private final class RowCell extends ListCell<Row> {
        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("branch-popup-header");
            setTooltip(null);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setDisable(false);
                return;
            }
            if (item instanceof Header h) {
                setGraphic(null);
                setText(h.title());
                getStyleClass().add("branch-popup-header");
                setDisable(true);
            } else if (item instanceof ActionRow a) {
                setDisable(false);
                setText(null);
                setGraphic(new Label(a.label()));
            } else if (item instanceof TaskRow t) {
                setDisable(false);
                setText(null);
                if (!t.task().tooltip().isEmpty()) {
                    setTooltip(new Tooltip(t.task().tooltip()));
                }
                setGraphic(taskRow(t.task()));
            } else if (item instanceof ToggleRow tg) {
                setDisable(false);
                setText(null);
                CheckBox box = new CheckBox(tg.toggle().label() + tg.toggle().note());
                box.setSelected(activeToggles.contains(tg.toggle().id()));
                box.setMouseTransparent(true); // the row (not the box) handles the click
                setGraphic(box);
            }
        }

        private HBox taskRow(BuildAction.Task t) {
            Label label = new Label(t.label());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox box = new HBox(10, label, spacer);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        }
    }
}

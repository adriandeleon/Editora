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

import com.editora.maven.MavenLifecycle;
import com.editora.maven.MavenPluginPrefix;
import com.editora.maven.PomModel;

import static com.editora.i18n.Messages.tr;

/**
 * The Maven toolbar button's dropdown: a search field over Lifecycle phases, declared Profiles (checkable
 * — composed with a phase/goal rather than acting standalone, so checking one does not close the popup),
 * top-level Plugin goals, and — for each checked profile that declares its own {@code <build>/<plugins>} —
 * a nested section of that profile's goals. Modeled on {@link BranchPopup} (search + sectioned
 * {@link ListView}, arrow-key nav, Enter/click to act, Esc to close), anchored <em>below</em> the toolbar
 * button via {@link OverlayHost#showBelow}.
 *
 * <p>Only a plugin execution's explicitly declared goals are shown — a plugin with no {@code <executions>}
 * (config-only, or relying on Maven's own default-lifecycle binding) contributes no row here; the
 * "Run custom goal(s)…" action covers those.
 */
public final class MavenActionsPopup {

    /** Runs the given goal(s)/phase(s) with the currently-checked profiles. */
    @FunctionalInterface
    public interface RunHandler {
        void run(List<String> goalsOrPhases, List<String> profiles);
    }

    private sealed interface Row permits Header, ActionRow, PhaseRow, ProfileRow, GoalRow {}

    private record Header(String title) implements Row {}

    private record ActionRow(String label, Runnable run) implements Row {}

    private record PhaseRow(String phase) implements Row {}

    private record ProfileRow(String id, boolean activeByDefault) implements Row {}

    private record GoalRow(String label, String tooltip) implements Row {}

    private final Label titleLabel = new Label(tr("mavenpopup.title"));
    private final TextField search = new TextField();
    private final ListView<Row> list = new ListView<>();
    private final ObservableList<Row> items = FXCollections.observableArrayList();
    private List<Row> all = List.of();

    private OverlayHost overlayHost;
    private final VBox content;
    private boolean showing;
    private long lastHiddenAt;

    private PomModel model;
    private final Set<String> selectedProfiles = new LinkedHashSet<>();

    private Runnable onRunCustom = () -> {};
    private RunHandler onRun = (goals, profiles) -> {};

    public MavenActionsPopup() {
        search.setPromptText(tr("mavenpopup.searchPrompt"));
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
        Label hint = new Label(tr("mavenpopup.hint"));
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

    /** Populates and shows the popup anchored below {@code anchor} (a top-of-window toolbar button). */
    public void show(Window owner, Node anchor, PomModel model) {
        this.model = model;
        selectedProfiles.clear();
        rebuildRows();
        if (overlayHost == null) {
            return;
        }
        search.clear();
        filter("");
        showing = true;
        overlayHost.showBelow(content, anchor, search::requestFocus, () -> {
            showing = false;
            lastHiddenAt = System.currentTimeMillis();
        });
    }

    private void rebuildRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new ActionRow(tr("mavenpopup.runCustom"), onRunCustom));
        rows.add(new Header(tr("mavenpopup.lifecycle")));
        for (String phase : MavenLifecycle.PHASES) {
            rows.add(new PhaseRow(phase));
        }
        if (!model.profiles().isEmpty()) {
            rows.add(new Header(tr("mavenpopup.profiles")));
            for (PomModel.Profile p : model.profiles()) {
                rows.add(new ProfileRow(p.id(), p.activeByDefault()));
            }
        }
        List<GoalRow> topGoals = goalRowsFor(model.plugins());
        if (!topGoals.isEmpty()) {
            rows.add(new Header(tr("mavenpopup.plugins")));
            rows.addAll(topGoals);
        }
        for (PomModel.Profile p : model.profiles()) {
            if (!selectedProfiles.contains(p.id())) {
                continue;
            }
            List<GoalRow> profileGoals = goalRowsFor(p.plugins());
            if (profileGoals.isEmpty()) {
                continue;
            }
            rows.add(new Header(tr("mavenpopup.profilePlugins", p.id())));
            rows.addAll(profileGoals);
        }
        all = rows;
        filter(search.getText());
    }

    private List<GoalRow> goalRowsFor(List<PomModel.Plugin> plugins) {
        List<GoalRow> out = new ArrayList<>();
        for (PomModel.Plugin plugin : plugins) {
            String prefix = MavenPluginPrefix.derive(plugin.groupId(), plugin.artifactId());
            for (PomModel.Execution exec : plugin.executions()) {
                for (String goal : exec.goals()) {
                    String label = prefix + ":" + goal;
                    String tooltip =
                            tr("mavenpopup.goalTooltip", exec.phase().isEmpty() ? "-" : exec.phase(), exec.id());
                    out.add(new GoalRow(label, tooltip));
                }
            }
        }
        return out;
    }

    private void toggleProfile(String id) {
        if (!selectedProfiles.add(id)) {
            selectedProfiles.remove(id);
        }
        String selectedLabel = selectedLabel();
        rebuildRows();
        reselect(selectedLabel);
    }

    private String selectedLabel() {
        Row r = list.getSelectionModel().getSelectedItem();
        return r == null ? null : labelOf(r);
    }

    private void reselect(String label) {
        if (label == null) {
            selectFirstSelectable();
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            if (labelOf(items.get(i)).equals(label)) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                return;
            }
        }
        selectFirstSelectable();
    }

    // --- filtering + navigation ---

    private static String labelOf(Row r) {
        if (r instanceof ActionRow a) {
            return a.label();
        }
        if (r instanceof PhaseRow p) {
            return p.phase();
        }
        if (r instanceof ProfileRow p) {
            return p.id();
        }
        if (r instanceof GoalRow g) {
            return g.label();
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
            hide();
            a.run().run();
        } else if (row instanceof PhaseRow p) {
            hide();
            onRun.run(List.of(p.phase()), List.copyOf(selectedProfiles));
        } else if (row instanceof GoalRow g) {
            hide();
            onRun.run(List.of(g.label()), List.copyOf(selectedProfiles));
        } else if (row instanceof ProfileRow p) {
            toggleProfile(p.id()); // composes with a run rather than acting standalone — doesn't close
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
            } else if (item instanceof PhaseRow p) {
                setDisable(false);
                setText(null);
                setGraphic(new Label(p.phase()));
            } else if (item instanceof ProfileRow p) {
                setDisable(false);
                setText(null);
                CheckBox box = new CheckBox(p.id() + (p.activeByDefault() ? tr("mavenpopup.activeByDefault") : ""));
                box.setSelected(selectedProfiles.contains(p.id()));
                box.setMouseTransparent(true); // the row (not the box) handles the click
                setGraphic(box);
            } else if (item instanceof GoalRow g) {
                setDisable(false);
                setText(null);
                setTooltip(new Tooltip(g.tooltip()));
                setGraphic(goalRow(g));
            }
        }

        private HBox goalRow(GoalRow g) {
            Label label = new Label(g.label());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox box = new HBox(10, label, spacer);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        }
    }
}

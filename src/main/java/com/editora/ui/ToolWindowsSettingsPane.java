package com.editora.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.config.Settings;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The rows of Settings ▸ Tool Windows: one per stripe window, with show / side / move earlier / move later.
 *
 * <p>The rows are shown under their side (Left, Right, Bottom) in the stripe's own order, and laid out again
 * after every move, side change, show/hide and load — the page used to keep the order it was built in, so ▲ ▼
 * changed the stripe but nothing on the page. A window whose feature is off (Commit without Git, Problems
 * without LSP…) has its row disabled with the reason. The side of a hidden window can still be chosen.
 */
final class ToolWindowsSettingsPane {

    /** One window's row and its controls. */
    record Row(
            ToolWindow window,
            HBox node,
            CheckBox show,
            ComboBox<ToolWindow.Side> side,
            Button up,
            Button down,
            @Nullable Label disabledNote) {}

    private final ToolWindowManager tools;
    private final Supplier<Settings> settings;
    /** Receives each side's row nodes in stripe order, to put under that side's heading. */
    private final Consumer<Map<ToolWindow.Side, List<Node>>> place;

    private final Map<String, Row> rows = new LinkedHashMap<>();
    /** True while {@link #load} copies the manager's state into the controls, so that is not an edit. */
    private boolean syncing;

    ToolWindowsSettingsPane(
            ToolWindowManager tools, Supplier<Settings> settings, Consumer<Map<ToolWindow.Side, List<Node>>> place) {
        this.tools = tools;
        this.settings = settings;
        this.place = place;
        for (ToolWindow tw : tools.getStripeToolWindows()) {
            rows.put(tw.getId(), build(tw));
        }
    }

    /** Every row, in the order the windows were registered. */
    List<Row> rows() {
        return List.copyOf(rows.values());
    }

    /** The row of the window with this id, or null. */
    @Nullable
    Row row(String id) {
        return rows.get(id);
    }

    /** The feature a window needs, or null for one that is always available. */
    private @Nullable Predicate<Settings> gate(String id) {
        return switch (id) {
            case "project" -> Settings::isProjectSupport;
            case "commit" -> Settings::isGitSupport;
            case "notes" -> Settings::isNotesSupport;
            case "problems" -> Settings::isLspSupport;
            case "debug" -> Settings::isDebugSupport;
            default -> null;
        };
    }

    /** The "Disabled — turn on …" key for a gated window. */
    private static @Nullable String disabledKey(String id) {
        return switch (id) {
            case "project" -> "settings.toolWindows.projectDisabled";
            case "commit" -> "settings.toolWindows.commitDisabled";
            case "notes" -> "settings.toolWindows.notesDisabled";
            case "problems" -> "settings.toolWindows.problemsDisabled";
            case "debug" -> "settings.toolWindows.debugDisabled";
            default -> null;
        };
    }

    private Row build(ToolWindow tw) {
        CheckBox showCheck = new CheckBox(tr("settings.show"));
        ComboBox<ToolWindow.Side> sideCombo = new ComboBox<>();
        sideCombo.getItems().setAll(ToolWindow.Side.values());
        sideCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ToolWindow.Side side) {
                return SettingsWindow.sideName(side);
            }

            @Override
            public ToolWindow.@Nullable Side fromString(String s) {
                for (ToolWindow.Side side : ToolWindow.Side.values()) {
                    if (SettingsWindow.sideName(side).equals(s)) {
                        return side;
                    }
                }
                return null; // the combo is not editable: nothing types a name into it
            }
        });
        Button moveUp = new Button("▲");
        Button moveDown = new Button("▼");
        moveUp.getStyleClass().addAll("flat", "reorder-button");
        moveDown.getStyleClass().addAll("flat", "reorder-button");
        moveUp.setTooltip(new Tooltip(tr("settings.moveEarlier")));
        moveDown.setTooltip(new Tooltip(tr("settings.moveLater")));
        moveUp.setAccessibleText(tr("settings.moveEarlier") + " — " + tw.getTitle());
        moveDown.setAccessibleText(tr("settings.moveLater") + " — " + tw.getTitle());

        // A card row like every other page: the window's name on the left (with the gated ones'
        // explanation as its description), and show / side / reorder pinned right.
        Label title = new Label(tw.getTitle());
        title.getStyleClass().add("settings-row-title");
        VBox main = new VBox(2, title);
        HBox.setHgrow(main, Priority.ALWAYS);
        Label disabledNote = null;
        String key = disabledKey(tw.getId());
        if (key != null) {
            disabledNote = new Label(tr(key));
            disabledNote.getStyleClass().addAll("settings-hint", "settings-row-desc");
            disabledNote.setWrapText(true);
            disabledNote.setVisible(false);
            disabledNote.setManaged(false);
            main.getChildren().add(disabledNote);
        }
        SettingSwitch show = SettingSwitch.boundTo(showCheck);
        show.setAccessibleText(tw.getTitle()); // this row is hand-built, so name its controls here
        sideCombo.setAccessibleText(tw.getTitle());
        title.setLabelFor(show);
        HBox controls = new HBox(8, show, sideCombo, new HBox(2, moveUp, moveDown));
        controls.setAlignment(Pos.CENTER_RIGHT);
        controls.setMinWidth(Region.USE_PREF_SIZE);
        HBox node = new HBox(16, main, controls);
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("settings-row");
        Row row = new Row(tw, node, showCheck, sideCombo, moveUp, moveDown, disabledNote);

        moveUp.setOnAction(e -> {
            tools.move(tw, -1);
            refresh();
        });
        moveDown.setOnAction(e -> {
            tools.move(tw, 1);
            refresh();
        });
        showCheck.selectedProperty().addListener((obs, was, visible) -> {
            if (!syncing) {
                tools.setVisible(tw, visible);
                refresh();
            }
        });
        sideCombo.valueProperty().addListener((obs, old, now) -> {
            if (now != null && !syncing) {
                tools.setSide(tw, now);
                refresh();
            }
        });
        return row;
    }

    /** Re-reads every row from the tool-window manager (a window may have been moved or hidden meanwhile). */
    void load() {
        syncing = true;
        try {
            for (Row row : rows.values()) {
                row.show().setSelected(tools.isVisible(row.window()));
                row.side().setValue(tools.currentSide(row.window()));
            }
        } finally {
            syncing = false;
        }
        refresh();
    }

    /** Applies the feature gates and the arrows' limits, and lays the rows out by side in stripe order. */
    void refresh() {
        Settings s = settings.get();
        syncing = true;
        try {
            for (Row row : rows.values()) {
                Predicate<Settings> gate = gate(row.window().getId());
                boolean on = gate == null || gate.test(s);
                if ("project".equals(row.window().getId())) {
                    // With projects off the Project window is not shown at all, so its switch says so.
                    row.show().setSelected(on && tools.isVisible(row.window()));
                }
                row.show().setDisable(!on);
                if (row.disabledNote() != null) {
                    row.disabledNote().setVisible(!on);
                    row.disabledNote().setManaged(!on);
                }
                boolean shown = row.show().isSelected();
                row.side().setDisable(!on);
                row.up().setDisable(!on || !shown || !tools.canMove(row.window(), -1));
                row.down().setDisable(!on || !shown || !tools.canMove(row.window(), 1));
            }
        } finally {
            syncing = false;
        }
        Map<ToolWindow.Side, List<Node>> bySide = new EnumMap<>(ToolWindow.Side.class);
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            List<Node> nodes = new ArrayList<>();
            for (ToolWindow tw : tools.orderedOnSide(side)) {
                Row row = rows.get(tw.getId());
                if (row != null) {
                    nodes.add(row.node());
                }
            }
            bySide.put(side, nodes);
        }
        place.accept(bySide);
    }

    /** Puts the layout back to that of a fresh window, then shows it. */
    void restoreDefaultLayout() {
        tools.resetLayout();
        load();
    }
}

package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;

import com.editora.editor.UiZoom;
import com.editora.maven.PomUpgrades;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The rows of Check for Newer Versions: a heading per kind of artifact, then a check box per newer version found,
 * each checked to start with, a <b>major</b> tag beside one whose leading number changes. A virtualized list (PERF-08,
 * Pauta 6.0.1): a pom far behind its dependencies builds the rows on screen. The list is one Tab stop; the arrow keys
 * move along it and Space ticks or clears the row the keyboard is on, as a click on its box does.
 */
final class MavenUpgradeList {

    /** A row of the list: a kind's heading, or a newer version with whether it is ticked. */
    sealed interface Item permits Heading, Upgrade {}

    /** A kind's heading. */
    record Heading(String text) implements Item {}

    /** A newer version, and whether it is ticked (it is to start with). */
    static final class Upgrade implements Item {
        final PomUpgrades.Row row;
        final BooleanProperty selected = new SimpleBooleanProperty(true);

        Upgrade(PomUpgrades.Row row) {
            this.row = row;
        }
    }

    /** A row's height at 100 % zoom: a check box, or a heading, on one line. */
    static final double ROW_HEIGHT = 26;

    private final ListView<Item> list = new ListView<>();
    private final List<Upgrade> upgrades = new ArrayList<>();
    private final BooleanBinding anySelected;

    /** The list of {@code rows}, grouped by kind in {@link PomUpgrades.Kind} order, each row said by {@code text}. */
    MavenUpgradeList(List<PomUpgrades.Row> rows, Function<PomUpgrades.Row, String> text) {
        for (PomUpgrades.Kind kind : PomUpgrades.Kind.values()) {
            List<PomUpgrades.Row> group =
                    rows.stream().filter(r -> r.kind() == kind).toList();
            if (group.isEmpty()) {
                continue;
            }
            list.getItems()
                    .add(new Heading(
                            tr("dialog.mavenVersions.group." + kind.name().toLowerCase(java.util.Locale.ROOT))));
            for (PomUpgrades.Row row : group) {
                Upgrade u = new Upgrade(row);
                upgrades.add(u);
                list.getItems().add(u);
            }
        }
        anySelected = Bindings.createBooleanBinding(
                () -> upgrades.stream().anyMatch(u -> u.selected.get()),
                upgrades.stream().map(u -> u.selected).toArray(BooleanProperty[]::new));
        list.getStyleClass().add("maven-version-list");
        list.setAccessibleText(tr("dialog.mavenVersions.title"));
        list.setCellFactory(view -> new UpgradeCell(text));
        UiZoom.follow(list, () -> {
            list.setFixedCellSize(UiZoom.px(ROW_HEIGHT));
            list.setPrefHeight(UiZoom.px(260));
        });
        list.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE && list.getSelectionModel().getSelectedItem() instanceof Upgrade u) {
                u.selected.set(!u.selected.get());
                e.consume();
            }
        });
    }

    /** The list, for the dialog's body. */
    Node node() {
        return list;
    }

    /** Whether any row is ticked: Apply needs one. */
    BooleanBinding anySelectedProperty() {
        return anySelected;
    }

    /** The rows left ticked, in the list's order. */
    List<PomUpgrades.Row> chosen() {
        return upgrades.stream().filter(u -> u.selected.get()).map(u -> u.row).toList();
    }

    /** The list. Test accessor. */
    ListView<Item> listForTest() {
        return list;
    }

    /** A heading, or a check box bound to its upgrade's tick, with the major tag beside it when it is one. */
    private static final class UpgradeCell extends ListCell<Item> {
        private final Function<PomUpgrades.Row, String> text;
        private final CheckBox box = new CheckBox();
        private final Label major = new Label(tr("dialog.mavenVersions.major"));
        private final HBox majorRow = UiZoom.spacing(new HBox(), 8);
        private final Label heading = new Label();
        private @Nullable BooleanProperty bound;

        UpgradeCell(Function<PomUpgrades.Row, String> text) {
            this.text = text;
            box.getStyleClass().add("maven-version-row");
            box.setFocusTraversable(false); // the list is the Tab stop; Space ticks the row the keyboard is on
            major.getStyleClass().addAll("settings-hint", "maven-version-major");
            majorRow.setAlignment(Pos.CENTER_LEFT);
            heading.getStyleClass().add("maven-version-group");
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            BooleanProperty was = bound;
            if (was != null) {
                box.selectedProperty().unbindBidirectional(was);
                bound = null;
            }
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            switch (item) {
                case Heading h -> {
                    heading.setText(h.text());
                    setGraphic(heading);
                }
                case Upgrade u -> {
                    box.setText(text.apply(u.row));
                    box.setSelected(u.selected.get());
                    box.selectedProperty().bindBidirectional(u.selected);
                    bound = u.selected;
                    if (u.row.major()) {
                        majorRow.getChildren().setAll(box, major);
                        setGraphic(majorRow);
                    } else {
                        majorRow.getChildren().clear();
                        setGraphic(box);
                    }
                }
            }
        }
    }
}

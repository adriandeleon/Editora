package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.config.Abbreviation;
import com.editora.config.ConfigManager;

import static com.editora.i18n.Messages.tr;

/**
 * The master-detail editor of Settings → Abbreviations: the user's abbreviations on the left, the selected
 * one's key and expansion on the right.
 *
 * <p>The list is a working copy of the shared store, written back whole, so it is re-read ({@link #reload})
 * whenever the store may have changed elsewhere. A field commits on Enter (the key) or on losing focus, and
 * only when its text differs from what the entry holds: a mere focus pass must never rewrite the store.
 * The expansion is multi-line — an expansion may hold line breaks (a signature, a block), and a single-line
 * field silently dropped them on the next focus loss.
 */
final class AbbreviationsEditor {

    private final ConfigManager config;
    /** Pushes the saved table to every window's open buffers (they hold a copy). */
    private final Runnable applied;

    final ObservableList<Abbreviation> items = FXCollections.observableArrayList();
    final ListView<Abbreviation> list = new ListView<>(items);
    final TextField abbrev = new TextField();
    final TextArea expansion = new TextArea();
    private boolean loading;
    private Supplier<PendingFormEdit> formEdit = PendingFormEdit::none;

    AbbreviationsEditor(ConfigManager config, Runnable applied) {
        this.config = config;
        this.applied = applied;
    }

    /** Whether the form shows something other than {@code a} holds — i.e. whether a commit has anything to write. Pure. */
    static boolean edited(Abbreviation a, String key, String text) {
        return a != null
                && (!Objects.equals(nz(a.getAbbreviation()), nz(key))
                        || !Objects.equals(nz(a.getExpansion()), nz(text)));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    Node node() {
        reload();
        list.setPrefSize(200, 380);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Abbreviation a, boolean empty) {
                super.updateItem(a, empty);
                setText(
                        empty || a == null
                                ? null
                                : a.getAbbreviation().isBlank() ? tr("settings.abbrev.unnamed") : a.getAbbreviation());
            }
        });

        expansion.setPrefRowCount(4);
        expansion.setWrapText(true);
        // Tab moves on, as in the other fields: an expansion rarely needs a tab, and a trap is worse.
        expansion.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                e.consume();
                expansion.requestFocusTraversal(
                        e.isShiftDown()
                                ? javafx.scene.TraversalDirection.PREVIOUS
                                : javafx.scene.TraversalDirection.NEXT);
            }
        });
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.abbrev.abbreviation"), abbrev);
        formRow(form, 1, tr("settings.abbrev.expansion"), expansion);
        form.setDisable(true);
        HBox.setHgrow(form, Priority.ALWAYS);

        Runnable commit = () -> {
            Abbreviation a = list.getSelectionModel().getSelectedItem();
            if (loading || !edited(a, abbrev.getText(), expansion.getText())) {
                return; // nothing selected, or a field merely lost focus: never rewrite the store for that
            }
            a.setAbbreviation(abbrev.getText());
            a.setExpansion(expansion.getText());
            list.refresh();
            persist();
        };
        abbrev.setOnAction(e -> commit.run());
        abbrev.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commit.run();
            }
        });
        expansion.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commit.run();
            }
        });
        formEdit = () -> {
            Abbreviation cur = list.getSelectionModel().getSelectedItem();
            return cur == null
                    ? PendingFormEdit.none()
                    : PendingFormEdit.capture(
                            List.of(abbrev, expansion), List.of(cur.getAbbreviation(), cur.getExpansion()));
        };

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            loading = true;
            try {
                form.setDisable(now == null);
                abbrev.setText(now == null ? "" : now.getAbbreviation());
                expansion.setText(now == null ? "" : now.getExpansion());
            } finally {
                loading = false;
            }
        });

        Button add = new Button(tr("settings.abbrev.add"));
        add.setOnAction(e -> {
            Abbreviation a = new Abbreviation("", "");
            items.add(a);
            persist();
            list.getSelectionModel().select(a);
            abbrev.requestFocus();
        });
        Button remove = new Button(tr("settings.abbrev.remove"));
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                items.remove(i);
                persist();
            }
        });
        Button save = new Button(tr("settings.save"));
        save.getStyleClass().add("success");
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit.run());

        VBox left = new VBox(6, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox right = new VBox(8, form);
        VBox.setVgrow(form, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        if (!items.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        HBox top = new HBox(12, left, right);
        top.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(top, Priority.ALWAYS);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox buttons = new HBox(6, add, remove, gap, save);
        buttons.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, top, buttons);
    }

    private static void formRow(GridPane form, int row, String text, Region field) {
        Label l = new Label(text);
        l.setMinWidth(Region.USE_PREF_SIZE); // a Label's own minimum is an ellipsis
        l.setLabelFor(field);
        form.add(l, 0, row);
        form.add(field, 1, row);
        field.setMinWidth(180);
        if (field instanceof TextArea) {
            GridPane.setValignment(l, javafx.geometry.VPos.TOP);
        }
    }

    private void persist() {
        config.setAbbreviations(new ArrayList<>(items));
        config.saveAbbreviations();
        applied.run();
    }

    /**
     * Re-reads the list from the store if it changed there, keeping the selected entry and whatever was typed
     * into it but not committed yet.
     */
    void reload() {
        if (same(items, config.getAbbreviations())) {
            return;
        }
        Abbreviation selected = list.getSelectionModel().getSelectedItem();
        String selectedKey = selected == null ? null : selected.getAbbreviation();
        PendingFormEdit typing = formEdit.get(); // before the items change: that reloads the form
        items.setAll(copy(config.getAbbreviations()));
        if (selectedKey != null) {
            for (Abbreviation a : items) {
                if (selectedKey.equals(a.getAbbreviation())) {
                    list.getSelectionModel().select(a);
                    typing.restore(); // the entry being edited is still there: what was typed stays typed
                    break;
                }
            }
        }
    }

    static boolean same(List<Abbreviation> a, List<Abbreviation> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Abbreviation x = a.get(i);
            Abbreviation y = b.get(i);
            if (x != y
                    && !(Objects.equals(x.getAbbreviation(), y.getAbbreviation())
                            && Objects.equals(x.getExpansion(), y.getExpansion()))) {
                return false;
            }
        }
        return true;
    }

    private static List<Abbreviation> copy(List<Abbreviation> src) {
        List<Abbreviation> out = new ArrayList<>();
        for (Abbreviation a : src) {
            out.add(new Abbreviation(a.getAbbreviation(), a.getExpansion()));
        }
        return out;
    }
}

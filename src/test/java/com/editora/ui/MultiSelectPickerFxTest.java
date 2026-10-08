package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.ui.MultiSelectPicker.Item;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checkbox picker behind the "which members" step of the source generators: what is ticked to begin
 * with, Space toggling the focused row, Enter handing back the ticked values in listed order, and an
 * accept with nothing ticked counting as a cancel.
 */
@Tag("fx")
class MultiSelectPickerFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A shown picker over {@code items}: its card, its rows, and whatever it handed back. */
    private record Shown(
            Stage stage,
            OverlayHost host,
            Node card,
            ListView<CheckBox> list,
            List<List<String>> accepted,
            boolean[] reached) {

        /** Fires a key press at the card; true when the card's own filter consumed it. */
        boolean press(KeyCode code) {
            reached[0] = false;
            javafx.event.Event.fireEvent(
                    card, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            return !reached[0];
        }

        List<Boolean> ticks() {
            return list.getItems().stream().map(CheckBox::isSelected).toList();
        }

        Hyperlink link(String text) {
            return (Hyperlink) card.lookupAll(".hyperlink").stream()
                    .filter(n -> text.equals(((Hyperlink) n).getText()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static Shown show(List<Item<String>> items) {
        StackPane root = new StackPane();
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 800, 600));
        stage.show();
        OverlayHost host = new OverlayHost();
        host.install(root);
        List<List<String>> accepted = new ArrayList<>();
        MultiSelectPicker.show(host, "Generate toString()", items, accepted::add);
        Node card = root.lookup(".multi-select-picker");
        @SuppressWarnings("unchecked")
        ListView<CheckBox> list = card == null ? null : (ListView<CheckBox>) card.lookup(".list-view");
        boolean[] reached = new boolean[1];
        if (card != null) {
            // A press the card's filter consumes never gets as far as a handler on the card.
            card.addEventHandler(KeyEvent.KEY_PRESSED, e -> reached[0] = true);
        }
        return new Shown(stage, host, card, list, accepted, reached);
    }

    private static List<Item<String>> members() {
        return List.of(new Item<>("id", true, "ID"), new Item<>("name", false, "NAME"), new Item<>("age", true, "AGE"));
    }

    @Test
    void nothingIsShownWithoutAHostOrWithoutItems() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<List<String>> accepted = new ArrayList<>();
            MultiSelectPicker.show(null, "t", members(), accepted::add); // must simply return
            Shown none = show(null);
            assertNull(none.card(), "no card for a null item list");
            assertFalse(none.host().isShowing());
            none.stage().close();
            Shown empty = show(List.of());
            assertNull(empty.card(), "no card for an empty item list");
            assertFalse(empty.host().isShowing());
            empty.stage().close();
            assertTrue(accepted.isEmpty());
        });
    }

    @Test
    void theCardListsTheRowsWithTheServersPreselectionAndTheFirstRowHighlighted() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(members());
            assertNotNull(s.card());
            assertTrue(s.host().isShowing());
            assertEquals(
                    List.of("id", "name", "age"),
                    s.list().getItems().stream().map(CheckBox::getText).toList());
            assertEquals(List.of(true, false, true), s.ticks());
            assertEquals(0, s.list().getSelectionModel().getSelectedIndex());
            assertEquals(Boolean.TRUE, s.card().getProperties().get("editora.ownsKeys"), "C-n/C-p stay with the card");
            assertEquals(28.0 * 3 + 16, s.list().getPrefHeight(), "the list hugs a short member list");
            s.stage().close();
        });
    }

    @Test
    void aLongListIsCappedInHeight() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<Item<String>> many = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                many.add(new Item<>("f" + i, false, "F" + i));
            }
            Shown s = show(many);
            assertEquals(360, s.list().getPrefHeight());
            s.stage().close();
        });
    }

    @Test
    void spaceTogglesTheHighlightedRowAndArrowsMoveTheHighlight() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(members());

            assertTrue(s.press(KeyCode.SPACE));
            assertEquals(List.of(false, false, true), s.ticks(), "Space unticked the first row");

            assertTrue(s.press(KeyCode.DOWN));
            assertEquals(1, s.list().getSelectionModel().getSelectedIndex());
            s.press(KeyCode.SPACE);
            assertEquals(List.of(false, true, true), s.ticks(), "Space ticked the second row");

            s.list().getSelectionModel().clearSelection();
            assertTrue(s.press(KeyCode.SPACE), "Space with no row highlighted is still the card's key");
            assertEquals(List.of(false, true, true), s.ticks());

            assertFalse(s.press(KeyCode.A), "a letter is not the picker's key");
            assertTrue(s.accepted().isEmpty());
            s.stage().close();
        });
    }

    @Test
    void enterHidesTheCardAndHandsBackTheTickedValuesInListedOrder() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(members());

            assertTrue(s.press(KeyCode.ENTER));

            assertEquals(List.of(List.of("ID", "AGE")), s.accepted());
            assertFalse(s.host().isShowing(), "the card is gone before the generator runs");
            s.stage().close();
        });
    }

    @Test
    void enterWithNothingTickedIsACancel() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(members());
            s.link("Select none").fire();
            assertEquals(List.of(false, false, false), s.ticks());

            s.press(KeyCode.ENTER);

            assertTrue(s.accepted().isEmpty(), "an empty member list is never generated");
            assertFalse(s.host().isShowing());
            s.stage().close();
        });
    }

    @Test
    void selectAllTicksEveryRow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show(members());
            s.link("Select all").fire();
            assertEquals(List.of(true, true, true), s.ticks());

            s.press(KeyCode.ENTER);

            assertEquals(List.of(List.of("ID", "NAME", "AGE")), s.accepted());
            s.stage().close();
        });
    }
}

package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-08 (Pauta 6.0.1): the HTTP client's empty state lists the file's requests in a virtualized list. A file of
 * 10,000 requests builds the rows on screen; the arrow keys and Enter run the row the keyboard is on; the caret's row
 * is marked; a blocked file runs nothing.
 */
@Tag("fx")
class HttpEmptyStateListFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void tenThousandRequestsBuildOnlyTheRowsOnScreen() throws Exception {
        List<HttpEmptyState.Row> rows = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            rows.add(new HttpEmptyState.Row(i * 3, "GET", "Request " + i, "https://example.com/r/" + i));
        }
        FxTestSupport.runOnFx(() -> {
            List<Integer> ran = new ArrayList<>();
            HttpEmptyState empty = new HttpEmptyState(ran::add);
            Stage stage = new Stage();
            Scene scene = new Scene(new StackPane(empty), 700, 500);
            scene.getRoot().getStyleClass().add("http-panel");
            stage.setScene(scene);
            stage.show();
            try {
                empty.setRequests(rows, 2);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                ListView<HttpEmptyState.Row> list = empty.rowListForTest();
                assertEquals(10_000, list.getItems().size(), "every request is a row");
                assertTrue(list.getFixedCellSize() > 0, "rows are one line: a fixed cell size");
                List<ListCell<?>> cells = cells(empty);
                assertTrue(cells.size() > 3 && cells.size() < 60, "rows with nodes: " + cells.size());
                ListCell<?> caretCell = cells.stream()
                        .filter(c -> c.getIndex() == 2)
                        .findFirst()
                        .orElseThrow();
                assertTrue(
                        caretCell.getPseudoClassStates().stream()
                                .anyMatch(p -> p.getPseudoClassName().equals("caret")),
                        "the caret's request is marked");

                // The same requests again: nothing is rebuilt, only the mark moves.
                empty.setRequests(new ArrayList<>(rows), 3);
                assertEquals(10_000, list.getItems().size());

                list.requestFocus();
                Event.fireEvent(list, key(KeyCode.END));
                scene.getRoot().layout();
                assertEquals(9_999, list.getSelectionModel().getSelectedIndex());
                assertTrue(cells(empty).size() < 60, "scrolled to the end, still only the rows on screen");
                Event.fireEvent(list, key(KeyCode.ENTER));
                assertEquals(List.of(9_999 * 3), ran, "Enter runs the request the keyboard is on");

                empty.setRunBlocked(true);
                Event.fireEvent(list, key(KeyCode.ENTER));
                assertEquals(1, ran.size(), "a blocked file's requests do not run from here");
            } finally {
                stage.hide();
            }
        });
    }

    private static List<ListCell<?>> cells(HttpEmptyState empty) {
        List<ListCell<?>> out = new ArrayList<>();
        for (javafx.scene.Node n : empty.lookupAll(".http-empty-row")) {
            if (n instanceof ListCell<?> c && !c.isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }
}

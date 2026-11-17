package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.diff.ConflictParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-08 (Pauta 6.0.1): a file of 300 conflicts shows {@link MergeViewerPane#PAGE_SIZE} conflict cards at a time, each
 * with its text areas, and says which; a choice made on one page is kept when the page is left and shown again when
 * it comes back, and the Result and Save cover every conflict.
 */
@Tag("fx")
class MergeViewerPagingFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void threeHundredConflictsArePagedAndChoicesAreKeptAcrossPages() throws Exception {
        int count = 300;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.addAll(List.of("same " + i, "<<<<<<< HEAD", "ours " + i, "=======", "theirs " + i, ">>>>>>> b"));
        }
        lines.add("end");
        ConflictParser.ConflictFile file = ConflictParser.parse(lines);
        assertEquals(count, file.conflictCount());
        AtomicReference<String> applied = new AtomicReference<>();
        MergeViewerPane pane = FxTestSupport.callOnFx(
                () -> new MergeViewerPane("merge", file, "Monospaced", 13, "\n", true, applied::set));
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 650));
            window.show();
            return window;
        });
        try {
            FxTestSupport.runOnFx(() -> {
                assertEquals(MergeViewerPane.PAGE_SIZE, pane.cardsForTest().size(), "a page of cards");
                int areas = ((Parent) pane.node()).lookupAll(".merge-side").size();
                assertEquals(2 * MergeViewerPane.PAGE_SIZE, areas, "two text areas a card, a page of them");
                assertEquals(tr("merge.page", 1, MergeViewerPane.PAGE_SIZE, count), pane.pageTextForTest());
                assertTrue(header(pane, 0).equals(tr("merge.conflictN", 1, count)), header(pane, 0));

                // Keep ours in the first conflict, go to the next page and back: the choice is still said.
                button(pane, tr("merge.acceptOurs")).fire();
                pane.nextPageForTest();
                assertEquals(
                        tr("merge.page", MergeViewerPane.PAGE_SIZE + 1, 2 * MergeViewerPane.PAGE_SIZE, count),
                        pane.pageTextForTest());
                assertEquals(tr("merge.conflictN", MergeViewerPane.PAGE_SIZE + 1, count), header(pane, 0));
                button(pane, tr("merge.acceptTheirs")).fire();
                button(pane, tr("merge.pagePrevious")).fire();
                assertEquals(tr("merge.kept.ours"), chosen(pane, 0), "the first page shows its choice again");

                TextArea result = FxTestSupport.field(pane, "resultArea");
                assertTrue(result.getText().startsWith("same 0\nours 0\nsame 1\n<<<<<<< HEAD"), "the result: ours");
                assertTrue(
                        result.getText()
                                .contains("same " + MergeViewerPane.PAGE_SIZE + "\ntheirs " + MergeViewerPane.PAGE_SIZE
                                        + "\n"),
                        "and theirs for the conflict on the second page");
                Label status = FxTestSupport.field(pane, "status");
                assertEquals(tr("merge.status", 2L, count), status.getText());

                for (int page = 0; page < count / MergeViewerPane.PAGE_SIZE - 1; page++) {
                    pane.nextPageForTest();
                }
                assertEquals(
                        tr("merge.page", count - MergeViewerPane.PAGE_SIZE + 1, count, count), pane.pageTextForTest());
                assertTrue(button(pane, tr("merge.pageNext")).isDisabled(), "the last page has no next");
                assertTrue(
                        ((Parent) pane.node())
                                .lookupAll(".merge-context").stream()
                                        .anyMatch(n -> ((Label) n).getText().equals(tr("merge.unchanged", 1))),
                        "the line after the last conflict is on the last page");
            });
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    @Test
    void aFileThatFitsAPageHasNoPager() throws Exception {
        ConflictParser.ConflictFile file =
                ConflictParser.parse(List.of("a", "<<<<<<< HEAD", "ours", "=======", "theirs", ">>>>>>> b", "z"));
        MergeViewerPane pane =
                FxTestSupport.callOnFx(() -> new MergeViewerPane("merge", file, "Monospaced", 13, "\n", true, t -> {}));
        FxTestSupport.runOnFx(() -> {
            assertNull(pane.pageTextForTest());
            assertEquals(1, pane.cardsForTest().size());
        });
    }

    private static String header(MergeViewerPane pane, int card) {
        return ((Label) pane.cardsForTest().get(card).lookup(".merge-conflict-header")).getText();
    }

    private static String chosen(MergeViewerPane pane, int card) {
        return ((Label) pane.cardsForTest().get(card).lookup(".merge-chosen")).getText();
    }

    /** The first button with {@code text} in the pane, in scene order. */
    private static Button button(MergeViewerPane pane, String text) {
        for (Node n : ((Parent) pane.node()).lookupAll(".button")) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("Missing button: " + text);
    }
}

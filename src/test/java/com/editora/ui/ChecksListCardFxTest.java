package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.github.ChecksParser.CheckRun;
import com.editora.github.GitHubService;
import com.editora.github.PrViewParser.PrDetail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pull-request checks card's rows are a virtualized list (PERF-08, Pauta 6.2.0): a pull request with thousands
 * of check runs builds the rows on screen and no more, in the order and with the content the card always had, and
 * the list is driven from the keyboard as the app's other lists are.
 */
@Tag("fx")
class ChecksListCardFxTest {

    private static final class Recorder implements ChecksListCard.Actions {
        final List<String> log = new ArrayList<>();

        @Override
        public void open(String url) {
            log.add("open " + url);
        }

        @Override
        public void viewFailedLog(long runId, String name) {
            log.add("log " + runId + " " + name);
        }

        @Override
        public void refresh() {
            log.add("refresh");
        }

        @Override
        public void close() {
            log.add("close");
        }
    }

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static GitHubService.BranchChecks checks(int count) {
        List<CheckRun> runs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Every hundredth run failed, with a GitHub Actions link; the rest passed, the odd ones without a link.
            boolean failed = i % 100 == 7;
            String link = failed || i % 2 == 0 ? "https://github.com/o/r/actions/runs/" + (1000 + i) + "/job/1" : "";
            runs.add(new CheckRun("check-" + i, "workflow-" + (i % 5), failed ? "fail" : "pass", link));
        }
        PrDetail pr = new PrDetail(7, "Many checks", "", "me", "master", "topic", "OPEN", "", 0, 0);
        return new GitHubService.BranchChecks(true, pr, runs);
    }

    @Test
    void fiveThousandChecksBuildOnlyTheRowsOnScreen() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Recorder actions = new Recorder();
            ChecksListCard card = new ChecksListCard(actions);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(card), 700, 600));
            stage.show();
            try {
                card.update(checks(5_000));
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                ListView<CheckRun> list = FxTestSupport.field(card, "list");
                assertEquals(5_000, list.getItems().size(), "every check is a row of the list");
                int built = card.rowNodes();
                assertTrue(built >= 12 && built <= 30, "rows with nodes: " + built);
                assertTrue(card.lookupAll(".hyperlink").size() <= 2 * built, "and their links only");

                // Failed first, in gh's order, then the rest: what the card always showed.
                List<String> shown = card.rowTexts();
                assertEquals(List.of("✗ check-7", "✗ check-107", "✗ check-207"), shown.subList(0, 3));
                List<String> firstLinks = card.lookupAll(".hyperlink").stream()
                        .map(n -> ((Hyperlink) n).getText())
                        .limit(2)
                        .toList();
                assertEquals(List.of(tr("github.checks.viewLog"), tr("github.checks.open")), firstLinks);

                // One Tab stop; the arrow keys move along the rows; Enter runs the row's first action.
                card.focusFirst();
                assertSame(list, stage.getScene().getFocusOwner(), "the card opens on the list");
                assertEquals(0, list.getSelectionModel().getSelectedIndex());
                Event.fireEvent(list, key(KeyCode.ENTER));
                assertEquals(List.of("log 1007 check-7"), actions.log, "a failed job's first action is its log");
                Event.fireEvent(list, key(KeyCode.END));
                stage.getScene().getRoot().layout();
                assertEquals(4_999, list.getSelectionModel().getSelectedIndex());
                assertTrue(card.rowNodes() <= 30, "scrolled to the end, still only the rows on screen");
                assertTrue(card.rowTexts().contains("✓ check-4999"), "the last check is there to read");
                actions.log.clear();
                Event.fireEvent(list, key(KeyCode.ENTER));
                assertEquals(List.of(), actions.log, "a check with no link has nothing to run");
                Event.fireEvent(list, key(KeyCode.UP));
                Event.fireEvent(list, key(KeyCode.ENTER));
                assertEquals(
                        List.of("open https://github.com/o/r/actions/runs/5998/job/1"),
                        actions.log,
                        "a passed check's first action is its page");

                // A poll updates the open card in place and keeps the row the keyboard is on.
                card.update(checks(4_000));
                assertEquals(3_999, list.getSelectionModel().getSelectedIndex(), "kept, within the new list");
                card.update(null);
                assertEquals(0, list.getItems().size());
            } finally {
                stage.hide();
            }
        });
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }
}

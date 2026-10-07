package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.git.GitService.BranchInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branch dropdown as the user meets it: what a click activates, what fits at the default height, and
 * what a recycled row says about itself. Shown through a real {@link OverlayHost} with the app style sheet.
 */
@Tag("fx")
class BranchPopupBehaviourFxTest {

    private static final List<String> ACTIONS =
            List.of("git.newBranch", "git.pull", "git.fetch", "git.push", "git.stash", "git.unstash", "git.commit");

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A shown dropdown over five local branches (current: {@code main}) and two remote ones. */
    private record Shown(Stage stage, BranchPopup popup, ListView<Object> list, List<String> ran) {}

    private static Shown show() {
        StackPane root = new StackPane();
        Label anchor = new Label("main");
        StackPane.setAlignment(anchor, javafx.geometry.Pos.BOTTOM_RIGHT);
        root.getChildren().add(anchor);
        Scene scene = new Scene(root, 900, 700);
        scene.getStylesheets()
                .add(BranchPopupBehaviourFxTest.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        OverlayHost host = new OverlayHost();
        host.install(root);

        List<String> ran = new ArrayList<>();
        BranchPopup popup = new BranchPopup();
        popup.setOverlayHost(host);
        List<BranchPopup.MenuAction> actions = ACTIONS.stream()
                .map(id -> new BranchPopup.MenuAction(id, id, () -> ran.add(id)))
                .toList();
        List<BranchInfo> local = List.of(
                new BranchInfo("feature/a", "origin/feature/a", 2, 3, false),
                new BranchInfo("feature/b", "", 0, 0, false),
                new BranchInfo("fix/c", "origin/fix/c", 0, 0, true),
                new BranchInfo("main", "origin/main", 0, 0, false),
                new BranchInfo("spike", "", 0, 0, false));
        popup.show(
                stage,
                anchor,
                "main",
                local,
                List.of("origin/feature/a", "origin/main"),
                "",
                actions,
                name -> ran.add("local:" + name),
                name -> ran.add("remote:" + name));
        root.applyCss();
        root.layout();
        ListView<Object> list = FxTestSupport.field(popup, "list");
        return new Shown(stage, popup, list, ran);
    }

    private static String kind(Object row) {
        return row.getClass().getSimpleName();
    }

    private static String name(Object row) {
        return String.valueOf(
                FxTestSupport.call(row, kind(row).equals("BranchRow") ? "name" : "label", new Class<?>[] {}));
    }

    private static ListCell<?> cellOf(ListView<Object> list, int index) {
        for (Node n : list.lookupAll(".list-cell")) {
            if (n instanceof ListCell<?> c && !c.isEmpty() && c.getIndex() == index) {
                return c;
            }
        }
        throw new AssertionError("row " + index + " is not laid out");
    }

    private static void click(Node target) {
        Event.fireEvent(
                target,
                new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        5,
                        5,
                        5,
                        5,
                        MouseButton.PRIMARY,
                        1,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        true,
                        null));
    }

    private static int indexOf(ListView<Object> list, String kind, String name) {
        for (int i = 0; i < list.getItems().size(); i++) {
            Object row = list.getItems().get(i);
            if (kind(row).equals(kind) && (kind.equals("Header") || name(row).equals(name))) {
                return i;
            }
        }
        throw new AssertionError("no " + kind + " " + name);
    }

    @Test
    void onlyAClickOnARealRowActivatesIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show();
            try {
                // A branch is selected (as after arrowing to it), then the user clicks somewhere that is not a row.
                s.list().getSelectionModel().select(indexOf(s.list(), "BranchRow", "feature/b"));

                click(s.list()); // the blank area under the rows: the event's target is the list itself
                assertTrue(s.ran().isEmpty(), "a click on empty list space checked out the selected branch");
                assertTrue(s.popup().isShown());

                click(cellOf(s.list(), indexOf(s.list(), "Header", ""))); // the "Local" section label
                assertTrue(s.ran().isEmpty(), "a click on a section header activated the selected row");
                assertTrue(s.popup().isShown());

                click(cellOf(s.list(), indexOf(s.list(), "BranchRow", "spike")));
                assertEquals(List.of("local:spike"), s.ran(), "the clicked row runs — not the selected one");
                assertFalse(s.popup().isShown());
            } finally {
                s.stage().hide();
            }
        });
    }

    @Test
    void theBranchesComeFirstAndFitWithoutScrolling() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show();
            try {
                List<Object> rows = s.list().getItems();
                assertEquals("Header", kind(rows.get(0)));
                assertEquals("main", name(rows.get(1)), "the current branch is the first row under Local");
                assertEquals(1, s.list().getSelectionModel().getSelectedIndex(), "and it is where the cursor starts");

                int lastBranch = -1;
                int firstAction = -1;
                for (int i = 0; i < rows.size(); i++) {
                    if (kind(rows.get(i)).equals("BranchRow")) {
                        lastBranch = i;
                    } else if (kind(rows.get(i)).equals("ActionRow") && firstAction < 0) {
                        firstAction = i;
                    }
                }
                assertTrue(lastBranch < firstAction, "every branch is listed before the first action");

                // At the default height the whole branch list is on screen (seven action rows used to push the
                // current branch below the fold).
                double viewport = s.list().getHeight();
                for (int i = 0; i <= lastBranch; i++) {
                    ListCell<?> cell = cellOf(s.list(), i);
                    assertTrue(
                            cell.getBoundsInParent().getMaxY() <= viewport + 0.5,
                            "row " + i + " (" + kind(rows.get(i)) + ") ends at "
                                    + cell.getBoundsInParent().getMaxY() + " in a " + viewport + " px list");
                }
                assertEquals(
                        7,
                        rows.stream().filter(r -> kind(r).equals("ActionRow")).count(),
                        "actions still listed");
            } finally {
                s.stage().hide();
            }
        });
    }

    @Test
    void aRecycledCellDropsTheBranchTooltip() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show();
            try {
                List<Object> rows = s.list().getItems();
                int branch = indexOf(s.list(), "BranchRow", "feature/a");
                ListCell<?> cell = cellOf(s.list(), branch);
                assertNotNull(cell.getTooltip(), "a branch row describes its tracking state");

                Class<?> rowType = rows.get(0).getClass().getInterfaces()[0]; // the private Row interface
                Object action = rows.get(indexOf(s.list(), "ActionRow", "git.fetch"));
                FxTestSupport.call(cell, "updateItem", new Class<?>[] {rowType, boolean.class}, action, false);
                assertNull(cell.getTooltip(), "reused for an action, it still described feature/a");

                FxTestSupport.call(
                        cell, "updateItem", new Class<?>[] {rowType, boolean.class}, rows.get(branch), false);
                assertNotNull(cell.getTooltip());
                FxTestSupport.call(cell, "updateItem", new Class<?>[] {rowType, boolean.class}, null, true);
                assertNull(cell.getTooltip(), "…and an empty cell says nothing");
            } finally {
                s.stage().hide();
            }
        });
    }

    @Test
    void aheadComesBeforeBehindAsInTheStatusBar() {
        assertEquals("↑2 ↓3", BranchPopup.trackBadge(2, 3));
        assertEquals("↑1", BranchPopup.trackBadge(1, 0));
        assertEquals("↓99+", BranchPopup.trackBadge(0, 250));
        assertEquals("", BranchPopup.trackBadge(0, 0));
    }
}

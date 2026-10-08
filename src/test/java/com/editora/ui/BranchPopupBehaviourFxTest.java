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

import static com.editora.i18n.Messages.tr;
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

    private static void relayout(ListView<Object> list) {
        list.getScene().getRoot().applyCss();
        list.getScene().getRoot().layout();
    }

    private static void press(Node target, javafx.scene.input.KeyCode code) {
        Event.fireEvent(
                target,
                new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private static String title(Object header) {
        return String.valueOf(FxTestSupport.call(header, "title", new Class<?>[] {}));
    }

    private static List<String> headers(ListView<Object> list) {
        return list.getItems().stream()
                .filter(r -> kind(r).equals("Header"))
                .map(BranchPopupBehaviourFxTest::title)
                .toList();
    }

    private static List<String> branches(ListView<Object> list) {
        return list.getItems().stream()
                .filter(r -> kind(r).equals("BranchRow"))
                .map(BranchPopupBehaviourFxTest::name)
                .toList();
    }

    private static int headerIndex(ListView<Object> list, String title) {
        for (int i = 0; i < list.getItems().size(); i++) {
            Object row = list.getItems().get(i);
            if (kind(row).equals("Header") && title(row).equals(title)) {
                return i;
            }
        }
        throw new AssertionError("no header " + title);
    }

    /** The text of every label inside {@code cell}'s graphic. */
    private static List<String> labels(ListCell<?> cell) {
        List<String> out = new ArrayList<>();
        for (Node n : cell.lookupAll(".label")) {
            if (n instanceof Label label && n != cell) {
                out.add(label.getText());
            }
        }
        return out;
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

                click(cellOf(s.list(), indexOf(s.list(), "Header", ""))); // the "Actions" section label
                assertTrue(s.ran().isEmpty(), "a click on a section header activated the selected row");
                assertTrue(s.popup().isShown());
                relayout(s.list()); // the click folded the section: the rows below it moved up

                click(cellOf(s.list(), indexOf(s.list(), "BranchRow", "spike")));
                assertEquals(List.of("local:spike"), s.ran(), "the clicked row runs — not the selected one");
                assertFalse(s.popup().isShown());
            } finally {
                s.stage().hide();
            }
        });
    }

    @Test
    void theActionsComeFirstAndTheCursorStartsOnTheCurrentBranch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show();
            try {
                List<Object> rows = s.list().getItems();
                assertEquals(List.of("Actions", "Local", "Remote"), headers(s.list()));
                assertEquals("Header", kind(rows.get(0)));
                assertEquals("ActionRow", kind(rows.get(1)));
                int main = indexOf(s.list(), "BranchRow", "main");
                assertEquals("Header", kind(rows.get(main - 1)), "the current branch is the first row under Local");
                assertEquals(
                        main,
                        s.list().getSelectionModel().getSelectedIndex(),
                        "the cursor starts on the current branch, not on an action Enter would run");
                assertEquals(
                        7,
                        rows.stream().filter(r -> kind(r).equals("ActionRow")).count(),
                        "actions still listed");

                // At the default height everything is on screen: the actions above do not push a branch
                // below the fold.
                double viewport = s.list().getHeight();
                for (int i = 0; i < rows.size(); i++) {
                    ListCell<?> cell = cellOf(s.list(), i);
                    assertTrue(
                            cell.getBoundsInParent().getMaxY() <= viewport + 0.5,
                            "row " + i + " (" + kind(rows.get(i)) + ") ends at "
                                    + cell.getBoundsInParent().getMaxY() + " in a " + viewport + " px list");
                }
            } finally {
                s.stage().hide();
            }
        });
    }

    @Test
    void aClickOnAHeaderFoldsItsSectionAndASearchStillLooksInside() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show();
            try {
                List<java.util.Set<String>> told = new ArrayList<>();
                s.popup().setOnCollapsedSectionsChanged(told::add);
                javafx.scene.control.TextField search = FxTestSupport.field(s.popup(), "search");
                int local = headerIndex(s.list(), "Local");

                click(cellOf(s.list(), local));
                relayout(s.list());
                assertEquals(List.of(java.util.Set.of("local")), told);
                assertEquals(
                        List.of("origin/feature/a", "origin/main"),
                        branches(s.list()),
                        "the Local rows are folded away; Remote is untouched");
                assertEquals(List.of("Actions", "Local", "Remote"), headers(s.list()), "the header stays");
                assertEquals(local, s.list().getSelectionModel().getSelectedIndex(), "and keeps the cursor");
                assertTrue(labels(cellOf(s.list(), local)).contains("5"), "it says how many rows it hides");

                search.setText("spike");
                assertEquals(List.of("spike"), branches(s.list()), "a search finds a branch in a folded section");
                assertEquals("spike", name(s.list().getSelectionModel().getSelectedItem()));
                relayout(s.list());
                click(cellOf(s.list(), headerIndex(s.list(), "Local")));
                assertEquals(1, told.size(), "during a search a header is a plain label");

                search.setText("");
                assertEquals(
                        List.of("origin/feature/a", "origin/main"),
                        branches(s.list()),
                        "the fold is back once the search is cleared");

                relayout(s.list());
                click(cellOf(s.list(), headerIndex(s.list(), "Local")));
                assertEquals(java.util.Set.of(), told.get(told.size() - 1));
                assertEquals(7, branches(s.list()).size());
                assertTrue(s.ran().isEmpty());
                assertTrue(s.popup().isShown());
            } finally {
                s.stage().hide();
            }
        });
    }

    @Test
    void theKeyboardReachesAHeaderAndFoldsIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show();
            try {
                javafx.scene.control.TextField search = FxTestSupport.field(s.popup(), "search");
                assertEquals("main", name(s.list().getSelectionModel().getSelectedItem()));

                press(search, javafx.scene.input.KeyCode.UP);
                int local = headerIndex(s.list(), "Local");
                assertEquals(local, s.list().getSelectionModel().getSelectedIndex(), "↑ stops on the header");

                press(search, javafx.scene.input.KeyCode.LEFT);
                assertEquals(2, branches(s.list()).size(), "← folds");
                press(search, javafx.scene.input.KeyCode.LEFT);
                assertEquals(2, branches(s.list()).size(), "← on a folded section changes nothing");
                press(search, javafx.scene.input.KeyCode.RIGHT);
                assertEquals(7, branches(s.list()).size(), "→ unfolds");
                press(search, javafx.scene.input.KeyCode.ENTER);
                assertEquals(2, branches(s.list()).size(), "Enter toggles");
                assertEquals(local, s.list().getSelectionModel().getSelectedIndex());
                assertTrue(s.popup().isShown(), "folding does not close the dropdown");

                press(search, javafx.scene.input.KeyCode.DOWN);
                assertEquals(
                        headerIndex(s.list(), "Remote"),
                        s.list().getSelectionModel().getSelectedIndex(),
                        "↓ from a folded header goes to the next section");
                assertTrue(s.ran().isEmpty());
            } finally {
                s.stage().hide();
            }
        });
    }

    @Test
    void theOwnersCollapsedSetIsHonouredWhenShown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = show();
            try {
                s.popup().hide();
                s.popup().setCollapsedSections(List.of("actions", "local", "remote"));
                FxTestSupport.call(
                        s.popup(), "present", new Class<?>[] {javafx.stage.Window.class, Node.class}, s.stage(), null);
                assertEquals(3, s.list().getItems().size(), "three headers and nothing else");
                assertEquals(0, s.list().getSelectionModel().getSelectedIndex(), "the first header takes the cursor");
            } finally {
                s.popup().hide();
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

    // --- per-row secondary actions, remote groups, the "gone" marker --------------------------------

    /** A dropdown over two remotes, whose branch rows offer one secondary action each. */
    private static Shown showWithRowActions() {
        Shown base = show();
        base.popup().hide();
        StackPane root = (StackPane) base.stage().getScene().getRoot();
        Node anchor = root.getChildren().get(0);
        base.popup()
                .show(
                        base.stage(),
                        anchor,
                        "main",
                        List.of(
                                new BranchInfo("fix/c", "origin/fix/c", 0, 0, true),
                                new BranchInfo("main", "origin/main", 0, 0, false)),
                        List.of("origin/main", "fork/topic", "fork/main"),
                        List.of("origin", "fork"),
                        "",
                        List.of(),
                        name -> base.ran().add("local:" + name),
                        name -> base.ran().add("remote:" + name),
                        branch -> List.of(
                                new BranchPopup.RowAction(
                                        "Delete " + branch.name(),
                                        true,
                                        () -> base.ran().add("delete:" + branch.name())),
                                BranchPopup.RowAction.SEPARATOR,
                                new BranchPopup.RowAction(
                                        "gone=" + branch.gone() + " current=" + branch.current(), false, () -> {})));
        root.applyCss();
        root.layout();
        return base;
    }

    @Test
    void aBranchRowsSecondaryActionClosesTheDropdownAndRuns() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = showWithRowActions();
            try {
                List<BranchPopup.RowAction> actions = s.popup().rowActionsFor("fix/c");
                assertEquals("Delete fix/c", actions.get(0).label());
                assertTrue(actions.get(0).danger());
                assertEquals("gone=true current=false", actions.get(2).label(), "the row describes its branch");
                assertEquals(
                        "gone=false current=true",
                        s.popup().rowActionsFor("main").get(2).label());

                s.popup().runRowAction(actions.get(0));
                assertEquals(List.of("delete:fix/c"), s.ran(), "the action ran, and nothing was checked out");
                assertFalse(s.popup().isShown(), "the dropdown closes like it does for a checkout");
            } finally {
                s.stage().close();
            }
        });
    }

    /** The visible way into the secondary actions, and a click on it is not a click on the row. */
    @Test
    void everyBranchRowCarriesAMoreButtonThatDoesNotCheckOut() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = showWithRowActions();
            try {
                int rows = 0;
                for (Node n : s.list().lookupAll(".list-cell")) {
                    if (!(n instanceof ListCell<?> cell)
                            || cell.isEmpty()
                            || !kind(cell.getItem()).equals("BranchRow")) {
                        continue;
                    }
                    rows++;
                    Node more = cell.lookup(".branch-more");
                    assertTrue(more != null, "no more-button on " + name(cell.getItem()));
                    click(more);
                }
                assertTrue(rows >= 5, "expected the branch rows to be laid out, got " + rows);
                assertEquals(List.of(), s.ran(), "the more-button must not activate its row");
                assertTrue(s.popup().isShown());
            } finally {
                s.popup().hide();
                s.stage().close();
            }
        });
    }

    @Test
    void severalRemotesGetASectionEachAndAGoneUpstreamIsMarked() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = showWithRowActions();
            try {
                List<String> headers = new ArrayList<>();
                for (Object row : s.list().getItems()) {
                    if (kind(row).equals("Header")) {
                        headers.add(String.valueOf(FxTestSupport.call(row, "title", new Class<?>[] {})));
                    }
                }
                assertEquals(
                        List.of(
                                tr("branchpopup.local"),
                                tr("branchpopup.remoteOf", "fork"),
                                tr("branchpopup.remoteOf", "origin")),
                        headers);
                boolean marked = false;
                for (Node n : s.list().lookupAll(".list-cell")) {
                    if (n instanceof ListCell<?> cell && !cell.isEmpty() && n.lookup(".branch-gone") != null) {
                        marked = true;
                        assertEquals("fix/c", name(cell.getItem()));
                        assertTrue(cell.getTooltip().getText().contains(tr("branchpopup.tip.goneHint")));
                    }
                }
                assertTrue(marked, "the branch whose upstream is gone carries the marker");
            } finally {
                s.popup().hide();
                s.stage().close();
            }
        });
    }

    /** The Menu key opens the selected branch's secondary menu; choosing an entry runs it and closes all. */
    @Test
    void theMenuKeyOpensTheSelectedRowsSecondaryMenu() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Shown s = showWithRowActions();
            try {
                javafx.scene.control.TextField search = FxTestSupport.field(s.popup(), "search");
                assertEquals("main", name(s.list().getSelectionModel().getSelectedItem()), "the current branch");
                Event.fireEvent(
                        search,
                        new javafx.scene.input.KeyEvent(
                                javafx.scene.input.KeyEvent.KEY_PRESSED,
                                "",
                                "",
                                javafx.scene.input.KeyCode.CONTEXT_MENU,
                                false,
                                false,
                                false,
                                false));
                javafx.scene.control.ContextMenu menu = FxTestSupport.field(s.popup(), "rowMenu");
                assertNotNull(menu, "the Menu key opened nothing");
                assertTrue(menu.isShowing());
                assertTrue(s.popup().isShown(), "the dropdown stays up under its menu");
                assertEquals(3, menu.getItems().size());
                assertTrue(menu.getItems().get(0).getStyleClass().contains("danger"));
                assertTrue(menu.getItems().get(1) instanceof javafx.scene.control.SeparatorMenuItem);

                menu.getItems().get(0).fire();
                assertEquals(List.of("delete:main"), s.ran());
                assertFalse(s.popup().isShown());
                assertNull(FxTestSupport.<Object>field(s.popup(), "rowMenu"), "the menu went down with the dropdown");
            } finally {
                s.popup().hide();
                s.stage().close();
            }
        });
    }
}

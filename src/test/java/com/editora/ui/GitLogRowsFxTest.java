package com.editora.ui;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.stage.Stage;

import com.editora.git.GitLog;
import com.editora.git.GitLog.Entry;
import com.editora.git.GitLog.Ref;
import com.editora.git.GitLog.RefKind;
import com.editora.git.GitService.CommitFile;
import com.editora.github.PrListParser.PullRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Git Log and GitHub rows, measured in the real (headless) renderer with the app style sheet.
 *
 * <p>Their rows were 42 px high (the theme's fixed list-cell size) beside the Commit window's 21 px tree rows
 * — so the default bottom panel showed two commits — and were built from a {@code TextFlow}, which cannot
 * ellipsize and made the list scroll sideways. A row is now one text line: hash, ref chips, subject
 * (ellipsized), author, relative date.
 */
@Tag("fx")
class GitLogRowsFxTest {

    private static final String LONG_SUBJECT = "feat(orders): " + "a deliberately long subject line ".repeat(12);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @SuppressWarnings("unchecked")
    private static <T> T noop(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> null);
    }

    private static Stage show(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets()
                .add(GitLogRowsFxTest.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }

    private static List<ListCell<?>> filledCells(ListView<?> list) {
        List<ListCell<?>> cells = new ArrayList<>();
        for (Node n : list.lookupAll(".list-cell")) {
            if (n instanceof ListCell<?> c && !c.isEmpty() && c.getItem() != null) {
                cells.add(c);
            }
        }
        return cells;
    }

    private static Label label(Node within, String styleClass) {
        return (Label) within.lookup("." + styleClass);
    }

    private static List<Entry> sampleLog() {
        long now = System.currentTimeMillis() / 1000;
        return List.of(
                new Entry(
                        "1111111aaaaaaaaaaaaa",
                        "1111111",
                        LONG_SUBJECT,
                        "Ada Lovelace",
                        now - 3 * 86_400,
                        "2026-10-03",
                        List.of(
                                new Ref(RefKind.LOCAL, "main", true),
                                new Ref(RefKind.REMOTE, "origin/main", false),
                                new Ref(RefKind.TAG, "v1.0", false))),
                new Entry(
                        "2222222aaaaaaaaaaaaa",
                        "2222222",
                        "Initial import",
                        "Grace Hopper",
                        now - 120,
                        "2026-10-06",
                        List.of()));
    }

    private static boolean horizontalBarShowing(ListView<?> list) {
        for (Node n : list.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL) {
                return bar.isVisible();
            }
        }
        return false;
    }

    /**
     * Height of a row of the Commit window's file tree ({@code .git-tree .tree-cell}) — the one-line row both
     * lists are held to. Measured, not assumed, so the test follows the theme's font.
     */
    private static double treeRowHeight() {
        javafx.scene.control.TreeItem<String> root = new javafx.scene.control.TreeItem<>("Changes");
        root.setExpanded(true);
        root.getChildren().add(new javafx.scene.control.TreeItem<>("docs/notes.md"));
        javafx.scene.control.TreeView<String> tree = new javafx.scene.control.TreeView<>(root);
        tree.getStyleClass().add("git-tree");
        Stage stage = show(new javafx.scene.layout.StackPane(tree), 300, 200);
        try {
            for (Node n : tree.lookupAll(".tree-cell")) {
                if (n instanceof javafx.scene.control.TreeCell<?> c && !c.isEmpty()) {
                    assertTrue(
                            c.getHeight() > 10 && c.getHeight() < 30, "a tree row is one text line: " + c.getHeight());
                    return c.getHeight();
                }
            }
            throw new AssertionError("no tree row was laid out");
        } finally {
            stage.hide();
        }
    }

    private static List<ListCell<?>> fileRows(GitLogPanel panel) {
        ListView<CommitFile> files = FxTestSupport.field(panel, "files");
        List<ListCell<?>> cells = filledCells(files);
        assertFalse(cells.isEmpty(), "the changed-file row is on screen");
        return cells;
    }

    @Test
    void aCommitRowIsOneTextLineHighAndEllipsizesALongSubject() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(noop(GitLogPanel.Actions.class));
            Stage stage = show(panel, 700, 420);
            try {
                panel.setLog(new GitLog.Page(sampleLog(), false), null, "main");
                ListView<Entry> commits = FxTestSupport.field(panel, "commits");
                commits.getSelectionModel().select(0);
                panel.setCommitFiles(List.of(new CommitFile('M', "docs/notes.md", null)));
                panel.applyCss();
                panel.layout();

                double treeRow = treeRowHeight();
                List<ListCell<?>> rows = filledCells(commits);
                assertEquals(2, rows.size(), "both commits are laid out");
                for (ListCell<?> row : rows) {
                    assertEquals(
                            treeRow,
                            row.getHeight(),
                            2.0,
                            "a commit row is as high as a Commit-window file row (it measured twice that)");
                }
                for (ListCell<?> row : fileRows(panel)) {
                    assertEquals(treeRow, row.getHeight(), 2.0, "and so is a changed-file row under it");
                }

                ListCell<?> decorated = rows.stream()
                        .filter(c -> ((Entry) c.getItem()).shortHash().equals("1111111"))
                        .findFirst()
                        .orElseThrow();
                Label subject = label(decorated, "git-log-subject");
                assertTrue(subject.isTextTruncated(), "the long subject is ellipsized, not laid out in full");
                assertTrue(subject.getWidth() > 150, "…and still gets the row's free width: " + subject.getWidth());
                for (Node n : decorated.lookupAll(".label")) {
                    Label l = (Label) n;
                    if (l != subject) {
                        assertFalse(l.isTextTruncated(), "\"" + l.getText() + "\" is cut although the row has room");
                    }
                }
                assertFalse(horizontalBarShowing(commits), "…and the list does not scroll sideways for it");
                assertTrue(decorated.getWidth() <= commits.getWidth() + 0.5);

                // The row says who and when, and which names point at the commit.
                assertEquals("1111111", label(decorated, "git-log-hash").getText());
                List<String> meta = decorated.lookupAll(".git-log-meta").stream()
                        .map(n -> ((Label) n).getText())
                        .toList();
                assertEquals(List.of("Ada Lovelace", tr("blame.daysAgo", 3L)), meta);
                List<String> chips = decorated.lookupAll(".git-ref").stream()
                        .map(n -> ((Label) n).getText())
                        .toList();
                assertEquals(List.of("HEAD → main", "origin/main", "v1.0"), chips);
            } finally {
                stage.hide();
            }
        });
    }

    /** main ← merge of a side branch: m(b, f), f(a), b(a), a. */
    private static List<Entry> mergeLog() {
        long now = System.currentTimeMillis() / 1000;
        return List.of(
                new Entry("mmmm", "mmmm", "Merge side", "Ada", now, "2026-10-06", List.of("bbbb", "ffff"), List.of()),
                new Entry("ffff", "ffff", "Side work", "Ada", now, "2026-10-06", List.of("aaaa"), List.of()),
                new Entry("bbbb", "bbbb", "Main work", "Ada", now, "2026-10-06", List.of("aaaa"), List.of()),
                new Entry("aaaa", "aaaa", "Root", "Ada", now, "2026-10-06", List.of(), List.of()));
    }

    @Test
    void theGraphColumnLeavesARowOneTextLineHighAndItsStripsTouch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(noop(GitLogPanel.Actions.class));
            Stage stage = show(panel, 700, 420);
            try {
                panel.setLog(new GitLog.Page(mergeLog(), false), new GitLogPanel.View(null, "main", false, "", true));
                panel.applyCss();
                panel.layout();
                ListView<Entry> commits = FxTestSupport.field(panel, "commits");
                double treeRow = treeRowHeight();
                List<ListCell<?>> rows = filledCells(commits);
                assertEquals(4, rows.size());
                rows.sort(java.util.Comparator.comparingDouble(
                        c -> c.getBoundsInParent().getMinY()));
                double expectedWidth = 2 * GitLogPanel.GRAPH_LANE_WIDTH; // the merge needs two lanes
                for (int i = 0; i < rows.size(); i++) {
                    ListCell<?> row = rows.get(i);
                    assertEquals(treeRow, row.getHeight(), 2.0, "the graph does not make a commit row taller");
                    javafx.scene.canvas.Canvas canvas = (javafx.scene.canvas.Canvas) row.lookup(".canvas");
                    if (canvas == null) {
                        canvas = row.lookupAll("*").stream()
                                .filter(n -> n instanceof javafx.scene.canvas.Canvas)
                                .map(n -> (javafx.scene.canvas.Canvas) n)
                                .findFirst()
                                .orElseThrow();
                    }
                    assertEquals(expectedWidth, canvas.getWidth(), 0.5, "every row has the same graph column");
                    assertEquals(
                            row.getHeight(),
                            canvas.getHeight(),
                            0.5,
                            "the strip is as high as its row, so the lanes of adjacent rows meet");
                    javafx.geometry.Bounds strip = canvas.localToScene(canvas.getBoundsInLocal());
                    javafx.geometry.Bounds cell = row.localToScene(row.getBoundsInLocal());
                    assertEquals(cell.getMinY(), strip.getMinY(), 0.5, "…with no gap above it");
                    assertEquals(
                            "mmmm ffff bbbb aaaa".split(" ")[i],
                            label(row, "git-log-hash").getText());
                }
                assertFalse(horizontalBarShowing(commits));

                // Typing a filter shows a subset of the commits: the graph column goes, and comes back.
                javafx.scene.control.TextField filter = FxTestSupport.field(panel, "filterField");
                filter.setText("side");
                panel.applyCss();
                panel.layout();
                assertEquals(2, commits.getItems().size());
                assertEquals(0, (int) FxTestSupport.<Integer>field(panel, "graphColumns"));
                filter.setText("");
                assertEquals(2, (int) FxTestSupport.<Integer>field(panel, "graphColumns"));

                // A file history has no graph at all.
                panel.setLog(new GitLog.Page(mergeLog(), false), "Orders.java", "main");
                assertEquals(0, (int) FxTestSupport.<Integer>field(panel, "graphColumns"));
            } finally {
                stage.hide();
            }
        });
    }

    @Test
    void scrollingToTheEndAsksForTheNextPageOnceAndAppendingKeepsThePlace() throws Exception {
        List<String> calls = new ArrayList<>();
        GitLogPanel.Actions actions = (GitLogPanel.Actions) Proxy.newProxyInstance(
                GitLogPanel.Actions.class.getClassLoader(), new Class<?>[] {GitLogPanel.Actions.class}, (p, m, a) -> {
                    calls.add(m.getName());
                    return null;
                });
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(actions);
            Stage stage = show(panel, 700, 420);
            try {
                panel.setLog(
                        new GitLog.Page(numbered(0, 200), true), new GitLogPanel.View(null, "main", false, "", true));
                panel.applyCss();
                panel.layout();
                ListView<Entry> commits = FxTestSupport.field(panel, "commits");
                Label truncated = FxTestSupport.field(panel, "truncatedLabel");
                javafx.scene.control.Button more = FxTestSupport.field(panel, "loadMoreButton");
                assertFalse(calls.contains("loadMore"), "the top of 200 rows is nowhere near the end");
                assertTrue(truncated.getParent().isVisible(), "older history exists: the footer offers it");
                assertEquals(tr("gitlog.loadMore"), more.getText());

                commits.getSelectionModel().select(150);
                commits.scrollTo(185);
                panel.applyCss();
                panel.layout();
                assertEquals(1, calls.stream().filter("loadMore"::equals).count(), "asked once, however many rows");
                assertTrue(more.isDisabled(), "…and the footer shows the page is on its way");
                assertEquals(tr("gitlog.loadingMore"), more.getText());

                calls.clear();
                panel.appendLog(new GitLog.Page(numbered(200, 260), false));
                panel.applyCss();
                panel.layout();
                assertEquals(260, commits.getItems().size());
                assertEquals(150, commits.getSelectionModel().getSelectedIndex(), "the selection stays put");
                assertEquals("c150", panel.selectedHash());
                assertFalse(calls.contains("selected"), "…and is not re-fetched");
                assertFalse(truncated.getParent().isVisible(), "the whole history is loaded: no footer");
                assertEquals(
                        260, FxTestSupport.<List<?>>field(panel, "graphRows").size());
                assertEquals("c259", panel.lastLoadedHash());
            } finally {
                stage.hide();
            }
        });
    }

    private static List<Entry> numbered(int from, int to) {
        List<Entry> entries = new ArrayList<>();
        for (int i = from; i < to; i++) {
            entries.add(new Entry(
                    "c" + i, "c" + i, "commit " + i, "Ada", 0L, "2026-10-06", List.of("c" + (i + 1)), List.of()));
        }
        return entries;
    }

    @Test
    void theDetailsPaneDescribesTheSelectedCommitAndFollowsTheSelection() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(noop(GitLogPanel.Actions.class));
            panel.setLog(new GitLog.Page(mergeLog(), false), new GitLogPanel.View(null, "main", false, "", true));
            ListView<Entry> commits = FxTestSupport.field(panel, "commits");
            Label hash = FxTestSupport.field(panel, "detailsHash");
            javafx.scene.control.TextInputControl message = FxTestSupport.field(panel, "detailsMessage");
            javafx.scene.layout.HBox parents = FxTestSupport.field(panel, "detailsParents");
            javafx.scene.Node scroll = FxTestSupport.field(panel, "detailsScroll");
            assertFalse(scroll.isVisible(), "nothing selected: no details");

            commits.getSelectionModel().select(0);
            assertTrue(scroll.isVisible() && scroll.isManaged());
            assertEquals("mmmm", hash.getText());
            assertEquals("Merge side", message.getText(), "the subject at once; the body when git has answered");
            List<String> links = parents.getChildren().stream()
                    .filter(n -> n instanceof javafx.scene.control.Hyperlink)
                    .map(n -> ((javafx.scene.control.Hyperlink) n).getText())
                    .toList();
            assertEquals(List.of("bbbb", "ffff"), links, "both parents of a merge");

            // Details for another commit (a slow answer to an earlier selection) are not shown.
            panel.setCommitDetails(new GitLog.Details(
                    "ffff",
                    List.of("aaaa"),
                    "Ada",
                    "ada@example.org",
                    1L,
                    "Ada",
                    "ada@example.org",
                    1L,
                    List.of(),
                    "x"));
            assertEquals("Merge side", message.getText());
            panel.setCommitDetails(new GitLog.Details(
                    "mmmm",
                    List.of("bbbb", "ffff"),
                    "Ada",
                    "ada@example.org",
                    1_791_342_219L,
                    "Grace",
                    "grace@example.org",
                    1_791_342_219L,
                    List.of(new Ref(RefKind.TAG, "v9", false)),
                    "Merge side\n\nThe body."));
            assertEquals("Merge side\n\nThe body.", message.getText());
            Label author = FxTestSupport.field(panel, "detailsAuthor");
            assertTrue(author.getText().contains("Ada <ada@example.org>"), author.getText());
            assertTrue(author.getText().contains(GitLogPanel.absoluteDate(1_791_342_219L)), author.getText());
            assertTrue(author.getText().contains("Grace <grace@example.org>"), "a different committer is named too");

            // A parent link selects that commit's row.
            ((javafx.scene.control.Hyperlink) parents.getChildren().get(2)).fire();
            assertEquals("ffff", panel.selectedHash());
            assertEquals("ffff", hash.getText());

            commits.getSelectionModel().clearSelection();
            assertFalse(scroll.isVisible());
        });
    }

    @Test
    void enterOpensACommitAsAReviewAndTwoSelectedCommitsCompare() throws Exception {
        List<String> calls = new ArrayList<>();
        GitLogPanel.Actions actions = (GitLogPanel.Actions) Proxy.newProxyInstance(
                GitLogPanel.Actions.class.getClassLoader(), new Class<?>[] {GitLogPanel.Actions.class}, (p, m, a) -> {
                    if (m.getName().equals("reviewCommit") || m.getName().equals("compareCommits")) {
                        calls.add(m.getName() + java.util.Arrays.toString(a));
                    }
                    return null;
                });
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(actions);
            panel.setLog(new GitLog.Page(mergeLog(), false), null, "main");
            ListView<Entry> commits = FxTestSupport.field(panel, "commits");
            commits.getSelectionModel().select(1);
            FxTestSupport.call(panel, "openSelectedCommits", new Class<?>[] {});
            // Selected bottom-up: the older commit is still the left side.
            commits.getSelectionModel().clearSelection();
            commits.getSelectionModel().selectIndices(3, 0);
            FxTestSupport.call(panel, "openSelectedCommits", new Class<?>[] {});
            assertEquals(List.of("reviewCommit[ffff]", "compareCommits[aaaa, mmmm]"), calls);
        });
    }

    @Test
    void aNarrowRowDropsColumnsInsteadOfSqueezingThemAll() throws Exception {
        assertEquals(3, GitLogPanel.chipBudget(900));
        assertEquals(2, GitLogPanel.chipBudget(500));
        assertEquals(1, GitLogPanel.chipBudget(300));
        assertTrue(GitLogPanel.showsAuthor(900));
        assertFalse(GitLogPanel.showsAuthor(400));

        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(noop(GitLogPanel.Actions.class));
            Stage stage = show(panel, 360, 420); // docked on a side
            try {
                panel.setLog(new GitLog.Page(sampleLog(), false), null, "main");
                panel.applyCss();
                panel.layout();
                ListView<Entry> commits = FxTestSupport.field(panel, "commits");
                ListCell<?> decorated = filledCells(commits).stream()
                        .filter(c -> ((Entry) c.getItem()).shortHash().equals("1111111"))
                        .findFirst()
                        .orElseThrow();
                List<String> chips = decorated.lookupAll(".git-ref").stream()
                        .map(n -> ((Label) n).getText())
                        .toList();
                assertEquals(List.of("HEAD → main", "+2"), chips, "one chip and a count, not three stubs");
                for (Node n : decorated.lookupAll(".git-ref")) {
                    assertFalse(((Label) n).isTextTruncated(), "a chip is shown whole or not at all");
                }
                List<Node> meta = List.copyOf(decorated.lookupAll(".git-log-meta"));
                assertFalse(meta.get(0).isVisible(), "the author column gives way");
                assertTrue(meta.get(1).isVisible(), "the date stays");
                assertFalse(horizontalBarShowing(commits));
            } finally {
                stage.hide();
            }
        });
    }

    @Test
    void theHeaderNamesTheBranchAndATruncatedListSaysSo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(noop(GitLogPanel.Actions.class));
            Label header = FxTestSupport.field(panel, "filterLabel");
            Label truncated = FxTestSupport.field(panel, "truncatedLabel");

            panel.setLog(new GitLog.Page(sampleLog(), false), null, "main");
            assertEquals(tr("gitlog.branch", "main"), header.getText(), "it is one branch's history, not all commits");
            assertFalse(truncated.isVisible());
            assertFalse(truncated.isManaged(), "no footer row is reserved for a complete history");

            panel.setLog(new GitLog.Page(sampleLog(), true), null, "");
            assertEquals(tr("gitlog.currentBranch"), header.getText());
            assertTrue(truncated.isVisible() && truncated.isManaged());
            assertEquals(tr("gitlog.truncated", 2), truncated.getText());

            panel.setLog(new GitLog.Page(sampleLog(), false), "Orders.java", "main");
            assertEquals(tr("gitlog.history", "Orders.java"), header.getText());
        });
    }

    @Test
    void aReloadKeepsTheSelectedCommitAndItsFilesWhenNothingMoved() throws Exception {
        List<String> fetched = new ArrayList<>();
        GitLogPanel.Actions actions = (GitLogPanel.Actions) Proxy.newProxyInstance(
                GitLogPanel.Actions.class.getClassLoader(), new Class<?>[] {GitLogPanel.Actions.class}, (p, m, a) -> {
                    if (m.getName().equals("selected")) {
                        fetched.add((String) a[0]);
                    }
                    return null;
                });
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(actions);
            List<Entry> log = sampleLog();
            panel.setLog(new GitLog.Page(log, false), null, "main");
            ListView<Entry> commits = FxTestSupport.field(panel, "commits");
            ListView<CommitFile> files = FxTestSupport.field(panel, "files");
            commits.getSelectionModel().select(1);
            panel.setCommitFiles(List.of(new CommitFile('M', "a.txt", null)));
            fetched.clear();

            // The reload after a stage or a fetch with nothing new: the same commits come back.
            panel.setLog(new GitLog.Page(new ArrayList<>(log), false), null, "main");
            assertEquals("2222222aaaaaaaaaaaaa", panel.selectedHash());
            assertEquals(1, files.getItems().size(), "the changed-files pane is not emptied and refetched");
            assertTrue(fetched.isEmpty());

            // A commit landed on top: the list changes, the selection follows its commit.
            List<Entry> grown = new ArrayList<>(log);
            grown.add(0, new Entry("3333333aaaaaaaaaaaaa", "3333333", "New work", "Ada", "2026-10-06"));
            panel.setLog(new GitLog.Page(grown, false), null, "main");
            assertEquals("2222222aaaaaaaaaaaaa", panel.selectedHash());
            assertEquals(2, commits.getSelectionModel().getSelectedIndex());

            // The selected commit is gone (a reset): nothing stays selected rather than a neighbour.
            panel.setLog(new GitLog.Page(grown.subList(0, 1), false), null, "main");
            assertNull(panel.selectedHash());
        });
    }

    @Test
    void theFilterMatchesRefNames() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(noop(GitLogPanel.Actions.class));
            panel.setLog(new GitLog.Page(sampleLog(), false), null, "main");
            javafx.scene.control.TextField filter = FxTestSupport.field(panel, "filterField");
            ListView<Entry> commits = FxTestSupport.field(panel, "commits");
            filter.setText("v1.0");
            assertEquals(1, commits.getItems().size());
            assertEquals("1111111", commits.getItems().get(0).shortHash());
        });
    }

    @Test
    void aGitHubRowIsOneTextLineHighToo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitHubPanel github = new GitHubPanel(noop(GitHubPanel.Actions.class));
            Stage stage = show(github, 500, 300);
            try {
                double treeRow = treeRowHeight();

                github.setPrs(List.of(
                        new PullRequest(12, LONG_SUBJECT, "ada", "feat/x", "main", "OPEN", true, "2026-10-06", "u"),
                        new PullRequest(13, "Short", "ada", "feat/y", "main", "OPEN", false, "2026-10-06", "u")));
                github.applyCss();
                github.layout();
                ListView<Object> list = FxTestSupport.field(github, "list");
                List<ListCell<?>> rows = filledCells(list);
                assertEquals(2, rows.size());
                for (ListCell<?> row : rows) {
                    assertEquals(treeRow, row.getHeight(), 2.0, "a pull-request row is one text line high");
                }
                Label title = label(rows.get(0), "git-log-subject");
                assertTrue(title.isTextTruncated(), "a long title ellipsizes");
                assertFalse(horizontalBarShowing(list));
                assertEquals("#12", label(rows.get(0), "git-log-hash").getText());
            } finally {
                stage.hide();
            }
        });
    }

    @Test
    void theChangedFilesListOpensItsRowMenuFromTheKeyboard() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(noop(GitLogPanel.Actions.class));
            Stage stage = show(panel, 700, 420);
            try {
                panel.setLog(new GitLog.Page(sampleLog(), false), null, "main");
                ListView<Entry> commits = FxTestSupport.field(panel, "commits");
                ListView<CommitFile> files = FxTestSupport.field(panel, "files");
                commits.getSelectionModel().select(0);
                panel.setCommitFiles(List.of(new CommitFile('M', "docs/notes.md", null)));
                panel.applyCss();
                panel.layout();
                files.getSelectionModel().select(0);
                ListCell<?> row = fileRows(panel).get(0);
                boolean[] reached = new boolean[1];
                row.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
                    reached[0] = true;
                    e.consume(); // the routing is what is under test; do not pop a real menu up
                });

                // The Menu key / Shift+F10 arrives at the list (cells are not focusable).
                files.fireEvent(new javafx.scene.input.ContextMenuEvent(
                        javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, true, null));
                assertTrue(reached[0], "the selected file row's menu is requested for the keyboard too");
            } finally {
                stage.hide();
            }
        });
    }
}

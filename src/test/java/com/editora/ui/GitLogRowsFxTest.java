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

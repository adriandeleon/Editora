package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;

import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-FX coverage of {@link GitPanel#setStatus}: the Staged/Changes/Untracked grouping of a
 * {@link GitStatus}, the branch label, the commit-button enablement (staged ⇒ enabled), and the
 * clean / not-a-repo states. Uses a no-op {@link GitPanel.Actions} stub — no live git.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitPanelFxTest {

    /** A stub Actions that records nothing — setStatus is mouse-free, so callbacks never fire here. */
    private static final GitPanel.Actions NOOP = new GitPanel.Actions() {
        @Override
        public void open(String repoRelativePath) {}

        @Override
        public void stage(List<String> paths) {}

        @Override
        public void unstage(List<String> paths) {}

        @Override
        public void discard(List<String> tracked, List<String> untracked) {}

        @Override
        public void stageAll() {}

        @Override
        public void commit(String message) {}

        @Override
        public void push() {}

        @Override
        public void refresh() {}

        @Override
        public void diff(String repoRelativePath, boolean staged) {}
    };

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private GitPanel panel() throws Exception {
        return FxTestSupport.callOnFx(() -> new GitPanel(NOOP));
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(GitPanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    @Test
    void groupsStagedChangesAndUntracked() throws Exception {
        GitPanel p = panel();
        GitStatus status = new GitStatus(
                true,
                "main",
                "origin/main",
                1,
                0,
                List.of(
                        new FileEntry("staged.txt", 'M', '.', null), // staged only
                        new FileEntry("changed.txt", '.', 'M', null), // unstaged only
                        new FileEntry("new.txt", '?', '?', null))); // untracked
        FxTestSupport.runOnFx(() -> p.setStatus(status));

        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(3, root.getChildren().size(), "Staged + Changes + Untracked groups");

        Label branch = FxTestSupport.field(p, "branchLabel");
        assertTrue(FxTestSupport.callOnFx(() -> branch.getText()).contains("main"), "branch label shows the branch");

        Button commit = FxTestSupport.field(p, "commitButton");
        assertFalse(FxTestSupport.callOnFx(commit::isDisable), "commit enabled when something is staged");
    }

    @Test
    void aFileStagedAndUnstagedShowsInBothGroups() throws Exception {
        GitPanel p = panel();
        GitStatus status =
                new GitStatus(true, "dev", "origin/dev", 0, 0, List.of(new FileEntry("both.txt", 'M', 'M', null)));
        FxTestSupport.runOnFx(() -> p.setStatus(status));
        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        // One file that is both staged and unstaged populates the Staged group AND the Changes group.
        assertEquals(2, root.getChildren().size());
    }

    @Test
    void cleanRepoDisablesCommitAndShowsNoGroups() throws Exception {
        GitPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setStatus(new GitStatus(true, "main", "origin/main", 0, 0, List.of())));
        Button commit = FxTestSupport.field(p, "commitButton");
        assertTrue(FxTestSupport.callOnFx(commit::isDisable), "nothing staged ⇒ commit disabled");
    }

    /** Records what the panel asks the controller to do, so the multi-selection actions can be asserted. */
    private static final class Recording implements GitPanel.Actions {
        final List<List<String>> staged = new ArrayList<>();
        final List<List<String>> unstaged = new ArrayList<>();
        final List<List<String>> discardedTracked = new ArrayList<>();
        final List<List<String>> discardedUntracked = new ArrayList<>();

        @Override
        public void open(String repoRelativePath) {}

        @Override
        public void stage(List<String> paths) {
            staged.add(paths);
        }

        @Override
        public void unstage(List<String> paths) {
            unstaged.add(paths);
        }

        @Override
        public void discard(List<String> tracked, List<String> untracked) {
            discardedTracked.add(tracked);
            discardedUntracked.add(untracked);
        }

        @Override
        public void stageAll() {}

        @Override
        public void commit(String message) {}

        @Override
        public void push() {}

        @Override
        public void refresh() {}

        @Override
        public void diff(String repoRelativePath, boolean staged) {}
    }

    /** The three-group status the multi-selection tests select rows out of. */
    private static GitStatus mixedStatus() {
        return new GitStatus(
                true,
                "main",
                "origin/main",
                0,
                0,
                List.of(
                        new FileEntry("staged.txt", 'M', '.', null),
                        new FileEntry("changed.txt", '.', 'M', null),
                        new FileEntry("new.txt", '?', '?', null)));
    }

    /** Selects every file row (skipping the group headers), i.e. what a Ctrl-A / full Shift-range gives. */
    private static void selectAllFileRows(GitPanel p) throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeView<Object> t = tree(p);
            t.getSelectionModel().clearSelection();
            for (TreeItem<Object> group : t.getRoot().getChildren()) {
                for (TreeItem<Object> file : group.getChildren()) {
                    t.getSelectionModel().select(file);
                }
            }
        });
    }

    private static javafx.scene.control.TextField filterOf(GitPanel p) {
        return FxTestSupport.field(p, "filterField");
    }

    /** The file-row paths currently rendered, flattened across the groups. */
    private static List<String> renderedPaths(GitPanel p) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<String> out = new ArrayList<>();
            TreeItem<Object> root = tree(p).getRoot();
            if (root == null) {
                return out;
            }
            for (TreeItem<Object> group : root.getChildren()) {
                for (TreeItem<Object> file : group.getChildren()) {
                    out.add(String.valueOf(file.getValue()));
                }
            }
            return out;
        });
    }

    @Test
    void filterNarrowsTheFileRows() throws Exception {
        GitPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        assertEquals(3, renderedPaths(p).size(), "all three files before filtering");

        FxTestSupport.runOnFx(() -> filterOf(p).setText("chang"));
        List<String> filtered = renderedPaths(p);
        assertEquals(1, filtered.size(), filtered.toString());
        assertTrue(filtered.get(0).contains("changed.txt"), filtered.toString());

        FxTestSupport.runOnFx(() -> filterOf(p).clear());
        assertEquals(3, renderedPaths(p).size(), "clearing restores every file");
    }

    @Test
    void filteringByGroupNameKeepsThatWholeGroup() throws Exception {
        GitPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        // "untracked" matches no path, but it names a group — list that group rather than nothing.
        FxTestSupport.runOnFx(() -> filterOf(p).setText("untracked"));
        List<String> filtered = renderedPaths(p);
        assertEquals(1, filtered.size(), filtered.toString());
        assertTrue(filtered.get(0).contains("new.txt"), filtered.toString());
    }

    @Test
    void aFilterHidingTheStagedFileLeavesCommitEnabled() throws Exception {
        GitPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        FxTestSupport.runOnFx(() -> filterOf(p).setText("new.txt")); // hides staged.txt
        Button commit = FxTestSupport.field(p, "commitButton");
        assertFalse(
                FxTestSupport.callOnFx(commit::isDisable),
                "the commit affordances read the full status, not the filtered view");
    }

    @Test
    void aFilterMatchingNothingKeepsTheFilterBarReachable() throws Exception {
        GitPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        FxTestSupport.runOnFx(() -> filterOf(p).setText("nothing-matches-this"));
        assertTrue(renderedPaths(p).isEmpty(), "no rows survive the filter");
        // The bar must stay on screen, or there is no way left to clear the filter that emptied the panel.
        assertTrue(
                FxTestSupport.callOnFx(() -> p.getChildren().contains(FxTestSupport.<HBox>field(p, "filterBar"))),
                "the filter bar survives an empty result");
    }

    @Test
    void ctrlNAndCtrlPMoveTheSelectionInsteadOfGrowingIt() throws Exception {
        GitPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        FxTestSupport.runOnFx(() -> tree(p).getSelectionModel().clearAndSelect(0));
        FxTestSupport.runOnFx(() -> pressCtrl(p, javafx.scene.input.KeyCode.N));
        // On a MULTIPLE-selection tree a plain select() would ADD the row; C-n must move, leaving one row.
        assertEquals(
                1,
                FxTestSupport.callOnFx(
                        () -> tree(p).getSelectionModel().getSelectedItems().size()),
                "C-n moves the selection, it does not grow it");
        assertEquals(
                1,
                (int) FxTestSupport.callOnFx(() -> tree(p).getSelectionModel().getSelectedIndex()),
                "C-n moved down one row");
        FxTestSupport.runOnFx(() -> pressCtrl(p, javafx.scene.input.KeyCode.P));
        assertEquals(
                0,
                (int) FxTestSupport.callOnFx(() -> tree(p).getSelectionModel().getSelectedIndex()),
                "C-p moves back up");
    }

    @Test
    void ctrlNFromTheFilterFieldMovesTheSelection() throws Exception {
        GitPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        FxTestSupport.runOnFx(() -> tree(p).getSelectionModel().clearAndSelect(0));
        // The shared FilterFieldNav binding: move the results without taking your hands off the filter.
        FxTestSupport.runOnFx(() -> filterOf(p)
                .fireEvent(new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        javafx.scene.input.KeyCode.N,
                        false,
                        true,
                        false,
                        false)));
        assertEquals(
                1,
                (int) FxTestSupport.callOnFx(() -> tree(p).getSelectionModel().getSelectedIndex()),
                "C-n in the filter field moves the tree selection");
        assertEquals(
                1,
                FxTestSupport.callOnFx(
                        () -> tree(p).getSelectionModel().getSelectedItems().size()),
                "and moves it rather than growing it");
    }

    /** Fires a Ctrl+&lt;code&gt; KEY_PRESSED at the panel's tree, as the scene would. */
    private static void pressCtrl(GitPanel p, javafx.scene.input.KeyCode code) {
        tree(p).fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, true, false, false));
    }

    @Test
    void treeIsMultiSelect() throws Exception {
        GitPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        selectAllFileRows(p);
        assertEquals(
                3,
                FxTestSupport.callOnFx(
                        () -> tree(p).getSelectionModel().getSelectedItems().size()),
                "three file rows stay selected at once");
    }

    @Test
    void stageSelectedStagesEveryNonStagedRowInOneCall() throws Exception {
        Recording rec = new Recording();
        GitPanel p = FxTestSupport.callOnFx(() -> new GitPanel(rec));
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        selectAllFileRows(p);

        assertTrue(FxTestSupport.callOnFx(p::stageSelected));
        assertEquals(1, rec.staged.size(), "one git invocation for the whole selection");
        // The already-staged row is not re-staged; the modified + untracked ones are.
        assertEquals(List.of("changed.txt", "new.txt"), rec.staged.get(0));
    }

    @Test
    void unstageSelectedUnstagesOnlyTheStagedRows() throws Exception {
        Recording rec = new Recording();
        GitPanel p = FxTestSupport.callOnFx(() -> new GitPanel(rec));
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        selectAllFileRows(p);

        assertTrue(FxTestSupport.callOnFx(p::unstageSelected));
        assertEquals(List.of(List.of("staged.txt")), rec.unstaged);
    }

    @Test
    void selectedActionsReportWhenTheSelectionHasNothingToDo() throws Exception {
        Recording rec = new Recording();
        GitPanel p = FxTestSupport.callOnFx(() -> new GitPanel(rec));
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        // Select the untracked row only: nothing to unstage there.
        FxTestSupport.runOnFx(() -> {
            TreeView<Object> t = tree(p);
            t.getSelectionModel().clearSelection();
            TreeItem<Object> untrackedGroup =
                    t.getRoot().getChildren().get(t.getRoot().getChildren().size() - 1);
            t.getSelectionModel().select(untrackedGroup.getChildren().get(0));
        });
        assertFalse(FxTestSupport.callOnFx(p::unstageSelected), "no staged row selected");
        assertTrue(rec.unstaged.isEmpty(), "and nothing is run");
        assertTrue(FxTestSupport.callOnFx(p::stageSelected), "but it can be staged");
    }

    @Test
    void groupRowsInTheSelectionAreIgnored() throws Exception {
        Recording rec = new Recording();
        GitPanel p = FxTestSupport.callOnFx(() -> new GitPanel(rec));
        FxTestSupport.runOnFx(() -> p.setStatus(mixedStatus()));
        // A Shift-range across a group boundary sweeps up the header row too — it must not break staging.
        FxTestSupport.runOnFx(() -> {
            TreeView<Object> t = tree(p);
            t.getSelectionModel().clearSelection();
            for (int i = 0; i < t.getExpandedItemCount(); i++) {
                t.getSelectionModel().select(i);
            }
        });
        assertTrue(FxTestSupport.callOnFx(p::stageSelected));
        assertEquals(List.of("changed.txt", "new.txt"), rec.staged.get(0));
    }

    @Test
    void notARepoHidesContent() throws Exception {
        GitPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setStatus(GitStatus.NOT_A_REPO));
        FxTestSupport.runOnFx(() -> p.setStatus(null)); // null is treated like NOT_A_REPO — no throw
        // The tree is not attached when there's no repo; the placeholder is shown instead.
        assertFalse(
                FxTestSupport.callOnFx(() -> p.getChildren().contains(tree(p))), "tree detached when not a repository");
    }
}

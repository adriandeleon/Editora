package com.editora.ui;

import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

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
        public void stage(String path) {}

        @Override
        public void unstage(String path) {}

        @Override
        public void discard(String path, boolean untracked) {}

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

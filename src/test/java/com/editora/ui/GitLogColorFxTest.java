package com.editora.ui;

import java.lang.reflect.Proxy;

import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.git.GitService.Commit;
import com.editora.git.GitService.CommitFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #562: the Git Log tool window colors its rows and uses real file icons. A changed-file row is
 * tinted by its git status ({@code .git-status-*}) and shows the file-type icon (not a generic sheet); a commit
 * row renders its short hash in the accent color ({@code .git-log-hash}).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitLogColorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static GitLogPanel.Actions noopActions() {
        return (GitLogPanel.Actions) Proxy.newProxyInstance(
                GitLogPanel.Actions.class.getClassLoader(), new Class[] {GitLogPanel.Actions.class}, (p, m, a) -> null);
    }

    @Test
    void changedFileRowIsStatusColoredWithARealIcon() throws Exception {
        boolean[] r = FxTestSupport.callOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(noopActions());
            ListView<CommitFile> files = FxTestSupport.field(panel, "files");
            ListCell<CommitFile> cell = files.getCellFactory().call(files);
            FxTestSupport.call(
                    cell,
                    "updateItem",
                    new Class[] {Object.class, boolean.class},
                    new CommitFile('A', "Foo.java", null),
                    false);
            boolean colored = cell.getStyleClass().contains("git-status-added");
            boolean hasIcon = cell.getGraphic() != null;
            return new boolean[] {colored, hasIcon};
        });
        assertTrue(r[0], "an added file row gets the git-status-added color class");
        assertTrue(r[1], "the row shows a file-type icon");
    }

    @Test
    void commitRowRendersTheShortHashInTheAccentColor() throws Exception {
        Node[] graphic = new Node[1];
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(noopActions());
            ListView<Commit> commits = FxTestSupport.field(panel, "commits");
            ListCell<Commit> cell = commits.getCellFactory().call(commits);
            FxTestSupport.call(
                    cell,
                    "updateItem",
                    new Class[] {Object.class, boolean.class},
                    new Commit("abc1234def", "abc1234", "Fix the thing", "Ada", "2026-07-18"),
                    false);
            graphic[0] = cell.getGraphic();
        });

        TextFlow flow =
                assertInstanceOf(TextFlow.class, graphic[0], "commit row is a TextFlow (colored hash + subject)");
        Text hash = (Text) flow.getChildren().get(0);
        assertNotNull(hash);
        assertTrue(hash.getStyleClass().contains("git-log-hash"), "the short hash carries the git-log-hash class");
        assertTrue(hash.getText().startsWith("abc1234"), "the hash text is the short hash: " + hash.getText());
    }
}

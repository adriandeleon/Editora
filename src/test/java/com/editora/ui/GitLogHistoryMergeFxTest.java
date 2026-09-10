package com.editora.ui;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ListView;

import com.editora.git.GitService.Commit;
import com.editora.git.GitService.CommitFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** File-filtered Git history opens an editable revision-to-working comparison by default. */
@Tag("fx")
class GitLogHistoryMergeFxTest {

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void fileHistoryUsesWorkingComparisonWhileRepositoryLogKeepsCommitDiff() throws Exception {
        AtomicReference<String> invoked = new AtomicReference<>();
        GitLogPanel.Actions actions = (GitLogPanel.Actions) Proxy.newProxyInstance(
                GitLogPanel.Actions.class.getClassLoader(),
                new Class[] {GitLogPanel.Actions.class},
                (proxy, method, args) -> {
                    invoked.set(method.getName());
                    return null;
                });
        Commit commit = new Commit("abc1234def", "abc1234", "Change sample", "Ada", "2026-09-10");
        CommitFile file = new CommitFile('M', "sample.txt", null);

        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = new GitLogPanel(actions);
            panel.setLog(List.of(commit), "sample.txt");
            select(panel, file);
            FxTestSupport.call(panel, "openSelectedFile", new Class<?>[] {});
            assertEquals("compareFileWithWorking", invoked.get());

            panel.setLog(List.of(commit), null);
            select(panel, file);
            FxTestSupport.call(panel, "openSelectedFile", new Class<?>[] {});
            assertEquals("openFileDiff", invoked.get());
        });
    }

    @SuppressWarnings("unchecked")
    private static void select(GitLogPanel panel, CommitFile file) {
        ListView<Commit> commits = FxTestSupport.field(panel, "commits");
        ListView<CommitFile> files = FxTestSupport.field(panel, "files");
        commits.getSelectionModel().selectFirst();
        panel.setCommitFiles(List.of(file));
        files.getSelectionModel().selectFirst();
    }
}

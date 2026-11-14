package com.editora.ui;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.git.GitLog;
import com.editora.git.GitLog.Entry;
import com.editora.git.GitService.CommitFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Git Log's changed-file commands ({@code git.log.showFileDiff}, {@code git.log.compareFileWithWorking},
 * {@code git.log.openFile}, {@code git.log.fileHistoryOfFile}, {@code git.log.copyFilePath}): what each asks the
 * window to do for the selected commit's selected file, and, in a file's history, for that file.
 */
@Tag("fx")
class GitLogFileCommandsFxTest {

    private static final Entry COMMIT = new Entry("abc1234def", "abc1234", "Change sample", "Ada", "2026-09-10");
    private static final CommitFile CHANGED = new CommitFile('M', "src/A.java", "src/Old.java");
    private static final CommitFile GONE = new CommitFile('D', "src/Gone.java", null);
    private static final CommitFile FOLLOWED = new CommitFile('M', "sample.txt", null);

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private final List<String> calls = new ArrayList<>();

    private GitLogPanel panel() {
        GitLogPanel.Actions actions = (GitLogPanel.Actions) Proxy.newProxyInstance(
                GitLogPanel.Actions.class.getClassLoader(), new Class<?>[] {GitLogPanel.Actions.class}, (p, m, a) -> {
                    calls.add(m.getName() + (a == null ? "" : " " + Arrays.toString(a)));
                    return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
        return new GitLogPanel(actions);
    }

    @SuppressWarnings("unchecked")
    private static ListView<CommitFile> files(GitLogPanel panel) {
        return (ListView<CommitFile>) FxTestSupport.<ListView<?>>field(panel, "files");
    }

    @SuppressWarnings("unchecked")
    private static ListView<Entry> commits(GitLogPanel panel) {
        return (ListView<Entry>) FxTestSupport.<ListView<?>>field(panel, "commits");
    }

    private static boolean[] runAll(GitLogPanel panel) {
        return new boolean[] {
            panel.showSelectedFileDiff(),
            panel.compareSelectedFileWithWorking(),
            panel.openSelectedChangedFile(),
            panel.selectedFileHistory(),
            panel.copySelectedFilePath()
        };
    }

    @Test
    void theFileCommandsActOnTheSelectedFileOfTheSelectedCommit() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitLogPanel panel = panel();
            panel.setLog(List.of(COMMIT), null);
            calls.clear();
            assertTrue(Arrays.equals(new boolean[5], runAll(panel)), "no commit, no file: nothing to act on");

            commits(panel).getSelectionModel().selectFirst();
            panel.setCommitFiles(List.of(CHANGED, GONE));
            calls.clear();
            assertTrue(Arrays.equals(new boolean[5], runAll(panel)), "a commit but no file chosen");
            assertEquals(List.of(), calls);

            files(panel).getSelectionModel().select(CHANGED);
            assertTrue(Arrays.equals(new boolean[] {true, true, true, true, true}, runAll(panel)));
            assertEquals(
                    List.of(
                            "openFileDiff [abc1234def, src/A.java, src/Old.java]",
                            "compareFileWithWorking [abc1234def, src/A.java]",
                            "openFile [src/A.java]",
                            "showFileHistory [src/A.java]",
                            "copyPath [src/A.java]"),
                    calls);

            calls.clear();
            files(panel).getSelectionModel().select(GONE);
            assertFalse(panel.openSelectedChangedFile(), "a deleted file has no copy to open, as its row is grayed");
            assertTrue(panel.copySelectedFilePath());
            assertEquals(List.of("copyPath [src/Gone.java]"), calls);
        });
    }

    @Test
    void inAFilesHistoryTheFileIsTheFollowedOneUnlessTheFilesListHasTheKeyboard() throws Exception {
        Stage[] stage = new Stage[1];
        GitLogPanel[] panel = new GitLogPanel[1];
        FxTestSupport.runOnFx(() -> {
            panel[0] = panel();
            GitLog.Page page = new GitLog.Page(List.of(COMMIT), false, Map.of(COMMIT.hash(), FOLLOWED), "");
            panel[0].setLog(page, "sample.txt", "main");
            commits(panel[0]).getSelectionModel().selectFirst();
            panel[0].setCommitFiles(List.of(CHANGED, FOLLOWED));
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new StackPane(panel[0]), 800, 500));
            stage[0].show();
        });
        try {
            FxTestSupport.runOnFx(() -> {
                files(panel[0]).getSelectionModel().select(CHANGED); // picked, but the keyboard is elsewhere
                commits(panel[0]).requestFocus();
                calls.clear();
                assertTrue(panel[0].showSelectedFileDiff());
                assertEquals(List.of("openFileDiff [abc1234def, sample.txt, null]"), calls, "the followed file");
            });
            FxTestSupport.runOnFx(() -> files(panel[0]).requestFocus());
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertTrue(files(panel[0]).isFocused());
                calls.clear();
                assertTrue(panel[0].compareSelectedFileWithWorking());
                assertEquals(
                        List.of("compareFileWithWorking [abc1234def, src/A.java]"),
                        calls,
                        "the file picked in the list that has the keyboard");
            });
        } finally {
            FxTestSupport.runOnFx(() -> stage[0].close());
        }
    }
}

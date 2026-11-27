package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code intelligence on a diff's file side, against a real window and repository: the caret there names the file's
 * own line (filler rows and the other side name none), and acting "from the file" opens it at that place first.
 */
@Tag("fx")
class DiffCodeIntelFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> c) {
        try {
            return FxTestSupport.callOnFx(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theFileSideNamesTheFilesLineAndActsFromTheFileThere(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("letters.txt", "a\nb\nc\nd\n");
        repo.commitAll("init");
        repo.write("letters.txt", "a\nnew\nb\nc\nd\n"); // a line added: the left side has a filler row there
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            GitCoordinator git = FxTestSupport.field(fx.controller, "git");
            GitStatus status = new GitStatus(true, "main", null, 0, 0, List.of());
            FxTestSupport.runOnFx(
                    () -> git.applyState(new GitService.RepoState(repo.root, status, Map.of(), Map.of())));
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(() -> diff.diffGitPanelFile("letters.txt", false));
            Await.until(() -> onFx(() -> new DiffOpsHost(fx.controller).activeDiffPane()) != null, "the diff opened");
            DiffViewerPane pane = onFx(() -> new DiffOpsHost(fx.controller).activeDiffPane());
            assertEquals(file.toRealPath(), onFx(pane::localFile).toRealPath());

            assertNull(onFx(() -> fx.controller.diffIntel.at()), "the keyboard is not on the file side yet");
            FxTestSupport.runOnFx(() -> {
                var area = pane.localArea();
                area.requestFocus();
                area.moveTo(3, 1); // row 3 of the right side: "c"
            });
            Await.until(() -> onFx(() -> pane.localArea().isFocused()), "the file side has the keyboard");
            DiffCodeIntel.At at = onFx(() -> fx.controller.diffIntel.at());
            assertEquals(3, at.line(), "the file's own line (0-based)");
            assertEquals(1, at.column());

            boolean[] ran = {false};
            assertTrue(onFx(() -> fx.controller.diffIntel.fromFile(() -> ran[0] = true)));
            Await.until(() -> ran[0], "the action ran from the file");
            EditorBuffer b =
                    onFx(() -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
            assertEquals(file.toRealPath(), b.getPath().toRealPath());
            assertEquals(3, onFx(() -> b.getArea().getCurrentParagraph()), "at the same line");
            assertEquals(1, onFx(() -> b.getArea().getCaretColumn()));
        } finally {
            fx.dispose();
        }
    }
}

package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Clicking the blame annotation of a line whose commit predates a move of the file. Blame follows the rename;
 * the commit diff must be read at the file's path <em>in that commit</em> — at the current path both blobs
 * are missing and the tab showed two empty sides ("No differences") for a commit that added the whole file.
 */
@Tag("fx")
class GitBlameRenamedFileFxTest {

    private static final String CREATED = "line one\nline two\nline three\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theCommitDiffOfAPreRenameLineShowsThatCommitsText(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("src/a/Foo.txt", CREATED);
        repo.commitAll("c1 create");
        Files.createDirectories(repo.root.resolve("src/b"));
        repo.git("mv", "src/a/Foo.txt", "src/b/Foo.txt");
        repo.commitAll("c2 move");
        Path file = repo.write("src/b/Foo.txt", "line one\nline two CHANGED\nline three\n");
        repo.commitAll("c3 edit");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            fx.shared.getSettings().setGitSupport(true);
            fx.shared.getSettings().setGitBlameInline(true);
            GitCoordinator git = FxTestSupport.field(fx.controller, "git");
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            FxTestSupport.runOnFx(() -> {
                git.applySupport();
                fx.controller.openAndNavigate(file, 0);
            });
            await(
                    "the file's tab",
                    () -> buffer(area) != null && file.equals(buffer(area).getPath()));
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> buffer(area));
            FxTestSupport.runOnFx(git::refresh);
            await("blame", () -> buffer.blameHashAt(0) != null);

            FxTestSupport.runOnFx(() -> git.onGutterBlameClick(buffer, 0)); // "line one": written by c1, in src/a
            await("the commit diff tab", () -> area.selectedTab().getUserData() instanceof DiffViewerPane);
            DiffViewerPane pane = (DiffViewerPane)
                    FxTestSupport.callOnFx(() -> area.selectedTab().getUserData());
            await(
                    "the commit side to load",
                    () -> FxTestSupport.<String>field(pane, "rightText") != null
                            && !FxTestSupport.<String>field(pane, "rightText").isEmpty());

            assertEquals(CREATED, FxTestSupport.<String>field(pane, "rightText"), "src/a/Foo.txt as c1 created it");
        }
    }

    private static EditorBuffer buffer(EditorArea area) {
        Tab tab = area.selectedTab();
        return tab != null && tab.getUserData() instanceof EditorBuffer b ? b : null;
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!FxTestSupport.callOnFx(condition)) {
            assertFalse(System.nanoTime() > deadline, "timed out waiting for " + what);
            Thread.sleep(25);
        }
    }
}

package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffEditableResultIntegrationFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        fx.shared.getSettings().setGitSupport(true);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void appliesDraftThroughUndoableBackgroundBufferWithoutSaving() throws Exception {
        Path repo = Files.createTempDirectory("editora-diff-result");
        Path file = repo.resolve("sample.txt");
        Files.writeString(file, "base\n");
        git(repo, "init", "-q");
        git(repo, "add", "sample.txt");
        git(repo, "-c", "user.email=t@e.st", "-c", "user.name=Test", "commit", "-q", "-m", "init");
        Files.writeString(file, "working\n");

        Object git = FxTestSupport.field(fx.controller, "git");
        Object diff = FxTestSupport.field(fx.controller, "diffCoordinator");
        Object ops = FxTestSupport.field(diff, "ops");
        applyState(git, repo(repo));
        int previousPanes = paneCount(ops);
        FxTestSupport.runOnFx(() -> FxTestSupport.call(diff, "diffPathVsHead", new Class<?>[] {Path.class}, file));

        DiffViewerPane pane = awaitPaneAfter(ops, previousPanes);
        assertNotNull(pane);
        FxTestSupport.runOnFx(() -> {
            pane.toggleResultEditing();
            CodeArea result = FxTestSupport.field(pane, "resultArea");
            result.replaceText("draft\n");
            ((javafx.scene.control.Button) FxTestSupport.field(pane, "applyResultButton")).fire();
        });

        EditorBuffer buffer = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(ops, "openBufferFor", new Class<?>[] {Path.class}, file));
        assertNotNull(buffer, "applying the result should open the target in a background editor buffer");
        assertEquals("draft\n", FxTestSupport.callOnFx(buffer::text));
        assertEquals("working\n", Files.readString(file), "Apply Result must not save implicitly");

        FxTestSupport.runOnFx(() -> ((javafx.scene.control.Button) FxTestSupport.field(pane, "undoButton")).fire());
        assertEquals("working\n", FxTestSupport.callOnFx(buffer::text));
    }

    @Test
    void rejectsDraftWhenTheLocalFileChangedAfterTheDiffOpened() throws Exception {
        Path repo = Files.createTempDirectory("editora-diff-result-stale");
        Path file = repo.resolve("sample.txt");
        Files.writeString(file, "base\n");
        git(repo, "init", "-q");
        git(repo, "add", "sample.txt");
        git(repo, "-c", "user.email=t@e.st", "-c", "user.name=Test", "commit", "-q", "-m", "init");
        Files.writeString(file, "working\n");

        Object git = FxTestSupport.field(fx.controller, "git");
        Object diff = FxTestSupport.field(fx.controller, "diffCoordinator");
        Object ops = FxTestSupport.field(diff, "ops");
        applyState(git, repo(repo));
        int previousPanes = paneCount(ops);
        FxTestSupport.runOnFx(() -> FxTestSupport.call(diff, "diffPathVsHead", new Class<?>[] {Path.class}, file));

        DiffViewerPane pane = awaitPaneAfter(ops, previousPanes);
        assertNotNull(pane);
        FxTestSupport.runOnFx(() -> {
            pane.toggleResultEditing();
            ((CodeArea) FxTestSupport.field(pane, "resultArea")).replaceText("draft\n");
        });
        Files.writeString(file, "external\n");
        FxTestSupport.runOnFx(
                () -> ((javafx.scene.control.Button) FxTestSupport.field(pane, "applyResultButton")).fire());

        assertEquals("external\n", Files.readString(file));
        assertTrue(pane.hasDirtyResult(), "a rejected draft must remain available to the user");
        EditorBuffer buffer = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(ops, "openBufferFor", new Class<?>[] {Path.class}, file));
        assertEquals(null, buffer, "stale rejection must happen before opening or mutating an editor buffer");
    }

    private void applyState(Object git, GitService.RepoState state) throws Exception {
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(git, "applyState", new Class<?>[] {GitService.RepoState.class}, state));
    }

    private static GitService.RepoState repo(Path root) {
        return new GitService.RepoState(root, new GitStatus(true, "main", null, 0, 0, List.of()), Map.of(), Map.of());
    }

    private static int paneCount(Object ops) throws Exception {
        return FxTestSupport.callOnFx(
                () -> ((List<?>) FxTestSupport.call(ops, "openDiffPanes", new Class<?>[] {})).size());
    }

    private static DiffViewerPane awaitPaneAfter(Object ops, int previousPanes) throws Exception {
        for (int i = 0; i < 100; i++) {
            DiffViewerPane pane = FxTestSupport.callOnFx(() -> {
                List<?> panes = (List<?>) FxTestSupport.call(ops, "openDiffPanes", new Class<?>[] {});
                return panes.size() <= previousPanes ? null : (DiffViewerPane) panes.get(panes.size() - 1);
            });
            if (pane != null) {
                return pane;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }
}

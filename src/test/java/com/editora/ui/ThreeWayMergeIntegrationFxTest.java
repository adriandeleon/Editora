package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Tab;

import com.editora.diff.ConflictParser.Conflict;
import com.editora.diff.ConflictParser.ConflictFile;
import com.editora.diff.ConflictParser.ConflictSegment;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("fx")
class ThreeWayMergeIntegrationFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void resolverUsesGitIndexStagesEvenWhenWorkingFileHasTwoWayMarkers() throws Exception {
        assertResolverUsesStages(false);
    }

    @Test
    void resolverUsesGitIndexStagesAfterMarkersWereEditedOut() throws Exception {
        assertResolverUsesStages(true);
    }

    private static void assertResolverUsesStages(boolean removeMarkers) throws Exception {
        Path repo = Files.createTempDirectory("editora-three-way");
        Path file = repo.resolve("story.txt");
        git(repo, true, "init", "-q");
        git(repo, true, "config", "user.email", "editora-test@example.invalid");
        git(repo, true, "config", "user.name", "Editora Test");
        Files.writeString(file, "before\nbase\nafter\n");
        git(repo, true, "add", "story.txt");
        git(repo, true, "commit", "-q", "-m", "base");
        git(repo, true, "checkout", "-q", "-b", "incoming");
        Files.writeString(file, "before\ntheirs\nafter\n");
        git(repo, true, "commit", "-q", "-am", "theirs");
        git(repo, true, "checkout", "-q", "-b", "local", "HEAD~1");
        Files.writeString(file, "before\nours\nafter\n");
        git(repo, true, "commit", "-q", "-am", "ours");
        git(repo, false, "merge", "incoming");

        // Git's default merge style writes no ||||||| base marker into the worktree file.
        String markerText = Files.readString(file);
        org.junit.jupiter.api.Assertions.assertFalse(markerText.contains("|||||||"));
        if (removeMarkers) {
            Files.writeString(file, "before\nmanual work in progress\nafter\n");
        }

        Path config = Files.createTempDirectory("editora-three-way-config");
        FxWindowFixture fx = FxWindowFixture.create(
                config,
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(file, 0, 0)),
                true,
                controller -> {});
        try {
            EditorBuffer buffer = awaitActiveBuffer(fx.controller, removeMarkers ? "manual work" : "<<<<<<<");
            assertNotNull(buffer);

            Object gitCoordinator = FxTestSupport.field(fx.controller, "git");
            GitStatus status = new GitStatus(true, "local", null, 0, 0, List.of());
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    gitCoordinator,
                    "applyState",
                    new Class<?>[] {GitService.RepoState.class},
                    new GitService.RepoState(repo, status, Map.of(), Map.of())));

            Object diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(diff, "resolveConflicts"));
            MergeViewerPane pane = awaitMergePane(fx.controller);
            assertNotNull(pane);

            ConflictFile conflictFile = FxTestSupport.field(pane, "file");
            Conflict conflict = ((ConflictSegment) conflictFile.segments().get(1)).conflict();
            assertEquals(List.of("base"), conflict.base());
            assertEquals(List.of("ours"), conflict.ours());
            assertEquals(List.of("theirs"), conflict.theirs());
        } finally {
            fx.dispose();
        }
    }

    private static EditorBuffer awaitActiveBuffer(MainController controller, String expectedText) throws Exception {
        EditorArea area = FxTestSupport.field(controller, "editorArea");
        for (int i = 0; i < 120; i++) {
            Object data = FxTestSupport.callOnFx(() -> {
                Tab selected = area.selectedTab();
                return selected == null ? null : selected.getUserData();
            });
            if (data instanceof EditorBuffer buffer && buffer.text().contains(expectedText)) {
                return buffer;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static MergeViewerPane awaitMergePane(MainController controller) throws Exception {
        EditorArea area = FxTestSupport.field(controller, "editorArea");
        for (int i = 0; i < 120; i++) {
            Object data = FxTestSupport.callOnFx(() -> {
                Tab selected = area.selectedTab();
                return selected == null ? null : selected.getUserData();
            });
            if (data instanceof MergeViewerPane pane) {
                return assertInstanceOf(MergeViewerPane.class, data);
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static String git(Path dir, boolean expectSuccess, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (expectSuccess && exit != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }
}

package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Tab;

import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("fx")
class GitMultiFileReviewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void opensWorkingAndStagedRepositoryReviewsWithTheRightFiles() throws Exception {
        Path repo = Files.createTempDirectory("editora-git-review");
        Path staged = repo.resolve("staged.txt");
        Path working = repo.resolve("working.txt");
        Path untracked = repo.resolve("new.txt");
        Files.writeString(staged, "old staged\n");
        Files.writeString(working, "old working\n");
        git(repo, "init", "-q");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=t@e.st", "-c", "user.name=Test", "commit", "-q", "-m", "base");
        Files.writeString(staged, "new staged\n");
        git(repo, "add", "staged.txt");
        Files.writeString(working, "new working\n");
        Files.writeString(untracked, "brand new\n");

        FxWindowFixture fx = FxWindowFixture.create(
                Files.createTempDirectory("editora-git-review-config"),
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(working, 0, 0)),
                true,
                controller -> {});
        try {
            GitStatus status = new GitStatus(
                    true,
                    "main",
                    null,
                    0,
                    0,
                    List.of(
                            new FileEntry("staged.txt", 'M', '.', null),
                            new FileEntry("working.txt", '.', 'M', null),
                            new FileEntry("new.txt", '?', '?', null)));
            Object gitCoordinator = FxTestSupport.field(fx.controller, "git");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    gitCoordinator,
                    "applyState",
                    new Class<?>[] {GitService.RepoState.class},
                    new GitService.RepoState(repo, status, Map.of(), Map.of())));

            Object diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(diff, "reviewGitChanges", new Class<?>[] {boolean.class}, false));
            PatchReviewPane workingReview = awaitReview(fx.controller, null);
            assertNotNull(workingReview);
            assertEquals(
                    List.of("working.txt", "new.txt"),
                    entries(workingReview).stream()
                            .map(PatchReviewPane.Entry::label)
                            .toList());
            assertEquals(
                    List.of("M", "?"),
                    entries(workingReview).stream()
                            .map(PatchReviewPane.Entry::status)
                            .toList());

            // A non-project fixture has no repository context while its review tab is selected; restore the
            // same snapshot before exercising the other entry point (a normal project window retains it).
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    gitCoordinator,
                    "applyState",
                    new Class<?>[] {GitService.RepoState.class},
                    new GitService.RepoState(repo, status, Map.of(), Map.of())));
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(diff, "reviewGitChanges", new Class<?>[] {boolean.class}, true));
            PatchReviewPane stagedReview = awaitReview(fx.controller, workingReview);
            assertNotNull(stagedReview);
            assertEquals(
                    List.of("staged.txt"),
                    entries(stagedReview).stream()
                            .map(PatchReviewPane.Entry::label)
                            .toList());
        } finally {
            fx.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PatchReviewPane.Entry> entries(PatchReviewPane pane) {
        return FxTestSupport.field(pane, "entries");
    }

    private static PatchReviewPane awaitReview(MainController controller, PatchReviewPane previous) throws Exception {
        EditorArea area = FxTestSupport.field(controller, "editorArea");
        for (int i = 0; i < 120; i++) {
            Object data = FxTestSupport.callOnFx(() -> {
                Tab selected = area.selectedTab();
                return selected == null ? null : selected.getUserData();
            });
            if (data instanceof PatchReviewPane review && review != previous) {
                return review;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static String git(Path dir, String... args) throws Exception {
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
        return output;
    }
}

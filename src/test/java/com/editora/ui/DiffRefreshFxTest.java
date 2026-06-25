package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.git.GitService;
import com.editora.git.GitStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the diff-viewer "HEAD pane goes blank on refresh" bug: a {@code diff.vsHead} tab's
 * left (HEAD) side was re-fetched against the <em>live</em> {@code git.repoRoot()}, which goes null while a
 * (non-buffer) diff tab is the active tab in a No-Project window (the Git state machine applies
 * {@link GitService.RepoState#NONE} when there's no active buffer + no project) — so the HEAD re-fetch used a
 * null root and returned {@code ""}, blanking the left pane and rendering the whole working side as all-added.
 * The fix captures the repo root at open time and closes over it; this test pins that by opening a real-git
 * vs-HEAD diff, dropping the repo context to NONE, refreshing, and asserting the HEAD side still has content.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffRefreshFxTest {

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
    void headSideSurvivesRefreshWhenRepoContextLost() throws Exception {
        // A real git repo: file committed as HEAD-CONTENT, then changed on disk so the diff is non-empty.
        Path repo = Files.createTempDirectory("editora-diff-refresh");
        Path file = repo.resolve("sample.txt");
        Files.writeString(file, "HEAD-CONTENT\n");
        git(repo, "init", "-q");
        git(repo, "add", "sample.txt");
        git(repo, "-c", "user.email=t@e.st", "-c", "user.name=Test", "commit", "-q", "-m", "init");
        Files.writeString(file, "WORKING-CONTENT\n");

        Object git = FxTestSupport.field(fx.controller, "git");
        Object diff = FxTestSupport.field(fx.controller, "diffCoordinator");
        Object ops = FxTestSupport.field(diff, "ops");

        // Point the Git state machine at the real repo (so reportIfNoRepo passes + repoRoot is set), then open
        // the vs-HEAD diff. git show runs off-thread and posts back to FX, so we poll for the pane.
        applyState(git, repo(repo, "main", null));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(diff, "diffPathVsHead", new Class<?>[] {Path.class}, file));

        Object pane = awaitPaneWithLeftText(ops);
        assertNotNull(pane, "a diff pane should be created");
        String before = FxTestSupport.field(pane, "leftText");
        assertTrue(before != null && before.contains("HEAD-CONTENT"), "HEAD side renders before refresh: " + before);

        // The regression trigger: a diff tab becomes the active tab in a No-Project window → repoRoot is nulled.
        applyState(git, GitService.RepoState.NONE);
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(diff, "refreshOpenDiffs"));

        // Give an (erroneous) async re-fetch time to land, then assert the HEAD side still has its content.
        settle(900);
        String after = FxTestSupport.field(pane, "leftText");
        assertTrue(
                after != null && !after.isBlank() && after.contains("HEAD-CONTENT"),
                "HEAD side must survive a refresh after the repo context is lost, was: [" + after + "]");
    }

    // --- helpers ---

    private void applyState(Object git, GitService.RepoState state) throws Exception {
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(git, "applyState", new Class<?>[] {GitService.RepoState.class}, state));
    }

    private static GitService.RepoState repo(Path root, String branch, String upstream) {
        GitStatus status = new GitStatus(true, branch, upstream, 0, 0, List.of());
        return new GitService.RepoState(root, status, Map.of(), Map.of());
    }

    /** Polls (off-FX) for the first open diff pane to exist with a non-null leftText, up to ~5s. */
    private Object awaitPaneWithLeftText(Object ops) throws Exception {
        for (int i = 0; i < 100; i++) {
            Object pane = FxTestSupport.callOnFx(() -> {
                List<?> panes = (List<?>) FxTestSupport.call(ops, "openDiffPanes", new Class<?>[] {});
                return panes.isEmpty() ? null : panes.get(0);
            });
            if (pane != null && FxTestSupport.field(pane, "leftText") != null) {
                return pane;
            }
            Thread.sleep(50);
        }
        return null;
    }

    /** Lets queued FX work (an async re-fetch + Platform.runLater) drain for {@code millis}. */
    private void settle(long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            FxTestSupport.runOnFx(() -> {});
            Thread.sleep(50);
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed: " + new String(out, StandardCharsets.UTF_8));
        }
    }
}

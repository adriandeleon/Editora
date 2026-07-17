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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for #435: the diff viewer force-decoded git blobs as UTF-8, so a Latin-1 (or UTF-16)
 * tracked file's HEAD side rendered as mojibake and produced a spurious whole-file "change" against the
 * (correctly-decoded) working side. The fix fetches the blob's raw bytes ({@code GitService.showBytes}) and
 * decodes it with the same charset resolution the editor uses (BOM / {@code .editorconfig} charset). Here a
 * real git repo commits a Latin-1 file with an {@code .editorconfig} of {@code charset = latin1}; the opened
 * vs-HEAD diff's HEAD side must read the real accented text, not the U+FFFD mojibake UTF-8 would produce.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffCharsetFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        fx.shared.getSettings().setGitSupport(true);
        fx.shared.getSettings().setEditorConfigSupport(true);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void headSideDecodesLatin1BlobNotUtf8Mojibake() throws Exception {
        Path repo = Files.createTempDirectory("editora-diff-charset");
        Files.writeString(repo.resolve(".editorconfig"), "root = true\n[*]\ncharset = latin1\n");
        Path file = repo.resolve("notes.txt");
        // The committed blob is Latin-1: 'é' is byte 0xE9, which is an invalid UTF-8 start byte here.
        Files.write(file, "café\n".getBytes(StandardCharsets.ISO_8859_1));
        git(repo, "init", "-q");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=t@e.st", "-c", "user.name=Test", "commit", "-q", "-m", "init");
        // Change the working copy (still Latin-1) so the diff is non-empty.
        Files.write(file, "café modifié\n".getBytes(StandardCharsets.ISO_8859_1));

        Object git = FxTestSupport.field(fx.controller, "git");
        Object diff = FxTestSupport.field(fx.controller, "diffCoordinator");
        Object ops = FxTestSupport.field(diff, "ops");

        applyState(git, repo(repo, "main"));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(diff, "diffPathVsHead", new Class<?>[] {Path.class}, file));

        Object pane = awaitPaneWithLeftText(ops);
        assertNotNull(pane, "a diff pane should be created");
        String left = FxTestSupport.field(pane, "leftText");
        assertNotNull(left, "HEAD side text");
        assertTrue(left.contains("café"), "HEAD side must decode as Latin-1, was: [" + left + "]");
        assertFalse(left.contains("�"), "HEAD side must not contain the UTF-8 replacement char: [" + left + "]");

        // The working (right) side reads the same accented text — so the two sides agree on the encoding and
        // only the genuine edit differs (no spurious whole-file change).
        String right = FxTestSupport.field(pane, "rightText");
        assertNotNull(right, "working side text");
        assertTrue(right.contains("café modifié"), "working side must decode as Latin-1, was: [" + right + "]");
        assertFalse(right.contains("�"), "working side must not mojibake: [" + right + "]");
    }

    // --- helpers ---

    private void applyState(Object git, GitService.RepoState state) throws Exception {
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(git, "applyState", new Class<?>[] {GitService.RepoState.class}, state));
    }

    private static GitService.RepoState repo(Path root, String branch) {
        GitStatus status = new GitStatus(true, branch, null, 0, 0, List.of());
        return new GitService.RepoState(root, status, Map.of(), Map.of());
    }

    private Object awaitPaneWithLeftText(Object ops) throws Exception {
        for (int i = 0; i < 120; i++) {
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

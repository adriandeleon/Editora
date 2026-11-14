package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A git failure that arrives after its window has closed sets the status and opens no dialog: an orphan modal
 * alert outlived its window and was taken for the next dialog by every later test in the JVM (CI, PR 1401). It is
 * said in another open window instead — its status line and message log, as an unread error naming the window it came
 * from — or, with none open, in the Debug Log (HTML review 3, A204).
 */
@Tag("fx")
class GitErrorClosedWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aGitErrorAfterTheWindowClosedOpensNoDialog() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            Stage stage = FxTestSupport.field(w.fx.controller, "stage");
            FxTestSupport.runOnFx(stage::hide);
            List<String> logged = new CopyOnWriteArrayList<>();
            Handler handler = capture(logged);
            try {
                // On a hidden window the old code entered showAndWait and this call never returned.
                FxTestSupport.runOnFx(() -> w.git.gitError("push failed", "remote: denied"));
            } finally {
                Logger.getLogger(GitCoordinator.class.getName()).removeHandler(handler);
            }

            assertEquals(List.of(), dialogs(), "no orphan dialog");
            assertEquals(1, logged.size(), "no other window: the Debug Log has it");
            assertTrue(
                    logged.get(0).contains("push failed") && logged.get(0).contains("remote: denied"), logged.get(0));
        }
    }

    /**
     * The reviewer's case: a push the remote refuses after a couple of seconds, its window closed meanwhile, another
     * window open. Real git, a real remote, the window's real close.
     */
    @Test
    void aPushThatFailsAfterItsWindowClosedIsSaidInAnotherWindow(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            // Both repositories in a JUnit TempDir: its cleanup clears the read-only flag git puts on objects
            // (the window fixture's own folder cleanup cannot delete them on Windows).
            Path repo = Files.createDirectories(dir.resolve("project"));
            FxTestSupport.runOnFx(() -> {
                w.fx.shared.getSettings().setProjectSupport(true);
                w.fx.controller.setWindowContext(
                        w.fx.windowManager, new com.editora.config.Project("project", "project", repo.toString()));
            });
            Path bare = dir.resolve("remote.git");
            git(dir, "init", "-q", "--bare", bare.toString());
            git(repo, "init", "-q", "-b", "main");
            Files.writeString(repo.resolve("plain.txt"), "one\n");
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "add", "-A");
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");
            git(repo, "remote", "add", "origin", bare.toString());
            git(repo, "push", "-q", "-u", "origin", "main");
            Files.writeString(repo.resolve("plain.txt"), "two\n");
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "second");
            // The remote leaves a marker once git really runs, so the window closes mid-push, not before it starts.
            Path started = dir.resolve("push-started");
            git(
                    repo,
                    "config",
                    "remote.origin.receivepack",
                    "touch '" + started + "'; sleep 2; echo 'remote: rejected by policy' >&2; false");
            MainController other = FxTestSupport.callOnFx(() -> {
                w.fx.windowManager.newWindow();
                List<?> holders = FxTestSupport.field(w.fx.windowManager, "windows");
                return (MainController)
                        FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
            });
            w.open(repo.resolve("plain.txt"));

            FxTestSupport.runOnFx(w.git::gitPush);
            String pushing = tr("status.gitRunning", tr("gitlabel.push"));
            GitFeatureFx.await("the push to start", () -> w.messages().contains(pushing));
            GitFeatureFx.await("git to reach the remote", () -> Files.exists(started));
            Stage stage = FxTestSupport.field(w.fx.controller, "stage");
            FxTestSupport.runOnFx(() -> {
                stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                stage.close();
            });

            // "<summary> (in the window of project, now closed): <git's output>", in any language
            String marked = tr("status.git.closedWindowErrorDetail", "\u0001", "project", "\u0002");
            String from = marked.substring(marked.indexOf('\u0001') + 1, marked.indexOf('\u0002'));
            GitFeatureFx.await(
                    "the other window to say it",
                    () -> errors(other).stream()
                            .anyMatch(e -> e.contains(from) && e.contains("remote: rejected by policy")));
            assertEquals(List.of(), dialogs(), "no dialog, owned or not");
            FxTestSupport.runOnFx(() -> {
                MessageLog log = FxTestSupport.field(other.statusBar, "messageLog");
                assertTrue(log.unreadErrors() > 0, "an unread error marks the other window's log");
                assertTrue(other.stage.isShowing());
            });
        }
    }

    @Test
    void aClosedWindowsFailureReadsAsOneLine() {
        assertEquals(
                tr("status.git.closedWindowError", "Push failed", "site"),
                GitCoordinator.closedWindowError("Push failed", "  ", "site"));
        assertEquals(
                tr("status.git.closedWindowError", "Push failed", "site"),
                GitCoordinator.closedWindowError("Push failed", null, "site"));
        assertEquals(
                tr("status.git.closedWindowErrorDetail", "Push failed", "site", "remote: no · fatal: gone"),
                GitCoordinator.closedWindowError("Push failed", "remote: no\n  fatal: gone\n", "site"));
        String longDetail = "x".repeat(GitCoordinator.CLOSED_WINDOW_DETAIL_MAX + 50);
        String said = GitCoordinator.closedWindowError("Pull failed", longDetail, "site");
        assertTrue(said.endsWith("…"), said);
        assertTrue(said.length() < longDetail.length(), said);
    }

    private static List<String> errors(MainController c) {
        MessageLog log = FxTestSupport.field(c.statusBar, "messageLog");
        return log.entries().stream()
                .filter(e -> e.severity() == MessageLog.Severity.ERROR)
                .map(MessageLog.Entry::text)
                .toList();
    }

    private static List<Window> dialogs() throws Exception {
        return FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .filter(win -> win.getScene() != null && win.getScene().getRoot() instanceof DialogPane)
                .toList());
    }

    private static Handler capture(List<String> logged) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger.getLogger(GitCoordinator.class.getName()).addHandler(handler);
        return handler;
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
            throw new IllegalStateException(String.join(" ", command) + ": " + output);
        }
    }
}

package com.editora.ui;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Shared plumbing of the stash / blame / patch feature tests: a window with Git on, in a real repository. */
final class GitFeatureFx {

    final FxWindowFixture fx;
    final GitCoordinator git;
    final EditorArea area;

    private GitFeatureFx(FxWindowFixture fx, GitCoordinator git, EditorArea area) {
        this.fx = fx;
        this.git = git;
        this.area = area;
    }

    /** A window with Git (and blame) enabled. */
    static GitFeatureFx create(AsyncTestScope async) throws Exception {
        FxWindowFixture fx = async.own(FxWindowFixture.create());
        fx.shared.getSettings().setGitSupport(true);
        fx.shared.getSettings().setGitBlameInline(true);
        GitCoordinator git = FxTestSupport.field(fx.controller, "git");
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        FxTestSupport.runOnFx(git::applySupport);
        return new GitFeatureFx(fx, git, area);
    }

    /** Opens {@code file} as the active tab and waits until its repository is the active one. */
    EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        await("the tab of " + file.getFileName(), () -> active() != null && file.equals(active().getPath()));
        EditorBuffer buffer = FxTestSupport.callOnFx(this::active);
        FxTestSupport.runOnFx(git::refresh);
        await("the repository of " + file.getFileName(), () -> git.repoRoot() != null);
        return buffer;
    }

    /** The active tab's buffer, or {@code null} when the active tab is not an editor. Call on the FX thread. */
    EditorBuffer active() {
        Tab tab = area.selectedTab();
        return tab != null && tab.getUserData() instanceof EditorBuffer b ? b : null;
    }

    Object activeContent() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Tab tab = area.selectedTab();
            return tab == null ? null : tab.getUserData();
        });
    }

    /** A latch released when the status bar shows a message {@code expected} accepts. */
    CountDownLatch watchStatus(Predicate<String> expected) throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> {
            StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
            Label echo = FxTestSupport.field(statusBar, "echo");
            ChangeListener<String> listener = (observable, before, after) -> {
                if (after != null && expected.test(after)) {
                    seen.countDown();
                }
            };
            echo.textProperty().addListener(listener);
        });
        return seen;
    }

    static void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!FxTestSupport.callOnFx(condition)) {
            assertFalse(System.nanoTime() > deadline, "timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    /** Presses {@code code} in {@code target}, as the keyboard would. Call on the FX thread. */
    static void press(javafx.scene.Node target, KeyCode code) {
        javafx.event.Event.fireEvent(
                target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }
}

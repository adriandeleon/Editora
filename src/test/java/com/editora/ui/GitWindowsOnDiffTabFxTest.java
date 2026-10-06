package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Git Log and Commit tool windows open diff tabs. Such a tab is not an editor buffer, and "no buffer"
 * used to mean "no Git context": selecting the diff a window had just opened made it unavailable, which
 * closes it — so the log vanished on the double-click that was meant to browse it.
 */
@Tag("fx")
class GitWindowsOnDiffTabFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theGitWindowsStayAvailableWhileADiffTabIsSelected() throws Exception {
        Path dir = Files.createTempDirectory("editora-git-diff-tab");
        Path left = Files.writeString(dir.resolve("before.txt"), "one\n");
        Path right = Files.writeString(dir.resolve("after.txt"), "two\n");
        FxWindowFixture fx = FxWindowFixture.createDiff(dir, left, right, controller -> {});
        try {
            MainController controller = fx.controller;
            TabPane tabs = FxTestSupport.field(controller, "tabPane");
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (!FxTestSupport.callOnFx(() -> {
                Tab selected = tabs.getSelectionModel().getSelectedItem();
                return selected != null && selected.getUserData() instanceof DiffViewerPane;
            })) {
                assertTrue(Instant.now().isBefore(deadline), "the diff tab opened");
                Thread.sleep(20);
            }
            ToolWindowManager toolWindows = FxTestSupport.field(controller, "toolWindows");
            ToolWindow log = FxTestSupport.field(controller, "gitLogToolWindow");
            ToolWindow commit = FxTestSupport.field(controller, "commitToolWindow");
            Set<ToolWindow> unavailable = FxTestSupport.field(toolWindows, "unavailable");
            GitCoordinator.WindowOps gitOps = FxTestSupport.field(FxTestSupport.field(controller, "git"), "ops");

            FxTestSupport.runOnFx(() -> {
                // What Git's refresh reports for a repository, and what every tab selection re-derives.
                gitOps.setGitLogWindowAvailable(true);
                gitOps.setCommitWindowAvailable(true);
                FxTestSupport.call(controller, "updateBufferToolWindows", new Class<?>[] {});
                assertFalse(unavailable.contains(log), "Git Log stays usable beside the diff it opened");
                assertFalse(unavailable.contains(commit), "and so does the Commit window");

                gitOps.setGitLogWindowAvailable(false); // outside a repository they are still withdrawn
                assertTrue(unavailable.contains(log));
            });
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aTabWithNoGitContextStillHidesThem() {
        assertFalse(GitWindowGate.allows(null));
        Tab welcome = new Tab("Welcome");
        welcome.setUserData("welcome");
        assertFalse(GitWindowGate.allows(welcome));
    }
}

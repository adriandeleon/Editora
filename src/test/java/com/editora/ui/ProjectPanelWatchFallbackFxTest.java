package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.io.WatchServices;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the system refuses a file watcher (Linux: "User limit of inotify instances reached"), the project tree
 * used to stop following the disk without a word. It polls instead and still shows a file made outside Editora.
 */
@Tag("fx")
class ProjectPanelWatchFallbackFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aFileCreatedOutsideEditoraAppearsWhenTheSystemHasNoWatcherToGive(@TempDir Path root) throws Exception {
        WatchServices.Opener previous = WatchServices.setOpenerForTest(() -> {
            throw new IOException("User limit of inotify instances reached or too many open files");
        });
        ProjectPanel panel = null;
        try {
            Files.writeString(root.resolve("first.txt"), "1");
            panel = FxTestSupport.callOnFx(() -> {
                ProjectPanel p = new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false);
                p.setRoot(root);
                return p;
            });
            ProjectPanel shown = panel;
            WatchService ws = FxTestSupport.field(shown, "watchService");
            assertTrue(WatchServices.isPolling(ws), "a polling watcher stands in");

            Path made = Files.writeString(root.resolve("second.txt"), "2"); // by a terminal, git, another program
            long end = System.nanoTime() + 15_000_000_000L;
            boolean seen = false;
            while (!seen && System.nanoTime() < end) {
                seen = FxTestSupport.callOnFx(() -> {
                    TreeView<Path> tree = FxTestSupport.field(shown, "tree");
                    TreeItem<Path> top = tree.getRoot();
                    return top != null && top.getChildren().stream().anyMatch(i -> made.equals(i.getValue()));
                });
                if (!seen) {
                    Thread.sleep(50);
                }
            }
            assertTrue(seen, "the tree shows the new file");
        } finally {
            WatchServices.setOpenerForTest(previous);
            if (panel != null) {
                ProjectPanel p = panel;
                FxTestSupport.runOnFx(p::dispose);
            }
        }
    }
}

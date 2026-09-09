package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class ProjectPanelDirectoryLoadingFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void blockedDirectoryReadDoesNotBlockFxAndCannotOverwriteANewerRoot(@TempDir Path dir) throws Exception {
        Path oldRoot = Files.createDirectory(dir.resolve("old"));
        Files.writeString(oldRoot.resolve("old.txt"), "old");
        Path newRoot = Files.createDirectory(dir.resolve("new"));
        Files.writeString(newRoot.resolve("new.txt"), "new");

        ProjectPanel panel = FxTestSupport.callOnFx(() -> new ProjectPanel(p -> {}, (a, b) -> {}, p -> {}, p -> false));
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        panel.beforeDirectoryListForTest = () -> {
            if (first.compareAndSet(true, false)) {
                readStarted.countDown();
                try {
                    releaseRead.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        try {
            FxTestSupport.runOnFx(() -> panel.setRoot(oldRoot));
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));

            CountDownLatch heartbeat = new CountDownLatch(1);
            Platform.runLater(heartbeat::countDown);
            assertTrue(heartbeat.await(2, TimeUnit.SECONDS), "directory I/O must not block the FX thread");
            FxTestSupport.runOnFx(() -> panel.setRoot(newRoot));
            releaseRead.countDown();

            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            for (int i = 0; i < 100; i++) {
                boolean loaded = FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().stream()
                        .anyMatch(item -> item.getValue().equals(newRoot.resolve("new.txt"))));
                if (loaded) {
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals(newRoot, FxTestSupport.callOnFx(() -> tree.getRoot().getValue()));
            assertEquals(
                    java.util.List.of(newRoot.resolve("new.txt")),
                    FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().stream()
                            .map(i -> i.getValue())
                            .toList()));
        } finally {
            releaseRead.countDown();
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void asynchronousRefreshRestoresExpandedFoldersAndSelection(@TempDir Path root) throws Exception {
        Path sub = Files.createDirectory(root.resolve("sub"));
        Path file = Files.writeString(sub.resolve("file.txt"), "text");
        ProjectPanel panel = FxTestSupport.callOnFx(() -> new ProjectPanel(p -> {}, (a, b) -> {}, p -> {}, p -> false));
        try {
            FxTestSupport.runOnFx(() -> panel.setRoot(root));
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> subItem = awaitChild(tree.getRoot(), sub);
            FxTestSupport.runOnFx(() -> subItem.setExpanded(true));
            TreeItem<Path> fileItem = awaitChild(subItem, file);
            FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(fileItem));

            FxTestSupport.runOnFx(panel::refreshTree);

            for (int i = 0; i < 100; i++) {
                Path selected = FxTestSupport.callOnFx(() -> {
                    TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
                    return item == null ? null : item.getValue();
                });
                if (file.equals(selected)) {
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals(
                    file,
                    FxTestSupport.callOnFx(
                            () -> tree.getSelectionModel().getSelectedItem().getValue()));
            TreeItem<Path> refreshedSub = awaitChild(tree.getRoot(), sub);
            assertTrue(FxTestSupport.callOnFx(refreshedSub::isExpanded));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void revealContinuesAcrossAsynchronouslyLoadedDirectories(@TempDir Path root) throws Exception {
        Path sub = Files.createDirectory(root.resolve("sub"));
        Path nested = Files.createDirectory(sub.resolve("nested"));
        Path file = Files.writeString(nested.resolve("file.txt"), "text");
        ProjectPanel panel = FxTestSupport.callOnFx(() -> new ProjectPanel(p -> {}, (a, b) -> {}, p -> {}, p -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                panel.setRoot(root);
                panel.revealPath(file);
            });
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            for (int i = 0; i < 150; i++) {
                Path selected = FxTestSupport.callOnFx(() -> {
                    TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
                    return item == null ? null : item.getValue();
                });
                if (file.equals(selected)) {
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals(
                    file,
                    FxTestSupport.callOnFx(
                            () -> tree.getSelectionModel().getSelectedItem().getValue()));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    private static TreeItem<Path> awaitChild(TreeItem<Path> parent, Path path) throws Exception {
        for (int i = 0; i < 100; i++) {
            TreeItem<Path> match = FxTestSupport.callOnFx(() -> parent.getChildren().stream()
                    .filter(item -> path.equals(item.getValue()))
                    .findFirst()
                    .orElse(null));
            if (match != null) {
                return match;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("child did not load: " + path);
    }
}

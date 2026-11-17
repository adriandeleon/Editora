package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code file.save} through the real pipeline, with the real project watcher running: the atomic rename the
 * save ends with reaches the watcher as the file being created, and that used to rebuild the project tree and
 * mark the symbol index stale after every save.
 */
@Tag("fx")
class SaveIsNotAnExternalChangeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static <T> T await(Callable<T> probe) throws Exception {
        Object[] value = new Object[1];
        if (!Await.holdsWithin(() -> (value[0] = onFx(probe)) != null, Duration.ofSeconds(5))) {
            throw new AssertionError("timed out");
        }
        @SuppressWarnings("unchecked")
        T found = (T) value[0];
        return found;
    }

    @Test
    void savingAnOpenFileNeitherRefreshesTheTreeNorStalesTheIndex(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("Open.java"), "class Open {}\n");
            ProjectPanel panel = FxTestSupport.field(fx.controller, "projectPanel");
            IndexCoordinator index = FxTestSupport.field(fx.controller, "indexCoordinator");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> panel.setRoot(dir));
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> row = await(() -> tree.getRoot().getChildren().stream()
                    .filter(item -> file.equals(item.getValue()))
                    .findFirst()
                    .orElse(null));
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setPath(file);
                workflows.loadInto(b, file);
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
                return b;
            });
            await(() -> buffer.getContent().contains("Open") ? true : null);
            int trees = FxTestSupport.callOnFx(() -> panel.treeRefreshCountForTest);
            int lists = FxTestSupport.callOnFx(() -> panel.directoryListCountForTest);
            long stale = FxTestSupport.callOnFx(() -> FxTestSupport.<Long>field(index, "staleMarks"));
            PauseTransition debounce = FxTestSupport.field(panel, "watchDebounce");

            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "// edited\n"));
            FxTestSupport.runOnFx(() -> registry.run("file.save"));
            async.awaitWorker(FxTestSupport.<ExecutorService>field(workflows, "autoSaveExecutor"));
            async.awaitFx();
            assertEquals("// edited\nclass Open {}\n", Files.readString(file));
            // The watcher saw the write; let its debounced tick come and go.
            await(() -> debounce.getStatus() == Animation.Status.RUNNING ? true : null);
            await(() -> debounce.getStatus() == Animation.Status.STOPPED ? true : null);

            assertEquals(trees, FxTestSupport.callOnFx(() -> panel.treeRefreshCountForTest), "no tree rebuild");
            assertEquals(lists, FxTestSupport.callOnFx(() -> panel.directoryListCountForTest), "no re-listing");
            assertEquals(
                    stale,
                    FxTestSupport.callOnFx(() -> FxTestSupport.<Long>field(index, "staleMarks")),
                    "the index was not marked stale");
            assertSame(
                    row,
                    FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().stream()
                            .filter(item -> file.equals(item.getValue()))
                            .findFirst()
                            .orElse(null)));
        }
    }

    /** {@code probe}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> probe) {
        try {
            return FxTestSupport.callOnFx(probe);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

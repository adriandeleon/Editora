package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.ui.ProjectPanel.FsChange;
import com.editora.ui.ProjectPanel.FsKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Any file event in a watched folder used to rebuild the whole project tree on the FX thread — a new
 * {@code TreeItem} per entry of every expanded directory — and Editora's own saves counted as such events.
 * The watcher now re-lists the one directory whose entries changed and keeps the rows that are still right.
 */
@Tag("fx")
class ProjectPanelIncrementalRefreshFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static ProjectPanel panel(Path root, AtomicInteger external) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ProjectPanel p = new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false);
            p.setOnExternalChange(external::incrementAndGet);
            p.setRoot(root);
            return p;
        });
    }

    private static <T> T await(Callable<T> probe) throws Exception {
        for (int i = 0; i < 250; i++) {
            T value = FxTestSupport.callOnFx(probe::call);
            if (value != null) {
                return value;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }

    private static TreeItem<Path> child(TreeItem<Path> parent, Path path) {
        return parent.getChildren().stream()
                .filter(item -> path.equals(item.getValue()))
                .findFirst()
                .orElse(null);
    }

    /** Hands the debounce handler one synthetic watcher batch, as the watch loop would. */
    private static void deliver(ProjectPanel panel, Path dir, List<FsChange> batch) {
        ProjectWatchChanges changes = FxTestSupport.field(panel, "watchChanges");
        changes.add(dir, batch, false);
        PauseTransition debounce = FxTestSupport.field(panel, "watchDebounce");
        debounce.getOnFinished().handle(new ActionEvent());
    }

    @Test
    void anExternalCreateRelistsOnlyItsFolderAndKeepsEveryOtherRow(@TempDir Path root) throws Exception {
        Path one = Files.createDirectory(root.resolve("one"));
        Path two = Files.createDirectory(root.resolve("two"));
        Path a = Files.writeString(one.resolve("a.txt"), "a");
        Path z = Files.writeString(one.resolve("z.txt"), "z");
        Path other = Files.writeString(two.resolve("other.txt"), "o");
        AtomicInteger external = new AtomicInteger();
        ProjectPanel panel = panel(root, external);
        try {
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> rootItem = FxTestSupport.callOnFx(tree::getRoot);
            TreeItem<Path> oneItem = await(() -> child(rootItem, one));
            TreeItem<Path> twoItem = await(() -> child(rootItem, two));
            FxTestSupport.runOnFx(() -> {
                oneItem.setExpanded(true);
                twoItem.setExpanded(true);
            });
            TreeItem<Path> aItem = await(() -> child(oneItem, a));
            TreeItem<Path> zItem = await(() -> child(oneItem, z));
            TreeItem<Path> otherItem = await(() -> child(twoItem, other));
            FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(zItem));
            int listsBefore = FxTestSupport.callOnFx(() -> panel.directoryListCountForTest);
            int treeBefore = FxTestSupport.callOnFx(() -> panel.treeRefreshCountForTest);

            // The real watcher, end to end: another program drops a file into one/.
            Path fresh = Files.writeString(one.resolve("m.txt"), "m");
            TreeItem<Path> freshItem = await(() -> child(oneItem, fresh));

            assertEquals(
                    List.of(a, fresh, z),
                    FxTestSupport.callOnFx(() -> oneItem.getChildren().stream()
                            .map(TreeItem::getValue)
                            .toList()));
            assertSame(aItem, FxTestSupport.callOnFx(() -> child(oneItem, a)), "an unchanged row is the same node");
            assertSame(zItem, FxTestSupport.callOnFx(() -> child(oneItem, z)));
            assertSame(oneItem, FxTestSupport.callOnFx(() -> child(rootItem, one)));
            assertSame(twoItem, FxTestSupport.callOnFx(() -> child(rootItem, two)));
            assertSame(otherItem, FxTestSupport.callOnFx(() -> child(twoItem, other)));
            assertTrue(FxTestSupport.callOnFx(oneItem::isExpanded));
            assertSame(
                    zItem, FxTestSupport.callOnFx(() -> tree.getSelectionModel().getSelectedItem()));
            assertEquals(0, FxTestSupport.callOnFx(() -> panel.treeRefreshCountForTest) - treeBefore);
            assertEquals(
                    1,
                    FxTestSupport.callOnFx(() -> panel.directoryListCountForTest) - listsBefore,
                    "only one/ was listed again — not the root, not two/");
            assertTrue(freshItem.isLeaf());
            assertEquals(1, external.get(), "and it is still an external change (#529)");
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void aSaveEditoraMadeItselfRebuildsNothingAndIsNotAnExternalChange(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("open.txt"), "v1");
        AtomicInteger external = new AtomicInteger();
        ProjectPanel panel = panel(root, external);
        try {
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> rootItem = FxTestSupport.callOnFx(tree::getRoot);
            TreeItem<Path> fileItem = await(() -> child(rootItem, file));
            PauseTransition debounce = FxTestSupport.field(panel, "watchDebounce");

            // What a save does on disk: a sibling temporary file renamed over the target.
            FxTestSupport.runOnFx(() -> panel.noteLocalWrite(file));
            Path tmp = Files.writeString(root.resolve(".open.txt.123.editora-tmp"), "v2");
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // The watcher reports the rename as open.txt being created; wait for that tick to come and go.
            await(() -> debounce.getStatus() == javafx.animation.Animation.Status.RUNNING ? true : null);
            await(() -> debounce.getStatus() == javafx.animation.Animation.Status.STOPPED ? true : null);

            assertEquals(0, FxTestSupport.callOnFx(() -> panel.treeRefreshCountForTest));
            assertEquals(0, FxTestSupport.callOnFx(() -> panel.directoryListCountForTest));
            assertEquals(0, external.get(), "Git, diffs and the index are not told an outsider changed the project");
            assertSame(fileItem, FxTestSupport.callOnFx(() -> child(rootItem, file)));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void aFirstSaveOfANewFileStillAppearsInTheTree(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("old.txt"), "old");
        AtomicInteger external = new AtomicInteger();
        ProjectPanel panel = panel(root, external);
        try {
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> rootItem = FxTestSupport.callOnFx(tree::getRoot);
            TreeItem<Path> oldItem = await(() -> child(rootItem, root.resolve("old.txt")));
            Path fresh = root.resolve("new.txt");
            Files.writeString(fresh, "new");
            FxTestSupport.runOnFx(() -> {
                panel.noteLocalWrite(fresh);
                deliver(panel, root, List.of(new FsChange(fresh, FsKind.CREATED)));
            });

            await(() -> child(rootItem, fresh));
            assertSame(oldItem, FxTestSupport.callOnFx(() -> child(rootItem, root.resolve("old.txt"))));
            assertEquals(0, FxTestSupport.callOnFx(() -> panel.treeRefreshCountForTest));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void aFileThatIsOnlyModifiedRebuildsNothingButStillReachesGitAndDiffs(@TempDir Path root) throws Exception {
        Path log = Files.writeString(root.resolve("build.log"), "1");
        AtomicInteger external = new AtomicInteger();
        ProjectPanel panel = panel(root, external);
        try {
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> rootItem = FxTestSupport.callOnFx(tree::getRoot);
            await(() -> child(rootItem, log));

            FxTestSupport.runOnFx(() -> deliver(panel, root, List.of(new FsChange(log, FsKind.CHANGED))));
            assertEquals(1, external.get());
            // A writer that keeps going is coalesced: the second notice waits for the throttle.
            FxTestSupport.runOnFx(() -> deliver(panel, root, List.of(new FsChange(log, FsKind.CHANGED))));
            FxTestSupport.runOnFx(() -> deliver(panel, root, List.of(new FsChange(log, FsKind.CHANGED))));
            assertEquals(1, external.get());
            PauseTransition throttle = FxTestSupport.field(panel, "externalThrottle");
            assertEquals(javafx.animation.Animation.Status.RUNNING, FxTestSupport.callOnFx(throttle::getStatus));
            FxTestSupport.runOnFx(() -> throttle.getOnFinished().handle(new ActionEvent()));
            assertEquals(2, external.get(), "the held-back notice is delivered once, not dropped");

            assertEquals(0, FxTestSupport.callOnFx(() -> panel.treeRefreshCountForTest));
            assertEquals(0, FxTestSupport.callOnFx(() -> panel.directoryListCountForTest));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void aWholeTreeRefreshKeepsTheRowsToo(@TempDir Path root) throws Exception {
        Path sub = Files.createDirectory(root.resolve("sub"));
        Path kept = Files.writeString(sub.resolve("kept.txt"), "k");
        ProjectPanel panel = panel(root, new AtomicInteger());
        try {
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> rootItem = FxTestSupport.callOnFx(tree::getRoot);
            TreeItem<Path> subItem = await(() -> child(rootItem, sub));
            FxTestSupport.runOnFx(() -> subItem.setExpanded(true));
            TreeItem<Path> keptItem = await(() -> child(subItem, kept));
            // Remove the watch so only the explicit refresh (the focus-regain path) can pick the change up.
            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(panel, "cancelAllWatches"));
            Path added = Files.writeString(sub.resolve("added.txt"), "a");

            FxTestSupport.runOnFx(panel::refreshTree);

            await(() -> child(subItem, added));
            assertSame(rootItem, FxTestSupport.callOnFx(tree::getRoot));
            assertSame(subItem, FxTestSupport.callOnFx(() -> child(rootItem, sub)));
            assertSame(keptItem, FxTestSupport.callOnFx(() -> child(subItem, kept)));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }
}

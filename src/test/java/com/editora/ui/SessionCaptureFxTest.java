package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;

import com.editora.config.Bookmark;
import com.editora.config.ConfigManager;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a window remembers, and what it does when it is closed with work in flight: the open-file list is
 * captured as tabs change rather than only at a clean exit, an entry that cannot be read right now is not
 * forgotten, and a completion that arrives after the window is gone is dropped.
 */
@Tag("fx")
class SessionCaptureFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void openingAFileReachesTheSessionFileWithoutACleanClose(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path first = Files.writeString(dir.resolve("first.txt"), "one\n");
            Path second = Files.writeString(dir.resolve("second.txt"), "two\n");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");

            FxTestSupport.runOnFx(() -> {
                workflows.openPath(first);
                workflows.openPath(second);
            });
            SaveGuardsFxTest.awaitOnFx(async, "both files to load", () -> workflows.loadingBuffers.isEmpty());
            async.awaitFx(); // the coalesced save requested by the tab changes

            // No close, no persistSession: this is what a crash or a kill would leave behind.
            assertTrue(fx.shared.flushWrites());
            String saved = Files.readString(fx.configDir.resolve("workspace-state.json"));
            assertTrue(saved.contains("first.txt"), saved);
            assertTrue(saved.contains("second.txt"), saved);
            assertEquals(
                    second.toString(),
                    FxTestSupport.callOnFx(() -> state(fx).getActiveFile()),
                    "the active file follows the selection too");

            // Closing a tab is captured the same way.
            Tab secondTab = FxTestSupport.callOnFx(() ->
                    FxTestSupport.<EditorArea>field(fx.controller, "editorArea").selectedTab());
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(fx.controller, "closeTab", new Class<?>[] {Tab.class}, secondTab));
            async.awaitFx();
            assertEquals(List.of(first.toString()), FxTestSupport.callOnFx(() -> openPaths(fx)));
        }
    }

    @Test
    void anEntryOnAnUnreachableVolumeSurvivesTheNextSave(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectory(dir.resolve("config"));
        Path present = Files.writeString(dir.resolve("present.txt"), "here\n");
        // The whole tree is gone, as it is while a removable or network volume is not mounted.
        Path unmounted = dir.resolve("volume").resolve("project").resolve("Main.java");
        seedSession(config, present, unmounted);
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create(config, false, false, false, List.of(), c -> {}));
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            SaveGuardsFxTest.awaitOnFx(async, "the session to restore", () -> filledBuffers(fx) == 1);
            assertEquals(1, FxTestSupport.callOnFx(() -> tabCount(fx)), "the unreadable entry gets no tab");
            assertTrue(FxTestSupport.callOnFx(() -> workflows.loadingBuffers.isEmpty()));

            FxTestSupport.runOnFx(fx.controller::persistSessionForClose);

            assertEquals(
                    List.of(present.toString(), unmounted.toString()),
                    FxTestSupport.callOnFx(() -> openPaths(fx)),
                    "one launch without the volume must not erase its tabs from the session");
        }
    }

    @Test
    void aDeletedFileIsStillDroppedFromTheSession(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectory(dir.resolve("config"));
        Path present = Files.writeString(dir.resolve("present.txt"), "here\n");
        Path deleted = dir.resolve("deleted.txt"); // its folder exists; the file does not
        seedSession(config, present, deleted);
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create(config, false, false, false, List.of(), c -> {}));
            SaveGuardsFxTest.awaitOnFx(async, "the session to restore", () -> filledBuffers(fx) == 1);

            FxTestSupport.runOnFx(fx.controller::persistSessionForClose);

            assertEquals(List.of(present.toString()), FxTestSupport.callOnFx(() -> openPaths(fx)));
        }
    }

    @Test
    void aRestoredTabThatFailsToLoadIsRemovedNotLeftAsAnEmptyShell(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectory(dir.resolve("config"));
        Path present = Files.writeString(dir.resolve("present.txt"), "here\n");
        // Readable as far as the startup filter can tell, but reading it as a document fails.
        Path unloadable = Files.createDirectory(dir.resolve("a-folder.txt"));
        seedSession(config, present, unloadable);
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create(config, false, false, false, List.of(), c -> {}));
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            SaveGuardsFxTest.awaitOnFx(
                    async,
                    "the restore to settle",
                    () -> filledBuffers(fx) == 1 && workflows.loadingBuffers.isEmpty() && tabCount(fx) == 1);

            assertEquals(
                    List.of(present),
                    FxTestSupport.callOnFx(() -> bufferPaths(fx)),
                    "an empty buffer bound to the path is one Save away from emptying the file");
            assertTrue(echo(fx).startsWith(tr("status.failedOpen", "")), echo(fx));

            FxTestSupport.runOnFx(fx.controller::persistSessionForClose);
            assertEquals(
                    List.of(present.toString(), unloadable.toString()),
                    FxTestSupport.callOnFx(() -> openPaths(fx)),
                    "the entry is kept, so the tab returns when the file can be read again");
        }
    }

    @Test
    void aLoadThatFinishesAfterTheWindowClosedIsNotApplied(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("late.txt"), "arrives after the window is gone\n");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");

            // One FX task: the read starts, and the window is disposed before its completion can be delivered.
            Tab tab = FxTestSupport.callOnFx(() -> {
                workflows.openTextBufferAsync(file, false);
                Tab shell = FxTestSupport.<EditorArea>field(fx.controller, "editorArea")
                        .selectedTab();
                fx.controller.disposeWindow();
                return shell;
            });
            ExecutorService loader = workflows.fileLoadExecutor;
            assertTrue(loader.awaitTermination(30, TimeUnit.SECONDS), "the load worker never finished");
            async.awaitFx();

            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
            assertTrue(FxTestSupport.callOnFx(buffer::isDisposed));
            assertEquals("", FxTestSupport.callOnFx(buffer::getContent), "a disposed buffer must not be filled");
            assertTrue(
                    FxTestSupport.callOnFx(() -> FxTestSupport.<EditorArea>field(fx.controller, "editorArea")
                            .tabs()
                            .contains(tab)),
                    "and the failure path must not run against the closed window either");
        }
    }

    @Test
    void closingTheWindowMidRestoreStopsTheRestore(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectory(dir.resolve("config"));
        Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
        Path b = Files.writeString(dir.resolve("b.txt"), "b\n");
        Path c = Files.writeString(dir.resolve("c.txt"), "c\n");
        seedSession(config, a, b, c);
        try (AsyncTestScope async = new AsyncTestScope()) {
            // Disposed inside the build's own runnable: every restore step is still queued behind it. Each
            // used to run anyway — submitting to a shut-down executor, then opening files in a dead window.
            FxWindowFixture fx = async.own(
                    FxWindowFixture.create(config, false, false, false, List.of(), MainController::disposeWindow));
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            assertTrue(workflows.fileLoadExecutor.awaitTermination(30, TimeUnit.SECONDS));
            async.awaitFx();
            async.awaitFx(); // a second drain: the restore chains one runLater per file

            assertEquals(0, FxTestSupport.callOnFx(() -> filledBuffers(fx)));
        }
    }

    @Test
    void renamingFromTheTabMenuFollowsTheFileInEveryWindow(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path old = Files.writeString(dir.resolve("old-name.txt"), "content\n");
            Path target = dir.resolve("new-name.txt");
            MainController other = FxTestSupport.callOnFx(() -> {
                fx.windowManager.newWindow();
                List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
                return (MainController)
                        FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
            });
            EditorBuffer here = open(fx.controller, old);
            EditorBuffer there = open(other, old);
            FxTestSupport.runOnFx(() -> state(fx).getReadOnlyFiles().add(old.toString()));

            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    fx.controller,
                    "renameFileTo",
                    new Class<?>[] {EditorBuffer.class, Path.class, Path.class},
                    here,
                    old,
                    target));
            async.awaitFx();

            assertFalse(Files.exists(old));
            assertEquals("content\n", Files.readString(target));
            assertEquals(target, FxTestSupport.callOnFx(here::getPath));
            assertEquals(
                    target,
                    FxTestSupport.callOnFx(there::getPath),
                    "the other window's tab must follow, or its next save recreates the old file");
            assertEquals(
                    List.of(target.toString()),
                    FxTestSupport.callOnFx(() -> List.copyOf(state(fx).getReadOnlyFiles())),
                    "path-keyed session state moves with the file");
        }
    }

    @Test
    void pendingBookmarkPositionsOfEveryBufferAreFlushedOnClose(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path first = Files.writeString(dir.resolve("first.txt"), "a\nb\nc\n");
            Path second = Files.writeString(dir.resolve("second.txt"), "x\ny\nz\n");
            EditorBuffer one = open(fx.controller, first);
            EditorBuffer two = open(fx.controller, second);
            BookmarkCoordinator bookmarks = FxTestSupport.field(fx.controller, "bookmarkCoordinator");

            Map<String, List<Bookmark>> stored = FxTestSupport.callOnFx(() -> {
                // A line shift in each buffer inside one debounce window (what typing above a bookmark does):
                // the single pending slot kept only the second buffer.
                one.getBookmarkManager().add(1, "");
                two.getBookmarkManager().add(2, "");
                bookmarks.schedulePersistBookmarks(one);
                bookmarks.schedulePersistBookmarks(two);
                // …and the window closes before the 300 ms debounce fires.
                fx.controller.persistSessionForClose();
                Object ops = FxTestSupport.field(bookmarks, "ops");
                @SuppressWarnings("unchecked")
                Map<String, List<Bookmark>> map =
                        (Map<String, List<Bookmark>>) FxTestSupport.call(ops, "bookmarks", new Class<?>[] {});
                return Map.copyOf(map);
            });

            assertNotNull(stored.get(first.toString()), "the first buffer's positions were dropped: " + stored);
            assertNotNull(stored.get(second.toString()), "the second buffer's positions were dropped: " + stored);
        }
    }

    // --- helpers -------------------------------------------------------------------------------------

    private static WorkspaceState state(FxWindowFixture fx) {
        return FxTestSupport.<ConfigManager>field(fx.controller, "config").getWorkspaceState();
    }

    private static List<String> openPaths(FxWindowFixture fx) {
        List<String> paths = new ArrayList<>();
        for (WorkspaceState.OpenFile f : state(fx).getOpenFiles()) {
            paths.add(f.getPath());
        }
        return paths;
    }

    private static int tabCount(FxWindowFixture fx) {
        return FxTestSupport.<EditorArea>field(fx.controller, "editorArea")
                .tabs()
                .size();
    }

    private static List<Path> bufferPaths(FxWindowFixture fx) {
        List<Path> paths = new ArrayList<>();
        for (Tab tab :
                FxTestSupport.<EditorArea>field(fx.controller, "editorArea").tabs()) {
            if (tab.getUserData() instanceof EditorBuffer buffer) {
                paths.add(buffer.getPath());
            }
        }
        return paths;
    }

    /** How many open editor buffers currently hold their file's text. */
    private static int filledBuffers(FxWindowFixture fx) {
        int filled = 0;
        for (Tab tab :
                FxTestSupport.<EditorArea>field(fx.controller, "editorArea").tabs()) {
            if (tab.getUserData() instanceof EditorBuffer buffer
                    && !buffer.getContent().isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }

    private static EditorBuffer open(MainController controller, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(Files.readString(file));
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            FxTestSupport.call(
                    controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    /** Seeds a session whose open files are {@code files}, with the first one active. */
    private static void seedSession(Path configDir, Path... files) throws Exception {
        StringBuilder open = new StringBuilder();
        for (Path f : files) {
            if (!open.isEmpty()) {
                open.append(',');
            }
            open.append("{\"path\":\"")
                    .append(f.toAbsolutePath().toString().replace("\\", "\\\\"))
                    .append("\"}");
        }
        Files.writeString(
                configDir.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + open + "],\"activeFile\":\""
                        + files[0].toAbsolutePath().toString().replace("\\", "\\\\") + "\"}");
    }
}

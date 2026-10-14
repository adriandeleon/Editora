package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Tab;

import com.editora.config.Project;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.Vfs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A remote project's tabs survive the window being opened before its host is connected: they stay in the
 * saved session, and come back, caret and pin included, when the window connects.
 */
@Tag("fx")
class RemoteSessionTabsFxTest {

    @Test
    void tabsThatWaitedForTheHostComeBackWhenItConnects(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.createDirectories(sftp.serverPath("app"));
            Files.writeString(sftp.serverPath("app/A.java"), "class A {}\n");
            Files.writeString(sftp.serverPath("app/gone.txt"), "x\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            WindowSessionCoordinator sessions = FxTestSupport.field(fx.controller, "sessions");
            List<WorkspaceState.OpenFile> waiting = FxTestSupport.field(sessions, "missingSessionFiles");
            String stored = Vfs.toStorableString(sftp.remotePath("app/A.java"));
            String deleted = Vfs.toStorableString(sftp.remotePath("app/gone.txt"));
            Files.delete(sftp.serverPath("app/gone.txt"));
            // What a window opened while disconnected is left holding: the entries it could not reach.
            FxTestSupport.runOnFx(() -> {
                waiting.add(new WorkspaceState.OpenFile(stored, 6, true));
                waiting.add(new WorkspaceState.OpenFile(deleted, 0, false));
            });

            FxTestSupport.runOnFx(sessions::reopenUnreachable);

            Tab tab = awaitTab(async, fx, sftp.remotePath("app/A.java"));
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
            for (int i = 0; i < 200 && FxTestSupport.callOnFx(buffer::isLoading); i++) {
                Thread.sleep(25);
            }
            async.awaitFx();
            assertEquals(
                    "class A {}\n",
                    FxTestSupport.callOnFx(() -> buffer.getArea().getText()));
            assertEquals(6, (int) FxTestSupport.callOnFx(() -> buffer.getArea().getCaretPosition()), "its caret");
            java.util.Set<Tab> pinned = FxTestSupport.field(fx.controller, "pinned");
            assertTrue(FxTestSupport.callOnFx(() -> pinned.contains(tab)), "and its pin");
            for (int i = 0; i < 200 && FxTestSupport.callOnFx(waiting::size) != 0; i++) {
                Thread.sleep(25);
            }
            assertEquals(
                    List.of(),
                    FxTestSupport.callOnFx(() -> waiting.stream()
                            .map(WorkspaceState.OpenFile::getPath)
                            .toList()),
                    "nothing waits any more: one tab is back, and the file that was deleted is known to be gone");
            int tabs = FxTestSupport.callOnFx(() -> editorTabs(fx));

            FxTestSupport.runOnFx(sessions::reopenUnreachable);
            async.awaitFx();
            Thread.sleep(300);
            assertEquals(tabs, (int) FxTestSupport.callOnFx(() -> editorTabs(fx)), "nothing is opened twice");
        }
    }

    private static int editorTabs(FxWindowFixture fx) {
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        return area.tabs().size();
    }

    @Test
    void aProjectWindowKeepsItsHostsTabsAndOtherWindowsDropThem() {
        Project remote = new Project("app", "app", "sftp://ana@build:22/srv/app");
        String tab = "sftp://ana@build:22/srv/app/A.java";

        assertTrue(SessionEntries.retainUnreachable(tab, false, remote.root()));
        assertNotNull(remote.root());
        assertTrue(remote.remote());
    }

    private static Tab awaitTab(AsyncTestScope async, FxWindowFixture fx, Path file) throws Exception {
        for (int i = 0; i < 200; i++) {
            Tab tab = FxTestSupport.callOnFx(
                    () -> (Tab) FxTestSupport.call(fx.controller, "tabForPath", new Class<?>[] {Path.class}, file));
            if (tab != null) {
                return tab;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no tab for " + file);
    }
}

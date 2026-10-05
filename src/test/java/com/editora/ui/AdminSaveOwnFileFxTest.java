package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With "save as administrator" on, a file the user could replace themselves must not be sent to root. */
@Tag("fx")
class AdminSaveOwnFileFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void savingYourOwnReadOnlyFileAsksToOverwriteInsteadOfAskingForRoot(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(
                com.editora.process.ElevatedSave.supportedOnOs(System.getProperty("os.name")),
                "elevated save is not offered on this platform");
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path file = Files.writeString(dir.resolve("mine.txt"), "mine\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        Assumptions.assumeFalse(Files.isWritable(file), "running as a user the mode does not bind (root)");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            AtomicInteger elevatedWrites = new AtomicInteger();
            FxTestSupport.runOnFx(() -> {
                ConfigManager config = FxTestSupport.field(fx.controller, "config");
                config.getSettings().setAdminSave(true);
                workflows.adminToolAvailable = true; // as applyAdminSaveSupport() does when pkexec is on PATH
                workflows.elevatedWriter = (target, bytes) -> {
                    elevatedWrites.incrementAndGet();
                    return new FileWorkflowCoordinator.AdminResult(126, "dismissed", -1, -1);
                };
            });
            CountDownLatch loaded = new CountDownLatch(1);
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                workflows.openPath(file);
                EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
                Tab selected = area.selectedTab();
                EditorBuffer opening = (EditorBuffer) selected.getUserData();
                workflows.afterBufferLoad(opening, loaded::countDown);
                return opening;
            });
            async.await(loaded, "document load");
            async.awaitFx();
            FxTestSupport.runOnFx(() -> {
                buffer.setViewMode(false);
                buffer.getArea().appendText("edit\n");
            });

            assertFalse(FxTestSupport.callOnFx(() -> workflows.adminSaveApplicable(file)));
            CountDownLatch agreed = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.OK_DONE);
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.save"));
            async.await(agreed, "the read-only confirmation");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
            async.awaitWorker(worker);
            async.awaitFx();

            assertEquals(0, elevatedWrites.get(), "no privileged write for a file the user owns");
            assertEquals("mine\nedit\n", Files.readString(file));
            assertEquals("r--r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            // A file the user cannot replace (its folder allows no new entries) still goes to the elevated save.
            Path lockedDir = Files.createDirectories(dir.resolve("locked"));
            Path foreign = Files.writeString(lockedDir.resolve("conf"), "x\n");
            Files.setPosixFilePermissions(foreign, PosixFilePermissions.fromString("r--r--r--"));
            Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("r-xr-xr-x"));
            async.onClose(() -> Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("rwxr-xr-x")));
            assertTrue(FxTestSupport.callOnFx(() -> workflows.adminSaveApplicable(foreign)));
        }
    }
}

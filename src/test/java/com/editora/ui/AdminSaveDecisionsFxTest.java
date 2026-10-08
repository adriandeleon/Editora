package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.config.ConfigManager;
import com.editora.config.PathDisplay;
import com.editora.editor.EditorBuffer;
import com.editora.process.ElevatedSave;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Save as Administrator" from the command to the file: whether it may run at all, what each outcome of the
 * privileged copy leaves on disk and in the buffer, and the copy itself. No test raises the operating
 * system's password prompt: the elevation tool is replaced at the one place it is launched from, in the
 * tests of the copy by the same script run without privileges on a file the test owns.
 */
@Tag("fx")
class AdminSaveDecisionsFxTest {

    private static final String OS = System.getProperty("os.name");

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- may it run? -------------------------------------------------------------------------------------

    @Test
    void withTheFeatureOffTheCommandSaysSoAndWritesNothing() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = SaveDecisionsFxTest.workflows(fx);
            Path file = Files.writeString(dir.resolve("hosts"), "v1\n");
            EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            AtomicInteger elevated = neverElevate(workflows);

            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin"); // "save as administrator" is off in Settings
            SaveDecisionsFxTest.settle(async, workflows);

            assertEquals(tr("status.admin.unavailable"), SaveDecisionsFxTest.echo(fx));
            assertEquals("v1\n", Files.readString(file));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            // Switched on, but the elevation tool was not found on this machine: the same answer.
            FxTestSupport.runOnFx(() -> {
                settings(fx).getSettings().setAdminSave(true);
                workflows.adminToolAvailable = false;
                StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
                FxTestSupport.<javafx.scene.control.Label>field(status, "echo").setText(""); // the first answer
            });
            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin");
            SaveDecisionsFxTest.settle(async, workflows);

            assertEquals(tr("status.admin.unavailable"), SaveDecisionsFxTest.echo(fx));
            assertEquals("v1\n", Files.readString(file));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(0, elevated.get(), "no privileged write was started either time");
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));
        }
    }

    @Test
    void anUntitledBufferHasNoFileToElevateForSoItGoesToSaveAs() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = SaveDecisionsFxTest.workflows(fx);
            EditorBuffer buffer = SaveDecisionsFxTest.untitled(fx, "draft\n");
            AtomicInteger elevated = neverElevate(workflows);
            enableAdminSave(fx, workflows);
            AtomicReference<Path> answer = new AtomicReference<>();
            AtomicInteger asked = new AtomicInteger();
            workflows.saveAsTargetChooser = offered -> {
                asked.incrementAndGet();
                return answer.get();
            };

            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin"); // the chooser is cancelled
            SaveDecisionsFxTest.settle(async, workflows);
            assertEquals(1, asked.get());
            assertNull(FxTestSupport.callOnFx(buffer::getPath));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            Path target = dir.resolve("draft.txt");
            answer.set(target);
            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin");
            SaveDecisionsFxTest.settle(async, workflows);

            assertEquals("draft\n", Files.readString(target), "an ordinary save to the chosen file");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(0, elevated.get(), "which needed no privileges");
        }
    }

    // --- each outcome of the privileged copy -------------------------------------------------------------

    @Test
    void aSuccessfulElevatedSaveWritesTheBufferAndMarksItSaved() throws Exception {
        Assumptions.assumeTrue(ElevatedSave.supportedOnOs(OS), "elevated save is not offered on this platform");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = SaveDecisionsFxTest.workflows(fx);
            Path file = Files.writeString(dir.resolve("hosts"), "v1\n");
            EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            enableAdminSave(fx, workflows);
            List<String> written = new CopyOnWriteArrayList<>();
            workflows.elevatedWriter = (target, bytes) -> {
                written.add(target + " <- " + new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                Files.write(target, bytes);
                return new FileWorkflowCoordinator.AdminResult(
                        0, "", Files.getLastModifiedTime(target).toMillis(), Files.size(target));
            };

            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin");
            awaitSaveRetired(async, workflows, buffer);

            assertEquals(List.of(file + " <- v1\nedited\n"), written, "one privileged write, of this buffer's text");
            assertEquals("v1\nedited\n", Files.readString(file));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(tr("status.admin.saved", PathDisplay.of(file)), SaveDecisionsFxTest.echo(fx));

            // The buffer is in step with what was written: an ordinary save now asks nothing.
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("again\n"));
            SaveDecisionsFxTest.run(fx, "file.save");
            SaveDecisionsFxTest.settle(async, workflows);
            assertEquals("v1\nedited\nagain\n", Files.readString(file));
            assertEquals(List.of(), SaveDecisionsFxTest.dialogs());
        }
    }

    @Test
    void dismissingThePasswordPromptLeavesTheFileAndTheEditAsTheyWere() throws Exception {
        Assumptions.assumeTrue(ElevatedSave.supportedOnOs(OS), "elevated save is not offered on this platform");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = SaveDecisionsFxTest.workflows(fx);
            Path file = Files.writeString(dir.resolve("hosts"), "v1\n");
            EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            enableAdminSave(fx, workflows);
            // What pkexec (exit 126) and osascript ("User canceled. (-128)") report for a dismissed prompt.
            workflows.elevatedWriter = (target, bytes) -> ElevatedSave.isMac(OS)
                    ? new FileWorkflowCoordinator.AdminResult(1, "execution error: User canceled. (-128)", -1, -1)
                    : new FileWorkflowCoordinator.AdminResult(126, "", -1, -1);

            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin");
            awaitSaveRetired(async, workflows, buffer);

            assertEquals(tr("status.admin.cancelled"), SaveDecisionsFxTest.echo(fx));
            assertEquals("v1\n", Files.readString(file));
            assertEquals("v1\nedited\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void aFailedElevatedSaveKeepsTheEditUnsavedAndSaysWhereThePreviousBytesAre() throws Exception {
        Assumptions.assumeTrue(ElevatedSave.supportedOnOs(OS), "elevated save is not offered on this platform");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = SaveDecisionsFxTest.workflows(fx);
            Path file = Files.writeString(dir.resolve("hosts"), "v1\n");
            EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            enableAdminSave(fx, workflows);
            AtomicReference<FileWorkflowCoordinator.AdminResult> outcome = new AtomicReference<>();
            workflows.elevatedWriter = (target, bytes) -> outcome.get();
            Path backup = ElevatedSave.backupOf(file);

            // The copy failed part-way and the previous bytes could not be written back.
            outcome.set(new FileWorkflowCoordinator.AdminResult(
                    73, "cat: write error: No space left on device\neditora-admin-save:backup-kept\n", -1, -1));
            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin");
            awaitSaveRetired(async, workflows, buffer);
            assertEquals(
                    tr(
                            "status.admin.failedBackupKept",
                            PathDisplay.of(file),
                            PathDisplay.of(backup),
                            "cat: write error: No space left on device"),
                    SaveDecisionsFxTest.lastMessage(fx),
                    "the message names the backup file");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            // An earlier save's backup is still there: nothing was written.
            outcome.set(new FileWorkflowCoordinator.AdminResult(74, "editora-admin-save:stale-backup\n", -1, -1));
            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin");
            awaitSaveRetired(async, workflows, buffer);
            assertEquals(
                    tr("status.admin.failedStaleBackup", PathDisplay.of(file), PathDisplay.of(backup)),
                    SaveDecisionsFxTest.lastMessage(fx));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            // The tool could not be started at all.
            workflows.elevatedWriter = (target, bytes) -> {
                throw new IOException("Cannot run program \"pkexec\"");
            };
            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin");
            awaitSaveRetired(async, workflows, buffer);
            assertEquals(tr("status.admin.failed", "Cannot run program \"pkexec\""), SaveDecisionsFxTest.echo(fx));

            assertEquals("v1\n", Files.readString(file));
            assertEquals("v1\nedited\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "no failure marks the buffer saved");
        }
    }

    // --- the privileged copy itself ----------------------------------------------------------------------

    @Test
    void theElevatedCopyRewritesTheFileInPlaceThroughAPrivateTempFile() throws Exception {
        Assumptions.assumeTrue(ElevatedSave.isLinux(OS), "runs the pkexec form of the elevated script");
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = SaveDecisionsFxTest.workflows(fx);
            Path file = Files.writeString(dir.resolve("hosts"), "v1\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
            EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            enableAdminSave(fx, workflows);
            List<String> tempFiles = new CopyOnWriteArrayList<>();
            workflows.elevationProcess = (timeout, argv) -> {
                Path temp = Path.of(argv.get(argv.size() - 2));
                try {
                    tempFiles.add(temp + " " + PosixFilePermissions.toString(Files.getPosixFilePermissions(temp)) + " "
                            + Files.readString(temp).replace("\n", "|"));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return withoutPrivileges(argv);
            };

            SaveDecisionsFxTest.run(fx, "file.saveAsAdmin"); // the real elevated writer, down to the script
            awaitSaveRetired(async, workflows, buffer);

            assertEquals(1, tempFiles.size());
            assertTrue(
                    tempFiles.get(0).endsWith(" rw------- v1|edited|"),
                    "the new bytes wait in a file only the user can read: " + tempFiles.get(0));
            Path temp = Path.of(tempFiles.get(0).substring(0, tempFiles.get(0).indexOf(' ')));
            assertFalse(Files.exists(temp), "and that file is removed afterwards");
            assertEquals("v1\nedited\n", Files.readString(file));
            assertEquals(
                    "rw-r-----",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                    "rewritten in place: the file keeps its mode");
            assertFalse(Files.exists(ElevatedSave.backupOf(file)), "the script's own backup is gone after a success");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(tr("status.admin.saved", PathDisplay.of(file)), SaveDecisionsFxTest.echo(fx));
        }
    }

    @Test
    void theElevatedCopyReportsWhatTheToolReportedAndAlwaysRemovesItsTempFile() throws Exception {
        Assumptions.assumeTrue(ElevatedSave.isLinux(OS), "runs the pkexec form of the elevated script");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = SaveDecisionsFxTest.workflows(fx);
            Path file = Files.writeString(dir.resolve("conf"), "v1\n");
            byte[] bytes = "v2 from the editor\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            List<List<String>> launched = new CopyOnWriteArrayList<>();

            // The prompt was dismissed: the tool never ran the script.
            workflows.elevationProcess = (timeout, argv) -> {
                launched.add(argv);
                return new ProcessRunner.Result(126, "", "Request dismissed", false, false);
            };
            FileWorkflowCoordinator.AdminResult dismissed = workflows.elevatedWriter.write(file, bytes);
            assertEquals(126, dismissed.exit());
            assertEquals("Request dismissed", dismissed.error());
            assertEquals("v1\n", Files.readString(file));
            assertEquals(ElevatedSave.PKEXEC, launched.get(0).get(0), "the copy is run through the elevation tool");
            assertEquals(file.toString(), launched.get(0).get(launched.get(0).size() - 1));
            assertFalse(Files.exists(Path.of(launched.get(0).get(launched.get(0).size() - 2))));

            // A backup left by a save that did not finish: the script stops before it touches anything.
            Path stale = Files.writeString(ElevatedSave.backupOf(file), "from the unfinished save\n");
            workflows.elevationProcess = (timeout, argv) -> {
                launched.add(argv);
                return withoutPrivileges(argv);
            };
            FileWorkflowCoordinator.AdminResult refused = workflows.elevatedWriter.write(file, bytes);
            assertEquals(ElevatedSave.Failure.STALE_BACKUP, ElevatedSave.failureOf(refused.exit(), refused.error()));
            assertEquals("v1\n", Files.readString(file));
            assertEquals("from the unfinished save\n", Files.readString(stale), "the older backup is not overwritten");
            assertFalse(Files.exists(Path.of(launched.get(1).get(launched.get(1).size() - 2))));

            // With it out of the way the same call writes, and reports the file as it now is.
            Files.delete(stale);
            FileWorkflowCoordinator.AdminResult saved = workflows.elevatedWriter.write(file, bytes);
            assertEquals(0, saved.exit(), saved.error());
            assertEquals("v2 from the editor\n", Files.readString(file));
            assertEquals(Files.size(file), saved.size());
            assertEquals(Files.getLastModifiedTime(file).toMillis(), saved.modifiedMillis());
            assertFalse(Files.exists(stale));

            // The tool cannot be launched.
            workflows.elevationProcess = (timeout, argv) -> {
                launched.add(argv);
                throw new IllegalStateException("pkexec: not found");
            };
            assertThrows(IllegalStateException.class, () -> workflows.elevatedWriter.write(file, bytes));
            assertFalse(
                    Files.exists(Path.of(launched.get(3).get(launched.get(3).size() - 2))),
                    "the temp file holding the document does not outlive a failed launch");
            assertEquals("v2 from the editor\n", Files.readString(file));
        }
    }

    // --- is the elevation tool there? --------------------------------------------------------------------

    @Test
    void theToolIsProbedOnlyWhenTheFeatureIsOnAndItsAnswerDecidesTheOffer() throws Exception {
        Assumptions.assumeTrue(ElevatedSave.isLinux(OS), "macOS always has osascript; Linux probes pkexec");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = SaveDecisionsFxTest.workflows(fx);
            Path file = Files.writeString(dir.resolve("hosts"), "v1\n");
            EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
            List<List<String>> probes = new CopyOnWriteArrayList<>();
            AtomicReference<ProcessRunner.Result> answer = new AtomicReference<>();
            workflows.elevationProcess = (timeout, argv) -> {
                probes.add(argv);
                ProcessRunner.Result result = answer.get();
                if (result == null) {
                    throw new IllegalStateException("Cannot run program \"pkexec\"");
                }
                return result;
            };

            // Off in Settings: no probe, and an offer left over from before is withdrawn at once.
            FxTestSupport.runOnFx(() -> {
                workflows.adminToolAvailable = true;
                buffer.setAdminEditAvailable(true);
                workflows.applyAdminSaveSupport();
            });
            assertFalse(FxTestSupport.callOnFx(() -> workflows.adminToolAvailable));
            assertFalse(offered(buffer));
            assertEquals(List.of(), probes);

            // On, and pkexec answers: offered.
            answer.set(new ProcessRunner.Result(0, "pkexec version 126\n", "", false, false));
            FxTestSupport.runOnFx(() -> {
                settings(fx).getSettings().setAdminSave(true);
                workflows.applyAdminSaveSupport();
            });
            SaveGuardsFxTest.awaitOnFx(async, "the probe's answer", () -> workflows.adminToolAvailable);
            assertEquals(List.of(List.of(ElevatedSave.PKEXEC, "--version")), probes, "a probe, not a privileged run");
            assertTrue(offered(buffer), "an open local buffer is told it can be edited as administrator");
            assertTrue(FxTestSupport.callOnFx(workflows::elevationAvailable));

            // pkexec is there but does not work (no polkit agent, say).
            answer.set(new ProcessRunner.Result(127, "", "pkexec: not authorized", false, false));
            FxTestSupport.runOnFx(workflows::applyAdminSaveSupport);
            SaveGuardsFxTest.awaitOnFx(async, "the failed probe's answer", () -> !workflows.adminToolAvailable);
            assertFalse(offered(buffer));
            assertFalse(FxTestSupport.callOnFx(workflows::elevationAvailable));

            // pkexec is not installed: starting it throws.
            answer.set(new ProcessRunner.Result(0, "", "", false, false));
            FxTestSupport.runOnFx(workflows::applyAdminSaveSupport);
            SaveGuardsFxTest.awaitOnFx(async, "the offer to come back", () -> workflows.adminToolAvailable);
            answer.set(null);
            FxTestSupport.runOnFx(workflows::applyAdminSaveSupport);
            SaveGuardsFxTest.awaitOnFx(async, "the missing tool's answer", () -> !workflows.adminToolAvailable);
            assertFalse(offered(buffer));
            assertEquals(4, probes.size(), "one probe per time the setting was applied while on");
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private static ConfigManager settings(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "config");
    }

    /** Admin save on in Settings and the tool reported present, as after a successful probe. */
    private static void enableAdminSave(FxWindowFixture fx, FileWorkflowCoordinator workflows) throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings(fx).getSettings().setAdminSave(true);
            workflows.adminToolAvailable = true;
        });
    }

    /** Fails the test's expectations, not the machine: counts privileged writes that must not happen. */
    private static AtomicInteger neverElevate(FileWorkflowCoordinator workflows) {
        AtomicInteger elevated = new AtomicInteger();
        workflows.elevatedWriter = (target, bytes) -> {
            elevated.incrementAndGet();
            return new FileWorkflowCoordinator.AdminResult(1, "must not run", -1, -1);
        };
        workflows.elevationProcess = (timeout, argv) -> {
            elevated.incrementAndGet();
            return new ProcessRunner.Result(1, "", "must not run", false, false);
        };
        return elevated;
    }

    /** The elevated command with the elevation tool taken off the front: the same script, as this user. */
    private static ProcessRunner.Result withoutPrivileges(List<String> argv) {
        assertEquals(ElevatedSave.PKEXEC, argv.get(0));
        return ProcessRunner.run(null, Duration.ofSeconds(30), argv.subList(1, argv.size()));
    }

    private static void awaitSaveRetired(AsyncTestScope async, FileWorkflowCoordinator workflows, EditorBuffer buffer)
            throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, "the elevated save to finish", () -> !workflows.hasPendingSave(buffer));
    }

    private static boolean offered(EditorBuffer buffer) throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.<Boolean>field(buffer, "adminEditAvailable"));
    }
}

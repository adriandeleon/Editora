package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The document's life on disk, through the real load and the real {@code file.save}: what a save may write
 * when the charset cannot hold the text, what it does when the file cannot be replaced, and what the editor
 * says about a file whose disk state differs from what it holds.
 */
@Tag("fx")
class DocumentOnDiskFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- a charset that cannot hold the text ------------------------------------------------------------

    @Test
    void typingIntoAFileOfUnknownEncodingNeverReencodesIt(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            byte[] original = hex("93fa967b8cea81400d0a"); // 日本語 + ideographic space in Shift-JIS
            Path file = Files.write(dir.resolve("sjis.txt"), original);
            EditorBuffer buffer = load(fx, file);
            assertEquals("ISO-8859-1", statusSegment(fx, "encoding"));

            // What a Japanese user types into their own file: not representable in the stand-in charset.
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "ok\n日"));
            save(async, fx);

            assertEquals(hex(original), hex(Files.readAllBytes(file)), "not one byte may be re-encoded");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "the edit is still unsaved");
            assertEquals(tr("status.save.cannotEncodeAssumed", "sjis.txt", "ISO-8859-1", "日", "2"), echo(fx));
            assertEquals("ISO-8859-1", statusSegment(fx, "encoding"));

            // Auto-save and the synchronous save used by close/run pass through the same refusal.
            FileWorkflowCoordinator workflows = workflows(fx);
            FxTestSupport.runOnFx(() -> workflows.autoSaveBuffer(buffer));
            settle(async, workflows);
            assertFalse(FxTestSupport.callOnFx(() -> workflows.saveSynchronously(buffer)));
            assertEquals(hex(original), hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));

            // Taking the character out again makes the file savable, and only the edit reaches the disk.
            FxTestSupport.runOnFx(() -> buffer.getArea().deleteText(3, 4));
            save(async, fx);
            assertEquals("6f6b0d0a" + hex(original), hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void aUtf8FallbackIsWrittenSoTheNextOpenReadsItBack(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\ncharset = latin1\n");
            Path file = Files.write(dir.resolve("cafe.txt"), hex("636166e90a")); // café in Latin-1
            EditorBuffer buffer = load(fx, file);
            assertEquals("ISO-8859-1", statusSegment(fx, "encoding"));

            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "€"));
            save(async, fx);

            assertEquals("efbbbfe282ac636166c3a90a", hex(Files.readAllBytes(file)), "UTF-8, marked as such");
            assertEquals(tr("status.charsetFallback", "ISO-8859-1"), echo(fx), "the warning outlives the save");
            assertEquals("UTF-8 BOM", statusSegment(fx, "encoding"), "the status bar shows what is on disk");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            EditorBuffer reopened = load(fx, dir.resolve("cafe.txt"), false);
            assertEquals(
                    "€café\n",
                    FxTestSupport.callOnFx(reopened::getContent),
                    "what was on screen when saving is what comes back");

            // A second save of the same buffer keeps the file in the encoding it now has.
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x"));
            save(async, fx);
            assertEquals("efbbbf78e282ac636166c3a90a", hex(Files.readAllBytes(file)));
        }
    }

    @Test
    void bomlessUtf16DeclaredByEditorConfigOpensAsTextAndStaysBomless(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*.txt]\ncharset = utf-16le\n");
            Path file = Files.write(dir.resolve("wide.txt"), hex("680069000a00"));
            FileWorkflowCoordinator workflows = workflows(fx);

            assertFalse(workflows.prepareLoad(file, true).binary(), "it is text: the rule says exactly what it is");
            Path other = Files.write(dir.resolve("wide.bin"), hex("680069000a00"));
            assertTrue(workflows.prepareLoad(other, true).binary(), "without the rule the NUL bytes still mean binary");

            EditorBuffer buffer = load(fx, file);
            assertEquals("hi\n", FxTestSupport.callOnFx(buffer::getContent));
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x"));
            save(async, fx);

            assertEquals("7800680069000a00", hex(Files.readAllBytes(file)), "no byte-order mark was added");
        }
    }

    // --- a file that cannot be replaced -----------------------------------------------------------------

    @Test
    void aWritableFileInAReadOnlyFolderIsSavedInPlaceAndSaysSo(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Path file = Files.writeString(locked.resolve("w.txt"), "one\n");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        Assumptions.assumeFalse(Files.isWritable(locked), "running as a user the mode does not bind (root)");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = load(fx, file);

            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "zero\n"));
            save(async, fx);

            assertEquals("zero\none\n", Files.readString(file));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(tr("status.savedInPlace", com.editora.config.PathDisplay.of(file)), echo(fx));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aFileThatIsReadOnlyOnDiskIsNotReplacedWithoutAsking(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path file = Files.writeString(dir.resolve("ro.txt"), "locked\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        Assumptions.assumeFalse(Files.isWritable(file), "running as a user the mode does not bind (root)");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = load(fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "changed\n"));

            // A background save never asks, and never writes.
            FxTestSupport.runOnFx(() -> workflows.autoSaveBuffer(buffer));
            settle(async, workflows);
            assertEquals("locked\n", Files.readString(file));
            assertEquals(tr("status.autoSave.cannotWriteReadOnly", "ro.txt"), echo(fx));

            CountDownLatch declined = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            save(async, fx);
            async.await(declined, "the read-only confirmation");
            assertEquals("locked\n", Files.readString(file), "declined: the file is untouched");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(tr("status.save.cannotWriteReadOnly", "ro.txt"), echo(fx));

            CountDownLatch agreed = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.OK_DONE);
            save(async, fx);
            async.await(agreed, "the read-only confirmation");
            assertEquals("changed\nlocked\n", Files.readString(file));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("r--r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

            // Asked once per buffer and file, not on every save.
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "again\n"));
            save(async, fx);
            assertEquals("again\nchanged\nlocked\n", Files.readString(file));
            assertEquals(0, openDialogs());
        }
    }

    // --- .editorconfig ----------------------------------------------------------------------------------

    @Test
    void anEditorConfigChangedAfterTheFileWasOpenedGovernsTheNextSave(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path config = Files.writeString(dir.resolve(".editorconfig"), "root = true\n");
            Path file = Files.writeString(dir.resolve("f.txt"), "x  \ny\n");
            EditorBuffer buffer = load(fx, file);

            // As a pull or a branch switch would: the file changes underneath the open tab.
            Files.writeString(config, "root = true\n[*]\ntrim_trailing_whitespace = true\nend_of_line = crlf\n");
            Files.setLastModifiedTime(
                    config,
                    FileTime.fromMillis(Files.getLastModifiedTime(config).toMillis() + 5_000));
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("z"));
            save(async, fx);

            assertEquals("780d0a790d0a7a", hex(Files.readAllBytes(file)));
            assertEquals("CRLF", statusSegment(fx, "endings"));
        }
    }

    @Test
    void savingAnEditorConfigReachesTheFilesAlreadyOpen(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path config = Files.writeString(dir.resolve(".editorconfig"), "root = true\n");
            Path file = Files.writeString(dir.resolve("f.txt"), "x\n");
            EditorBuffer open = load(fx, file);
            EditorBuffer rules = load(fx, config);
            assertNull(FxTestSupport.callOnFx(() -> open.getEditorConfigProps().endOfLine()));

            FxTestSupport.runOnFx(() -> rules.getArea().appendText("[*]\nend_of_line = crlf\nindent_size = 3\n"));
            save(async, fx); // the .editorconfig tab is the active one

            assertEquals(
                    "crlf",
                    FxTestSupport.callOnFx(() -> open.getEditorConfigProps().endOfLine()));
            assertEquals("CRLF", FxTestSupport.callOnFx(open::getLineEnding));
        }
    }

    @Test
    void savingAnEditorConfigReachesTheFilesOpenInAnotherWindow(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController otherWindow = newWindow(fx);
            Path config = Files.writeString(dir.resolve(".editorconfig"), "root = true\n");
            Path file = Files.writeString(dir.resolve("f.txt"), "x\n");
            EditorBuffer elsewhere = load(otherWindow, file);
            EditorBuffer rules = load(fx, config);
            assertNull(FxTestSupport.callOnFx(
                    () -> elsewhere.getEditorConfigProps().endOfLine()));

            FxTestSupport.runOnFx(() -> rules.getArea().appendText("[*]\nend_of_line = crlf\n"));
            save(async, fx); // saved from the first window; the file it governs is open in the second

            assertEquals(
                    "crlf",
                    FxTestSupport.callOnFx(
                            () -> elsewhere.getEditorConfigProps().endOfLine()));
            assertEquals("CRLF", FxTestSupport.callOnFx(elsewhere::getLineEnding));
        }
    }

    // --- large-file mode --------------------------------------------------------------------------------

    @Test
    void aFileWithAVeryLongLineSaysThatUndoIsOff(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            int length = FileWorkflowCoordinator.LONG_LINE_FILE_CHARS + 8;
            Path file = Files.writeString(dir.resolve("min.json"), "a".repeat(length) + "\nsecond line\n");
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer loading = new EditorBuffer();
                loading.setPath(file);
                assertEquals(tr("status.longLineFileTier", length), workflows.loadInto(loading, file));
                addBuffer(fx, loading);
                return loading;
            });
            assertTrue(tr("status.longLineFileTier", length).contains("undo"), "the tier message names undo");
            assertTrue(FxTestSupport.callOnFx(buffer::isEditable));

            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> {
                buffer.getArea().appendText("TYPED");
                registry.run("edit.undo");
            });

            assertEquals(tr("status.undoOffLargeFile"), echo(fx), "a no-op undo explains itself");
            assertTrue(FxTestSupport.callOnFx(buffer::getContent).endsWith("TYPED"), "the documented design: no undo");
        }
    }

    @Test
    void reloadingAFileThatIsNoLongerHugeMakesItEditableAgain(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("rotated.log.txt"), "small now\n");
            FileWorkflowCoordinator workflows = workflows(fx);
            FileWorkflowCoordinator.PreparedLoad small = workflows.prepareLoad(file, false);
            // The same file as it was when it was over the huge-file cap: a truncated, read-only slice.
            FileWorkflowCoordinator.PreparedLoad huge = new FileWorkflowCoordinator.PreparedLoad(
                    file,
                    "first chunk\n",
                    EditorBuffer.HUGE_FILE_BYTES + 1,
                    small.mtime(),
                    2,
                    11,
                    "utf-8",
                    small.editorConfig(),
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    0,
                    false,
                    null,
                    null);

            EditorBuffer buffer = load(fx, file);
            FxTestSupport.callOnFx(() -> workflows.applyPreparedLoad(buffer, huge));
            assertTrue(FxTestSupport.callOnFx(buffer::isReadOnly));

            FxTestSupport.callOnFx(() -> workflows.applyPreparedLoad(buffer, small));

            assertEquals("small now\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isReadOnly));
            assertTrue(FxTestSupport.callOnFx(buffer::isEditable));
            assertTrue(FxTestSupport.callOnFx(() -> buffer.getArea().isEditable()));
            assertFalse(FxTestSupport.callOnFx(buffer::isLargeFile));
        }
    }

    // --- Save As ----------------------------------------------------------------------------------------

    @Test
    void saveAsOntoAFileOpenInAnotherTabIsRefused(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path a = Files.writeString(dir.resolve("a.txt"), "AAA");
            Path b = Files.writeString(dir.resolve("b.txt"), "BBB");
            EditorBuffer other = load(fx, b);
            FxTestSupport.runOnFx(() -> other.getArea().appendText("|unsaved-b"));
            EditorBuffer buffer = load(fx, a);
            FileWorkflowCoordinator workflows = workflows(fx);

            assertFalse(FxTestSupport.callOnFx(() -> workflows.applySaveAsTarget(buffer, b)));
            settle(async, workflows);

            assertEquals(a, FxTestSupport.callOnFx(buffer::getPath), "the buffer was not re-pointed");
            assertEquals("BBB", Files.readString(b), "and the other tab's file was not replaced");
            assertEquals(tr("status.saveAs.cannotReplaceOpenFile", "b.txt"), echo(fx));
            assertEquals(1, tabsOn(fx, b));

            // Its own path, and a path nobody has open, are still fine.
            assertTrue(FxTestSupport.callOnFx(() -> workflows.applySaveAsTarget(buffer, dir.resolve("c.txt"))));
            settle(async, workflows);
            assertEquals("AAA", Files.readString(dir.resolve("c.txt")));
        }
    }

    @Test
    void saveAsOntoAFileOpenInAnotherWindowIsRefused(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController otherWindow = newWindow(fx);
            Path a = Files.writeString(dir.resolve("a.txt"), "AAA");
            Path b = Files.writeString(dir.resolve("b.txt"), "BBB");
            EditorBuffer other = load(otherWindow, b);
            FxTestSupport.runOnFx(() -> other.getArea().appendText("|unsaved-b"));
            EditorBuffer buffer = load(fx, a);
            FileWorkflowCoordinator workflows = workflows(fx);

            assertFalse(FxTestSupport.callOnFx(() -> workflows.applySaveAsTarget(buffer, b)));
            settle(async, workflows);

            assertEquals(a, FxTestSupport.callOnFx(buffer::getPath), "the buffer was not re-pointed");
            assertEquals("BBB", Files.readString(b), "and the other window's file was not replaced");
            assertEquals(tr("status.saveAs.cannotReplaceOpenFile", "b.txt"), echo(fx));
            assertEquals("BBB|unsaved-b", FxTestSupport.callOnFx(other::getContent));

            // The other window may still save its own file, and this one may save onto a path nobody has open.
            FileWorkflowCoordinator otherWorkflows = FxTestSupport.field(otherWindow, "fileWorkflows");
            FxTestSupport.runOnFx(() -> otherWorkflows.save(other));
            settle(async, otherWorkflows);
            assertEquals("BBB|unsaved-b", Files.readString(b));
            assertTrue(FxTestSupport.callOnFx(() -> workflows.applySaveAsTarget(buffer, dir.resolve("c.txt"))));
            settle(async, workflows);
            assertEquals("AAA", Files.readString(dir.resolve("c.txt")));
        }
    }

    // --- line endings -----------------------------------------------------------------------------------

    @Test
    void aFileWithMixedLineEndingsSaysWhatASaveWillWrite(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path mixed = Files.write(dir.resolve("mixed.txt"), hex("610d0a620a630a640a"));
            Path bareCr = Files.write(dir.resolve("progress.txt"), hex("610d620a630a"));
            Path plain = Files.write(dir.resolve("plain.txt"), hex("610d0a620d0a"));

            assertEquals(tr("status.mixedLineEndings", "mixed.txt", "LF"), loadNote(workflows, mixed));
            assertEquals(tr("status.mixedLineEndings", "progress.txt", "LF"), loadNote(workflows, bareCr));
            assertEquals("", loadNote(workflows, plain));
        }
    }

    @Test
    void aLineEndingConversionChosenDuringASaveIsStillUnsavedAfterIt(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("slow.txt"), "one\ntwo\n");
            EditorBuffer buffer = load(fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            CountDownLatch writing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            async.onClose(release::countDown);
            workflows.beforeDocumentWriteForTest = () -> {
                writing.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            FxTestSupport.runOnFx(() -> {
                buffer.getArea().appendText("x");
                workflows.save(buffer);
            });
            async.await(writing, "the write to start");
            FxTestSupport.runOnFx(() -> buffer.convertLineEndings(true)); // picked in the status bar meanwhile
            workflows.beforeDocumentWriteForTest = null;
            release.countDown();
            settle(async, workflows);

            assertEquals("6f6e650a74776f0a78", hex(Files.readAllBytes(file)), "the in-flight write was LF");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "so the conversion is not on disk yet");

            save(async, fx);
            assertEquals("6f6e650d0a74776f0d0a78", hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void convertingBackAfterASaveThatWroteTheOldLineEndingIsCleanAgain(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("slow.txt"), "one\ntwo\n");
            EditorBuffer buffer = load(fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            CountDownLatch writing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            async.onClose(release::countDown);
            workflows.beforeDocumentWriteForTest = () -> {
                writing.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            FxTestSupport.runOnFx(() -> {
                buffer.getArea().appendText("x");
                workflows.save(buffer);
            });
            async.await(writing, "the write to start");
            FxTestSupport.runOnFx(() -> buffer.convertLineEndings(true)); // LF -> CRLF while LF is being written
            workflows.beforeDocumentWriteForTest = null;
            release.countDown();
            settle(async, workflows);
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "CRLF is not what the disk holds");

            // The save recorded the ending it wrote (LF), so going back to it matches the disk again. The
            // buffer used to be forced "unsaved" instead and stayed so whatever the user did next.
            FxTestSupport.runOnFx(() -> buffer.convertLineEndings(false));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty), "LF is exactly what was written");
            assertEquals("6f6e650a74776f0a78", hex(Files.readAllBytes(file)));

            // And an edit made during a later save is still unsaved after it, in the ending that was written.
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("y"));
            save(async, fx);
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("6f6e650a74776f0a7879", hex(Files.readAllBytes(file)));
        }
    }

    // --- external changes -------------------------------------------------------------------------------

    @Test
    void keepingTheEditorsVersionLeavesItMarkedUnsaved(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("t.txt"), "mine");
            EditorBuffer buffer = load(fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            Files.writeString(file, "theirs!");

            CountDownLatch kept = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            async.await(kept, "the external-change prompt");
            SaveGuardsFxTest.awaitOnFx(async, "the prompt to close", () -> openDialogs() == 0);

            assertEquals("mine", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(
                    FxTestSupport.callOnFx(buffer::isDirty),
                    "what was kept is not what is on disk: closing must ask, auto-save must write it");
        }
    }

    @Test
    void aFileWhoseBytesDidNotChangeDoesNotPrompt(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("touched.txt"), "same");
            EditorBuffer buffer = load(fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            long touched = Files.getLastModifiedTime(file).toMillis() + 60_000;
            Files.setLastModifiedTime(file, FileTime.fromMillis(touched)); // touch, or an identical rewrite

            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            SaveGuardsFxTest.awaitOnFx(
                    async, "the content comparison", () -> buffer.diskSnapshot().modifiedMillis() == touched);

            assertEquals(0, openDialogs(), "identical bytes are not an external change");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(FxTestSupport.callOnFx(() -> buffer.diskChangedFrom(touched, 4)), "and it is not asked again");
        }
    }

    // --- a path that does not exist yet -----------------------------------------------------------------

    @Test
    void aCommandLinePathThatDoesNotExistOpensAsANewFile(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path target = dir.resolve("new.txt");
            FxWindowFixture fx = async.own(FxWindowFixture.create(
                    Files.createTempDirectory("editora-fx-test"),
                    false,
                    false,
                    false,
                    List.of(new MainController.OpenTarget(target, 0, 0)),
                    true,
                    c -> {}));
            FileWorkflowCoordinator workflows = workflows(fx);
            SaveGuardsFxTest.awaitOnFx(async, "the startup target", () -> tabsOnFx(fx, target) == 1);

            Tab tab = FxTestSupport.callOnFx(() -> host(fx).tabForPath(target));
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> host(fx).bufferOf(tab));
            assertNotNull(buffer, "a text buffer bound to the path, not a failed open");
            assertEquals(tr("status.newFileAtPath", com.editora.config.PathDisplay.of(target)), echo(fx));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "nothing is on disk yet");
            assertTrue(FxTestSupport.callOnFx(buffer::isEditable), "and it can be typed into");
            assertFalse(Files.exists(target), "opening must not create it");

            FxTestSupport.runOnFx(() -> {
                host(fx).editorArea().select(tab);
                buffer.getArea().appendText("hello\n");
            });
            save(async, fx);

            assertEquals("hello\n", Files.readString(target));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            // Not for a file that exists, nor for one whose folder does not: those open (or fail) as before.
            assertFalse(FxTestSupport.callOnFx(() -> workflows.openNewFileAt(target)));
            assertFalse(FxTestSupport.callOnFx(() -> workflows.openNewFileAt(dir.resolve("no-such-dir/x.txt"))));
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private static FileWorkflowCoordinator workflows(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "fileWorkflows");
    }

    private static FileWorkflowCoordinator.Host host(FxWindowFixture fx) {
        return FxTestSupport.field(workflows(fx), "host");
    }

    private static void addBuffer(FxWindowFixture fx, EditorBuffer buffer) {
        FxTestSupport.call(
                fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
    }

    /** A second window of the same application, as "New Window" opens it. */
    private static MainController newWindow(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            Object holder = holders.get(holders.size() - 1);
            return (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
        });
    }

    private static EditorBuffer load(MainController window, Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(window, "fileWorkflows");
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            workflows.loadInto(buffer, file);
            FxTestSupport.call(window, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static EditorBuffer load(FxWindowFixture fx, Path file) throws Exception {
        return load(fx, file, true);
    }

    private static EditorBuffer load(FxWindowFixture fx, Path file, boolean addTab) throws Exception {
        FileWorkflowCoordinator workflows = workflows(fx);
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            workflows.loadInto(buffer, file);
            if (addTab) {
                addBuffer(fx, buffer);
            }
            return buffer;
        });
    }

    private static String loadNote(FileWorkflowCoordinator workflows, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            return workflows.loadInto(buffer, file);
        });
    }

    /** {@code file.save} on the active tab, then the write worker and its FX acknowledgment. */
    private static void save(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> registry.run("file.save"));
        settle(async, workflows(fx));
    }

    private static void settle(AsyncTestScope async, FileWorkflowCoordinator workflows) throws Exception {
        ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
        async.awaitWorker(worker);
        async.awaitFx();
    }

    private static String statusSegment(FxWindowFixture fx, String field) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            status.refresh();
            return FxTestSupport.<Label>field(status, field).getText();
        });
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }

    private static int tabsOn(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> tabsOnFx(fx, file));
    }

    private static int tabsOnFx(FxWindowFixture fx, Path file) {
        return (int) host(fx).editorArea().tabs().stream()
                .filter(tab -> file.equals(host(fx).tabPath(tab)))
                .count();
    }

    private static int openDialogs() {
        return (int) Window.getWindows().stream()
                .filter(window -> window.getScene() != null && window.getScene().getRoot() instanceof DialogPane)
                .count();
    }

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}

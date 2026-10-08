package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.control.ButtonBar;

import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LspDiagnostic;
import com.editora.mcp.McpBridge;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an MCP client sees of, and does to, a real project window: the read tools against live buffers, a
 * search, a TODO scan and a Git status of a temp project, and every way {@code save_buffer} can end — with
 * the bytes on disk and the buffer's unsaved state checked after each.
 */
@Tag("fx")
class WindowMcpBridgeFxTest {

    @TempDir
    Path work;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- what is open -------------------------------------------------------------------------------

    @Test
    void openFilesAndTabsDescribeTheWindowAsItIs() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "open");
            Path a = Files.writeString(window.root.resolve("A.java"), "class A {}\n");
            Path notes = Files.writeString(window.root.resolve("notes.md"), "# notes\n");
            EditorBuffer first = open(async, window, a);
            EditorBuffer second = open(async, window, notes);
            FxTestSupport.runOnFx(() -> first.getArea().appendText("// unsaved\n"));

            List<McpBridge.OpenFile> files = window.mcp.listOpenFiles().stream()
                    .filter(file -> file.path() != null)
                    .toList();

            assertEquals(
                    List.of(a.toString(), notes.toString()),
                    files.stream().map(McpBridge.OpenFile::path).toList());
            assertEquals("A.java", files.get(0).title());
            assertEquals(
                    FxTestSupport.callOnFx(first::getLanguage), files.get(0).language());
            assertTrue(files.get(0).dirty(), "the edited buffer");
            assertFalse(files.get(0).active());
            assertFalse(files.get(1).dirty());
            assertTrue(files.get(1).active(), "the tab opened last is the one being looked at");
            assertEquals(
                    FxTestSupport.callOnFx(second::getLanguage), files.get(1).language());

            List<McpBridge.TabInfo> tabs = window.mcp.listTabs();
            assertEquals(1, tabs.stream().filter(McpBridge.TabInfo::active).count(), "exactly one active tab");
            McpBridge.TabInfo activeTab =
                    tabs.stream().filter(McpBridge.TabInfo::active).findFirst().orElseThrow();
            assertEquals("editor", activeTab.type());
            assertEquals(notes.toString(), activeTab.path());
            assertEquals("notes.md", activeTab.title());
            assertTrue(
                    tabs.stream().anyMatch(tab -> a.toString().equals(tab.path()) && "editor".equals(tab.type())),
                    tabs.toString());
            assertEquals(
                    files.size(),
                    tabs.stream()
                            .filter(tab -> "editor".equals(tab.type()) && tab.path() != null)
                            .count(),
                    "every file-backed editor tab is an open file and the other way round");
        }
    }

    @Test
    void anUntitledBufferIsListedWithoutAPathAndCanBeReadButNotSaved() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "untitled");
            EditorBuffer untitled = FxTestSupport.callOnFx(() -> {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setContent("never saved\n");
                FxTestSupport.call(
                        window.controller,
                        "addBuffer",
                        new Class<?>[] {EditorBuffer.class, boolean.class},
                        buffer,
                        true);
                return buffer;
            });

            McpBridge.OpenFile listed = window.mcp.listOpenFiles().stream()
                    .filter(McpBridge.OpenFile::active)
                    .findFirst()
                    .orElseThrow();
            assertNull(listed.path());
            assertEquals(FxTestSupport.callOnFx(untitled::getTitle), listed.title());

            McpBridge.BufferContent read = window.mcp.readBuffer(null);
            assertNull(read.path());
            assertEquals("never saved\n", read.text());

            String refused = window.mcp.saveBuffer(null);
            assertNotNull(refused, "saving it would need a Save As dialog");
            assertTrue(refused.contains("Untitled"), refused);
            assertNull(FxTestSupport.callOnFx(untitled::getPath), "and it was not given a path behind the user's back");
            try (var files = Files.list(window.root)) {
                assertEquals(0, files.count(), "nothing was written into the project");
            }
        }
    }

    // --- reading ------------------------------------------------------------------------------------

    @Test
    void readBufferServesTheLiveTextOfAnOpenBufferOnly() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "read");
            Path opened = Files.writeString(window.root.resolve("opened.txt"), "on disk\n");
            Path other = Files.writeString(window.root.resolve("other.txt"), "also open\n");
            Path closed = Files.writeString(window.root.resolve("closed.txt"), "never opened\n");
            EditorBuffer buffer = open(async, window, opened);
            open(async, window, other);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("typed, not saved\n"));

            McpBridge.BufferContent byPath = window.mcp.readBuffer(opened.toString());
            assertEquals("on disk\ntyped, not saved\n", byPath.text(), "the buffer, not the file");
            assertTrue(byPath.dirty());
            assertEquals(opened.toString(), byPath.path());
            assertEquals("on disk\n", Files.readString(opened), "reading writes nothing");

            assertEquals("also open\n", window.mcp.readBuffer(null).text(), "no path means the active buffer");
            assertEquals(
                    "on disk\ntyped, not saved\n",
                    window.mcp
                            .readBuffer(window.root.resolve("sub/../opened.txt").toString())
                            .text(),
                    "another spelling of an open file's path is that file");

            assertNull(window.mcp.readBuffer(closed.toString()), "a file with no tab is not read from disk");
            assertNull(window.mcp.readBuffer(window.root.resolve("missing.txt").toString()));
            assertEquals(
                    2,
                    window.mcp.listOpenFiles().stream()
                            .filter(file -> file.path() != null)
                            .count(),
                    "and asking did not open it");
        }
    }

    @Test
    void getSelectionReportsTheCaretAndTheSelectedRangeOneBased() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "selection");
            Path file = Files.writeString(window.root.resolve("lines.txt"), "first\nsecond line\nthird\n");
            EditorBuffer buffer = open(async, window, file);

            // "cond li" on the second line, selected forwards: the caret is at its end.
            FxTestSupport.runOnFx(() -> buffer.getArea().selectRange(8, 15));
            McpBridge.Selection selection = window.mcp.getSelection();
            assertEquals(file.toString(), selection.path());
            assertEquals("cond li", selection.selectedText());
            assertEquals(2, selection.selStartLine());
            assertEquals(3, selection.selStartCol());
            assertEquals(2, selection.selEndLine());
            assertEquals(10, selection.selEndCol());
            assertEquals(2, selection.caretLine());
            assertEquals(10, selection.caretCol());

            // Across lines, selected backwards: the range is still start-to-end, the caret is at its start.
            FxTestSupport.runOnFx(() -> buffer.getArea().selectRange(20, 3));
            McpBridge.Selection backwards = window.mcp.getSelection();
            assertEquals("st\nsecond line\nth", backwards.selectedText());
            assertEquals(1, backwards.selStartLine());
            assertEquals(4, backwards.selStartCol());
            assertEquals(3, backwards.selEndLine());
            assertEquals(3, backwards.selEndCol());
            assertEquals(1, backwards.caretLine());
            assertEquals(4, backwards.caretCol());

            FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(0));
            McpBridge.Selection none = window.mcp.getSelection();
            assertEquals("", none.selectedText());
            assertEquals(1, none.selStartLine());
            assertEquals(1, none.selStartCol());
            assertEquals(1, none.selEndLine());
            assertEquals(1, none.selEndCol());
        }
    }

    @Test
    void diagnosticsAreThoseOfTheNamedFileAndSymbolsAreEmptyWithoutAServer() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "diagnostics");
            Path broken = Files.writeString(window.root.resolve("Broken.java"), "class Broken { int x = ; }\n");
            Path fine = Files.writeString(window.root.resolve("Fine.java"), "class Fine {}\n");
            open(async, window, fine);
            open(async, window, broken);
            LspCoordinator lsp = FxTestSupport.field(window.controller, "lspCoordinator");
            FxTestSupport.runOnFx(() -> {
                lsp.problems()
                        .put(
                                broken,
                                List.of(
                                        new LspDiagnostic(
                                                0,
                                                23,
                                                0,
                                                24,
                                                LspDiagnostic.Severity.ERROR,
                                                "illegal start of expression",
                                                "1610612940",
                                                "Java"),
                                        new LspDiagnostic(
                                                0, 6, 0, 12, LspDiagnostic.Severity.HINT, "unused", null, null)));
                lsp.problems()
                        .put(
                                fine,
                                List.of(new LspDiagnostic(
                                        0, 0, 0, 5, LspDiagnostic.Severity.WARNING, "not this file", null, "Java")));
            });
            async.onClose(() -> FxTestSupport.runOnFx(() -> lsp.problems().clear()));

            List<McpBridge.Diagnostic> named = window.mcp.getDiagnostics(broken.toString());
            assertEquals(2, named.size(), named.toString());
            assertEquals(1, named.get(0).line());
            assertEquals(24, named.get(0).col());
            assertEquals("ERROR", named.get(0).severity());
            assertEquals("illegal start of expression", named.get(0).message());
            assertEquals("Java: 1610612940", named.get(0).origin());
            assertEquals("HINT", named.get(1).severity());
            assertEquals("", named.get(1).origin());

            assertEquals(named, window.mcp.getDiagnostics(null), "no path means the active buffer's file");
            assertEquals(
                    List.of("not this file"),
                    window.mcp.getDiagnostics(fine.toString()).stream()
                            .map(McpBridge.Diagnostic::message)
                            .toList());
            assertEquals(
                    List.of(),
                    window.mcp.getDiagnostics(window.root.resolve("Other.java").toString()));

            assertEquals(List.of(), window.mcp.documentSymbols(broken.toString()), "no language server is running");
            assertEquals(List.of(), window.mcp.documentSymbols(null));
        }
    }

    // --- opening and commands -----------------------------------------------------------------------

    @Test
    void openFileOpensAProjectFileAtTheRequestedPositionAndReportsAMissingOne() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "opening");
            Path file = Files.writeString(window.root.resolve("three.txt"), "one\ntwo\nthree\n");
            Path missing = window.root.resolve("missing.txt");

            assertFalse(window.mcp.openFile(missing.toString(), 1, 1));
            assertFalse(Files.exists(missing), "a file that does not exist is not created by opening it");
            assertTrue(window.mcp.listOpenFiles().stream()
                    .noneMatch(open -> missing.toString().equals(open.path())));

            assertTrue(window.mcp.openFile(file.toString(), 3, 2));
            EditorBuffer buffer = awaitLoaded(async, window, file);
            SaveGuardsFxTest.awaitOnFx(
                    async,
                    "the caret to reach the requested line",
                    () -> buffer.getArea().getCurrentParagraph() == 2);
            McpBridge.Selection at = window.mcp.getSelection();
            assertEquals(file.toString(), at.path());
            assertEquals(3, at.caretLine());
            assertEquals(2, at.caretCol());

            // Asking again selects the tab that is there; it does not open a second one.
            assertTrue(window.mcp.openFile(file.toString(), 0, 0));
            async.awaitFx();
            assertEquals(
                    1,
                    window.mcp.listOpenFiles().stream()
                            .filter(open -> file.toString().equals(open.path()))
                            .count());
            assertEquals(3, window.mcp.getSelection().caretLine(), "and without a line the caret stays put");
        }
    }

    @Test
    void executeCommandRunsARegisteredCommandAndNothingForAnUnknownId() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "commands");
            Path file = Files.writeString(window.root.resolve("case.txt"), "shout\n");
            EditorBuffer buffer = open(async, window, file);

            List<McpBridge.CommandInfo> commands = window.mcp.listCommands();
            assertTrue(commands.stream().anyMatch(command -> "edit.selectAll".equals(command.id())));
            assertTrue(commands.stream()
                    .allMatch(command ->
                            command.title() != null && !command.title().isBlank()));
            assertEquals(
                    commands.size(),
                    commands.stream().map(McpBridge.CommandInfo::id).distinct().count(),
                    "one entry per id");

            assertFalse(window.mcp.executeCommand("no.such.command"));
            assertEquals("", window.mcp.getSelection().selectedText());

            assertTrue(window.mcp.executeCommand("edit.selectAll"));
            assertEquals("shout\n", window.mcp.getSelection().selectedText(), "the command ran in this window");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    // --- save_buffer: every way it can end -----------------------------------------------------------

    @Test
    void savingADirtyBufferWritesItAndSavingACleanOneLeavesTheSameBytes() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "save");
            Path file = Files.writeString(window.root.resolve("doc.txt"), "v1\n");
            Path other = Files.writeString(window.root.resolve("other.txt"), "untouched\n");
            EditorBuffer buffer = open(async, window, file);
            EditorBuffer otherBuffer = open(async, window, other);
            FxTestSupport.runOnFx(() -> {
                buffer.getArea().appendText("v2\n");
                otherBuffer.getArea().appendText("not asked for\n");
            });

            assertNull(window.mcp.saveBuffer(file.toString()));

            assertEquals("v1\nv2\n", Files.readString(file));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("untouched\n", Files.readString(other), "only the named buffer is saved, not the active one");
            assertTrue(FxTestSupport.callOnFx(otherBuffer::isDirty));

            // Clean now: saving again is still a truthful "saved" and changes nothing.
            assertNull(window.mcp.saveBuffer(file.toString()));
            assertEquals("v1\nv2\n", Files.readString(file));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            // No path: the active buffer.
            assertNull(window.mcp.saveBuffer(null));
            assertEquals("untouched\nnot asked for\n", Files.readString(other));
            assertFalse(FxTestSupport.callOnFx(otherBuffer::isDirty));

            String noBuffer =
                    window.mcp.saveBuffer(window.root.resolve("closed.txt").toString());
            assertNotNull(noBuffer);
            assertTrue(noBuffer.startsWith("No open buffer for"), noBuffer);
            assertFalse(Files.exists(window.root.resolve("closed.txt")), "a path with no buffer is not created");
        }
    }

    @Test
    void aWriteThatFailsIsReportedAndLeavesTheFileAndTheUnsavedStateAlone() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "failing");
            Path file = Files.writeString(window.root.resolve("doc.txt"), "v1\n");
            EditorBuffer buffer = open(async, window, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("v2\n"));
            AtomicInteger attempts = new AtomicInteger();
            window.workflows.setDocumentWriter((target, bytes, commit) -> {
                attempts.incrementAndGet();
                throw new IOException("No space left on device");
            });

            String failed = window.mcp.saveBuffer(file.toString());

            assertNotNull(failed, "a failed write is never 'saved'");
            assertTrue(failed.startsWith("Not saved"), failed);
            assertEquals(1, attempts.get());
            assertEquals("v1\n", Files.readString(file));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "the edit is still unsaved");
            assertEquals("v1\nv2\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(() -> window.workflows.hasPendingSave(buffer)), "nothing left pending");

            // The disk recovers: the same call now saves the same text.
            window.workflows.setDocumentWriter(com.editora.io.AtomicFileWrite::writeIf);
            assertNull(window.mcp.saveBuffer(file.toString()));
            assertEquals("v1\nv2\n", Files.readString(file));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void aReadOnlyFileIsOnlyReplacedOnceTheUserAgrees() throws Exception {
        Assumptions.assumeTrue(
                work.getFileSystem().supportedFileAttributeViews().contains("posix"));
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "readonly");
            Path file = Files.writeString(window.root.resolve("locked.txt"), "v1\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
            Assumptions.assumeFalse(Files.isWritable(file), "running as a user the mode does not bind (root)");
            EditorBuffer buffer = open(async, window, file);
            FxTestSupport.runOnFx(() -> {
                buffer.setViewMode(false);
                buffer.getArea().appendText("v2\n");
            });

            CountDownLatch declined = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            String refused = window.mcp.saveBuffer(file.toString());
            async.await(declined, "the read-only question");

            assertNotNull(refused, "the agent cannot answer the question itself");
            assertTrue(refused.startsWith("Not saved"), refused);
            assertEquals("v1\n", Files.readString(file));
            assertEquals("r--r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            CountDownLatch agreed = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.OK_DONE);
            assertNull(window.mcp.saveBuffer(file.toString()));
            async.await(agreed, "the read-only question, answered yes");

            assertEquals("v1\nv2\n", Files.readString(file));
            assertEquals(
                    "r--r--r--",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                    "the file keeps its mode");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void aFileThatNeedsElevationIsNotSavedAndNoPrivilegedWriteIsStarted() throws Exception {
        Assumptions.assumeTrue(
                com.editora.process.ElevatedSave.supportedOnOs(System.getProperty("os.name")),
                "elevated save is not offered on this platform");
        Assumptions.assumeTrue(
                work.getFileSystem().supportedFileAttributeViews().contains("posix"));
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "elevated");
            Path lockedDir = Files.createDirectories(window.root.resolve("etc"));
            Path file = Files.writeString(lockedDir.resolve("conf"), "v1\n");
            EditorBuffer buffer = open(async, window, file);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
            Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("r-xr-xr-x"));
            async.onClose(() -> Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("rwxr-xr-x")));
            Assumptions.assumeFalse(Files.isWritable(file), "running as a user the mode does not bind (root)");
            AtomicInteger elevatedWrites = new AtomicInteger();
            FxTestSupport.runOnFx(() -> {
                ConfigManager config = FxTestSupport.field(window.controller, "config");
                config.getSettings().setAdminSave(true);
                window.workflows.adminToolAvailable = true;
                window.workflows.elevatedWriter = (target, bytes) -> {
                    elevatedWrites.incrementAndGet();
                    return new FileWorkflowCoordinator.AdminResult(0, "", -1, -1);
                };
                buffer.setViewMode(false);
                buffer.getArea().appendText("v2\n");
            });
            assertTrue(FxTestSupport.callOnFx(() -> window.workflows.adminSaveApplicable(file)), "precondition");

            String refused = window.mcp.saveBuffer(file.toString());

            assertNotNull(refused);
            assertTrue(refused.startsWith("Not saved"), refused);
            assertEquals(0, elevatedWrites.get(), "an agent's save never asks for root");
            assertEquals("v1\n", Files.readString(file));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    // --- the project: search, TODOs, Git ------------------------------------------------------------

    @Test
    void findInFilesSearchesTheProjectOnDiskAndTheUnsavedTextOfOpenBuffers() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "search");
            Path onDisk = Files.createDirectories(window.root.resolve("src")).resolve("Disk.java");
            Files.writeString(onDisk, "class Disk {\n    int needle = 1;\n    int Needle2 = 2;\n}\n");
            Path edited = Files.writeString(window.root.resolve("edited.txt"), "nothing here\n");
            Path outside = Files.writeString(work.resolve("outside.txt"), "needle outside the project\n");
            EditorBuffer buffer = open(async, window, edited);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("an unsaved needle\n"));

            List<McpBridge.SearchMatch> hits = window.mcp.findInFiles("needle", false, false, false);

            assertEquals(
                    List.of("Disk.java:2:9", "Disk.java:3:9", "edited.txt:2:12"),
                    hits.stream().map(WindowMcpBridgeFxTest::where).sorted().toList());
            McpBridge.SearchMatch first = hits.stream()
                    .filter(hit -> where(hit).equals("Disk.java:2:9"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(onDisk.toString(), first.file(), "an absolute path the client can hand to open_file");
            assertEquals("    int needle = 1;", first.lineText());
            assertTrue(
                    hits.stream().noneMatch(hit -> hit.file().equals(outside.toString())),
                    "a file beside the project is not searched");
            assertEquals("nothing here\n", Files.readString(edited), "searching unsaved text does not save it");

            assertEquals(
                    List.of("Disk.java:2:9", "edited.txt:2:12"),
                    window.mcp.findInFiles("needle", true, false, false).stream()
                            .map(WindowMcpBridgeFxTest::where)
                            .sorted()
                            .toList(),
                    "match case");
            assertEquals(
                    List.of("Disk.java:2:9", "edited.txt:2:12"),
                    window.mcp.findInFiles("needle", false, false, true).stream()
                            .map(WindowMcpBridgeFxTest::where)
                            .sorted()
                            .toList(),
                    "whole word: not Needle2");
            assertEquals(
                    List.of("Disk.java:2:5", "Disk.java:3:5"),
                    window.mcp.findInFiles("int \\w+ = \\d", false, true, false).stream()
                            .map(WindowMcpBridgeFxTest::where)
                            .sorted()
                            .toList(),
                    "a regular expression");
            assertEquals(List.of(), window.mcp.findInFiles("int \\w+ = \\d", false, false, false), "taken literally");
            assertEquals(List.of(), window.mcp.findInFiles("no such text anywhere", false, false, false));
        }
    }

    @Test
    void gitStatusReportsTheBranchAndEachChangedFileOfTheProjectRepository() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path root = Files.createDirectories(work.resolve("repo"));
            git(root, "init", "-q", "-b", "main");
            git(root, "config", "user.email", "editora-test@example.invalid");
            git(root, "config", "user.name", "Editora Test");
            git(root, "config", "core.autocrlf", "false");
            Path tracked = Files.writeString(root.resolve("tracked.txt"), "v1\n");
            Files.writeString(root.resolve("renamed-from.txt"), "a file that keeps its content\n");
            Files.writeString(root.resolve("quiet.txt"), "unchanged\n");
            git(root, "add", "-A");
            git(root, "commit", "-q", "-m", "initial");
            Window window = projectWindow(async, root);

            McpBridge.GitState clean = window.mcp.gitStatus();
            assertTrue(clean.repo());
            assertEquals(root.toRealPath(), Path.of(clean.root()).toRealPath());
            assertEquals("main", clean.branch());
            assertEquals("", clean.upstream(), "no upstream is configured");
            assertEquals(List.of(), clean.files(), "a clean tree lists nothing");

            Files.writeString(tracked, "v2\n");
            Files.writeString(root.resolve("new.txt"), "untracked\n");
            git(root, "mv", "renamed-from.txt", "renamed-to.txt");

            McpBridge.GitState changed = window.mcp.gitStatus();
            assertEquals(
                    List.of("new.txt ??", "renamed-to.txt R. <- renamed-from.txt", "tracked.txt .M"),
                    changed.files().stream()
                            .map(file -> file.path() + " " + file.index() + file.worktree()
                                    + (file.origPath() == null ? "" : " <- " + file.origPath()))
                            .sorted()
                            .toList());
            assertEquals("main", changed.branch());
            assertEquals(0, changed.ahead());
            assertEquals(0, changed.behind());

            // Reading the status is all it does.
            assertEquals("v2\n", Files.readString(tracked));
            assertEquals("", git(root, "diff", "--cached", "--name-only", "--", "tracked.txt"), "nothing was staged");

            FxTestSupport.runOnFx(() -> FxTestSupport.<ConfigManager>field(window.controller, "config")
                    .getSettings()
                    .setGitSupport(false));
            McpBridge.GitState off = window.mcp.gitStatus();
            assertFalse(off.repo(), "Git switched off in Settings");
            assertNull(off.branch());
            assertEquals(List.of(), off.files());
        }
    }

    @Test
    void gitStatusOfAProjectThatIsNotARepositorySaysSo() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window window = projectWindow(async, "plain");
            Files.writeString(window.root.resolve("a.txt"), "a\n");
            // A repository further up (the checkout these tests run from, say) is not this project's.
            Assumptions.assumeFalse(insideAWorkTree(window.root), "the temp directory is inside a Git work tree");

            McpBridge.GitState state = window.mcp.gitStatus();

            assertFalse(state.repo());
            assertNull(state.root());
            assertEquals(List.of(), state.files());
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    /** A project window, its MCP face, and the folder it has open. */
    private record Window(MainController controller, McpBridge mcp, FileWorkflowCoordinator workflows, Path root) {}

    private Window projectWindow(AsyncTestScope async, String name) throws Exception {
        return projectWindow(async, Files.createDirectories(work.resolve(name)));
    }

    /** Opens {@code root} as a project in a window of its own, as "Open Project" does. */
    private Window projectWindow(AsyncTestScope async, Path root) throws Exception {
        FxWindowFixture fx = async.own(FxWindowFixture.create(Files.createTempDirectory(work, "config"), shared -> {}));
        return projectWindow(async, fx, root);
    }

    static MainController openProject(AsyncTestScope async, FxWindowFixture fx, Path root) throws Exception {
        Project project = FxTestSupport.callOnFx(
                () -> fx.shared.projects().createOrGet(root.getFileName().toString(), root));
        FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(project));
        async.awaitFx();
        List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
        for (Object holder : holders) {
            if (project.id().equals(FxTestSupport.call(holder, "key", new Class<?>[] {}))) {
                return (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
            }
        }
        throw new IllegalStateException("no window for " + root);
    }

    private Window projectWindow(AsyncTestScope async, FxWindowFixture fx, Path root) throws Exception {
        MainController controller = openProject(async, fx, root);
        return new Window(controller, controller, FxTestSupport.field(controller, "fileWorkflows"), root);
    }

    private static EditorBuffer open(AsyncTestScope async, Window window, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> window.workflows.openPath(file));
        return awaitLoaded(async, window, file);
    }

    /** The buffer of the selected tab, once its document has arrived from disk. */
    private static EditorBuffer awaitLoaded(AsyncTestScope async, Window window, Path file) throws Exception {
        return awaitLoaded(async, window.controller, file);
    }

    static EditorBuffer awaitLoaded(AsyncTestScope async, MainController controller, Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(controller, "fileWorkflows");
        CountDownLatch loaded = new CountDownLatch(1);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorArea area = FxTestSupport.field(controller, "editorArea");
            EditorBuffer opening = (EditorBuffer) area.selectedTab().getUserData();
            workflows.afterBufferLoad(opening, loaded::countDown);
            return opening;
        });
        async.await(loaded, "the load of " + file.getFileName());
        async.awaitFx();
        assertEquals(file, FxTestSupport.callOnFx(buffer::getPath));
        return buffer;
    }

    private static String where(McpBridge.SearchMatch hit) {
        return Path.of(hit.file()).getFileName() + ":" + hit.line() + ":" + hit.col();
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version")
                            .redirectErrorStream(true)
                            .start()
                            .waitFor()
                    == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean insideAWorkTree(Path dir) throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }

    static String git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(repo.toFile()).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + err + out);
        }
        return out;
    }
}

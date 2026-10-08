package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;

import com.editora.config.Project;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.update.ReleaseInfo;
import com.editora.update.UpdateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the window itself decides — as opposed to the coordinators it hosts: update notices, the MCP server's
 * lifetime, the project windows, and a handful of commands whose whole logic lives in the controller.
 */
@Tag("fx")
class MainControllerWindowFxTest {

    @TempDir
    Path dir;

    private AsyncTestScope async;
    private FxWindowFixture fx;
    private MainController controller;
    private Settings settings;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        async = new AsyncTestScope();
        fx = async.own(FxWindowFixture.create());
        controller = fx.controller;
        settings = fx.shared.getSettings();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            FxTestSupport.runOnFx(() -> {
                // The MCP server and the known update are per application, not per window: leave neither
                // behind for the next test class in this JVM.
                if (settings.isMcpSupport()) {
                    settings.setMcpSupport(false);
                    FxTestSupport.invoke(controller, "applyMcpSupport");
                }
                setStatic("latestKnownUpdate", null);
            });
        } finally {
            async.close();
        }
    }

    private static void setStatic(String field, Object value) {
        try {
            java.lang.reflect.Field f = MainController.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object getStatic(String field) {
        try {
            java.lang.reflect.Field f = MainController.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private EditorBuffer active() {
        return (EditorBuffer) FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
    }

    /** Types {@code query} into the find bar and lets it search now rather than after its typing delay. */
    private static void find(FindReplaceBar bar, String query) {
        bar.show(false);
        FxTestSupport.<javafx.scene.control.TextField>field(bar, "findField").setText(query);
        FxTestSupport.invoke(bar, "recompute");
    }

    private String echoNow() {
        StatusBar status = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    private Label statusSegment(String name) {
        StatusBar status = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.field(status, name);
    }

    private void outcome(UpdateService.Outcome outcome, boolean manual) {
        FxTestSupport.call(
                controller,
                "onUpdateOutcome",
                new Class<?>[] {UpdateService.Outcome.class, boolean.class},
                outcome,
                manual);
    }

    private List<MainController> windows() {
        List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
        return holders.stream()
                .map(h -> (MainController) FxTestSupport.call(h, "controller", new Class<?>[] {}))
                .toList();
    }

    private MainController windowFor(String projectId) {
        return windows().stream()
                .filter(c -> projectId.equals(FxTestSupport.<String>field(c, "projectKey")))
                .findFirst()
                .orElse(null);
    }

    // --- updates ---------------------------------------------------------------------------------

    @Test
    void aNewerReleaseIsAnnouncedAndItsNoticeGoesOnceItsPageWasOpened() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Label notice = statusSegment("update");
            assertFalse(notice.isVisible());

            outcome(new UpdateService.Outcome(true, new ReleaseInfo("99.0.0", "", "Ninety-nine"), null), false);
            assertTrue(notice.isVisible(), "even an automatic check shows what it found");
            assertEquals(tr("statusbar.update", "99.0.0"), notice.getText());
            assertEquals(tr("status.update.available", "99.0.0"), echoNow());

            FxTestSupport.invoke(controller, "openUpdateDownloadPage");
            assertEquals("99.0.0", settings.getDismissedUpdateVersion(), "seen: not announced again");
            assertFalse(notice.isVisible());

            // The same release found again stays quiet; a newer one is announced.
            outcome(new UpdateService.Outcome(true, new ReleaseInfo("99.0.0", "", ""), null), false);
            assertFalse(notice.isVisible());
            outcome(new UpdateService.Outcome(true, new ReleaseInfo("99.1.0", "", ""), null), false);
            assertTrue(notice.isVisible());
            assertEquals(tr("statusbar.update", "99.1.0"), notice.getText());
        });
    }

    @Test
    void onlyACheckTheUserAskedForReportsThatNothingIsNewOrThatItFailed() throws Exception {
        FxTestSupport.runOnFx(() -> {
            controller.setStatus("before");
            ReleaseInfo current = new ReleaseInfo(com.editora.AppInfo.VERSION, "", "");

            outcome(new UpdateService.Outcome(false, null, "no route to host"), false);
            assertEquals("before", echoNow(), "an automatic check fails silently");
            outcome(new UpdateService.Outcome(false, current, null), false);
            assertEquals("before", echoNow(), "and says nothing when up to date");

            outcome(new UpdateService.Outcome(false, null, "no route to host"), true);
            assertEquals(tr("status.update.failed", "no route to host"), echoNow());
            outcome(new UpdateService.Outcome(false, current, null), true);
            assertEquals(tr("status.update.upToDate", com.editora.AppInfo.VERSION), echoNow());
            assertFalse(statusSegment("update").isVisible());
        });
    }

    @Test
    void anUpToDateResultWithdrawsANoticeShownEarlier() throws Exception {
        FxTestSupport.runOnFx(() -> {
            outcome(new UpdateService.Outcome(true, new ReleaseInfo("99.0.0", "", ""), null), false);
            assertTrue(statusSegment("update").isVisible());
            outcome(new UpdateService.Outcome(false, new ReleaseInfo("1.0.0", "", ""), null), false);
            assertFalse(statusSegment("update").isVisible());
            assertNull(getStatic("latestKnownUpdate"));

            // With no update known, "open the download page" has nothing to dismiss.
            String dismissed = settings.getDismissedUpdateVersion();
            FxTestSupport.invoke(controller, "openUpdateDownloadPage");
            assertEquals(dismissed, settings.getDismissedUpdateVersion());
        });
    }

    // --- MCP -------------------------------------------------------------------------------------

    @Test
    void theMcpServerStartsOnlyAfterTheNoticeIsAcceptedAndStopsWhenSwitchedOff() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Label indicator = statusSegment("mcp");
            assertFalse(settings.isMcpSupport());

            FxTestSupport.invoke(controller, "copyMcpEndpoint");
            assertEquals(tr("status.mcp.notRunning"), echoNow());

            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    ButtonBar.ButtonData.CANCEL_CLOSE, () -> FxTestSupport.invoke(controller, "toggleMcpSupport"));
            assertEquals(1, asked.size());
            assertEquals(tr("dialog.mcp.enableHeader"), asked.get(0).header());
            assertFalse(settings.isMcpSupport(), "declined: stays off");
            assertNull(getStatic("mcpServer"));
            assertFalse(indicator.isVisible());

            asked = SettingsRig.answering(
                    ButtonBar.ButtonData.OK_DONE, () -> FxTestSupport.invoke(controller, "toggleMcpSupport"));
            assertEquals(1, asked.size());
            assertTrue(settings.isMcpSupport());
            com.editora.mcp.McpServer server = (com.editora.mcp.McpServer) getStatic("mcpServer");
            assertNotNull(server);
            assertTrue(server.isRunning());
            assertSame(controller, getStatic("mcpOwner"));
            assertTrue(indicator.isVisible());
            assertEquals(tr("status.toggle.mcp", tr("common.on")), echoNow());
            assertTrue(server.url().startsWith("http://127.0.0.1:"), "loopback only: " + server.url());

            FxTestSupport.invoke(controller, "copyMcpEndpoint");
            assertEquals(tr("status.mcp.endpointCopied"), echoNow());
            String copied = Clipboard.getSystemClipboard().getString();
            assertEquals(
                    "claude mcp add --transport http editora " + server.url() + " --header \"Authorization: Bearer "
                            + server.token() + "\"",
                    copied);

            // Off again needs no notice.
            asked = SettingsRig.answering(
                    ButtonBar.ButtonData.OK_DONE, () -> FxTestSupport.invoke(controller, "toggleMcpSupport"));
            assertEquals(List.of(), asked);
            assertFalse(settings.isMcpSupport());
            assertFalse(server.isRunning());
            assertNull(getStatic("mcpServer"));
            assertFalse(indicator.isVisible());
            assertEquals(tr("status.toggle.mcp", tr("common.off")), echoNow());
        });
    }

    @Test
    void theMcpServerMovesToAnotherWindowWhenItsOwnerCloses() throws Exception {
        Path root = Files.createDirectories(dir.resolve("proj"));
        FxTestSupport.runOnFx(() -> {
            settings.setMcpSupport(true);
            FxTestSupport.invoke(controller, "applyMcpSupport");
            com.editora.mcp.McpServer server = (com.editora.mcp.McpServer) getStatic("mcpServer");
            assertSame(controller, getStatic("mcpOwner"));
            String url = server.url();

            Project project = fx.shared.projects().createOrGet("proj", root);
            fx.windowManager.openOrFocus(project);
            MainController second = windowFor(project.id());
            assertNotNull(second);

            FxTestSupport.invoke(second, "stopMcpIfOwner"); // not the owner: nothing changes
            assertSame(controller, getStatic("mcpOwner"));

            FxTestSupport.invoke(controller, "stopMcpIfOwner"); // the owner closes
            assertSame(second, getStatic("mcpOwner"), "the surviving window takes the server over");
            assertSame(server, getStatic("mcpServer"));
            assertTrue(server.isRunning());
            assertEquals(url, server.url(), "same endpoint: a connected agent is not dropped");
            StatusBar status = FxTestSupport.field(second, "statusBar");
            assertTrue(FxTestSupport.<Label>field(status, "mcp").isVisible());

            // Switched off: the window that owns it now is the one that stops it.
            settings.setMcpSupport(false);
            FxTestSupport.invoke(second, "applyMcpSupport");
            assertFalse(server.isRunning());
            assertNull(getStatic("mcpOwner"));
        });
    }

    @Test
    void theLastWindowClosingStopsTheMcpServer() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setMcpSupport(true);
            FxTestSupport.invoke(controller, "applyMcpSupport");
            com.editora.mcp.McpServer server = (com.editora.mcp.McpServer) getStatic("mcpServer");
            assertTrue(server.isRunning());

            FxTestSupport.invoke(controller, "stopMcpIfOwner");
            assertFalse(server.isRunning());
            assertNull(getStatic("mcpServer"));
            assertNull(getStatic("mcpOwner"));
            settings.setMcpSupport(false);
        });
    }

    // --- projects --------------------------------------------------------------------------------

    @Test
    void openingAFolderAsAProjectGivesItAWindowOfItsOwnOnce() throws Exception {
        Path root = Files.createDirectories(dir.resolve("alpha"));
        FxTestSupport.runOnFx(() -> {
            assertEquals(1, windows().size());
            FxTestSupport.invokeWith(controller, "openProjectRoot", Path.class, root);
            Project alpha = fx.shared.projects().list().stream()
                    .filter(p -> p.name().equals("alpha"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(2, windows().size());
            MainController alphaWindow = windowFor(alpha.id());
            assertNotNull(alphaWindow);

            FxTestSupport.invokeWith(controller, "openProjectRoot", Path.class, root);
            assertEquals(2, windows().size(), "opening it again focuses the window it already has");

            assertEquals(
                    Boolean.TRUE,
                    FxTestSupport.invokeWith(alphaWindow, "switchToProject", Project.class, alpha),
                    "its own project: already here");
            assertEquals(2, windows().size());
            assertEquals(Boolean.FALSE, FxTestSupport.invokeWith(controller, "switchToProject", Project.class, null));

            // "No Project" from a project window is the global window, which is this one.
            assertEquals(
                    Boolean.TRUE,
                    FxTestSupport.invokeWith(alphaWindow, "switchToProject", Project.class, ProjectCombo.NO_PROJECT));
            assertEquals(2, windows().size());

            assertEquals("alpha", controller.projectDisplayName(alpha.id()));
            assertEquals(tr("scope.general"), controller.projectDisplayName(""));
            assertEquals(tr("scope.general"), controller.projectDisplayName(null));
            assertEquals("a-project-deleted-since", controller.projectDisplayName("a-project-deleted-since"));
        });
    }

    @Test
    void aFileBelongsToTheDeepestProjectThatContainsIt() throws Exception {
        Path outer = Files.createDirectories(dir.resolve("outer"));
        Path inner = Files.createDirectories(outer.resolve("modules").resolve("inner"));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        FxTestSupport.runOnFx(() -> {
            Project outerProject = fx.shared.projects().createOrGet("outer", outer);
            Project innerProject = fx.shared.projects().createOrGet("inner", inner);

            assertEquals(innerProject, owning(inner.resolve("src").resolve("A.java")));
            assertEquals(outerProject, owning(outer.resolve("README.md")));
            assertNull(owning(elsewhere.resolve("notes.txt")));

            settings.setProjectSupport(false);
            assertNull(owning(inner.resolve("src").resolve("A.java")), "no projects, no owner");
        });
    }

    private Project owning(Path file) {
        return (Project) FxTestSupport.invokeWith(controller, "owningProject", Path.class, file);
    }

    @Test
    void closingAProjectAsksFirstAndTheGlobalWindowHasNoneToClose() throws Exception {
        Path root = Files.createDirectories(dir.resolve("beta"));
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invoke(controller, "closeProject");
            assertEquals(tr("status.noProjectOpen"), echoNow());
            FxTestSupport.invoke(controller, "deleteProject");
            assertEquals(tr("status.noProjectToDelete"), echoNow());

            Project beta = fx.shared.projects().createOrGet("beta", root);
            fx.windowManager.openOrFocus(beta);
            MainController betaWindow = windowFor(beta.id());

            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    ButtonBar.ButtonData.CANCEL_CLOSE, () -> FxTestSupport.invoke(betaWindow, "closeProject"));
            assertEquals(1, asked.size());
            assertEquals(tr("dialog.closeProject.body", "beta"), asked.get(0).content());
            assertSame(betaWindow, windowFor(beta.id()), "declined: the window stays");

            SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, () -> FxTestSupport.invoke(betaWindow, "closeProject"));
            assertNull(windowFor(beta.id()));
            assertEquals(1, windows().size());
            assertTrue(fx.shared.projects().list().contains(beta), "closed, not deleted");
        });
    }

    @Test
    void deletingAProjectRemovesItsEntryAndSessionButNotItsFolder() throws Exception {
        Path root = Files.createDirectories(dir.resolve("gamma"));
        Path file = Files.writeString(root.resolve("keep.txt"), "kept\n");
        FxTestSupport.runOnFx(() -> {
            Project gamma = fx.shared.projects().createOrGet("gamma", root);
            fx.windowManager.openOrFocus(gamma);
            MainController gammaWindow = windowFor(gamma.id());
            assertNotNull(gammaWindow);

            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    ButtonBar.ButtonData.CANCEL_CLOSE, () -> FxTestSupport.invoke(gammaWindow, "deleteProject"));
            assertEquals(1, asked.size());
            assertEquals(tr("dialog.deleteProject.body", "gamma"), asked.get(0).content());
            assertTrue(fx.shared.projects().list().contains(gamma));
            assertNotNull(windowFor(gamma.id()));

            // Deleted from the global window, while the project has a window of its own open.
            SettingsRig.answering(
                    ButtonBar.ButtonData.OK_DONE,
                    () -> FxTestSupport.invokeWith(controller, "deleteProject", Project.class, gamma));
            assertFalse(fx.shared.projects().list().contains(gamma));
            assertNull(windowFor(gamma.id()), "its window was closed first");
            assertFalse(Files.exists(fx.shared.projects().stateFile(gamma)));
        });
        assertEquals("kept\n", Files.readString(file), "the folder and its files are untouched");
    }

    // --- commands whose logic is the controller's ------------------------------------------------

    @Test
    void thePersonalDictionaryFileIsCreatedOnFirstOpenAndTheBundledOneOpensReadOnly() throws Exception {
        Path dictionary =
                FxTestSupport.callOnFx(() -> FxTestSupport.<com.editora.config.ConfigManager>field(controller, "config")
                        .getDictionaryFile());
        Files.deleteIfExists(dictionary);
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(controller, "openPersonalDictionary"));
        EditorBuffer personal = WindowMcpBridgeFxTest.awaitLoaded(async, controller, dictionary);
        assertTrue(Files.isRegularFile(dictionary), "created so there is something to edit");
        assertEquals("", FxTestSupport.callOnFx(personal::getContent));

        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invoke(controller, "openTechnicalDictionary");
            EditorBuffer technical = active();
            assertEquals("technical.txt", technical.getDisplayName());
            assertTrue(technical.isViewMode(), "the bundled list is for reading");
            assertNull(technical.getPath());
            assertTrue(technical.getContent().lines().count() > 100, "the list itself, not a stub");

            // The banner's "Enable Editing" turns read-only off; on an editable buffer it does nothing.
            FxTestSupport.invokeWith(controller, "enableEditing", EditorBuffer.class, technical);
            assertFalse(technical.isViewMode());
            assertEquals(tr("status.editingEnabled"), echoNow());
            controller.setStatus("unchanged");
            FxTestSupport.invokeWith(controller, "enableEditing", EditorBuffer.class, technical);
            FxTestSupport.invokeWith(controller, "enableEditing", EditorBuffer.class, null);
            assertEquals("unchanged", echoNow());
        });
    }

    @Test
    void theWheelZoomsTheEditorTextWithinItsLimits() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setFontZoom(1.0);
            controller.zoomFromWheel(wheel(40));
            assertEquals(1.1, settings.getFontZoom(), 1e-9);
            assertEquals(tr("status.textZoom", 110), echoNow());
            controller.zoomFromWheel(wheel(-40));
            controller.zoomFromWheel(wheel(-40));
            assertEquals(0.9, settings.getFontZoom(), 1e-9);

            settings.setFontZoom(3.0);
            controller.setStatus("at the limit");
            controller.zoomFromWheel(wheel(40));
            assertEquals(3.0, settings.getFontZoom(), 1e-9);
            assertEquals("at the limit", echoNow(), "no change, nothing to announce");
            settings.setFontZoom(0.5);
            controller.zoomFromWheel(wheel(-40));
            assertEquals(0.5, settings.getFontZoom(), 1e-9);

            controller.textZoom(0);
            assertEquals(1.0, settings.getFontZoom(), 1e-9);
            assertEquals(tr("status.textZoom", 100), echoNow());
        });
    }

    private static ScrollEvent wheel(double deltaY) {
        return new ScrollEvent(
                ScrollEvent.SCROLL,
                0,
                0,
                0,
                0,
                false,
                true,
                false,
                false,
                false,
                false,
                0,
                deltaY,
                0,
                deltaY,
                ScrollEvent.HorizontalTextScrollUnits.NONE,
                0,
                ScrollEvent.VerticalTextScrollUnits.NONE,
                0,
                0,
                null);
    }

    @Test
    void aTypstFilesRootIsItsPackageFolderElseItsOwnFolder() throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("paper"));
        Files.writeString(pkg.resolve("typst.toml"), "[package]\n");
        Path chapter = Files.createDirectories(pkg.resolve("chapters")).resolve("one.typ");
        Path loose = Files.createDirectories(dir.resolve("loose")).resolve("note.typ");
        FxTestSupport.runOnFx(() -> {
            assertEquals(pkg, typstRoot(chapter), "the folder with typst.toml, above the file");
            assertEquals(loose.getParent(), typstRoot(loose), "no package: the file's own folder");
            assertNull(typstRoot(null));
        });
    }

    private Path typstRoot(Path file) {
        return (Path) FxTestSupport.invokeWith(controller, "resolveTypstRoot", Path.class, file);
    }

    @Test
    void renamingATabsFileRefusesANameThatIsTakenAndFollowsTheFileOtherwise() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "A\n");
        Path taken = Files.writeString(dir.resolve("taken.txt"), "someone else's\n");
        EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, a);
        FxTestSupport.runOnFx(() -> {
            controller.setStatus("unchanged");
            controller.renameFileTo(buffer, a, a); // the same name: nothing to do
            assertEquals("unchanged", echoNow());

            controller.renameFileTo(buffer, a, taken);
            assertEquals(tr("status.renameFailedExists", "taken.txt"), echoNow());
            assertEquals(a, buffer.getPath());

            controller.renameFileTo(buffer, a, dir.resolve("no-such-folder").resolve("b.txt"));
            assertTrue(echoNow().startsWith(tr("status.renameFailed", "").strip()), echoNow());
            assertEquals(a, buffer.getPath(), "a rename that failed leaves the tab on its file");
        });
        assertEquals("someone else's\n", Files.readString(taken));
        assertEquals("A\n", Files.readString(a));

        Path b = dir.resolve("b.md");
        FxTestSupport.runOnFx(() -> controller.renameFileTo(buffer, a, b));
        FxTestSupport.drainFx();
        assertFalse(Files.exists(a));
        assertEquals("A\n", Files.readString(b));
        FxTestSupport.runOnFx(() -> {
            assertEquals(b, buffer.getPath());
            assertTrue(
                    fx.shared.recentFiles().getList().stream().anyMatch(p -> p.endsWith("b.md")),
                    "the new name is the recent file");
        });
    }

    @Test
    void floatingAndMaximizingActOnTheOpenToolWindowAndSayWhenThereIsNone() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ToolWindowManager tools = FxTestSupport.field(controller, "toolWindows");
            tools.closeAllOpen();
            FxTestSupport.invoke(controller, "toggleMaximizedToolWindow");
            assertEquals(tr("status.toolwindow.noMaximizeTarget"), echoNow());
            controller.setStatus("");
            FxTestSupport.invoke(controller, "toggleFloatingToolWindow");
            assertEquals(tr("status.toolwindow.noMaximizeTarget"), echoNow());
            FxTestSupport.invoke(controller, "showSplitToolWindowPalette");
            assertEquals(tr("status.toolwindow.nothingToSplit"), echoNow());

            ToolWindow bookmarks = tools.getRegisteredToolWindows().stream()
                    .filter(t -> t.getId().equals("bookmarks"))
                    .findFirst()
                    .orElseThrow();
            tools.setVisible(bookmarks, true);
            tools.open(bookmarks);
            assertTrue(tools.isOpen(bookmarks));

            FxTestSupport.invoke(controller, "toggleMaximizedToolWindow");
            assertTrue(tools.isMaximized(bookmarks));
            assertEquals(tr("status.toolwindow.maximized", bookmarks.getTitle()), echoNow());
            FxTestSupport.invoke(controller, "toggleMaximizedToolWindow");
            assertFalse(tools.isMaximized(bookmarks));
            assertEquals(tr("status.toolwindow.restored", bookmarks.getTitle()), echoNow());

            FxTestSupport.invoke(controller, "toggleFloatingToolWindow");
            assertTrue(tools.isFloating(bookmarks));
            assertEquals(tr("status.toolwindow.floated", bookmarks.getTitle()), echoNow());
            FxTestSupport.invoke(controller, "toggleFloatingToolWindow");
            assertFalse(tools.isFloating(bookmarks));
            assertEquals(tr("status.toolwindow.docked", bookmarks.getTitle()), echoNow());
            tools.close(bookmarks);
        });
    }

    @Test
    void theGoToPrefixPressedInsideAToolWindowClosesItInstead() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ToolWindowManager tools = FxTestSupport.field(controller, "toolWindows");
            tools.closeAllOpen();
            ToolWindow bookmarks = tools.getRegisteredToolWindows().stream()
                    .filter(t -> t.getId().equals("bookmarks"))
                    .findFirst()
                    .orElseThrow();
            tools.setVisible(bookmarks, true);
            tools.open(bookmarks);
            KeyEvent altG = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.G, false, false, true, false);

            javafx.event.Event.fireEvent(bookmarks.getContent(), altG);
            assertFalse(tools.isOpen(bookmarks), "M-g in the panel closes the panel");

            // In the editor the same key is the go-to prefix: nothing is closed by it.
            tools.open(bookmarks);
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("text\n");
            FxTestSupport.call(
                    controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            javafx.event.Event.fireEvent(buffer.getArea(), altG);
            assertTrue(tools.isOpen(bookmarks));
            javafx.event.Event.fireEvent(
                    buffer.getArea(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.G, false, true, false, false)); // C-g: cancel
            tools.close(bookmarks);
        });
    }

    @Test
    void selectingEveryFindMatchNeedsTheFindBarAndMultipleCursors() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("one two one three one\n");
            FxTestSupport.call(
                    controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            FindReplaceBar find = FxTestSupport.field(controller, "findBar");

            FxTestSupport.invoke(controller, "selectAllFindMatches");
            assertEquals(tr("status.find.notOpen"), echoNow());

            settings.setMultiCaret(false);
            FxTestSupport.invoke(controller, "selectAllOccurrences");
            assertEquals(tr("status.multiCaret.disabled"), echoNow());
            settings.setMultiCaret(true);

            find(find, "one");
            FxTestSupport.invoke(controller, "selectAllFindMatches");
            assertEquals(tr("status.occurrences.selected", 3), echoNow());
            assertFalse(find.isShown(), "the bar gives the focus back to the editor");

            find(find, "nowhere in the text");
            FxTestSupport.invoke(controller, "selectAllFindMatches");
            assertEquals(tr("status.occurrences.none"), echoNow());
        });
    }

    @Test
    void closingTheWelcomeOrDoctorTabForgetsItSoItCanBeOpenedAgain() throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invoke(controller, "showWelcome");
            Tab welcome = FxTestSupport.field(controller, "welcomeTab");
            assertNotNull(welcome);
            FxTestSupport.invoke(controller, "showWelcome");
            assertSame(welcome, FxTestSupport.field(controller, "welcomeTab"), "one Welcome tab, selected again");

            javafx.event.Event.fireEvent(welcome, new javafx.event.Event(Tab.CLOSED_EVENT));
            assertNull(FxTestSupport.field(controller, "welcomeTab"));
        });
    }
}

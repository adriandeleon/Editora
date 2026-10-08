package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.config.ConfigManager;
import com.editora.config.PathKeys;
import com.editora.config.Project;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
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

/** The window's own chrome and small services: toolbar buttons, the menu bar, previews, project settings. */
@Tag("fx")
class MainControllerChromeFxTest {

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
        async.close();
    }

    private String echo() {
        StatusBar status = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    private EditorBuffer active() {
        return (EditorBuffer) FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
    }

    private EditorBuffer addBuffer(String content) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setContent(content);
        FxTestSupport.call(controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
        return buffer;
    }

    private Button button(String field) {
        return FxTestSupport.field(controller, field);
    }

    private MainController windowFor(Project project) {
        for (Object holder : FxTestSupport.<List<?>>field(fx.windowManager, "windows")) {
            MainController c = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
            if (project.id().equals(FxTestSupport.<String>field(c, "projectKey"))) {
                return c;
            }
        }
        return null;
    }

    @Test
    void theToolbarsEditButtonsActOnTheActiveBuffer() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = addBuffer("");
            buffer.getArea().appendText("hello");
            buffer.getArea().getUndoManager().preventMerge();
            buffer.getArea().appendText(" world");
            assertEquals("hello world", buffer.getContent());

            button("undoButton").fire();
            assertEquals("hello", buffer.getContent());
            button("redoButton").fire();
            assertEquals("hello world", buffer.getContent());

            buffer.getArea().selectRange(0, 5);
            button("copyButton").fire();
            assertEquals("hello", Clipboard.getSystemClipboard().getString());
            assertEquals("hello world", buffer.getContent(), "Copy leaves the text");

            buffer.getArea().selectRange(5, 11);
            button("cutButton").fire();
            assertEquals(" world", Clipboard.getSystemClipboard().getString());
            assertEquals("hello", buffer.getContent());

            ClipboardContent paste = new ClipboardContent();
            paste.putString(", pasted");
            Clipboard.getSystemClipboard().setContent(paste);
            buffer.getArea().moveTo(buffer.getArea().getLength());
            button("pasteButton").fire();
            assertEquals("hello, pasted", buffer.getContent());

            FindReplaceBar find = FxTestSupport.field(controller, "findBar");
            assertFalse(find.isShown());
            button("findButton").fire();
            assertTrue(find.isShown());
            find.hideBar();

            ToolWindowManager tools = FxTestSupport.field(controller, "toolWindows");
            ToolWindow search = FxTestSupport.field(controller, "searchToolWindow");
            tools.closeAllOpen();
            button("findInFilesButton").fire();
            assertTrue(tools.isOpen(search), "Find in Files opens its tool window");
        });
    }

    @Test
    void theToolbarsViewButtonsSplitTheEditorOpenThePaletteAndSwitchSimpleMode() throws Exception {
        FxTestSupport.runOnFx(() -> {
            addBuffer("one\n");
            EditorBuffer buffer = addBuffer("two\n");
            EditorArea area = FxTestSupport.field(controller, "editorArea");
            assertEquals(EditorBuffer.Split.NONE, buffer.getSplit());

            button("splitVerticalButton").fire();
            assertEquals(EditorBuffer.Split.SIDE_BY_SIDE, buffer.getSplit(), "two views of the same buffer");
            assertEquals(tr("status.editorSplit"), echo());
            button("splitHorizontalButton").fire();
            assertEquals(EditorBuffer.Split.STACKED, buffer.getSplit());
            button("splitHorizontalButton").fire(); // the same button again takes the split away
            assertEquals(EditorBuffer.Split.NONE, buffer.getSplit());
            assertEquals(tr("status.editorUnsplit"), echo());

            // Editor groups are the other kind of split: the tab moves to a group of its own.
            assertEquals(1, area.groupCount());
            FxTestSupport.invokeWith(
                    controller,
                    "splitEditorGroup",
                    javafx.geometry.Orientation.class,
                    javafx.geometry.Orientation.HORIZONTAL);
            assertEquals(2, area.groupCount());
            assertEquals(tr("status.editorGroupSplit"), echo());
            FxTestSupport.invoke(controller, "unsplitEditorGroups");
            assertEquals(1, area.groupCount());
            assertEquals(tr("status.editorGroupsMerged"), echo());
            FxTestSupport.invoke(controller, "unsplitEditorGroups");
            assertEquals(tr("status.editorGroupNotSplit"), echo());

            OverlayHost overlay = FxTestSupport.field(controller, "overlayHost");
            button("paletteButton").fire();
            assertTrue(overlay.isShowing(), "the palette opens in the window");
            overlay.hide();

            WindowChromeCoordinator chrome = FxTestSupport.field(controller, "chrome");
            assertFalse(chrome.simpleModeActive());
            button("simpleModeButton").fire();
            assertTrue(chrome.simpleModeActive());
            button("simpleModeButton").fire();
            assertFalse(chrome.simpleModeActive());

            int tabs = area.tabs().size();
            button("closeTabButton").fire();
            assertEquals(tabs - 1, area.tabs().size(), "Close closes the active tab");
        });
    }

    @Test
    void aMenuBarEntryRunsItsCommand() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setFontZoom(1.0);
            MainMenuBar menuBar = FxTestSupport.field(controller, "menuBar");
            MenuBar bar = (MenuBar) menuBar.node();
            List<MenuItem> all = new ArrayList<>();
            for (Menu menu : bar.getMenus()) {
                collect(menu, all);
            }
            java.util.Map<String, ?> rows = FxTestSupport.field(menuBar, "items");
            Object row = rows.get("view.toggleMinimap");
            assertNotNull(row, "the View menu offers the minimap switch");
            MenuItem minimap = (MenuItem) FxTestSupport.call(row, "item", new Class<?>[] {});
            assertTrue(all.contains(minimap), "and it is one of the entries on the bar");

            boolean before = settings.isShowMinimap();
            minimap.fire();
            assertEquals(!before, settings.isShowMinimap());
            minimap.fire();
            assertEquals(before, settings.isShowMinimap());
        });
    }

    private static void collect(Menu menu, List<MenuItem> out) {
        for (MenuItem item : menu.getItems()) {
            out.add(item);
            if (item instanceof Menu sub) {
                collect(sub, out);
            }
        }
    }

    @Test
    void revealAndTerminalNeedAFileOnThisComputer() throws Exception {
        FxTestSupport.runOnFx(() -> {
            controller.setStatus("unchanged");
            FxTestSupport.invoke(controller, "revealActiveBuffer");
            FxTestSupport.invoke(controller, "openTerminalForActiveBuffer");
            assertEquals("unchanged", echo(), "no buffer: nothing to reveal");

            addBuffer("never saved\n");
            FxTestSupport.invoke(controller, "revealActiveBuffer");
            assertEquals(tr("status.reveal.noFile"), echo());
            controller.setStatus("");
            FxTestSupport.invoke(controller, "openTerminalForActiveBuffer");
            assertEquals(tr("status.reveal.noFile"), echo());

            // A file on a remote site has no folder a local file manager or terminal could show.
            Class<?>[] types = {Path.class, boolean.class, boolean.class};
            controller.setStatus("");
            FxTestSupport.call(controller, "revealInFileManager", types, dir.resolve("remote.txt"), false, false);
            assertEquals(tr("status.reveal.remote"), echo());
            controller.setStatus("");
            FxTestSupport.call(controller, "openTerminalAt", types, dir.resolve("remote.txt"), false, false);
            assertEquals(tr("status.reveal.remote"), echo());
        });
    }

    @Test
    void theProjectMapPreviewShowsAnOpenBuffersUnsavedTextAndSaysWhenAFileIsNotOpen() throws Exception {
        Path file = Files.writeString(dir.resolve("mapped.txt"), "on disk\n");
        EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
        FxTestSupport.runOnFx(() -> {
            buffer.getArea().appendText("typed since\n");
            ProjectMapPreview.Content open = preview(file);
            assertTrue(open.open());
            assertEquals("on disk\ntyped since\n", open.text(), "what is in the editor, not what is on disk");
            assertFalse(open.truncated());

            ProjectMapPreview.Content closed = preview(dir.resolve("not-open.txt"));
            assertFalse(closed.open());
            assertEquals("", closed.text());

            // A note or bookmark names its file by key: the open buffer is found by it.
            Tab byKey =
                    (Tab) FxTestSupport.invokeWith(controller, "tabForKey", String.class, PathKeys.canonicalKey(file));
            assertNotNull(byKey);
            assertSame(buffer, byKey.getUserData());
            assertNull(FxTestSupport.invokeWith(
                    controller, "tabForKey", String.class, PathKeys.canonicalKey(dir.resolve("not-open.txt"))));
        });
    }

    private ProjectMapPreview.Content preview(Path file) {
        return (ProjectMapPreview.Content)
                FxTestSupport.invokeWith(controller, "projectMapPreviewContent", Path.class, file);
    }

    @Test
    void theSuggestedMainClassIsTheOneTheBuildDeclaresElseTheFilesOwn() throws Exception {
        Path plain = Files.createDirectories(dir.resolve("plain").resolve("src"));
        Path main = Files.writeString(
                plain.resolve("Tool.java"),
                "package demo;\n\npublic class Tool {\n    public static void main(String[] args) {}\n}\n");
        Path notMain = Files.writeString(plain.resolve("Helper.java"), "package demo;\n\nclass Helper {}\n");
        Path gradle = Files.createDirectories(dir.resolve("gradle"));
        Files.writeString(
                gradle.resolve("build.gradle"),
                "plugins { id 'application' }\napplication { mainClass = 'com.example.Launcher' }\n");
        Path inGradle = Files.createDirectories(gradle.resolve("src/main/java/com/example"));
        Path other = Files.writeString(
                inGradle.resolve("Other.java"),
                "package com.example;\n\npublic class Other {\n    public static void main(String[] a) {}\n}\n");

        assertNull(FxTestSupport.callOnFx(controller::suggestedMainClass), "no buffer");
        FxTestSupport.runOnFx(() -> addBuffer("class Untitled { public static void main(String[] a) {} }"));
        assertNull(FxTestSupport.callOnFx(controller::suggestedMainClass), "an unsaved buffer belongs to no project");

        SaveDecisionsFxTest.open(async, fx, main);
        assertEquals("demo.Tool", FxTestSupport.callOnFx(controller::suggestedMainClass));
        SaveDecisionsFxTest.open(async, fx, notMain);
        assertNull(FxTestSupport.callOnFx(controller::suggestedMainClass), "a file with no main method");
        SaveDecisionsFxTest.open(async, fx, other);
        assertEquals(
                "com.example.Launcher",
                FxTestSupport.callOnFx(controller::suggestedMainClass),
                "what `gradle run` would start, not the file that happens to be open");
    }

    @Test
    void theUndoHistoryPickerAlwaysHasACheckpointAndRestoresTheOneChosen() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertEquals(List.of(), FxTestSupport.call(controller, "undoHistoryCheckpoints", new Class<?>[] {}));
            EditorBuffer buffer = addBuffer("first state\n");
            @SuppressWarnings("unchecked")
            List<com.editora.editor.UndoHistory.Checkpoint> checkpoints =
                    (List<com.editora.editor.UndoHistory.Checkpoint>)
                            FxTestSupport.call(controller, "undoHistoryCheckpoints", new Class<?>[] {});
            assertEquals(1, checkpoints.size(), "a baseline, so there is always something to pick");

            buffer.getArea().appendText("more\n");
            assertEquals("first state\nmore\n", buffer.getContent());
            FxTestSupport.invokeWith(
                    controller,
                    "restoreUndoCheckpoint",
                    com.editora.editor.UndoHistory.Checkpoint.class,
                    checkpoints.get(0));
            assertEquals("first state\n", buffer.getContent());
        });
    }

    @Test
    void aBookmarkOfAnotherProjectOpensInThatProjectsWindowAndOneOfThisWindowOpensHere() throws Exception {
        Path root = Files.createDirectories(dir.resolve("proj"));
        Path theirs = Files.writeString(root.resolve("theirs.txt"), "a\nb\nc\nd\n");
        Path mine = Files.writeString(dir.resolve("mine.txt"), "1\n2\n3\n4\n");
        Project[] project = new Project[1];
        Class<?>[] types = {String.class, Path.class, int.class};
        FxTestSupport.runOnFx(() -> {
            project[0] = fx.shared.projects().createOrGet("proj", root);
            fx.shared.projects().save();
            FxTestSupport.call(controller, "openInProjectWindow", types, "", mine, 2);
        });
        SettingsRig.awaitFx("mine.txt at line 3", () -> {
            EditorBuffer b = active();
            return b != null && mine.equals(b.getPath()) && b.getArea().getCurrentParagraph() == 2;
        });

        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(controller, "openInProjectWindow", types, project[0].id(), theirs, 1));
        MainController[] other = new MainController[1];
        SettingsRig.awaitFx("the project's window with its file", () -> {
            other[0] = windowFor(project[0]);
            return other[0] != null && other[0].hasFileOpen(theirs);
        });
        assertFalse(FxTestSupport.callOnFx(() -> controller.hasFileOpen(theirs)), "not opened out of context here");

        // With projects switched off there are no project windows: it opens in place.
        Path third = Files.writeString(dir.resolve("third.txt"), "x\ny\n");
        FxTestSupport.runOnFx(() -> {
            settings.setProjectSupport(false);
            FxTestSupport.call(controller, "openInProjectWindow", types, project[0].id(), third, 1);
        });
        SettingsRig.awaitFx("third.txt here", () -> controller.hasFileOpen(third));
    }

    @Test
    void theProjectSettingsFileIsSeededOnFirstEditAndNeedsAProject() throws Exception {
        Path root = Files.createDirectories(dir.resolve("proj"));
        MainController[] window = new MainController[1];
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invoke(controller, "editProjectSettings");
            assertEquals(tr("status.project.settingsNeedProject"), echo());

            Project project = fx.shared.projects().createOrGet("proj", root);
            fx.windowManager.openOrFocus(project);
            window[0] = windowFor(project);
            FxTestSupport.invoke(window[0], "editProjectSettings");
        });
        Path file = com.editora.config.ProjectSettings.migrateLegacyForEditing(root);
        SettingsRig.awaitFx("the settings file to open", () -> window[0].hasFileOpen(file));
        assertEquals(tr("project.settings.template"), Files.readString(file), "seeded, so the keys can be seen");

        // A file the user already has is opened as it is, not overwritten with the template.
        Files.writeString(file, "{\"lspCommands\":{}}");
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(window[0], "editProjectSettings"));
        FxTestSupport.drainFx();
        assertEquals("{\"lspCommands\":{}}", Files.readString(file));
        ConfigManager config = FxTestSupport.field(window[0], "config");
        assertEquals(FxTestSupport.<String>field(window[0], "projectKey"), config.currentProjectKey());
    }
}

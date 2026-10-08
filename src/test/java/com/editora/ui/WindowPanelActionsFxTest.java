package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;

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

/** What the Project tree's context menu asks of the window, and the preview slot a single click opens into. */
@Tag("fx")
class WindowPanelActionsFxTest {

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

    private ProjectPanel panel() {
        return FxTestSupport.field(controller, "projectPanel");
    }

    @Test
    void withGitSwitchedOffEveryGitEntryOfTheTreeSaysSoAndDoesNothing() throws Exception {
        Path file = Files.writeString(dir.resolve("tracked.txt"), "text\n");
        FxTestSupport.runOnFx(() -> {
            settings.setGitSupport(false);
            ProjectPanel.FileActions files = FxTestSupport.field(panel(), "fileActions");
            List<Consumer<Path>> gitEntries = List.of(
                    files::gitShowFileHistory,
                    files::gitCompareWithHead,
                    files::gitCompareWithBranch,
                    files::gitCompareWithTag,
                    files::gitCompareWithRevision,
                    files::gitAnnotate,
                    files::gitStage,
                    files::gitUnstage,
                    files::gitRevert,
                    files::gitAddToGitignore);
            for (Consumer<Path> entry : gitEntries) {
                controller.setStatus("");
                entry.accept(file);
                assertEquals(tr("statusbar.tip.gitDisabled"), echo());
            }
            assertFalse(controller.hasFileOpen(file), "Annotate did not even open the file");
            assertFalse(Files.exists(dir.resolve(".gitignore")));
            assertEquals(settings.isLocalHistory(), files.localHistoryEnabled());
            assertNotNull(files.gitScope(file), "a file outside any repository still has a scope: none");
        });
        assertEquals("text\n", Files.readString(file));
    }

    @Test
    void theTreesMarkerEntriesAddBookmarksAndNotesAndReportWhatAFileHas() throws Exception {
        Path file = Files.writeString(dir.resolve("marked.txt"), "one\ntwo\nthree\n");
        Path folder = Files.createDirectories(dir.resolve("folder"));
        Path plain = Files.writeString(dir.resolve("plain.txt"), "x\n");
        FxTestSupport.runOnFx(() -> {
            settings.setNotesSupport(true);
            ProjectPanel.MarkerActions markers = FxTestSupport.field(panel(), "markerActions");
            assertTrue(markers.personalNotesEnabled());
            assertFalse(markers.hasBookmarks(file));
            assertFalse(markers.hasPersonalNotes(file));
            assertEquals(List.of(), markers.personalNotes(file));
            assertTrue(markers.markedPaths().isEmpty());

            markers.addBookmark(file, 1);
            assertTrue(markers.hasBookmarks(file));
            markers.addBookmark(folder); // a folder is bookmarked as a whole
            assertTrue(markers.hasBookmarks(folder));
            assertFalse(markers.hasBookmarks(plain));
            assertTrue(markers.markedPaths().stream().anyMatch(p -> p.endsWith("marked.txt")));
            assertTrue(markers.markedPaths().stream().anyMatch(p -> p.endsWith("folder")));
            assertFalse(markers.markedPaths().stream().anyMatch(p -> p.endsWith("plain.txt")));

            String tip = markers.personalNotesTooltip(file);
            assertTrue(tip == null || tip.isBlank(), "no notes, no tooltip: " + tip);

            settings.setNotesSupport(false);
            assertFalse(markers.personalNotesEnabled());
        });
        assertTrue(
                Files.readString(fx.configDir.resolve("bookmarks.json")).contains("marked.txt"),
                "a bookmark is saved when it is set");
    }

    @Test
    void aSingleClickOpensIntoOnePreviewSlotAndADeliberateOpenKeepsItsTab() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
        Path b = Files.writeString(dir.resolve("b.txt"), "b\n");
        Path c = Files.writeString(dir.resolve("c.txt"), "c\n");
        EditorBuffer kept = SaveDecisionsFxTest.open(async, fx, c); // opened on purpose

        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(controller, "openPathPreview", Path.class, a));
        SettingsRig.awaitFx("a.txt in the preview slot", () -> controller.hasFileOpen(a));
        Tab[] slot = new Tab[1];
        FxTestSupport.runOnFx(() -> {
            slot[0] = FxTestSupport.field(controller, "previewTab");
            assertNotNull(slot[0]);
            assertEquals(a, ((EditorBuffer) slot[0].getUserData()).getPath());
            FxTestSupport.invokeWith(controller, "openPathPreview", Path.class, b);
        });
        SettingsRig.awaitFx("b.txt replacing it", () -> controller.hasFileOpen(b));
        FxTestSupport.runOnFx(() -> {
            assertFalse(controller.hasFileOpen(a), "a glance at the next file replaces the last glance");
            assertTrue(controller.hasFileOpen(c), "a file opened on purpose is not disposable");
            Tab second = FxTestSupport.field(controller, "previewTab");
            assertEquals(b, ((EditorBuffer) second.getUserData()).getPath());

            // Glancing at a file that already has a tab selects it and leaves the slot alone.
            FxTestSupport.invokeWith(controller, "openPathPreview", Path.class, c);
            assertSame(kept, active());
            assertSame(second, FxTestSupport.field(controller, "previewTab"));

            // Choosing the previewed file makes it permanent; promoting any other tab changes nothing.
            EditorArea area = FxTestSupport.field(controller, "editorArea");
            Tab keptTab = area.tabs().stream()
                    .filter(t -> t.getUserData() == kept)
                    .findFirst()
                    .orElseThrow();
            FxTestSupport.invokeWith(controller, "promoteTab", Tab.class, keptTab);
            assertSame(second, FxTestSupport.field(controller, "previewTab"));
            FxTestSupport.invokeWith(controller, "promoteTab", Tab.class, second);
            assertNull(FxTestSupport.field(controller, "previewTab"));
            FxTestSupport.invokeWith(controller, "promoteTab", Tab.class, null);
            FxTestSupport.invokeWith(controller, "openPathPreview", Path.class, a);
        });
        SettingsRig.awaitFx("a.txt again", () -> controller.hasFileOpen(a));
        assertTrue(FxTestSupport.callOnFx(() -> controller.hasFileOpen(b)), "the promoted tab stayed");
    }

    @Test
    void aPreviewedFileThatWasEditedIsNotReplacedByTheNextGlance() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
        Path b = Files.writeString(dir.resolve("b.txt"), "b\n");
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(controller, "openPathPreview", Path.class, a));
        EditorBuffer first = WindowMcpBridgeFxTest.awaitLoaded(async, controller, a);
        FxTestSupport.runOnFx(() -> {
            first.getArea().appendText("edited\n");
            // Put the edited tab back in the slot, as if the promotion had not happened yet.
            EditorArea area = FxTestSupport.field(controller, "editorArea");
            Tab tab = area.tabs().stream()
                    .filter(t -> t.getUserData() == first)
                    .findFirst()
                    .orElseThrow();
            try {
                java.lang.reflect.Field slot = MainController.class.getDeclaredField("previewTab");
                slot.setAccessible(true);
                slot.set(controller, tab);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            FxTestSupport.invokeWith(controller, "openPathPreview", Path.class, b);
        });
        SettingsRig.awaitFx("b.txt to open", () -> controller.hasFileOpen(b));
        FxTestSupport.runOnFx(() -> {
            assertTrue(controller.hasFileOpen(a), "unsaved work is never closed by a glance elsewhere");
            assertEquals("a\nedited\n", first.getContent());
        });
    }

    @Test
    void aRecentFileOfAnotherProjectBringsThatProjectsWindowForward() throws Exception {
        Path root = Files.createDirectories(dir.resolve("proj"));
        Path inProject = Files.writeString(root.resolve("in.txt"), "in\n");
        Path loose = Files.writeString(dir.resolve("loose.txt"), "loose\n");
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(controller, "openRecent", Path.class, loose));
        SettingsRig.awaitFx("the loose file here", () -> controller.hasFileOpen(loose));
        assertEquals(
                1,
                FxTestSupport.callOnFx(() -> FxTestSupport.<List<?>>field(fx.windowManager, "windows")
                        .size()));

        Project[] project = new Project[1];
        FxTestSupport.runOnFx(() -> {
            project[0] = fx.shared.projects().createOrGet("proj", root);
            FxTestSupport.invokeWith(controller, "openRecent", Path.class, inProject);
        });
        assertEquals(
                2,
                FxTestSupport.callOnFx(() -> FxTestSupport.<List<?>>field(fx.windowManager, "windows")
                        .size()),
                "the project the file belongs to gets its window");
    }
}

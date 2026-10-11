package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.config.ConfigManager;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session restore builds only the selected tab's editor before the first frame. Every other saved file is
 * a {@link RestorePlaceholder} tab — title, icon, pin, order and group in place — whose editor arrives on the
 * restore cadence, or at once when something needs it first. Both halves are pinned here: nothing but the
 * selected tab is built during the window build, and every tab still ends up a filled buffer in the same
 * {@link Tab} object it started in.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionRestorePlaceholderFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void onlyTheSelectedTabHasAnEditorBeforeTheFirstFrame(@TempDir Path dir) throws Exception {
        Path cfg = Files.createDirectories(dir.resolve("config"));
        Path a = Files.writeString(dir.resolve("A.java"), javaSource("A"));
        Path b = Files.writeString(dir.resolve("B.java"), javaSource("B"));
        Path c = Files.writeString(dir.resolve("C.java"), javaSource("C"));
        Files.writeString(
                cfg.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + entry(a, false, 0) + "," + entry(b, false, 0) + ","
                        + entry(c, true, 0) + "],\"activeFile\":\"" + json(b) + "\"}");

        List<Tab> tabsAtBuild = new ArrayList<>();
        List<Object> contentAtBuild = new ArrayList<>();
        boolean[] completeAtBuild = {true};
        FxWindowFixture fx = FxWindowFixture.create(cfg, false, false, false, List.of(), controller -> {
            for (Tab tab : tabPane(controller).getTabs()) {
                tabsAtBuild.add(tab);
                contentAtBuild.add(tab.getUserData());
            }
            WindowSessionCoordinator sessions = FxTestSupport.field(controller, "sessions");
            completeAtBuild[0] = sessions.isRestoreComplete();
        });
        try {
            assertEquals(3, tabsAtBuild.size(), "every saved file has a tab from the first frame");
            assertTrue(contentAtBuild.get(0) instanceof RestorePlaceholder, "a background file is a placeholder");
            assertTrue(contentAtBuild.get(1) instanceof EditorBuffer, "the selected file has its editor");
            assertTrue(contentAtBuild.get(2) instanceof RestorePlaceholder);
            assertEquals("C.java", ((RestorePlaceholder) contentAtBuild.get(2)).title());
            assertFalse(completeAtBuild[0], "the restore is still running after the build");
            assertTrue(
                    FxTestSupport.callOnFx(() -> fx.controller.pinned.contains(tabsAtBuild.get(2))),
                    "a pinned placeholder is pinned");
            assertEquals(
                    List.of(a, b, c),
                    FxTestSupport.callOnFx(() -> tabPane(fx.controller).getTabs().stream()
                            .map(t -> tabPath(fx.controller, t))
                            .toList()),
                    "a placeholder reports its file, so the tab counts as open");

            assertTrue(waitUntil(() -> allFilled(fx.controller) && restoreComplete(fx.controller)));
            List<Tab> tabsAfter = FxTestSupport.callOnFx(
                    () -> List.copyOf(tabPane(fx.controller).getTabs()));
            assertEquals(tabsAtBuild, tabsAfter, "the same Tab objects, in the same order");
            assertTrue(FxTestSupport.callOnFx(() -> fx.controller.pinned.contains(tabsAfter.get(2))));
            EditorBuffer background = (EditorBuffer) tabsAfter.get(0).getUserData();
            assertTrue(
                    FxTestSupport.callOnFx(() -> DeferredTabContent.isDeferred(tabsAfter.get(0))),
                    "a tab built in the background keeps its editor detached until shown");
            assertNull(FxTestSupport.callOnFx(() -> background.getNode().getScene()));
            assertTrue(
                    FxTestSupport.callOnFx(background::isHighlightDeferred),
                    "and its first highlight pass waits for the first show");
            assertFalse(FxTestSupport.callOnFx(() -> styled(background)), "no styles while never shown");
            assertEquals(javaSource("A"), FxTestSupport.callOnFx(background::getContent));

            FxTestSupport.runOnFx(
                    () -> tabPane(fx.controller).getSelectionModel().select(tabsAfter.get(0)));
            assertNotNull(FxTestSupport.callOnFx(() -> background.getNode().getScene()), "shown: attached");
            assertFalse(FxTestSupport.callOnFx(background::isHighlightDeferred));
            assertTrue(waitUntil(() -> styled(background)), "the owed highlight pass runs once shown");
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aPlaceholderSelectedBeforeItsTurnIsBuiltWithinThatSelection(@TempDir Path dir) throws Exception {
        Path cfg = Files.createDirectories(dir.resolve("config"));
        List<Path> files = new ArrayList<>();
        StringBuilder open = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            Path f = Files.writeString(dir.resolve("f" + i + ".txt"), "file " + i + "\n");
            files.add(f);
            open.append(i > 0 ? "," : "").append(entry(f, false, 0));
        }
        Files.writeString(
                cfg.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + open + "],\"activeFile\":\"" + json(files.get(0)) + "\"}");

        Object[] atSelection = new Object[3];
        FxWindowFixture fx = FxWindowFixture.create(cfg, false, false, false, List.of(), controller -> {
            TabPane pane = tabPane(controller);
            Tab fourth = pane.getTabs().get(4);
            assertTrue(fourth.getUserData() instanceof RestorePlaceholder);
            pane.getSelectionModel().select(fourth); // the user clicks a tab the cadence has not reached
            atSelection[0] = fourth.getUserData();
            atSelection[1] =
                    fourth.getContent() == null ? null : fourth.getContent().getScene();
            atSelection[2] = FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
        });
        try {
            assertTrue(atSelection[0] instanceof EditorBuffer, "built inside the selection, before any listener");
            assertNotNull(atSelection[1], "and attached to the scene at once");
            assertSame(atSelection[0], atSelection[2], "the tab-switch fan-out saw the buffer, not a placeholder");
            assertTrue(waitUntil(() -> allFilled(fx.controller) && restoreComplete(fx.controller)));
            assertEquals(
                    6,
                    FxTestSupport.callOnFx(
                                    () -> tabPane(fx.controller).getTabs().size())
                            .intValue());
            assertEquals(
                    "file 4\n",
                    FxTestSupport.callOnFx(() -> ((EditorBuffer)
                                    tabPane(fx.controller).getTabs().get(4).getUserData())
                            .getContent()));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void sessionCaptureKeepsAPlaceholdersSavedCaretAndPin(@TempDir Path dir) throws Exception {
        Path cfg = Files.createDirectories(dir.resolve("config"));
        Path a = Files.writeString(dir.resolve("a.txt"), "active\n");
        Path b = Files.writeString(dir.resolve("b.txt"), "background text with a caret\n");
        Files.writeString(
                cfg.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + entry(a, false, 0) + ",{\"path\":\"" + json(b)
                        + "\",\"caret\":7,\"pinned\":true}],\"activeFile\":\"" + json(a) + "\"}");

        List<WorkspaceState.OpenFile> captured = new ArrayList<>();
        FxWindowFixture fx = FxWindowFixture.create(cfg, false, false, false, List.of(), controller -> {
            // A save requested while b is still a placeholder (a crash a second after launch, say).
            WindowSessionCoordinator sessions = FxTestSupport.field(controller, "sessions");
            sessions.captureSession();
            ConfigManager config = FxTestSupport.field(controller, "config");
            captured.addAll(config.getWorkspaceState().getOpenFiles());
        });
        try {
            assertEquals(2, captured.size());
            assertEquals(b.toString(), captured.get(1).getPath());
            assertEquals(7, captured.get(1).getCaret(), "the saved caret survives a capture made before the fill");
            assertTrue(captured.get(1).isPinned());
            assertTrue(waitUntil(() -> allFilled(fx.controller) && restoreComplete(fx.controller)));
            EditorBuffer built = (EditorBuffer) FxTestSupport.callOnFx(
                    () -> tabPane(fx.controller).getTabs().get(1).getUserData());
            assertEquals(
                    7,
                    FxTestSupport.callOnFx(() -> built.getArea().getCaretPosition())
                            .intValue());
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aPlaceholderClosedBeforeItsTurnStaysClosed(@TempDir Path dir) throws Exception {
        Path cfg = Files.createDirectories(dir.resolve("config"));
        Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
        Path b = Files.writeString(dir.resolve("b.txt"), "b\n");
        Path c = Files.writeString(dir.resolve("c.txt"), "c\n");
        Files.writeString(
                cfg.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + entry(a, false, 0) + "," + entry(b, false, 0) + ","
                        + entry(c, false, 0) + "],\"activeFile\":\"" + json(a) + "\"}");

        FxWindowFixture fx = FxWindowFixture.create(cfg, false, false, false, List.of(), controller -> {
            EditorArea area = FxTestSupport.field(controller, "editorArea");
            Tab second = area.tabs().get(1);
            assertTrue(second.getUserData() instanceof RestorePlaceholder);
            area.remove(second);
        });
        try {
            assertTrue(waitUntil(() -> restoreComplete(fx.controller)), "the cadence skips the closed tab");
            assertEquals(
                    List.of(a, c),
                    FxTestSupport.callOnFx(() -> tabPane(fx.controller).getTabs().stream()
                            .map(t -> tabPath(fx.controller, t))
                            .toList()));
            assertTrue(FxTestSupport.callOnFx(() -> allFilled(fx.controller)));
            WindowSessionCoordinator sessions = FxTestSupport.field(fx.controller, "sessions");
            ConfigManager config = FxTestSupport.field(fx.controller, "config");
            List<String> saved = FxTestSupport.callOnFx(() -> {
                sessions.captureSession();
                return config.getWorkspaceState().getOpenFiles().stream()
                        .map(WorkspaceState.OpenFile::getPath)
                        .toList();
            });
            assertEquals(List.of(a.toString(), c.toString()), saved, "closed by the user: not written back");
        } finally {
            fx.dispose();
        }
    }

    @Test
    void eachSplitGroupsSelectedTabIsBuiltBeforeTheFirstFrame(@TempDir Path dir) throws Exception {
        Path cfg = Files.createDirectories(dir.resolve("config"));
        Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
        Path b = Files.writeString(dir.resolve("b.txt"), "b\n");
        Path c = Files.writeString(dir.resolve("c.txt"), "c\n");
        Path d = Files.writeString(dir.resolve("d.txt"), "d\n");
        Files.writeString(
                cfg.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + entry(a, false, 0) + "," + entry(b, false, 0) + ","
                        + entry(c, false, 1) + "," + entry(d, false, 1) + "],\"activeFile\":\"" + json(b) + "\","
                        + "\"editorLayout\":{\"orientation\":\"HORIZONTAL\",\"children\":"
                        + "[{\"selected\":1},{\"selected\":0}]}}");

        List<Object> contentAtBuild = new ArrayList<>();
        List<Boolean> deferredAtBuild = new ArrayList<>();
        FxWindowFixture fx = FxWindowFixture.create(cfg, false, false, false, List.of(), controller -> {
            EditorArea area = FxTestSupport.field(controller, "editorArea");
            for (Tab tab : area.tabs()) {
                contentAtBuild.add(tab.getUserData());
                deferredAtBuild.add(DeferredTabContent.isDeferred(tab));
            }
        });
        try {
            assertEquals(4, contentAtBuild.size());
            assertTrue(contentAtBuild.get(0) instanceof RestorePlaceholder, "a: hidden behind b");
            assertTrue(contentAtBuild.get(1) instanceof EditorBuffer, "b: the active tab");
            assertTrue(contentAtBuild.get(2) instanceof EditorBuffer, "c: the other group's visible tab");
            assertTrue(contentAtBuild.get(3) instanceof RestorePlaceholder, "d: hidden behind c");
            assertEquals(List.of(false, false, false, false), deferredAtBuild, "visible tabs are attached at once");
            assertTrue(waitUntil(() -> allFilled(fx.controller) && restoreComplete(fx.controller)));
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            assertEquals(2, FxTestSupport.callOnFx(area::groupCount).intValue());
            assertEquals(
                    List.of(0, 0, 1, 1),
                    FxTestSupport.callOnFx(
                            () -> area.tabs().stream().map(area::groupIndexOf).toList()));
            assertEquals(b, FxTestSupport.callOnFx(() -> tabPath(fx.controller, area.selectedTab())));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aBackgroundOpenOfAPlaceholdersFileBuildsItInsteadOfASecondTab(@TempDir Path dir) throws Exception {
        Path cfg = Files.createDirectories(dir.resolve("config"));
        Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
        Path b = Files.writeString(dir.resolve("b.txt"), "b on disk\n");
        Files.writeString(
                cfg.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + entry(a, false, 0) + "," + entry(b, false, 0)
                        + "],\"activeFile\":\"" + json(a) + "\"}");

        Object[] opened = new Object[2];
        FxWindowFixture fx = FxWindowFixture.create(cfg, false, false, false, List.of(), controller -> {
            // A diff apply or an agent write asks for b's buffer while b is still a placeholder.
            opened[0] = FxTestSupport.call(controller, "openBackgroundBuffer", new Class<?>[] {Path.class}, b);
            opened[1] = tabPane(controller).getTabs().get(1).getUserData();
        });
        try {
            assertTrue(opened[0] instanceof EditorBuffer, "the placeholder's editor, loaded on the spot");
            assertSame(opened[0], opened[1], "in the placeholder's own tab");
            assertEquals("b on disk\n", FxTestSupport.callOnFx(((EditorBuffer) opened[0])::getContent));
            assertTrue(waitUntil(() -> allFilled(fx.controller) && restoreComplete(fx.controller)));
            assertEquals(
                    2,
                    FxTestSupport.callOnFx(
                                    () -> tabPane(fx.controller).getTabs().size())
                            .intValue());
        } finally {
            fx.dispose();
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private static String javaSource(String name) {
        return "class " + name + " {\n    int value() {\n        return 42;\n    }\n}\n";
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private static String entry(Path path, boolean pinned, int group) {
        return "{\"path\":\"" + json(path) + "\",\"pinned\":" + pinned + ",\"group\":" + group + "}";
    }

    private static TabPane tabPane(MainController controller) {
        return FxTestSupport.field(controller, "tabPane");
    }

    private static Path tabPath(MainController controller, Tab tab) {
        return (Path) FxTestSupport.call(controller, "tabPath", new Class<?>[] {Tab.class}, tab);
    }

    private static boolean restoreComplete(MainController controller) {
        WindowSessionCoordinator sessions = FxTestSupport.field(controller, "sessions");
        return sessions.isRestoreComplete();
    }

    /** Whether any character of the buffer's area carries a style class. */
    private static boolean styled(EditorBuffer buffer) {
        var area = buffer.getArea();
        for (int i = 0; i < area.getLength(); i++) {
            if (!area.getStyleOfChar(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean allFilled(MainController controller) {
        for (Tab tab : tabPane(controller).getTabs()) {
            if (!(tab.getUserData() instanceof EditorBuffer buffer)
                    || buffer.isLoading()
                    || buffer.getContent().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Polls {@code condition} on the FX thread until it holds, or ~10 s elapse. */
    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 500; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}

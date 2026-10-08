package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.SharedConfig;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which windows a launch opens, and where a file handed to a running editor lands: {@code --single-window},
 * {@code --project}, the standalone diff, a bookmark of another project, and a file-manager click.
 */
@Tag("fx")
class WindowManagerLaunchFxTest {

    @TempDir
    Path tmp;

    private Launched app;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) {
            app.dispose();
        }
    }

    /** What {@code App.start} does with its arguments, with the restore queue stepped by hand. */
    private static final class Launched {
        SharedConfig shared;
        WindowManager wm;
        final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();

        /** A window manager over {@code dir}, prepared by {@code before}; nothing is launched yet. */
        Launched(Path dir, Consumer<SharedConfig> before) throws Exception {
            FxTestSupport.runOnFx(() -> {
                ConfigManager bootstrap = new ConfigManager(dir);
                bootstrap.load();
                shared = bootstrap.shared();
                before.accept(shared);
                KeymapManager keymap = new KeymapManager();
                keymap.loadNamed(shared.getSettings().getKeymap());
                wm = new WindowManager(shared, keymap, null);
                wm.adoptBootstrapConfig(bootstrap);
                wm.restoreScheduler = scheduled::add;
            });
        }

        void launch(String projectArg, List<MainController.OpenTarget> targets, String singleWindow) throws Exception {
            FxTestSupport.runOnFx(
                    () -> wm.launch(new Stage(), projectArg, targets, false, false, null, false, singleWindow, false));
        }

        List<String> keys() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                List<String> keys = new ArrayList<>();
                for (Object holder : FxTestSupport.<List<?>>field(wm, "windows")) {
                    keys.add((String) FxTestSupport.call(holder, "key", new Class<?>[] {}));
                }
                return keys;
            });
        }

        MainController window(String key) throws Exception {
            return FxTestSupport.callOnFx(() -> {
                for (Object holder : FxTestSupport.<List<?>>field(wm, "windows")) {
                    if (key.equals(FxTestSupport.call(holder, "key", new Class<?>[] {}))) {
                        return (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                    }
                }
                return null;
            });
        }

        /** As {@link #window}, for a caller already on the FX thread. */
        MainController windowNow(String key) {
            for (Object holder : FxTestSupport.<List<?>>field(wm, "windows")) {
                if (key.equals(FxTestSupport.call(holder, "key", new Class<?>[] {}))) {
                    return (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                }
            }
            return null;
        }

        void step() throws Exception {
            FxTestSupport.runOnFx(() -> scheduled.remove().run());
        }

        void dispose() throws Exception {
            FxTestSupport.runOnFx(() -> {
                for (Object holder : List.copyOf(FxTestSupport.<List<?>>field(wm, "windows"))) {
                    MainController c = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                    c.disposePlugins();
                    c.disposeWindow();
                    ((Stage) FxTestSupport.call(holder, "stage", new Class<?>[] {})).close();
                }
                FxTestSupport.<com.editora.plugin.PluginManager>field(wm, "pluginManager")
                        .closeAll();
            });
            FxTestSupport.drainFx();
            shared.shutdown();
            wm.recovery().close();
        }
    }

    private static EditorBuffer bufferFor(MainController controller, Path file) {
        Tab tab = (Tab) FxTestSupport.invokeWith(controller, "tabForPath", Path.class, file);
        return tab != null && tab.getUserData() instanceof EditorBuffer buffer ? buffer : null;
    }

    private static void awaitOpenAt(MainController controller, Path file, int line) throws Exception {
        SettingsRig.awaitFx(file.getFileName() + " open at line " + line, () -> {
            EditorBuffer buffer = bufferFor(controller, file);
            return buffer != null && buffer.getArea().getCurrentParagraph() == line;
        });
    }

    private static String lines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    void singleWindowOpensOnlyTheNoProjectWindowAndLeavesTheSavedSetAlone() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("alpha"));
        Path config = Files.createDirectories(tmp.resolve("config"));
        String[] id = new String[1];
        app = new Launched(config, shared -> {
            Project alpha = shared.projects().createOrGet("alpha", root);
            id[0] = alpha.id();
            shared.projects().markOpen(alpha.id());
            shared.projects().markOpen("");
            shared.projects().save();
        });
        app.launch(null, List.of(), "");

        assertEquals(List.of(""), app.keys(), "one window, whatever was open at the last quit");
        assertTrue(app.scheduled.isEmpty(), "nothing else is queued to restore");
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(app.wm, "reconcileOpenSet"));
        assertTrue(
                FxTestSupport.callOnFx(() -> app.shared.projects().openProjectIds())
                        .contains(id[0]),
                "a one-window run never shrinks the saved layout");
    }

    @Test
    void singleWindowWithAProjectNameOpensThatProjectWhateverTheCase() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("alpha"));
        Path config = Files.createDirectories(tmp.resolve("config"));
        String[] id = new String[1];
        app = new Launched(config, shared -> {
            id[0] = shared.projects().createOrGet("Alpha", root).id();
            shared.projects().save();
        });
        app.launch(null, List.of(), "  alpha ");

        assertEquals(List.of(id[0]), app.keys());
        MainController window = app.window(id[0]);
        assertEquals(root, FxTestSupport.callOnFx(() ->
                (Path) FxTestSupport.call(window, "windowProjectRoot", new Class<?>[] {})));
    }

    @Test
    void singleWindowWithANameNoProjectHasFallsBackToTheNoProjectWindow() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("alpha"));
        Path config = Files.createDirectories(tmp.resolve("config"));
        Path file = Files.writeString(tmp.resolve("note.txt"), lines(5));
        app = new Launched(config, shared -> {
            shared.projects().createOrGet("Alpha", root);
            shared.projects().save();
        });
        app.launch(null, List.of(new MainController.OpenTarget(file, 3, 1)), "no-such-project");

        assertEquals(List.of(""), app.keys());
        awaitOpenAt(app.window(""), file, 2); // the command-line file still opens, at its line
    }

    @Test
    void singleWindowIgnoresAProjectNameWhileProjectsAreSwitchedOff() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("alpha"));
        Path config = Files.createDirectories(tmp.resolve("config"));
        app = new Launched(config, shared -> {
            shared.projects().createOrGet("Alpha", root);
            shared.projects().save();
            shared.getSettings().setProjectSupport(false);
        });
        app.launch(null, List.of(), "Alpha");
        assertEquals(List.of(""), app.keys());
    }

    @Test
    void aProjectFolderOnTheCommandLineBecomesAProjectAndThePrimaryWindow() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("beta"));
        Path file = Files.writeString(root.resolve("main.txt"), lines(4));
        Path config = Files.createDirectories(tmp.resolve("config"));
        app = new Launched(config, shared -> {});
        app.launch(root.toString(), List.of(new MainController.OpenTarget(file, 2, 1)), null);

        Project beta = FxTestSupport.callOnFx(() -> app.shared.projects().list().stream()
                .filter(p -> p.name().equals("beta"))
                .findFirst()
                .orElseThrow());
        assertEquals(beta.id(), app.keys().get(0), "its window is built first");
        awaitOpenAt(app.window(beta.id()), file, 1); // and takes the command-line file
        assertTrue(app.scheduled.isEmpty());
        assertEquals(List.of(beta.id()), app.keys(), "a first start with a folder opens that folder only");
        assertTrue(Files.readString(config.resolve("projects.json")).contains("\"beta\""), "the project is saved");
    }

    @Test
    void aProjectDeletedSinceTheLastQuitIsSkippedWithoutStoppingTheRest() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Files.writeString(
                config.resolve("projects.json"),
                "{\"schemaVersion\":2,\"projects\":[],\"activeProjectId\":\"gone-1234\","
                        + "\"openProjectIds\":[\"gone-1234\",\"\"]}");
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);
        while (!app.scheduled.isEmpty()) {
            app.step();
        }
        assertEquals(List.of(""), app.keys());
        assertFalse(FxTestSupport.callOnFx(app.wm::restorePending));
    }

    @Test
    void theStandaloneDiffIsAWindowOfItsOwnThatIsNotPartOfTheSession() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Path left = Files.writeString(tmp.resolve("left.txt"), "a\nb\n");
        Path right = Files.writeString(tmp.resolve("right.txt"), "a\nc\n");
        app = new Launched(config, shared -> {});
        FxTestSupport.runOnFx(() -> app.wm.launchDiffUi(new Stage(), new WindowManager.DiffUiRequest(left, right)));

        List<String> keys = app.keys();
        assertEquals(1, keys.size());
        assertTrue(keys.get(0).startsWith(WindowKeys.UNTITLED_PREFIX + "diff-"), keys.toString());
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(app.wm, "reconcileOpenSet"));
        assertFalse(
                FxTestSupport.callOnFx(() -> app.shared.projects().openProjectIds())
                        .contains(keys.get(0)),
                "a diff window is not restored on the next start");
    }

    @Test
    void aFileOfAnotherProjectOpensInThatProjectsWindowBuildingItWhenNeeded() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("gamma"));
        Path inProject = Files.writeString(root.resolve("in.txt"), lines(6));
        Path loose = Files.writeString(tmp.resolve("loose.txt"), lines(6));
        Path config = Files.createDirectories(tmp.resolve("config"));
        String[] id = new String[1];
        app = new Launched(config, shared -> {
            id[0] = shared.projects().createOrGet("gamma", root).id();
            shared.projects().save();
        });
        app.launch(null, List.of(), null);
        assertEquals(List.of(""), app.keys());
        MainController global = app.window("");

        // A window that is already there: the file opens in it, at the line.
        FxTestSupport.runOnFx(() -> app.wm.openInWindow("", loose, 4));
        awaitOpenAt(global, loose, 4);

        // A project with no window yet, and a line to go to: its window is built with the file.
        FxTestSupport.runOnFx(() -> app.wm.openInWindow(id[0], inProject, 3));
        assertEquals(List.of("", id[0]), app.keys());
        MainController gamma = app.window(id[0]);
        awaitOpenAt(gamma, inProject, 3);
        assertNull(FxTestSupport.callOnFx(() -> bufferFor(global, inProject)), "not in the window that asked");
        assertTrue(FxTestSupport.callOnFx(() -> app.shared.projects().openProjectIds())
                .contains(id[0]));

        // The same window again: focused and reused.
        FxTestSupport.runOnFx(() -> app.wm.openInWindow(id[0], inProject, 1));
        awaitOpenAt(gamma, inProject, 1);
        assertEquals(2, app.keys().size());

        // A project deleted meanwhile: the file lands in the no-project window instead of nowhere.
        Path other = Files.writeString(tmp.resolve("other.txt"), lines(3));
        FxTestSupport.runOnFx(() -> app.wm.openInWindow("deleted-project-id", other, 2));
        awaitOpenAt(global, other, 2);
        FxTestSupport.runOnFx(() -> app.wm.openInWindow(null, other, 0));
        awaitOpenAt(global, other, 0);
        assertEquals(2, app.keys().size());
    }

    @Test
    void revealingAFileOfAProjectWithNoWindowOpensThatWindowWithoutOpeningTheFile() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("delta"));
        Path file = Files.writeString(root.resolve("shown.txt"), lines(3));
        Path config = Files.createDirectories(tmp.resolve("config"));
        String[] id = new String[1];
        app = new Launched(config, shared -> {
            id[0] = shared.projects().createOrGet("delta", root).id();
            shared.projects().save();
        });
        app.launch(null, List.of(), null);

        FxTestSupport.runOnFx(() -> app.wm.openInWindow(id[0], file, -1));
        FxTestSupport.drainFx();
        assertEquals(List.of("", id[0]), app.keys());
        MainController delta = app.window(id[0]);
        FxTestSupport.runOnFx(() -> {
            assertNull(bufferFor(delta, file), "revealed in the tree, not opened");
            ToolWindowManager tools = FxTestSupport.field(delta, "toolWindows");
            ToolWindow projectWindow = FxTestSupport.field(delta, "projectToolWindow");
            assertTrue(tools.isOpen(projectWindow), "the Project tool window is where it is shown");
        });
    }

    @Test
    void theNoProjectWindowIsBuiltAgainWhenSomethingNeedsItAfterItWasClosed() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("epsilon"));
        Path config = Files.createDirectories(tmp.resolve("config"));
        String[] id = new String[1];
        app = new Launched(config, shared -> {
            id[0] = shared.projects().createOrGet("epsilon", root).id();
            shared.projects().save();
        });
        app.launch(null, List.of(), null);
        FxTestSupport.runOnFx(() -> {
            Project epsilon = app.shared.projects().list().get(0);
            Stage projectStage = app.wm.openOrFocus(epsilon);
            assertSame(projectStage, app.wm.openOrFocus(epsilon), "an open project is focused, not built twice");
            assertEquals(epsilon.id(), app.shared.projects().active().id());
        });
        MainController global = app.window("");
        MainController project = app.window(id[0]);
        FxTestSupport.runOnFx(() -> {
            assertSame(project, app.wm.otherLiveController(global));
            assertSame(global, app.wm.otherLiveController(project));
            assertTrue(app.wm.closeWindowForKey("no-window-has-this-key"), "nothing to close is not a refusal");
            assertTrue(app.wm.requestClose(global));
        });
        FxTestSupport.drainFx();
        assertEquals(List.of(id[0]), app.keys());
        assertNull(FxTestSupport.callOnFx(() -> app.wm.otherLiveController(project)), "the last window has no other");

        FxTestSupport.runOnFx(() -> {
            Stage rebuilt = app.wm.openOrFocus(null); // "No Project"
            assertNotNull(rebuilt);
            assertSame(rebuilt, app.wm.openOrFocusGlobal(), "and from then on it is the one that is focused");
        });
        assertEquals(List.of(id[0], ""), app.keys());
        assertTrue(FxTestSupport.callOnFx(() -> app.shared.projects().openProjectIds())
                .contains(""));
    }

    @Test
    void aFileHandedToTheRunningEditorOpensInItsWindowInTheModeTheLaunchAskedFor() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Path file = Files.writeString(tmp.resolve("handed.txt"), lines(8));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);
        MainController global = app.window("");

        FxTestSupport.runOnFx(() -> {
            app.wm.openExternalFiles(null);
            app.wm.openExternalFiles(List.of());
            app.wm.openExternalLaunchInNewWindow(List.of(), false, false, false);
            app.wm.presentForExternalLaunch(); // the launcher clicked again with nothing to open
        });
        assertEquals(List.of(""), app.keys(), "nothing to open opens nothing");

        FxTestSupport.runOnFx(
                () -> app.wm.openExternalFiles(List.of(new MainController.OpenTarget(file, 6, 1)), true, false, false));
        awaitOpenAt(global, file, 5);
        assertTrue(
                FxTestSupport.callOnFx(() -> (Boolean)
                        FxTestSupport.call(FxTestSupport.field(global, "chrome"), "zenActive", new Class<?>[] {})),
                "the launcher entry's mode is applied to the window that takes the file");

        // The same file clicked again in the file manager: the window that has it is brought forward.
        FxTestSupport.runOnFx(() -> app.wm.openExternalLaunchInNewWindow(
                List.of(new MainController.OpenTarget(file, 2, 1)), false, false, false));
        awaitOpenAt(global, file, 1);
        assertEquals(List.of(""), app.keys(), "no second buffer over the same file");

        // A file nobody has open gets a window of its own, which is not part of the saved session.
        Path fresh = Files.writeString(tmp.resolve("fresh.txt"), lines(3));
        FxTestSupport.runOnFx(() -> app.wm.openExternalLaunchInNewWindow(
                List.of(new MainController.OpenTarget(fresh, 0, 0)), false, false, false));
        List<String> keys = app.keys();
        assertEquals(2, keys.size());
        MainController transientWindow = app.window(keys.get(1));
        SettingsRig.awaitFx("fresh.txt to open", () -> bufferFor(transientWindow, fresh) != null);
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(app.wm, "reconcileOpenSet"));
        assertFalse(FxTestSupport.callOnFx(() -> app.shared.projects().openProjectIds())
                .contains(keys.get(1)));
    }

    @Test
    void launchesThatArriveWhileWindowsAreStillBeingRestoredWaitForTheWholeSet() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Files.writeString(
                config.resolve("projects.json"),
                "{\"schemaVersion\":2,\"projects\":[],\"activeProjectId\":\"\","
                        + "\"openProjectIds\":[\"\",\"untitled:cccc3333\"]}");
        Path file = Files.writeString(tmp.resolve("late.txt"), lines(3));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);
        assertTrue(FxTestSupport.callOnFx(app.wm::restorePending));

        FxTestSupport.runOnFx(() -> {
            app.wm.openExternalFiles(List.of(new MainController.OpenTarget(file, 0, 0)));
            app.wm.presentForExternalLaunch();
        });
        FxTestSupport.drainFx();
        assertNull(
                FxTestSupport.callOnFx(() -> bufferFor(app.window(""), file)),
                "not decided against a half-restored set of windows");

        app.step();
        assertFalse(FxTestSupport.callOnFx(app.wm::restorePending));
        List<String> keys = app.keys();
        assertEquals(List.of("", "untitled:cccc3333"), keys);
        SettingsRig.awaitFx("the deferred file to open", () -> {
            for (Object holder : FxTestSupport.<List<?>>field(app.wm, "windows")) {
                MainController c = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                if (bufferFor(c, file) != null) {
                    return true;
                }
            }
            return false;
        });
    }

    // --- a launch forwarded by a second `editora` process (App.openForwardedLaunch) ----------------

    /** What the single-instance listener does with the arguments another process handed over. */
    private static void forwarded(Launched app, String... args) throws Exception {
        java.lang.reflect.Method apply = com.editora.App.class.getDeclaredMethod(
                "openForwardedLaunch", WindowManager.class, SharedConfig.class, List.class);
        apply.setAccessible(true);
        FxTestSupport.runOnFx(() -> {
            try {
                apply.invoke(null, app.wm, app.shared, List.of(args));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void aForwardedLaunchWithNothingToOpenOnlyBringsTheEditorForward() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);

        forwarded(app);
        forwarded(app, "--zen");
        assertEquals(List.of(""), app.keys(), "no window is opened for a launch that names no file");
        assertFalse(
                FxTestSupport.callOnFx(() -> (Boolean) FxTestSupport.call(
                        FxTestSupport.field(app.window(""), "chrome"), "zenActive", new Class<?>[] {})),
                "and the window the user is in keeps its mode");
    }

    @Test
    void aForwardedFileGetsAWindowOfItsOwnInTheModeTheLaunchAskedFor() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Path file = Files.writeString(tmp.resolve("forwarded.txt"), lines(5));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);

        forwarded(app, "--expert", file + ":3");

        List<String> keys = app.keys();
        assertEquals(2, keys.size());
        MainController opened = app.window(keys.get(1));
        awaitOpenAt(opened, file, 2);
        FxTestSupport.runOnFx(() -> {
            Object chrome = FxTestSupport.field(opened, "chrome");
            assertTrue((Boolean) FxTestSupport.call(chrome, "expertActive", new Class<?>[] {}));
            assertNull(bufferFor(app.windowNow(""), file), "the window the user was in is left alone");
        });
    }

    @Test
    void aForwardedProjectLaunchOpensThatProjectsWindowWithItsFiles() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Path root = Files.createDirectories(tmp.resolve("zeta"));
        Path file = Files.writeString(root.resolve("in-project.txt"), lines(6));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);

        forwarded(app, "--project=" + root, file + ":4");

        Project zeta = FxTestSupport.callOnFx(() -> app.shared.projects().list().stream()
                .filter(p -> p.name().equals("zeta"))
                .findFirst()
                .orElseThrow());
        assertEquals(List.of("", zeta.id()), app.keys());
        awaitOpenAt(app.window(zeta.id()), file, 3);

        forwarded(app, "--project", root.toString()); // again, with no file: its window, and nothing new
        assertEquals(List.of("", zeta.id()), app.keys());
    }

    @Test
    void aForwardedProjectOptionIsIgnoredWhileProjectsAreSwitchedOff() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Path root = Files.createDirectories(tmp.resolve("eta"));
        Path file = Files.writeString(root.resolve("just-a-file.txt"), lines(2));
        app = new Launched(config, shared -> shared.getSettings().setProjectSupport(false));
        app.launch(null, List.of(), null);

        forwarded(app, "--project=" + root, file.toString());

        assertTrue(FxTestSupport.callOnFx(() -> app.shared.projects().list().isEmpty()), "no project is created");
        List<String> keys = app.keys();
        assertEquals(2, keys.size());
        assertTrue(WindowKeys.isUntitled(keys.get(1)), "the file opens as any forwarded file does");
        SettingsRig.awaitFx("the file to open", () -> bufferFor(app.windowNow(keys.get(1)), file) != null);
    }

    @Test
    void everyWindowsSceneGetsTheUiFontStylesheetOnceIncludingASceneSetLater() throws Exception {
        java.lang.reflect.Method hook =
                com.editora.App.class.getDeclaredMethod("hookWindowFont", javafx.stage.Window.class);
        hook.setAccessible(true);
        String css = com.editora.App.class.getResource("styles/ui-font.css").toExternalForm();
        FxTestSupport.runOnFx(() -> {
            try {
                Stage stage = new Stage();
                javafx.scene.Scene first = new javafx.scene.Scene(new javafx.scene.layout.Pane());
                stage.setScene(first);
                hook.invoke(null, stage);
                hook.invoke(null, stage); // a window seen twice still has the sheet once
                assertEquals(
                        1, first.getStylesheets().stream().filter(css::equals).count());

                javafx.scene.Scene second = new javafx.scene.Scene(new javafx.scene.layout.Pane());
                stage.setScene(second);
                assertEquals(
                        1, second.getStylesheets().stream().filter(css::equals).count());
                stage.setScene(null); // a window between scenes has nothing to style
                hook.invoke(null, (Object) null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    /**
     * Finder's "Open With" arrives as a Glass open-files event, not on the command line. The handler that
     * routes it is installed over the toolkit's own here — and the toolkit's is put back afterwards.
     */
    @Test
    void filesTheOsHandsOverAsAnEventOpenLikeCommandLineFilesWithTheirLine() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Path file = Files.writeString(tmp.resolve("from-finder.txt"), lines(6));
        Path plain = Files.writeString(tmp.resolve("plain.txt"), lines(2));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);
        MainController global = app.window("");

        Class<?> glassClass = Class.forName("com.sun.glass.ui.Application");
        Class<?> handlerClass = Class.forName("com.sun.glass.ui.Application$EventHandler");
        Object glass = FxTestSupport.callOnFx(
                () -> glassClass.getMethod("GetApplication").invoke(null));
        org.junit.jupiter.api.Assumptions.assumeTrue(glass != null, "no Glass application in this toolkit");
        Object original = FxTestSupport.callOnFx(
                () -> glassClass.getMethod("getEventHandler").invoke(glass));
        java.lang.reflect.Method install =
                Class.forName("com.editora.MacOpenFiles").getDeclaredMethod("install", WindowManager.class);
        install.setAccessible(true);
        try {
            Object installed = FxTestSupport.callOnFx(() -> {
                install.invoke(null, app.wm);
                return glassClass.getMethod("getEventHandler").invoke(glass);
            });
            assertTrue(installed != original, "the open-files handler wraps the toolkit's own");
            java.lang.reflect.Method openFiles =
                    installed.getClass().getMethod("handleOpenFilesAction", glassClass, long.class, String[].class);
            openFiles.setAccessible(true);

            FxTestSupport.runOnFx(() -> {
                try {
                    openFiles.invoke(installed, glass, 0L, (Object) null);
                    openFiles.invoke(installed, glass, 0L, (Object) new String[0]);
                    openFiles.invoke(installed, glass, 0L, (Object) new String[] {null, "   "});
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            FxTestSupport.drainFx();
            assertEquals(
                    0,
                    FxTestSupport.callOnFx(() -> FxTestSupport.<EditorArea>field(global, "editorArea").tabs().stream()
                            .filter(t -> t.getUserData() instanceof EditorBuffer)
                            .count()),
                    "an event that names no file opens nothing");

            FxTestSupport.runOnFx(() -> {
                try {
                    openFiles.invoke(installed, glass, 0L, (Object) new String[] {file + ":4", null, plain.toString()});
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            awaitOpenAt(global, file, 3);
            SettingsRig.awaitFx("plain.txt to open", () -> bufferFor(global, plain) != null);
            assertEquals(List.of(""), app.keys(), "into the window the user is in, as Finder always did");
        } finally {
            FxTestSupport.runOnFx(() -> {
                try {
                    glassClass.getMethod("setEventHandler", handlerClass).invoke(glass, original);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    // --- windows as such -------------------------------------------------------------------------

    private static Stage stageOf(Launched app, String key) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            for (Object holder : FxTestSupport.<List<?>>field(app.wm, "windows")) {
                if (key.equals(FxTestSupport.call(holder, "key", new Class<?>[] {}))) {
                    return (Stage) FxTestSupport.call(holder, "stage", new Class<?>[] {});
                }
            }
            return null;
        });
    }

    @Test
    void aWindowComesBackAtTheSizeAndPlaceItsSessionRecorded() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Files.writeString(
                config.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"windowX\":40,\"windowY\":30,\"windowWidth\":900,\"windowHeight\":640}");
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);

        Stage stage = stageOf(app, "");
        FxTestSupport.runOnFx(() -> {
            assertEquals(900, stage.getWidth(), 0.5);
            assertEquals(640, stage.getHeight(), 0.5);
            if (!javafx.stage.Screen.getScreensForRectangle(40, 30, 900, 640).isEmpty()) {
                assertEquals(40, stage.getX(), 0.5);
                assertEquals(30, stage.getY(), 0.5);
            }
            assertFalse(stage.isMaximized());
        });
    }

    @Test
    void aWindowThatWasMaximizedComesBackMaximized() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Files.writeString(
                config.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"windowWidth\":0,\"windowHeight\":0,\"windowMaximized\":true}");
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);
        Stage stage = stageOf(app, "");
        assertTrue(FxTestSupport.callOnFx(stage::isMaximized));
    }

    @Test
    void aSecondWindowDoesNotOpenExactlyOnTopOfTheFirst() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);
        Stage first = stageOf(app, "");
        Stage[] more = new Stage[2];
        FxTestSupport.runOnFx(() -> {
            first.setX(100);
            first.setY(80);
            small(first);
            more[0] = app.wm.newWindow();
            small(more[0]);
            more[0].setX(100);
            more[0].setY(80);
            FxTestSupport.call(app.wm, "cascadeIfOverlapping", new Class<?>[] {Stage.class}, more[0]);
            assertFalse(
                    more[0].getX() == first.getX() && more[0].getY() == first.getY(),
                    "it is nudged so both title bars can be seen");

            assertEquals(more[0].getX() - first.getX(), more[0].getY() - first.getY(), 0.5, "down and right alike");

            more[1] = app.wm.newWindow();
            small(more[1]);
            more[1].setX(100);
            more[1].setY(80);
            FxTestSupport.call(app.wm, "cascadeIfOverlapping", new Class<?>[] {Stage.class}, more[1]);
            assertFalse(
                    more[1].getX() == more[0].getX() && more[1].getY() == more[0].getY(), "nor on top of the second");

            // A maximized window covers the screen on purpose: it is left where it is.
            more[1].setMaximized(true);
            double x = more[1].getX();
            FxTestSupport.call(app.wm, "cascadeIfOverlapping", new Class<?>[] {Stage.class}, more[1]);
            assertEquals(x, more[1].getX(), 0.5);
        });
        assertEquals(3, app.keys().size());
        assertTrue(app.keys().stream().skip(1).allMatch(WindowKeys::isUntitled));
    }

    @Test
    void ctrlAndTheWheelZoomTheTextOfTheWindowUnderThePointer() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);
        Stage stage = stageOf(app, "");
        FxTestSupport.runOnFx(() -> {
            app.shared.getSettings().setFontZoom(1.0);
            javafx.scene.Node root = stage.getScene().getRoot();
            javafx.event.Event.fireEvent(root, wheel(40, true));
            assertEquals(1.1, app.shared.getSettings().getFontZoom(), 1e-9);
            javafx.event.Event.fireEvent(root, wheel(-40, true));
            assertEquals(1.0, app.shared.getSettings().getFontZoom(), 1e-9);
            javafx.event.Event.fireEvent(root, wheel(40, false)); // a plain wheel scrolls; it is not a zoom
            javafx.event.Event.fireEvent(root, wheel(0, true)); // nor is a horizontal-only gesture
            assertEquals(1.0, app.shared.getSettings().getFontZoom(), 1e-9);
        });
    }

    /** Small enough that a cascade step stays on the (small) headless screen. */
    private static void small(Stage stage) {
        stage.setWidth(320);
        stage.setHeight(240);
    }

    private static javafx.scene.input.ScrollEvent wheel(double deltaY, boolean control) {
        return new javafx.scene.input.ScrollEvent(
                javafx.scene.input.ScrollEvent.SCROLL,
                10,
                10,
                10,
                10,
                false,
                control,
                false,
                false,
                false,
                false,
                0,
                deltaY,
                0,
                deltaY,
                javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE,
                0,
                javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE,
                0,
                0,
                null);
    }

    @Test
    void aSettingsFileThatCouldNotBeSavedBeforeAnyWindowExistedIsReportedByTheFirstWindow() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        app = new Launched(config, shared -> {});
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(
                app.wm, "notifyConfigWriteError", Path.class, config.resolve("settings.json")));
        app.launch(null, List.of(), null);
        FxTestSupport.drainFx(); // the report is deferred past the startup messages
        MainController global = app.window("");
        String expected = com.editora.i18n.Messages.tr("status.config.saveFailed", "settings.json");
        SettingsRig.awaitFx("the report", () -> {
            StatusBar status = FxTestSupport.field(global, "statusBar");
            return expected.equals(FxTestSupport.<javafx.scene.control.Label>field(status, "echo")
                    .getText());
        });

        // With a window open, a later failure is said at once.
        FxTestSupport.runOnFx(() -> {
            global.setStatus("");
            FxTestSupport.invokeWith(app.wm, "notifyConfigWriteError", Path.class, config.resolve("notes.json"));
            StatusBar status = FxTestSupport.field(global, "statusBar");
            assertEquals(
                    com.editora.i18n.Messages.tr("status.config.saveFailed", "notes.json"),
                    FxTestSupport.<javafx.scene.control.Label>field(status, "echo")
                            .getText());
        });
    }

    @Test
    void aLaunchHandedOverWhileNoWindowIsOpenOpensTheNoProjectWindowForIt() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Path file = Files.writeString(tmp.resolve("after-close.txt"), lines(3));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);
        FxTestSupport.runOnFx(() -> assertTrue(app.wm.requestClose(app.windowNow(""))));
        FxTestSupport.drainFx();
        assertEquals(List.of(), app.keys());
        assertFalse(FxTestSupport.callOnFx(app.wm::anyWindowFocused));

        FxTestSupport.runOnFx(() -> app.wm.presentForExternalLaunch());
        assertEquals(List.of(""), app.keys(), "the launcher clicked with everything closed: a window again");

        FxTestSupport.runOnFx(() -> assertTrue(app.wm.requestClose(app.windowNow(""))));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> app.wm.openExternalFiles(List.of(new MainController.OpenTarget(file, 2, 1))));
        assertEquals(List.of(""), app.keys());
        awaitOpenAt(app.window(""), file, 1);
    }

    @Test
    void quittingWithUnsavedTextCanBeCancelledAndEveryWindowStays() throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        app = new Launched(config, shared -> {});
        app.launch(null, List.of(), null);
        FxTestSupport.runOnFx(() -> {
            app.wm.newWindow();
            MainController global = app.windowNow("");
            EditorBuffer dirty = new EditorBuffer();
            FxTestSupport.call(global, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, dirty, true);
            dirty.getArea().appendText("not saved\n");

            boolean[] quit = new boolean[1];
            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE,
                    () -> quit[0] = app.wm.confirmCloseAllWindows());
            assertEquals(1, asked.size(), "the window with unsaved text asks");
            assertFalse(quit[0], "Cancel calls the quit off");
            assertEquals(2, FxTestSupport.<List<?>>field(app.wm, "windows").size(), "nothing was disposed");
            assertEquals("not saved\n", dirty.getContent());
        });
    }
}

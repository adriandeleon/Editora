package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Project;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a project window does for "the project" is <em>its</em> project, not the {@code ProjectManager}'s active
 * one: that is the window opened last, and focusing a window does not change it. Each case below used the active
 * project, so in project A's window, after project B's window had been opened, it acted on project B. (The Git,
 * snippet and AI agent readers have their own tests: GitWindowProjectRootFxTest and the like.)
 */
@Tag("fx")
class WindowProjectReadersFxTest {

    @TempDir
    Path work;

    private FxWindowFixture fx;
    private Path a;
    private Path b;
    private MainController windowA;
    private MainController windowB;

    @BeforeEach
    void twoProjectWindows() throws Exception {
        FxTestSupport.bootToolkit();
        a = Files.createDirectories(work.resolve("projA"));
        b = Files.createDirectories(work.resolve("projB"));
        fx = FxWindowFixture.create(Files.createTempDirectory(work, "cfg"), shared -> {});
        Project pa = fx.shared.projects().createOrGet("projA", a);
        Project pb = fx.shared.projects().createOrGet("projB", b);
        FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pa));
        FxTestSupport.drainFx();
        windowA = controllerFor(pa.id());
        FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pb)); // its own window; becomes "active"
        FxTestSupport.drainFx();
        windowB = controllerFor(pb.id());
    }

    @AfterEach
    void dispose() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void aTypstFilesRootIsThisWindowsProject() throws Exception {
        Path doc = Files.writeString(Files.createDirectories(a.resolve("docs")).resolve("report.typ"), "= Report\n");
        assertEquals(
                a,
                FxTestSupport.callOnFx(
                        () -> FxTestSupport.call(windowA, "resolveTypstRoot", new Class<?>[] {Path.class}, doc)),
                "imports above docs/ resolve against project A (it was docs/ itself: the file is not in project B)");
    }

    @Test
    void markdownLintAndRemoteDisconnectUseThisWindowsProject() throws Exception {
        PreviewCoordinator.Host previews = FxTestSupport.field(windowA.previews, "host");
        assertEquals(
                a, FxTestSupport.callOnFx(previews::activeProjectRoot), "the lint config lookup stops at project A");
        RemoteCoordinator.Ops remote = FxTestSupport.field(windowA.remoteCoordinator(), "ops");
        assertEquals(a, FxTestSupport.callOnFx(remote::activeProjectRoot), "disconnecting puts back project A's tree");
    }

    @Test
    void editProjectSettingsOpensThisWindowsProjectSettings() throws Exception {
        FxTestSupport.runOnFx(windowA::editProjectSettings);
        EditorBuffer opened = awaitActive(windowA, path -> path.startsWith(a.resolve(".editora")));
        assertTrue(opened.getPath().startsWith(a), String.valueOf(opened.getPath()));
        assertTrue(Files.notExists(b.resolve(".editora")), "nothing was written into project B");
    }

    @Test
    void aRecentFileOfAnotherProjectOpensInThatProjectsWindow() throws Exception {
        Path inB = Files.writeString(b.resolve("notes.txt"), "b\n");
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(windowA, "openRecent", Path.class, inB));
        awaitActive(windowB, inB::equals);
        assertTrue(
                FxTestSupport.callOnFx(() -> {
                    EditorBuffer active = active(windowA);
                    return active == null || !inB.equals(active.getPath());
                }),
                "not opened in project A's window");
    }

    @Test
    void templatesTheFileFinderTestNavigationAndHttpUseThisWindowsProject() throws Exception {
        assertEquals("projA", FxTestSupport.callOnFx(windowA.templateActions::projectName), "${projectName}");
        assertEquals(
                a, FxTestSupport.callOnFx(windowA.templateActions::defaultNewDir), "a new file, with no file open");
        assertEquals(
                a, FxTestSupport.callOnFx(windowA.navigation::finderStartDir), "the file finder, with no file open");
        assertEquals(
                a,
                FxTestSupport.callOnFx(
                        () -> FxTestSupport.call(windowA.testNavigation, "projectRoot", new Class<?>[] {})),
                "where a stack trace's or a CI log's files are looked up");
        HttpClientCoordinator.WindowOps http = FxTestSupport.field(windowA.httpClient, "ops");
        assertEquals(a, FxTestSupport.callOnFx(http::projectRoot), "where http-client.env.json is looked up");
    }

    private static EditorBuffer awaitActive(MainController window, java.util.function.Predicate<Path> wanted)
            throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            EditorBuffer active = FxTestSupport.callOnFx(() -> active(window));
            if (active != null && active.getPath() != null && wanted.test(active.getPath()) && !active.isLoading()) {
                return active;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the expected file never became active in the window");
    }

    private static EditorBuffer active(MainController window) {
        return (EditorBuffer) FxTestSupport.call(window, "activeBuffer", new Class<?>[] {});
    }

    private MainController controllerFor(String key) {
        List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
        for (Object h : holders) {
            if (key.equals(FxTestSupport.call(h, "key", new Class<?>[] {}))) {
                return (MainController) FxTestSupport.call(h, "controller", new Class<?>[] {});
            }
        }
        throw new IllegalStateException("no window for " + key);
    }
}

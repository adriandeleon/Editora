package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.build.BuildTool;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cycling tabs inside one project re-detects nothing: no thread is started and no build file is re-parsed
 * until one actually changes.
 *
 * <p>Detection used to start a thread per enabled tool on every tab switch and re-parse the nearest build
 * file each time. The counters asserted here are exact, so this needs no timing.
 */
@Tag("fx")
class BuildDetectionFxTest {

    private static FxWindowFixture fx;
    private static Path packageJson;
    private static Path first;
    private static Path second;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Path project = Files.createTempDirectory("editora-build-detect");
        packageJson = Files.writeString(
                project.resolve("package.json"), "{\"name\":\"demo-one\",\"scripts\":{\"build\":\"x\"}}");
        first = Files.writeString(project.resolve("a.js"), "let a = 1;\n");
        second = Files.writeString(project.resolve("b.js"), "let b = 2;\n");
        fx = FxWindowFixture.create(
                Files.createTempDirectory("editora-build-detect-cfg"),
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(first, -1, -1), new MainController.OpenTarget(second, -1, -1)),
                true,
                c -> {});
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
            fx = null;
        }
    }

    @Test
    void switchingTabsWithinAProjectStartsNoThreadAndParsesNothingUntilTheBuildFileChanges() throws Exception {
        assertTrue(await(() -> "demo-one".equals(npm().detectedLabel())), "package.json should be detected");
        select(first);
        settleDetection();
        long parses = BuildDetection.parses();
        int threads = BuildDetection.threadsStarted();
        assertEquals(1, threads, "one worker serves every tool");

        for (int i = 0; i < 6; i++) {
            select(i % 2 == 0 ? second : first);
        }
        settleDetection();
        assertEquals(threads, BuildDetection.threadsStarted(), "a tab switch starts no detect thread");
        assertEquals(parses, BuildDetection.parses(), "an unchanged build file is not re-parsed");
        assertEquals("demo-one", FxTestSupport.callOnFx(() -> npm().detectedLabel()));

        // A real change is still picked up by the same trigger.
        Files.writeString(packageJson, "{\"name\":\"demo-two\",\"scripts\":{\"build\":\"x\",\"test\":\"y\"}}");
        Files.setLastModifiedTime(packageJson, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        select(second);
        assertTrue(await(() -> "demo-two".equals(npm().detectedLabel())), "an edited build file is re-parsed");
        assertNotEquals(parses, BuildDetection.parses());
        assertEquals(threads, BuildDetection.threadsStarted());
    }

    /**
     * The tasks tree is the tool window's content, and a window that never opens it never builds it. Built
     * late, it must still show what detection found while it did not exist.
     */
    @Test
    void theTasksTreeIsBuiltOnFirstUseAndShowsWhatWasDetectedMeanwhile() throws Exception {
        assertTrue(await(() -> npm().isDetected()), "package.json should be detected");
        // Maven found nothing here and nobody opened its window: no tree was built for it.
        BuildCoordinator maven = FxTestSupport.callOnFx(() -> (BuildCoordinator)
                FxTestSupport.invokeWith(fx.controller, "buildCoordinator", BuildTool.class, BuildTool.MAVEN));
        assertNull(FxTestSupport.field(maven, "tree"));

        ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
        ToolWindow window = FxTestSupport.callOnFx(() -> toolWindows.getRegisteredToolWindows().stream()
                .filter(tw -> tw.getId().equals(BuildTool.NPM.id()))
                .findFirst()
                .orElseThrow());
        assertNull(FxTestSupport.callOnFx(window::contentIfBuilt), "registered, not built");
        assertNull(FxTestSupport.field(npm(), "tree"));

        FxTestSupport.runOnFx(() -> toolWindows.open(window));
        BuildActionsTree tree = FxTestSupport.callOnFx(() -> (BuildActionsTree) window.contentIfBuilt());
        assertNotNull(tree, "opening the window builds its content");
        assertSame(tree, FxTestSupport.field(npm(), "tree"));
        javafx.scene.control.TreeView<?> view = FxTestSupport.field(tree, "tree");
        assertTrue(
                FxTestSupport.callOnFx(() ->
                        view.getRoot() != null && !view.getRoot().getChildren().isEmpty()),
                "the tree shows the scripts detected before it existed");
    }

    private static BuildCoordinator npm() {
        return (BuildCoordinator)
                FxTestSupport.invokeWith(fx.controller, "buildCoordinator", BuildTool.class, BuildTool.NPM);
    }

    private static void select(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> {
            TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");
            for (Tab tab : tabPane.getTabs()) {
                if (tab.getUserData() instanceof EditorBuffer buffer && file.equals(buffer.getPath())) {
                    tabPane.getSelectionModel().select(tab);
                    return;
                }
            }
            throw new AssertionError("no tab for " + file);
        });
    }

    /** Waits for every detection queued so far, and the FX work it posted, to finish. */
    private static void settleDetection() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        BuildDetection.execute(done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        FxTestSupport.drainFx();
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }
}

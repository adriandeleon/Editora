package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.image.ImageView;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end coverage for the standalone {@code editora --diff-ui LEFT RIGHT} window. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffUiStartupFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void firstFrameIsDiffOnlyAndToolbarIconRestoresTheFullUi() throws Exception {
        Path dir = Files.createTempDirectory("editora-diff-ui");
        Path left = Files.writeString(dir.resolve("before.java"), "class Before {}\n");
        Path right = Files.writeString(dir.resolve("after.java"), "class After {}\n");
        List<Boolean> firstFrame = new ArrayList<>();

        FxWindowFixture fx = FxWindowFixture.createDiff(dir, left, right, controller -> {
            ToolBar toolBar = FxTestSupport.field(controller, "toolBar");
            javafx.scene.layout.Region statusBar = FxTestSupport.field(controller, "statusBar");
            TabPane tabPane = FxTestSupport.field(controller, "tabPane");
            MainMenuBar menuBar = FxTestSupport.field(controller, "menuBar");
            firstFrame.add(!toolBar.isVisible());
            firstFrame.add(!menuBar.node().isVisible());
            firstFrame.add(!statusBar.isVisible());
            firstFrame.add(tabPane.getStyleClass().contains("no-tab-header"));
            firstFrame.add((Boolean) FxTestSupport.call(controller, "diffUiActive", new Class<?>[] {}));
        });

        try {
            assertEquals(List.of(true, true, true, true, true), firstFrame);
            DiffViewerPane pane = awaitDiffPane(fx.controller);
            TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
            assertEquals(1, tabs.getTabs().size(), "standalone mode must not restore session or Welcome tabs");

            Button exit = FxTestSupport.field(pane, "exitDiffUiButton");
            assertTrue(FxTestSupport.callOnFx(exit::isVisible));
            assertTrue(FxTestSupport.callOnFx(() -> exit.getGraphic() instanceof ImageView));
            FxTestSupport.runOnFx(exit::fire);

            ToolBar toolBar = FxTestSupport.field(fx.controller, "toolBar");
            javafx.scene.layout.Region statusBar = FxTestSupport.field(fx.controller, "statusBar");
            MainMenuBar menuBar = FxTestSupport.field(fx.controller, "menuBar");
            assertTrue(FxTestSupport.callOnFx(toolBar::isVisible));
            assertTrue(FxTestSupport.callOnFx(() -> menuBar.node().isVisible()));
            assertTrue(FxTestSupport.callOnFx(statusBar::isVisible));
            assertFalse(FxTestSupport.callOnFx(exit::isVisible));
            assertEquals(1, FxTestSupport.callOnFx(() -> tabs.getTabs().size()), "the diff remains open");
        } finally {
            fx.dispose();
        }
    }

    @Test
    void directoryOperandsOpenALazyMultiFileReview() throws Exception {
        Path root = Files.createTempDirectory("editora-directory-diff-ui");
        Path left = Files.createDirectories(root.resolve("before/nested"));
        Path right = Files.createDirectories(root.resolve("after/nested"));
        left = left.getParent();
        right = right.getParent();
        Files.writeString(left.resolve("same.txt"), "same\n");
        Files.writeString(right.resolve("same.txt"), "same\n");
        Files.writeString(left.resolve("nested/changed.txt"), "before\n");
        Files.writeString(right.resolve("nested/changed.txt"), "after\n");
        Files.writeString(left.resolve("left.txt"), "left\n");
        Files.writeString(right.resolve("right.txt"), "right\n");

        FxWindowFixture fx = FxWindowFixture.createDiff(
                Files.createTempDirectory("editora-directory-diff-config"), left, right, controller -> {});
        try {
            DirectoryReviewPane review = awaitDirectoryReview(fx.controller);
            @SuppressWarnings("unchecked")
            List<DirectoryReviewPane.Entry> entries = FxTestSupport.field(review, "entries");
            assertEquals(
                    List.of("left.txt", "nested/changed.txt", "right.txt"),
                    entries.stream().map(DirectoryReviewPane.Entry::label).toList());

            DiffViewerPane active = awaitActiveDirectoryDiff(review);
            Button reviewExit = FxTestSupport.field(review, "exitDiffUiButton");
            Button childExit = FxTestSupport.field(active, "exitDiffUiButton");
            assertTrue(FxTestSupport.callOnFx(reviewExit::isVisible), "the directory review owns the full-UI control");
            assertFalse(FxTestSupport.callOnFx(childExit::isVisible), "the child diff does not duplicate it");
        } finally {
            fx.dispose();
        }
    }

    @Test
    void identicalDirectoriesStillProvideTheFullUiExit() throws Exception {
        Path left = Files.createTempDirectory("editora-directory-identical-left");
        Path right = Files.createTempDirectory("editora-directory-identical-right");
        Files.writeString(left.resolve("same.txt"), "same\n");
        Files.writeString(right.resolve("same.txt"), "same\n");

        FxWindowFixture fx = FxWindowFixture.createDiff(
                Files.createTempDirectory("editora-directory-identical-config"), left, right, controller -> {});
        try {
            DirectoryReviewPane review = awaitDirectoryReview(fx.controller);
            @SuppressWarnings("unchecked")
            List<DirectoryReviewPane.Entry> entries = FxTestSupport.field(review, "entries");
            assertTrue(entries.isEmpty());
            Button exit = FxTestSupport.field(review, "exitDiffUiButton");
            assertTrue(FxTestSupport.callOnFx(exit::isVisible));
        } finally {
            fx.dispose();
        }
    }

    private static DiffViewerPane awaitDiffPane(MainController controller) throws Exception {
        TabPane tabs = FxTestSupport.field(controller, "tabPane");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            DiffViewerPane pane = FxTestSupport.callOnFx(() -> {
                for (Tab tab : tabs.getTabs()) {
                    if (tab.getUserData() instanceof DiffViewerPane diff) {
                        return diff;
                    }
                }
                return null;
            });
            if (pane != null) {
                return pane;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("standalone diff did not open");
    }

    private static DirectoryReviewPane awaitDirectoryReview(MainController controller) throws Exception {
        TabPane tabs = FxTestSupport.field(controller, "tabPane");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            DirectoryReviewPane pane = FxTestSupport.callOnFx(() -> {
                for (Tab tab : tabs.getTabs()) {
                    if (tab.getUserData() instanceof DirectoryReviewPane review) {
                        return review;
                    }
                }
                return null;
            });
            if (pane != null) {
                return pane;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("directory review did not open");
    }

    private static DiffViewerPane awaitActiveDirectoryDiff(DirectoryReviewPane review) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            DiffViewerPane pane = FxTestSupport.callOnFx(review::activePane);
            if (pane != null) {
                return pane;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("directory file diff did not load");
    }
}

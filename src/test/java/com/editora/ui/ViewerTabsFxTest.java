package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.layout.HBox;

import com.editora.editor.ControlNames;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitFileStatus;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * A tab that shows a file in a viewer (an image, a PDF, a file's bytes) is a tab like an editor's: it has the
 * tab operations in its right-click menu, its tooltip is the file's full path with the Git status in words, it can
 * be pinned, and Reopen Closed Tab brings it back in its viewer. Every other tab of the strip has the tab
 * operations too, and Previous Tab goes the other way round from Next.
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
class ViewerTabsFxTest {

    private static FxWindowFixture fx;

    @TempDir
    static Path dir;

    @BeforeAll
    static void window() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    static void close() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static Path png(String name) throws Exception {
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Path file = dir.resolve(name);
        javax.imageio.ImageIO.write(bi, "png", file.toFile());
        return file;
    }

    /** The menu's visible rows, as it would show now. */
    private static List<String> rows(Tab tab) {
        ContextMenu menu = tab.getContextMenu();
        assertNotNull(menu, "the tab has a right-click menu");
        menu.getOnShowing().handle(null);
        return menu.getItems().stream()
                .filter(MenuItem::isVisible)
                .map(MenuItem::getText)
                .filter(text -> text != null)
                .toList();
    }

    private static MenuItem row(Tab tab, String text) {
        rows(tab); // built on its first showing
        return tab.getContextMenu().getItems().stream()
                .filter(item -> text.equals(item.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static EditorArea area() {
        return FxTestSupport.field(fx.controller, "editorArea");
    }

    @Test
    void aViewerTabHasTheTabMenuAndItsFilesFullPathAsTooltip() throws Exception {
        Path image = png("menu.png");
        Path bytes = Files.write(dir.resolve("menu.bin"), new byte[] {0, 1, 2, 3});
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            for (Tab tab :
                    List.of(c.fileWorkflows.openImageTab(image, true), c.fileWorkflows.openHexTab(bytes, true))) {
                Path file = MainController.tabPath(tab);
                List<String> rows = rows(tab);
                for (String key : List.of(
                        "menu.close",
                        "menu.closeOthers",
                        "menu.closeAll",
                        "menu.closeUnmodified",
                        "menu.closeLeft",
                        "menu.closeRight",
                        "menu.revealInFileManager",
                        "menu.copyPath",
                        "menu.pin")) {
                    assertTrue(rows.contains(tr(key)), key + " in " + rows);
                }
                assertEquals("", tab.getText(), "the header is the title");
                assertEquals(file.toAbsolutePath().toString(), ControlNames.tipText(tab));
                c.closeTab(tab);
            }
        });
    }

    @Test
    void aTabThatShowsNoFileHasTheTabOperationsAndNoFileRows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            c.registry.run("view.doctor");
            Tab doctor = area().selectedTab();
            assertNull(MainController.tabPath(doctor));
            List<String> rows = rows(doctor);
            assertTrue(
                    rows.containsAll(List.of(tr("menu.close"), tr("menu.closeOthers"), tr("menu.closeAll"))),
                    rows.toString());
            assertFalse(rows.contains(tr("menu.copyPath")), rows.toString());
            assertFalse(rows.contains(tr("menu.pin")), "nothing to pin: the session would not bring it back");
            row(doctor, tr("menu.close")).fire();
            assertFalse(area().tabs().contains(doctor), "Close closes it");
        });
    }

    @Test
    void aClosedViewerTabComesBackInItsViewer() throws Exception {
        Path image = png("back.png");
        Path text = Files.writeString(dir.resolve("back.txt"), "plain text\n");
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            Tab imageTab = c.fileWorkflows.openImageTab(image, true);
            Tab hexTab = c.fileWorkflows.openHexTab(text, true); // a text file, opened as its bytes
            c.closeTab(imageTab);
            c.closeTab(hexTab);
            assertNull(c.tabForPath(image));
            assertNull(c.tabForPath(text));

            c.registry.run("buffer.reopenClosed");
            Tab hexAgain = c.tabForPath(text);
            assertNotNull(hexAgain, "the newest first");
            assertNotNull(MainController.hexPaneOf(hexAgain), "as bytes again, though its name would open it as text");
            assertSame(hexAgain, area().selectedTab());

            c.registry.run("buffer.reopenClosed");
            Tab imageAgain = c.tabForPath(image);
            assertNotNull(imageAgain);
            assertNotNull(MainController.imagePaneOf(imageAgain));
            c.closeTab(hexAgain);
            c.closeTab(imageAgain);
        });
    }

    @Test
    void aViewerTabIsPinnedLikeAFilesTab() throws Exception {
        Path image = png("pin.png");
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            Set<Tab> pinned = FxTestSupport.field(c, "pinned");
            Tab tab = c.fileWorkflows.openImageTab(image, true);
            int plain = ((HBox) tab.getGraphic()).getChildren().size();
            row(tab, tr("menu.pin")).fire();
            assertTrue(pinned.contains(tab));
            assertTrue(tab.getStyleClass().contains("pinned"));
            List<Node> header = ((HBox) tab.getGraphic()).getChildren();
            assertEquals(plain + 1, header.size(), "the pin marker leads the header");
            assertTrue(rows(tab).contains(tr("menu.unpin")));
            c.closeAllTabs();
            assertTrue(area().tabs().contains(tab), "Close All leaves a pinned tab");
            c.registry.run("buffer.togglePin");
            assertFalse(pinned.contains(tab));
            assertFalse(tab.getStyleClass().contains("pinned"));
            c.closeTab(tab);
        });
    }

    @Test
    void theTooltipNamesTheGitStatusOfAViewersFileAndOfAnEditors() throws Exception {
        Path image = png("status.png");
        Path text = Files.writeString(dir.resolve("status.txt"), "text\n");
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(text));
        Await.until(
                () -> onFx(() -> fx.controller.tabForPath(text)) != null,
                "the text file opened",
                Duration.ofSeconds(20));
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            Tab imageTab = c.fileWorkflows.openImageTab(image, true);
            Tab textTab = c.tabForPath(text);
            Map<Path, GitFileStatus> status = Map.of(
                    image.toAbsolutePath().normalize(), GitFileStatus.UNTRACKED,
                    text.toAbsolutePath().normalize(), GitFileStatus.MODIFIED);
            TabGitStatus.apply(area().tabs(), status);
            assertTrue(imageTab.getStyleClass().contains("git-status-untracked"), "a viewer's tab is tinted too");
            assertEquals(
                    image.toAbsolutePath() + "\n" + TabGitStatus.words(GitFileStatus.UNTRACKED),
                    ControlNames.tipText(imageTab));
            assertEquals(
                    text.toAbsolutePath() + "\n" + TabGitStatus.words(GitFileStatus.MODIFIED),
                    ControlNames.tipText(textTab));
            // The header is rebuilt when the title changes: the status line stays.
            EditorBuffer buffer = MainController.bufferOf(textTab);
            c.updateTabMeta(textTab, buffer);
            assertTrue(ControlNames.tipText(textTab).endsWith(TabGitStatus.words(GitFileStatus.MODIFIED)));
            TabGitStatus.apply(area().tabs(), Map.of());
            assertEquals(image.toAbsolutePath().toString(), ControlNames.tipText(imageTab), "clean again");
            assertEquals(text.toAbsolutePath().toString(), ControlNames.tipText(textTab));
            c.closeTab(imageTab);
            c.closeTab(textTab);
        });
    }

    @Test
    void previousTabGoesTheOtherWayRoundFromNext() throws Exception {
        Path one = png("one.png");
        Path two = png("two.png");
        Path three = png("three.png");
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            c.closeAllTabs();
            Tab a = c.fileWorkflows.openImageTab(one, true);
            Tab b = c.fileWorkflows.openImageTab(two, true);
            Tab last = c.fileWorkflows.openImageTab(three, true);
            List<Tab> strip = List.copyOf(area().tabs());
            assertSame(last, area().selectedTab());
            c.registry.run("buffer.previous");
            assertSame(strip.get(strip.size() - 2), area().selectedTab());
            c.registry.run("buffer.next");
            assertSame(last, area().selectedTab());
            c.registry.run("buffer.next");
            assertSame(strip.get(0), area().selectedTab(), "Next wraps to the first");
            c.registry.run("buffer.previous");
            assertSame(last, area().selectedTab(), "and Previous wraps back to the last");
            for (Tab tab : List.of(a, b, last)) {
                c.closeTab(tab);
            }
        });
    }

    /** {@code probe}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> probe) {
        try {
            return FxTestSupport.callOnFx(probe);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

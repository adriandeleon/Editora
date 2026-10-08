package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The breadcrumb bar's dropdowns: clicking a crumb lists its folder, a file row opens, a folder row drills
 * in and reopens the dropdown there, and Reveal / Open Terminal act on the crumb that was clicked.
 */
@Tag("fx")
class FileBreadcrumbMenuFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A shown bar pointed at {@code root/src/Main.java}, with the project rooted at {@code root}. */
    private static final class Rig implements AutoCloseable {
        final AsyncTestScope async = new AsyncTestScope();
        final List<Path> opened = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final Path root;
        final Path src;
        final Path main;
        final Path util;
        FileBreadcrumb bar;
        Stage stage;

        Rig(Path dir) throws Exception {
            root = dir;
            src = Files.createDirectory(dir.resolve("src"));
            main = Files.writeString(src.resolve("Main.java"), "");
            Files.writeString(src.resolve("Other.java"), "");
            util = Files.createDirectory(src.resolve("util"));
            Files.writeString(util.resolve("Helper.java"), "");
            FxTestSupport.runOnFx(() -> {
                bar = new FileBreadcrumb(opened::add, () -> root);
                stage = new Stage();
                stage.setScene(new Scene(bar, 700, 60));
                stage.show();
                bar.setEnabled(true);
                bar.setActiveFile(main);
                bar.applyCss();
                bar.layout();
            });
            async.onClose(() -> FxTestSupport.runOnFx(() -> {
                if (bar.lastMenuForTest != null) {
                    bar.lastMenuForTest.hide();
                }
                stage.close();
            }));
        }

        /** The crumb labelled {@code text}. FX thread. */
        ButtonBase crumb(String text) {
            return (ButtonBase) bar.lookupAll(".file-breadcrumb-crumb").stream()
                    .filter(n -> text.equals(((Hyperlink) n).getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no crumb " + text + " among " + crumbs()));
        }

        List<String> crumbs() {
            return bar.lookupAll(".file-breadcrumb-crumb").stream()
                    .map(n -> ((Hyperlink) n).getText())
                    .toList();
        }

        /** Clicks a crumb and waits for the dropdown its click opens. */
        ContextMenu click(String text) throws Exception {
            ContextMenu before = bar.lastMenuForTest;
            FxTestSupport.runOnFx(() -> crumb(text).fire());
            SaveGuardsFxTest.awaitOnFx(async, "the dropdown of " + text, () -> bar.lastMenuForTest != before);
            return bar.lastMenuForTest;
        }

        static List<String> texts(ContextMenu menu) {
            return menu.getItems().stream()
                    .map(i -> i instanceof SeparatorMenuItem ? "---" : i.getText())
                    .toList();
        }

        static MenuItem item(ContextMenu menu, String text) {
            return menu.getItems().stream()
                    .filter(i -> text.equals(i.getText()))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() throws Exception {
            async.close();
        }
    }

    @Test
    void theTrailStartsAtTheProjectRootAndClickingACrumbListsItsFolder(@TempDir Path dir) throws Exception {
        try (Rig r = new Rig(dir)) {
            assertEquals(
                    List.of(dir.getFileName().toString(), "src", "Main.java"),
                    FxTestSupport.callOnFx(r::crumbs),
                    "nothing above the project is shown");

            ContextMenu menu = r.click("src");

            FxTestSupport.runOnFx(() -> {
                assertEquals(List.of("util", "Main.java", "Other.java"), Rig.texts(menu), "folders first");
                assertTrue(menu.isShowing());
                assertFalse(((Hyperlink) r.crumb("src")).isVisited(), "a crumb is a button, not a visited link");
                Rig.item(menu, "Other.java").fire();
            });
            assertEquals(List.of(r.src.resolve("Other.java")), r.opened);
        }
    }

    @Test
    void theFileCrumbListsItsSiblings(@TempDir Path dir) throws Exception {
        try (Rig r = new Rig(dir)) {
            ContextMenu menu = r.click("Main.java");
            assertEquals(
                    List.of("util", "Main.java", "Other.java"),
                    FxTestSupport.callOnFx(() -> Rig.texts(menu)),
                    "a file's dropdown is its folder");
        }
    }

    @Test
    void aFolderRowDrillsInAndReopensTheDropdownThere(@TempDir Path dir) throws Exception {
        try (Rig r = new Rig(dir)) {
            ContextMenu menu = r.click("src");

            FxTestSupport.runOnFx(() -> Rig.item(menu, "util").fire());
            SaveGuardsFxTest.awaitOnFx(r.async, "the dropdown of util", () -> r.bar.lastMenuForTest != menu);

            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        List.of(dir.getFileName().toString(), "src", "util"), r.crumbs(), "util is the last crumb now");
                assertEquals(List.of("Helper.java"), Rig.texts(r.bar.lastMenuForTest));
                Rig.item(r.bar.lastMenuForTest, "Helper.java").fire();
            });
            assertEquals(List.of(r.util.resolve("Helper.java")), r.opened);
            assertTrue(r.calls.isEmpty());
        }
    }

    @Test
    void revealAndOpenTerminalActOnTheClickedCrumb(@TempDir Path dir) throws Exception {
        try (Rig r = new Rig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.bar.setOnReveal((path, isDir) -> r.calls.add("reveal " + path.getFileName() + " " + isDir));
                r.bar.setOnOpenTerminal((path, isDir) -> r.calls.add("terminal " + path.getFileName() + " " + isDir));
            });

            ContextMenu folder = r.click("src");
            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        List.of(
                                tr("menu.revealInFileManager"),
                                tr("menu.openTerminal"),
                                "---",
                                "util",
                                "Main.java",
                                "Other.java"),
                        Rig.texts(folder));
                Rig.item(folder, tr("menu.revealInFileManager")).fire();
                Rig.item(folder, tr("menu.openTerminal")).fire();
            });

            ContextMenu file = r.click("Main.java");
            FxTestSupport.runOnFx(() -> {
                Rig.item(file, tr("menu.revealInFileManager")).fire();
                Rig.item(file, tr("menu.openTerminal")).fire();
            });
            assertEquals(
                    List.of(
                            "reveal src true",
                            "terminal src true",
                            "reveal Main.java false",
                            "terminal Main.java false"),
                    r.calls);

            // Only one of the two wired: the other entry is simply not offered.
            FxTestSupport.runOnFx(() -> r.bar.setOnOpenTerminal(null));
            ContextMenu revealOnly = r.click("src");
            assertEquals(
                    List.of(tr("menu.revealInFileManager"), "---", "util", "Main.java", "Other.java"),
                    FxTestSupport.callOnFx(() -> Rig.texts(revealOnly)));
        }
    }

    @Test
    void anEmptyFolderWithActionsHasNoDividerAndAnUnreadableOneShowsNoMenu(@TempDir Path dir) throws Exception {
        try (Rig r = new Rig(dir)) {
            Path empty = Files.createDirectory(dir.resolve("empty"));
            Path inEmpty = empty.resolve("not-yet.txt");
            FxTestSupport.runOnFx(() -> {
                r.bar.setOnReveal((path, isDir) -> r.calls.add("reveal " + path.getFileName()));
                r.bar.setActiveFile(inEmpty);
                r.bar.applyCss();
                r.bar.layout();
            });
            ContextMenu menu = r.click("empty");
            assertEquals(
                    List.of(tr("menu.revealInFileManager")),
                    FxTestSupport.callOnFx(() -> Rig.texts(menu)),
                    "nothing to list, so nothing to divide the action from");

            // A folder that cannot be read answers with no menu at all, not an empty one.
            FxTestSupport.runOnFx(() -> {
                menu.hide();
                r.bar.directoryReader = (folder, directoriesOnly) -> DirectoryListing.UNREADABLE;
                r.crumb("empty").fire();
            });
            r.async.awaitFx();
            FxTestSupport.runOnFx(() -> r.bar.directoryReader = DirectoryListing::read);
            ContextMenu next = r.click("empty");
            assertTrue(next != menu, "the unreadable read left nothing behind; this is the readable one's menu");
        }
    }

    @Test
    void aFolderWithNothingInItAndNoActionsShowsNoMenu(@TempDir Path dir) throws Exception {
        try (Rig r = new Rig(dir)) {
            Path empty = Files.createDirectory(dir.resolve("empty"));
            FxTestSupport.runOnFx(() -> {
                r.bar.setActiveFile(empty.resolve("not-yet.txt"));
                r.bar.applyCss();
                r.bar.layout();
            });

            ContextMenu menu = r.click("empty");

            FxTestSupport.runOnFx(() -> {
                assertTrue(menu.getItems().isEmpty());
                assertFalse(menu.isShowing(), "an empty popup would be a stray sliver on screen");
            });
        }
    }

    @Test
    void theBarIsHiddenUntilEnabledWithAFileAndWithoutAProjectStartsAtHome(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("loose.txt"), "");
        FxTestSupport.runOnFx(() -> {
            FileBreadcrumb bar = new FileBreadcrumb(p -> {});
            assertFalse(bar.isVisible() || bar.isManaged());
            bar.setActiveFile(file);
            assertFalse(bar.isVisible(), "a file, but the setting is off");
            bar.setEnabled(true);
            assertTrue(bar.isVisible() && bar.isManaged());
            bar.setActiveFile(null);
            assertFalse(bar.isVisible(), "enabled, but no file");
            assertNull(bar.lastMenuForTest);
        });
    }
}

package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Maven submenu offered on a {@code pom.xml} and on a project folder.
 *
 * <p>Both surfaces are built from one method on purpose — they cannot drift into offering different Maven
 * actions — so what is worth pinning is when it appears at all, and that its labels are the commands' own.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MavenMenuFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Menu menuFor(MavenProjectCoordinator c, Path context) {
        return (Menu) FxTestSupport.call(c, "mavenMenu", new Class[] {Path.class}, context);
    }

    private MavenProjectCoordinator coordinator(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "mavenProjectCoordinator");
    }

    @Test
    void aFolderWithAPomGetsTheSubmenu() throws Exception {
        Path dir = Files.createTempDirectory("editora-maven-menu");
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            Files.writeString(dir.resolve("pom.xml"), "<project><packaging>jar</packaging></project>");
            FxTestSupport.runOnFx(() -> {
                Menu menu = menuFor(coordinator(fx), dir);
                assertNotNull(menu, "a folder holding a pom should offer Maven actions");
                assertEquals(tr("menu.maven"), menu.getText());
                assertTrue(
                        menu.getItems().stream()
                                .anyMatch(
                                        i -> tr("command.maven.updateVersions").equals(i.getText())),
                        "Update Versions is the action this menu exists for");
                assertTrue(menu.getItems().size() >= 3);
            });
        } finally {
            fx.dispose();
            Files.deleteIfExists(dir.resolve("pom.xml"));
            Files.deleteIfExists(dir);
        }
    }

    /** A pom.xml file resolves through its own directory, which is the same project. */
    @Test
    void aPomFileGetsTheSameSubmenu() throws Exception {
        Path dir = Files.createTempDirectory("editora-maven-menu-file");
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            Path pom = dir.resolve("pom.xml");
            Files.writeString(pom, "<project><packaging>jar</packaging></project>");
            FxTestSupport.runOnFx(() -> {
                Menu fromFile = menuFor(coordinator(fx), pom);
                Menu fromDir = menuFor(coordinator(fx), dir);
                assertNotNull(fromFile);
                assertEquals(fromDir.getItems().size(), fromFile.getItems().size(), "one builder, one menu");
            });
        } finally {
            fx.dispose();
            Files.deleteIfExists(dir.resolve("pom.xml"));
            Files.deleteIfExists(dir);
        }
    }

    /** No pom anywhere above: there is no Maven project to act on, so no menu rather than a dead one. */
    @Test
    void aFolderWithNoPomGetsNoSubmenu() throws Exception {
        Path dir = Files.createTempDirectory("editora-maven-menu-none");
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            FxTestSupport.runOnFx(() -> assertNull(menuFor(coordinator(fx), dir)));
        } finally {
            fx.dispose();
            Files.deleteIfExists(dir);
        }
    }

    /**
     * Every file in a Maven project has a pom above it, so resolving a file context through its parent
     * directory would hang this menu off every source file in the tree — saying nothing about which pom.
     */
    @Test
    void aFileThatIsNotAPomGetsNoSubmenuEvenInsideAMavenProject() throws Exception {
        Path dir = Files.createTempDirectory("editora-maven-menu-other");
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            Files.writeString(dir.resolve("pom.xml"), "<project><packaging>jar</packaging></project>");
            Path other = Files.writeString(dir.resolve("Main.java"), "class Main {}");
            Path lookalike = Files.writeString(dir.resolve("effective-pom.xml"), "<project/>");
            FxTestSupport.runOnFx(() -> {
                assertNotNull(menuFor(coordinator(fx), dir), "precondition: this really is a Maven project");
                assertNull(menuFor(coordinator(fx), other));
                // Not a file mvn will run against — a menu here would silently act on the pom.xml beside it.
                assertNull(menuFor(coordinator(fx), lookalike));
            });
        } finally {
            fx.dispose();
            try (var paths = Files.list(dir)) {
                for (Path p : paths.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    /**
     * The project tree's own right-click menu on a {@code pom.xml} row — the wiring, not the builder: the
     * tree used to ask for this menu only for folders, so a pom.xml row offered nothing.
     */
    @Test
    void theProjectTreeOffersItOnAPomRow() throws Exception {
        Path dir = Files.createTempDirectory("editora-maven-menu-tree");
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            Path pom = Files.writeString(dir.resolve("pom.xml"), "<project><packaging>jar</packaging></project>");
            Path other = Files.writeString(dir.resolve("notes.txt"), "hello");
            ProjectPanel panel = FxTestSupport.field(fx.controller, "projectPanel");
            FxTestSupport.runOnFx(() -> {
                assertTrue(hasMavenMenu(panel, pom), "a pom.xml row must offer the Maven submenu");
                assertFalse(hasMavenMenu(panel, other), "an ordinary file must not");
                assertTrue(hasMavenMenu(panel, dir), "…and the folder menu keeps it");
            });
        } finally {
            fx.dispose();
            try (var paths = Files.list(dir)) {
                for (Path p : paths.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    /** Builds a real cell from the tree's own factory and asks it for the row's menu, as a right-click does. */
    @SuppressWarnings("unchecked")
    private static boolean hasMavenMenu(ProjectPanel panel, Path item) {
        javafx.scene.control.TreeView<Path> tree = FxTestSupport.field(panel, "tree");
        javafx.scene.control.TreeCell<Path> cell = tree.getCellFactory().call(tree);
        ContextMenu menu = (ContextMenu) FxTestSupport.call(
                cell,
                "contextMenuFor",
                new Class<?>[] {javafx.scene.control.TreeItem.class, boolean.class, boolean.class},
                new javafx.scene.control.TreeItem<>(item),
                Files.isDirectory(item),
                false);
        return menu.getItems().stream().anyMatch(i -> tr("menu.maven").equals(i.getText()));
    }
}

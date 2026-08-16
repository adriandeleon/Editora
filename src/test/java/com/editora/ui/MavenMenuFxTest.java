package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.Menu;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

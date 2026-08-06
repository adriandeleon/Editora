package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;

import com.editora.template.NewFileCatalog;
import com.editora.template.NewFileType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project tree's "New ▸" submenu, end to end: the menu the catalog produces, and the file a menu
 * item actually writes.
 *
 * <p>The pure tests pin what a typed name <em>means</em>; this pins that the menu is wired to it —
 * a catalog entry with no menu item, or a menu item wired to nothing, fails no unit test.
 */
@Tag("fx")
class NewFileMenuFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static FxWindowFixture window;

    @AfterAll
    static void tearDown() throws Exception {
        if (window != null) {
            window.dispose();
            window = null;
        }
    }

    // --- the menu ---------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Menu newMenuFor(ProjectPanel panel, Path dir) {
        return (Menu) FxTestSupport.call(panel, "newMenu", new Class<?>[] {TreeItem.class}, new TreeItem<>(dir));
    }

    private static List<String> labels(List<MenuItem> items) {
        List<String> out = new ArrayList<>();
        for (MenuItem item : items) {
            if (!(item instanceof SeparatorMenuItem)) {
                out.add(item.getText());
            }
        }
        return out;
    }

    @Test
    void theFolderMenuOffersEveryCatalogCategory(@TempDir Path dir) throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false);
            p.setOnNewFile((d, t) -> {});
            return p;
        });
        Menu menu = FxTestSupport.callOnFx(() -> newMenuFor(panel, dir));

        List<String> top = labels(menu.getItems());
        // The generic entries first, then the two everyday types, then one submenu per category.
        assertTrue(top.indexOf("File…") == 0, top.toString());
        assertTrue(top.contains("Text File") && top.contains("Markdown File"), top.toString());
        for (NewFileCatalog.Category category : NewFileCatalog.categories()) {
            Menu submenu = (Menu) menu.getItems().stream()
                    .filter(i -> i instanceof Menu)
                    .filter(i -> i.getText().equals(com.editora.i18n.Messages.tr(category.labelKey())))
                    .findFirst()
                    .orElse(null);
            assertNotNull(submenu, "no submenu for category " + category.id() + " in " + top);
            assertEquals(
                    category.types().size(), submenu.getItems().size(), category.id() + " submenu is missing entries");
            for (MenuItem item : submenu.getItems()) {
                assertFalse(item.getText().isBlank(), category.id() + " has an unlabelled entry");
                assertNotNull(item.getGraphic(), category.id() + " has an entry with no icon");
            }
        }
    }

    @Test
    void aTypeEntryHandsItsFolderAndTypeToTheHandler(@TempDir Path dir) throws Exception {
        AtomicReference<Path> gotDir = new AtomicReference<>();
        AtomicReference<NewFileType> gotType = new AtomicReference<>();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false);
            p.setOnNewFile((d, t) -> {
                gotDir.set(d);
                gotType.set(t);
            });
            return p;
        });
        Menu menu = FxTestSupport.callOnFx(() -> newMenuFor(panel, dir));
        Menu java = (Menu) menu.getItems().stream()
                .filter(i -> i instanceof Menu)
                .filter(i -> "Java".equals(i.getText()))
                .findFirst()
                .orElseThrow();
        MenuItem clazz = java.getItems().get(0);
        FxTestSupport.runOnFx(() -> clazz.fire());

        assertEquals(dir, gotDir.get());
        assertEquals("java.class", gotType.get().id());
    }

    @Test
    void withNoHandlerInjectedOnlyTheFolderEntryRemains(@TempDir Path dir) throws Exception {
        // The panel is constructed before MainController injects its handlers; the menu must not break.
        ProjectPanel panel = FxTestSupport.callOnFx(() -> new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false));
        Menu menu = FxTestSupport.callOnFx(() -> newMenuFor(panel, dir));
        assertEquals(List.of(com.editora.i18n.Messages.tr("project.menu.newFolder")), labels(menu.getItems()));
    }

    // --- creating the file ------------------------------------------------------------------------

    private static void create(MainController controller, Path dir, String typeId, String typed) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                controller,
                "createFileOfType",
                new Class<?>[] {Path.class, NewFileType.class, String.class},
                dir,
                NewFileCatalog.byId(typeId),
                typed));
    }

    @Test
    void creatingAJavaClassUnderASourceRootWritesItsPackage(@TempDir Path project) throws Exception {
        if (window == null) {
            window = FxWindowFixture.create();
        }
        Path pkgDir = project.resolve("src/main/java/demo");
        Files.createDirectories(pkgDir);

        create(window.controller, pkgDir, "java.class", "Slug");

        Path file = pkgDir.resolve("Slug.java");
        assertTrue(Files.exists(file), "the file was not created");
        String text = Files.readString(file);
        assertTrue(text.startsWith("package demo;\n"), text);
        assertTrue(text.contains("public class Slug {"), text);
    }

    @Test
    void aQualifiedJavaNameCreatesTheSubPackageFolder(@TempDir Path project) throws Exception {
        if (window == null) {
            window = FxWindowFixture.create();
        }
        Path pkgDir = project.resolve("src/main/java/demo");
        Files.createDirectories(pkgDir);

        create(window.controller, pkgDir, "java.record", "text.Point");

        Path file = pkgDir.resolve("text/Point.java");
        assertTrue(Files.exists(file), "the sub-package file was not created");
        assertTrue(Files.readString(file).startsWith("package demo.text;\n"), Files.readString(file));
    }

    @Test
    void aPlainTypeCreatesAnEmptyFileWithTheTypesExtension(@TempDir Path dir) throws Exception {
        if (window == null) {
            window = FxWindowFixture.create();
        }
        create(window.controller, dir, "data.yaml", "settings");

        Path file = dir.resolve("settings.yaml");
        assertTrue(Files.exists(file));
        assertEquals("", Files.readString(file));
    }

    @Test
    void anEscapingNameCreatesNothingAtAll(@TempDir Path root) throws Exception {
        if (window == null) {
            window = FxWindowFixture.create();
        }
        Path dir = root.resolve("inside");
        Files.createDirectories(dir);

        create(window.controller, dir, "data.yaml", "../escaped");

        assertFalse(Files.exists(root.resolve("escaped.yaml")), "a typed name escaped its folder");
        try (var entries = Files.list(dir)) {
            assertEquals(0, entries.count(), "something was created despite the refusal");
        }
    }

    @Test
    void anExistingFileIsNeverOverwritten(@TempDir Path dir) throws Exception {
        if (window == null) {
            window = FxWindowFixture.create();
        }
        Path file = dir.resolve("notes.md");
        Files.writeString(file, "keep me");

        create(window.controller, dir, "markdown", "notes");

        assertEquals("keep me", Files.readString(file));
    }
}

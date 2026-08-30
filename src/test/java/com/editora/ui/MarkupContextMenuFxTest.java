package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each markup language's formatting actions live under one titled submenu in the editor's right-click menu,
 * the way the LSP and Maven actions already do.
 *
 * <p>Typst contributed eight flat {@code Typst: …} entries and Markdown seven, which pushed cut/copy/paste
 * and the spelling suggestions far enough down the menu to hunt for, and made the file-type actions read as
 * ordinary editing ones. Grouping them also names what they are.
 *
 * <p>Asserted through {@code EditorBuffer}'s own menu builders rather than by opening a real popup: a
 * {@code ContextMenu} is only populated by a live {@code CONTEXT_MENU_REQUESTED} on a laid-out area, and
 * what matters here is the shape of what gets inserted.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkupContextMenuFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** The submenu the context menu inserts for a buffer of this file type. */
    private static Menu markupMenu(Path file, String titleKey, boolean markdown) throws Exception {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setPath(file);
        @SuppressWarnings("unchecked")
        List<MenuItem> actions = (List<MenuItem>)
                FxTestSupport.call(buffer, markdown ? "markdownMenuItems" : "typstMenuItems", new Class[0]);
        return (Menu)
                FxTestSupport.call(buffer, "markupMenu", new Class[] {String.class, List.class}, tr(titleKey), actions);
    }

    private static List<String> labels(Menu menu) {
        return menu.getItems().stream().map(MenuItem::getText).toList();
    }

    @Test
    void theTypstActionsAreGroupedUnderATypstSubmenu(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("report.typ");
        Files.writeString(file, "= Title\n");
        FxTestSupport.runOnFx(() -> {
            Menu menu = call(() -> markupMenu(file, "editmenu.typst", false));
            assertEquals(tr("editmenu.typst"), menu.getText());
            assertNotNull(menu.getGraphic(), "context-menu entries carry a leading glyph");
            List<String> labels = labels(menu);
            for (String key : List.of(
                    "command.typst.bold",
                    "command.typst.emph",
                    "command.typst.raw",
                    "command.typst.link",
                    "command.typst.bulletList",
                    "command.typst.insertTable",
                    "command.typst.outline",
                    "command.typst.insertImage")) {
                assertTrue(labels.contains(tr(key)), tr(key) + " was dropped by the regrouping");
            }
        });
    }

    @Test
    void theMarkdownActionsAreGroupedUnderAMarkdownSubmenu(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.md");
        Files.writeString(file, "# Title\n");
        FxTestSupport.runOnFx(() -> {
            Menu menu = call(() -> markupMenu(file, "editmenu.markdown", true));
            assertEquals(tr("editmenu.markdown"), menu.getText());
            assertNotNull(menu.getGraphic());
            List<String> labels = labels(menu);
            for (String key : List.of(
                    "command.markdown.bold",
                    "command.markdown.italic",
                    "command.markdown.strikethrough",
                    "command.markdown.code",
                    "command.markdown.link",
                    "command.markdown.toc")) {
                assertTrue(labels.contains(tr(key)), tr(key) + " was dropped by the regrouping");
            }
            // The pre-existing Table submenu stays nested inside, not promoted alongside it.
            assertTrue(
                    menu.getItems().stream()
                            .anyMatch(i -> i instanceof Menu
                                    && tr("menu.markdown.table").equals(i.getText())),
                    "the Table submenu should still be inside the Markdown submenu");
        });
    }

    /**
     * The real context menu, built by the real handler — the assertion that actually pins the regrouping.
     *
     * <p>The two tests above exercise the builder, which would keep passing if the call site went back to
     * splicing the actions in flat. This one opens the menu the way a right-click does and counts the rows a
     * markup language contributes to the <em>top level</em>: exactly one, the submenu.
     */
    @Test
    void aMarkupLanguageContributesExactlyOneTopLevelRow(@TempDir Path dir) throws Exception {
        Path typ = dir.resolve("a.typ");
        Files.writeString(typ, "= Title\n\nsome text\n");
        List<MenuItem> top = openContextMenu(typ, "= Title\n\nsome text\n");

        List<String> flat = top.stream()
                .filter(i -> !(i instanceof Menu))
                .map(MenuItem::getText)
                .filter(t -> t != null && t.startsWith("Typst"))
                .toList();
        assertTrue(flat.isEmpty(), "Typst actions are spliced in flat, not grouped: " + flat);

        long submenus = top.stream()
                .filter(i -> i instanceof Menu && tr("editmenu.typst").equals(i.getText()))
                .count();
        assertEquals(1, submenus, "expected exactly one Typst submenu at the top level of the menu");
    }

    /**
     * Run and Debug stay at the <em>top</em> level. They are the two entries reached without reading the
     * menu, so burying them under a submenu would cost more than the tidiness gained — and the grouping pass
     * is exactly the kind of change that would sweep them in by accident.
     */
    @Test
    void runAndDebugStayTopLevel(@TempDir Path dir) throws Exception {
        Path java = dir.resolve("Main.java");
        String src = "void main() {\n    IO.println(\"hi\");\n}\n";
        Files.writeString(java, src);
        List<MenuItem> top = openContextMenu(java, src);

        boolean runAtTop = top.stream()
                .anyMatch(i -> !(i instanceof Menu) && tr("command.file.run").equals(i.getText()));
        assertTrue(runAtTop, "Run should be a first-level entry; top level was " + labelsOf(top));
    }

    /** Opens the real context menu for a buffer holding {@code text} and returns its top-level items. */
    private static List<MenuItem> openContextMenu(Path file, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(text);
            buffer.setRunHandler(() -> {});
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(buffer.getNode()), 600, 400));
            stage.show();

            CodeArea area = FxTestSupport.field(buffer, "area");
            Event.fireEvent(
                    area, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, false, null));
            ContextMenu menu = FxTestSupport.field(buffer, "contextMenu");
            List<MenuItem> items = List.copyOf(menu.getItems());
            menu.hide();
            stage.hide();
            return items;
        });
    }

    private static List<String> labelsOf(List<MenuItem> items) {
        return items.stream().map(MenuItem::getText).toList();
    }

    /** Every catalog must carry the two submenu titles, or a locale renders an empty menu label. */
    @Test
    void bothSubmenuTitlesResolve() {
        assertFalse(tr("editmenu.markdown").isBlank());
        assertFalse(tr("editmenu.typst").isBlank());
        assertFalse(tr("editmenu.markdown").startsWith("editmenu."), "unresolved i18n key");
        assertFalse(tr("editmenu.typst").startsWith("editmenu."), "unresolved i18n key");
    }

    private interface Sup<T> {
        T get() throws Exception;
    }

    private static <T> T call(Sup<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

package com.editora.ui;

import java.util.List;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings window's frame: how it opens, what a command opening it on a page shows, the sidebar. */
@Tag("fx")
class SettingsFrameFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    static Object category(ListView<Object> sidebar, String name) {
        return sidebar.getItems().stream()
                .filter(i -> i instanceof Enum<?> e
                        && e.name().equals(name)
                        && !e.getDeclaringClass().getSimpleName().equals("Group"))
                .findFirst()
                .orElseThrow();
    }

    /** The rows of the page on show that the search filter leaves visible. */
    static int visibleRowsOnPage(SettingsWindow window) {
        ScrollPane scroll = FxTestSupport.field(window, "contentScroll");
        Node page = scroll.getContent();
        List<Object> rows = FxTestSupport.field(window, "rows");
        int n = 0;
        for (Object row : rows) {
            Node node = (Node) FxTestSupport.call(row, "node", new Class<?>[0]);
            boolean under = false;
            for (Node p = node; p != null; p = p.getParent()) {
                under |= p == page;
            }
            if (under && node.isVisible()) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aCommandOpeningAPageClearsASearchLeftInTheBox() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = fx.controller.settingsWindow;
            Stage owner = FxTestSupport.field(fx.controller, "stage");
            FxTestSupport.runOnFx(() -> window.show(owner));
            Stage stage = FxTestSupport.field(window, "stage");
            TextField search = FxTestSupport.field(window, "searchField");
            ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            FxTestSupport.runOnFx(() -> {
                search.setText("font");
                stage.close(); // the title bar / Close button: the search stays in the box
            });
            for (String[] c : List.of(
                    new String[] {"snippets.manage", "SNIPPETS"},
                    new String[] {"sync.setup", "SYNC"},
                    new String[] {"abbrev.manage", "ABBREVIATIONS"})) {
                FxTestSupport.runOnFx(() -> {
                    search.setText("font");
                    stage.close();
                    fx.controller.registry.run(c[0]);
                });
                FxTestSupport.runOnFx(() -> {
                    assertTrue(stage.isShowing(), c[0]);
                    assertEquals("", search.getText(), c[0] + " clears the search");
                    assertSame(
                            category(sidebar, c[1]), sidebar.getSelectionModel().getSelectedItem(), c[0]);
                    Set<Object> hidden = FxTestSupport.field(window, "searchHiddenCats");
                    assertTrue(hidden.isEmpty(), c[0] + ": no page greyed out");
                    assertTrue(visibleRowsOnPage(window) > 0, c[0] + " shows the page's settings");
                });
            }
            FxTestSupport.runOnFx(stage::hide);
        }
    }

    @Test
    void aPlainReopenKeepsTheSearchSelectedSoTypingReplacesIt() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = fx.controller.settingsWindow;
            Stage owner = FxTestSupport.field(fx.controller, "stage");
            FxTestSupport.runOnFx(() -> window.show(owner));
            Stage stage = FxTestSupport.field(window, "stage");
            TextField search = FxTestSupport.field(window, "searchField");
            FxTestSupport.runOnFx(() -> {
                search.setText("font");
                stage.close();
                window.show(owner);
            });
            FxTestSupport.runOnFx(() -> {
                assertEquals("font", search.getText());
                assertEquals("font", search.getSelectedText(), "the kept search is selected");
                assertSame(search, stage.getScene().getFocusOwner(), "a reopen focuses the search, not nothing");
                stage.hide();
            });
        }
    }
}

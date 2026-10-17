package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * At its minimum width (900 px) the Settings window fits every page sideways in every language: a page whose
 * controls cannot shrink to the viewport makes the whole page scroll horizontally (German Snippets did).
 */
@Tag("fx")
class SettingsMinWidthFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterAll
    static void english() throws Exception {
        FxTestSupport.runOnFx(() -> Messages.init("en"));
    }

    @Test
    void noPageScrollsSidewaysAtTheMinimumWidthInAnyLanguage() throws Exception {
        List<String> overflow = new ArrayList<>();
        for (String lang : Messages.available().keySet()) {
            FxTestSupport.runOnFx(() -> Messages.init(lang)); // before the window: its strings are read at build
            try (var fx = FxWindowFixture.create()) {
                SettingsWindow window = fx.controller.settingsWindow;
                FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
                Stage stage = FxTestSupport.field(window, "stage");
                ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
                ScrollPane scroll = FxTestSupport.field(window, "contentScroll");
                FxTestSupport.runOnFx(() -> {
                    stage.setWidth(stage.getMinWidth());
                    stage.setHeight(600);
                });
                int n = FxTestSupport.callOnFx(() -> sidebar.getItems().size());
                for (int i = 0; i < n; i++) {
                    int index = i;
                    FxTestSupport.runOnFx(() -> {
                        Object item = sidebar.getItems().get(index);
                        if (!item.getClass().getSimpleName().equals("Category")) {
                            return;
                        }
                        sidebar.getSelectionModel().select(index);
                        stage.getScene().getRoot().applyCss();
                        stage.getScene().getRoot().layout();
                        Region page = (Region) scroll.getContent();
                        double viewport = scroll.getViewportBounds().getWidth();
                        double needs = page.minWidth(-1);
                        if (needs > viewport + 0.5) {
                            overflow.add(lang + " " + item + ": needs " + Math.round(needs) + " px, has "
                                    + Math.round(viewport));
                        }
                    });
                }
                FxTestSupport.runOnFx(stage::hide);
            }
        }
        assertTrue(overflow.isEmpty(), "pages that scroll sideways at 900 px:\n" + String.join("\n", overflow));
    }

    @Test
    void cardsEndAtTheSameRightEdgeWithOrWithoutAScrollbar() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = fx.controller.settingsWindow;
            FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
            Stage stage = FxTestSupport.field(window, "stage");
            ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            FxTestSupport.runOnFx(() -> {
                stage.setWidth(1100);
                stage.setHeight(700);
            });
            for (int i = 0;
                    i < 200 && FxTestSupport.callOnFx(() -> stage.getScene().getWidth()) < 1099;
                    i++) {
                Thread.sleep(10); // the resize reaches the scene on a later pulse
            }
            java.util.Map<String, Double> rightEdge = new java.util.LinkedHashMap<>();
            for (String page : List.of("INTERFACE", "MCP", "EDITOR", "SEARCH")) { // long, short, long, short
                FxTestSupport.drainFx();
                FxTestSupport.runOnFx(() -> {
                    for (Object item : sidebar.getItems()) {
                        if (item instanceof Enum<?> e
                                && e.name().equals(page)
                                && item.getClass().getSimpleName().equals("Category")) {
                            sidebar.getSelectionModel().select(item);
                        }
                    }
                    stage.getScene().getRoot().applyCss();
                    stage.getScene().getRoot().layout();
                    stage.getScene().getRoot().layout(); // the scrollbar shows or hides after the first pass
                    javafx.scene.Node card = stage.getScene().getRoot().lookup(".settings-page .settings-card");
                    rightEdge.put(
                            page, card.localToScene(card.getBoundsInLocal()).getMaxX());
                });
            }
            FxTestSupport.runOnFx(stage::hide);
            double first = rightEdge.values().iterator().next();
            for (var e : rightEdge.entrySet()) {
                assertTrue(Math.abs(e.getValue() - first) < 1, "right edges: " + rightEdge);
            }
        }
    }
}

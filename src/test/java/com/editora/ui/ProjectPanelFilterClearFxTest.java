package com.editora.ui;

import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless-FX coverage of the Project filter's clear ("✕") button: hidden while the filter is empty,
 * shown once it has text, and clicking it empties the field (which hides it again).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectPanelFilterClearFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static ProjectPanel newPanel() throws Exception {
        return FxTestSupport.callOnFx(() -> new ProjectPanel(p -> {}, (a, b) -> {}, p -> {}, p -> false));
    }

    private static Button clearButton(ProjectPanel panel) {
        HBox bar = FxTestSupport.field(panel, "filterBar");
        return (Button) bar.getChildren().stream()
                .filter(n -> n instanceof Button)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void clearButtonVisibilityTracksFilterText() throws Exception {
        ProjectPanel panel = newPanel();
        TextField filter = FxTestSupport.field(panel, "filterField");
        Button clear = clearButton(panel);

        assertFalse(FxTestSupport.callOnFx(clear::isVisible), "hidden while the filter is empty");

        FxTestSupport.runOnFx(() -> filter.setText("foo"));
        assertTrue(FxTestSupport.callOnFx(clear::isVisible), "shown once there is text");
        assertTrue(FxTestSupport.callOnFx(clear::isManaged), "managed so it occupies space only when shown");

        FxTestSupport.runOnFx(clear::fire);
        assertTrue(FxTestSupport.callOnFx(() -> filter.getText().isEmpty()), "clicking it empties the filter");
        assertFalse(FxTestSupport.callOnFx(clear::isVisible), "hidden again after clearing");
    }
}

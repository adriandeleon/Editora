package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The main window's chord chips (the Welcome page's keys here) take the app theme's periwinkle: the editor
 * theme used to set it, so a light editor theme under a dark app theme drew light-theme ink on dark ground.
 */
@Tag("fx")
class ChordChipThemeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterAll
    static void restoreTheme() throws Exception {
        FxTestSupport.runOnFx(
                () -> javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(Themes.DEFAULT)));
    }

    @Test
    void theWelcomeKeysReadOnEveryAppThemeWhateverTheEditorTheme() throws Exception {
        try (var fx = FxWindowFixture.create(java.nio.file.Files.createTempDirectory("editora-fx-test"), shared -> {
            shared.getSettings().setEditorTheme("Editora Light"); // light editor ink, whatever the app theme
            shared.getSettings().setEditorThemeUserSet(true);
        })) {
            Stage main = FxTestSupport.field(fx.controller, "stage");
            for (String theme : List.of("Editora Dark", "Autumn", "Nord Dark", "Dracula", "Editora Light", "Yacht")) {
                FxTestSupport.runOnFx(() -> {
                    javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(theme));
                    main.getScene().getRoot().applyCss();
                    Node key = main.getScene().getRoot().lookup(".welcome-key");
                    assertNotNull(key, "the Welcome page shows its keys");
                    Color ink = (Color) ((Labeled) key).getTextFill();
                    // The app theme's -state-key, as JavaFX resolves it on the window's own ground: a probe on the
                    // scene root, outside the editor, where no editor theme reaches. Its contrast on every bundled
                    // theme is StyleContrastTest's (theKeyboardPeriwinkleReadsOnEveryBundledAppTheme), so no
                    // ratio is computed here (ICON-14, Pauta 6.2.0).
                    assertEquals(keyInk(main), ink, theme + ": the Welcome keys take the app theme's periwinkle");
                });
            }
        }
    }

    /** {@code -state-key} resolved on {@code stage}'s scene root, by a label added there for the moment. */
    static Color keyInk(Stage stage) {
        javafx.scene.layout.Pane root =
                (javafx.scene.layout.Pane) stage.getScene().getRoot();
        javafx.scene.control.Label probe = new javafx.scene.control.Label("K");
        probe.setStyle("-fx-text-fill: -state-key;");
        probe.setManaged(false);
        javafx.collections.ObservableList<Node> children = root.getChildren();
        children.add(probe);
        try {
            root.applyCss();
            return (Color) probe.getTextFill();
        } finally {
            children.remove(probe);
        }
    }
}

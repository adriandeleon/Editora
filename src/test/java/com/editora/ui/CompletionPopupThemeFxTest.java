package com.editora.ui;

import java.util.List;

import javafx.application.Application;
import javafx.geometry.BoundingBox;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Popup;
import javafx.stage.Stage;

import com.editora.completion.Completion;
import com.editora.editor.CompletionPopup;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class CompletionPopupThemeFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void selectedRowsResolveTheActiveThemeInTheirOwnPopupScene() throws Exception {
        FxTestSupport.runOnFx(() -> {
            String previous = Application.getUserAgentStylesheet();
            Stage stage = new Stage();
            CompletionPopup completion = new CompletionPopup();
            try {
                Region expected = new Region();
                expected.setStyle("-fx-background-color: -color-accent-emphasis;");
                stage.setScene(new Scene(new StackPane(expected), 400, 300));
                stage.show();
                for (String theme : List.of("Editora Light", "Editora Dark", "Primer Dark")) {
                    Application.setUserAgentStylesheet(Themes.stylesheetFor(theme));
                    stage.getScene().getRoot().applyCss();
                    completion.show(
                            stage,
                            new BoundingBox(50, 50, 1, 20),
                            List.of(Completion.lsp("String", "String", "java.lang")),
                            0);
                    Popup popup = FxTestSupport.field(completion, "popup");
                    popup.getScene().getRoot().applyCss();
                    popup.getScene().getRoot().layout();
                    ListCell<?> cell = (ListCell<?>) popup.getScene().getRoot().lookup(".list-cell:selected");
                    assertNotNull(cell, theme);
                    assertNotNull(cell.getBackground(), theme);
                    assertFalse(cell.getBackground().getFills().isEmpty(), theme);
                    assertEquals(
                            expected.getBackground().getFills().getFirst().getFill(),
                            cell.getBackground().getFills().getFirst().getFill(),
                            theme);
                    completion.hide();
                }
            } finally {
                completion.hide();
                stage.close();
                Application.setUserAgentStylesheet(previous);
            }
        });
    }
}

package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Advanced disclosure in a dialog form reads at the size of the field labels beside it. */
class DialogAdvancedTitleFxTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theAdvancedTitleIsTheSizeOfAFieldLabel() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Label field = new Label("Name");
            TitledPane advanced = new TitledPane("Advanced", new VBox());
            advanced.getStyleClass().add("dialog-advanced");
            TitledPane plain = new TitledPane("Plain", new VBox());
            Scene scene = new Scene(new VBox(field, advanced, plain), 400, 300);
            scene.setUserAgentStylesheet(Themes.stylesheetFor(Themes.DEFAULT));
            scene.getStylesheets()
                    .add(DialogAdvancedTitleFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            scene.getRoot().applyCss();

            double labelSize = field.getFont().getSize();
            // The theme sizes a TitledPane title up as a heading; that is what the dialog rule undoes.
            assertTrue(titleText(plain).getFont().getSize() > labelSize, "the theme still enlarges plain titles");
            assertEquals(labelSize, titleText(advanced).getFont().getSize(), 0.01);
        });
    }

    private static Text titleText(TitledPane pane) {
        Node text = pane.lookup(".title > .text");
        return assertInstanceOf(Text.class, text);
    }
}

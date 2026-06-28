package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards that a tool-window console's programmatic font ({@code setOutputFont}/{@code setConsoleFont},
 * used to match the editor) survives a CSS pass. Regression test: a {@code -fx-font-size} rule on
 * {@code .run-output}/{@code .debug-console} used to override the control's font property on every
 * {@code applyCss}, so the consoles never matched the editor.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleFontFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void consoleFontSurvivesCssPass() throws Exception {
        FxTestSupport.runOnFx(() -> {
            RunPanel run = new RunPanel(() -> {});
            run.setOutputFont("JetBrains Mono", 17);
            TextArea out = FxTestSupport.field(run, "output");

            StackPane root = new StackPane(out);
            Scene scene = new Scene(root, 400, 300);
            scene.getStylesheets()
                    .add(RunPanel.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            root.applyCss();
            root.layout();

            Font f = out.getFont();
            assertEquals("JetBrains Mono", f.getFamily(), "family kept after CSS pass");
            assertEquals(17.0, f.getSize(), 0.001, "size kept after CSS pass (CSS must not override setFont)");
        });
    }
}

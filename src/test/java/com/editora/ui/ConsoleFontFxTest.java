package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards that the Run console's programmatic font ({@code setOutputFont}, used to match the editor) survives a
 * CSS pass. The console is a RichTextFX {@link CodeArea} whose font is set via an inline {@code setStyle}
 * (which beats any {@code .run-output} stylesheet rule in the CSS cascade), so {@code applyCss} must not strip it.
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
            CodeArea out = FxTestSupport.field(run, "output");

            StackPane root = new StackPane(out);
            Scene scene = new Scene(root, 400, 300);
            scene.getStylesheets()
                    .add(RunPanel.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            root.applyCss();
            root.layout();

            String style = out.getStyle();
            assertTrue(style.contains("JetBrains Mono"), "family kept in the inline style after a CSS pass: " + style);
            assertTrue(style.contains("17"), "size kept in the inline style after a CSS pass: " + style);
        });
    }
}

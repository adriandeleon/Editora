package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.dap.DapModels;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review 2, C105 follow-up: the value popup in a dark window over a light editor theme. The popup was owned by the
 * editor area, which made the area its CSS parent: the editor theme's {@code .editor-area .text { -fx-fill: #1b1e2a }}
 * then coloured the String header (and Copy Value) dark on the dark popup.
 */
@Tag("fx")
class DebugValuePopupThemeFxTest {

    /** The dark window theme's colours the popup uses (what the AtlantaFX Editora Dark theme defines). */
    private static final String DARK_WINDOW =
            ".root { -color-fg-default: #e8eaf3; -color-bg-overlay: #232736;" + " -color-border-default: #3a3f52; }";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theStringHeaderIsReadableInADarkWindowOverALightEditorTheme() throws Exception {
        DebugValuePopup.Source strings = new DebugValuePopup.Source() {
            @Override
            public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
                cb.accept(new DapModels.EvalResult("\"line one\nline two\"", 7, "String"));
            }

            @Override
            public void children(
                    int ref, String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                cb.accept(List.of());
            }

            @Override
            public void addWatch(String expression) {}
        };
        Object[] made = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea("msg\n");
            area.getStyleClass().add("editor-area");
            Scene scene = new Scene(new StackPane(area), 600, 300);
            scene.getStylesheets()
                    .addAll(
                            getClass()
                                    .getResource("/com/editora/styles/app.css")
                                    .toExternalForm(),
                            getClass()
                                    .getResource("/com/editora/styles/editor-themes/editora-light.css")
                                    .toExternalForm(),
                            "data:text/css;base64,"
                                    + Base64.getEncoder().encodeToString(DARK_WINDOW.getBytes(StandardCharsets.UTF_8)));
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            return new Object[] {area, stage, new DebugValuePopup(strings)};
        });
        CodeArea area = (CodeArea) made[0];
        Stage stage = (Stage) made[1];
        DebugValuePopup popup = (DebugValuePopup) made[2];
        try {
            FxTestSupport.runOnFx(() -> {
                popup.show(area, "msg", stage.getX() + 20, stage.getY() + 40, true);
                assertTrue(popup.isShowing());
                Label header = FxTestSupport.field(popup, "textHeader");
                header.getScene().getRoot().applyCss();
                Color fill = (Color) header.getTextFill();
                Color background = Color.web("#232736");
                assertTrue(
                        contrast(fill, background) >= 4.5,
                        "header " + fill + " on " + background + ": contrast " + contrast(fill, background));
                for (javafx.css.Styleable s = header; s != null; s = s.getStyleableParent()) {
                    assertFalse(
                            s.getStyleClass().contains("editor-area"),
                            "the editor's own rules must not reach the popup");
                }
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                popup.hide();
                stage.close();
            });
        }
    }

    /** WCAG contrast ratio of two colours. */
    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}

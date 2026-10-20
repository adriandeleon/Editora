package com.editora.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.build.OutputStyle;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maven review B3: the Output console's {@code [ERROR]} / {@code [WARNING]} / {@code BUILD SUCCESS} /
 * {@code BUILD FAILURE} lines keep their colour under every editor theme. The style classes were applied all
 * along ({@code OutputStyle.maven()}), but the editor theme's {@code .editor-area .text} fill rule beat the bare
 * {@code .text.log-error} rule on source order, so a failed build read as bold text in the default ink. A probe
 * of the rendered {@link Text} fills, as the Git diffstat and stderr probes are.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildConsoleColorsFxTest {

    private static final Object MAVEN = new Object();

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @ParameterizedTest
    @ValueSource(strings = {"editora-light", "editora-dark", "nord-light", "dracula"})
    void mavenLevelsAndVerdictsKeepTheirColourUnderTheEditorTheme(String theme) throws Exception {
        Color[] fills = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel panel = new BuildOutputPanel();
            panel.started(MAVEN, "Maven", "compile", OutputStyle.maven(), () -> {});
            panel.appendOutput(MAVEN, "[INFO] Compiling 1 source file", false);
            panel.appendOutput(MAVEN, "[WARNING] /p/App.java:[12,5] [deprecation] foo() has been deprecated", false);
            panel.appendOutput(MAVEN, "[ERROR] /p/App.java:[5,18] illegal start of expression", false);
            panel.appendOutput(MAVEN, "[INFO] BUILD SUCCESS", false);
            panel.appendOutput(MAVEN, "[INFO] BUILD FAILURE", false);
            Stage stage = new Stage();
            try {
                Scene scene = new Scene(new StackPane(panel), 900, 300);
                for (String sheet : List.of("app.css", "syntax.css", "editor-themes/" + theme + ".css")) {
                    scene.getStylesheets()
                            .add(getClass()
                                    .getResource("/com/editora/styles/" + sheet)
                                    .toExternalForm());
                }
                stage.setScene(scene);
                stage.show();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                CodeArea out = FxTestSupport.field(
                        (BuildToolPanel) panel.getTabs().get(0).getContent(), "output");
                Color plain = fill(out, ".text");
                return new Color[] {
                    fill(out, ".log-error"),
                    fill(out, ".log-warn"),
                    fill(out, ".maven-build-success"),
                    fill(out, ".maven-build-failure"),
                    plain
                };
            } finally {
                stage.hide();
            }
        });
        assertTrue(fills[0].getRed() > fills[0].getGreen() * 1.5, theme + ": [ERROR] renders red: " + fills[0]);
        assertTrue(fills[1].getRed() > fills[1].getBlue() * 1.5, theme + ": [WARNING] renders amber: " + fills[1]);
        assertTrue(fills[2].getGreen() > fills[2].getRed() * 1.2, theme + ": BUILD SUCCESS renders green: " + fills[2]);
        assertTrue(fills[3].getRed() > fills[3].getGreen() * 1.5, theme + ": BUILD FAILURE renders red: " + fills[3]);
        assertTrue(
                !fills[0].equals(fills[4]) && !fills[2].equals(fills[4]),
                theme + ": the coloured lines differ from the theme's default ink " + fills[4]);
    }

    private static Color fill(CodeArea out, String selector) {
        Text text = out.lookupAll(selector).stream()
                .filter(Text.class::isInstance)
                .map(Text.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Text node matches " + selector));
        return (Color) text.getFill();
    }
}

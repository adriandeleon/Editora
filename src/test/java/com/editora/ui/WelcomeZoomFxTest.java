package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Labeled;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.RecentFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #540: text zoom must scale the Welcome page. Its {@code .welcome-*} font sizes are
 * {@code em}-relative, so {@code setFontScale(zoom)} setting the root font size scales the whole tree — this
 * verifies the title actually grows (≈2×) when the zoom doubles.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WelcomeZoomFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void doublingTheZoomRoughlyDoublesTheWelcomeTitle(@TempDir Path configDir) throws Exception {
        double[] sizes = FxTestSupport.callOnFx(() -> {
            WelcomePane pane = new WelcomePane(
                    new CommandRegistry(),
                    new KeymapManager(),
                    new RecentFiles(configDir),
                    p -> {},
                    u -> {},
                    () -> true,
                    () -> true,
                    List::of,
                    r -> {},
                    "");
            Scene scene = new Scene(pane, 900, 700);
            scene.getStylesheets()
                    .add(WelcomePane.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());

            pane.setFontScale(1.0);
            pane.applyCss();
            pane.layout();
            double s1 = ((Labeled) pane.lookup(".welcome-title")).getFont().getSize();

            pane.setFontScale(2.0);
            pane.applyCss();
            pane.layout();
            double s2 = ((Labeled) pane.lookup(".welcome-title")).getFont().getSize();
            return new double[] {s1, s2};
        });

        assertTrue(sizes[0] > 0, "welcome title has a font size at 100% zoom");
        assertTrue(
                sizes[1] > sizes[0] * 1.8,
                "welcome title roughly doubles at 200% zoom: " + sizes[0] + " -> " + sizes[1]);
    }
}

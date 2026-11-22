package com.editora.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screenshots are written at twice the scene's size (DOCS-18, Pauta 6.2.0): the shared helper every {@code shot()}
 * uses, and the harness the website's screenshots come from, run on its sample script.
 */
@Tag("fx")
class ShotsFxTest {

    @TempDir
    Path dir;

    @Test
    void aShotIsTwiceTheSceneSizeAndDrawsAtThatScale() throws Exception {
        FxTestSupport.bootToolkit();
        Stage stage = FxTestSupport.callOnFx(() -> {
            Rectangle red = new Rectangle(10, 10, 20, 20);
            red.setFill(Color.RED);
            Pane pane = new Pane(red);
            // Its own white ground: a theme another test set as the user-agent stylesheet paints .root otherwise.
            pane.setStyle("-fx-background-color: white;");
            Stage s = new Stage();
            s.setScene(new Scene(pane, 320, 200, Color.WHITE));
            s.show();
            return s;
        });
        try {
            Path file = dir.resolve("helper.png");
            BufferedImage written = FxTestSupport.writeShot(stage.getScene(), file);
            BufferedImage read = javax.imageio.ImageIO.read(file.toFile());

            assertEquals(640, written.getWidth());
            assertEquals(400, written.getHeight());
            assertEquals(640, read.getWidth(), "the PNG on disk");
            assertEquals(400, read.getHeight(), "the PNG on disk");
            // The square at (10, 10) 20 wide covers (20, 20)–(60, 60) at 2x: drawn at the scale, not cropped from 1x.
            assertEquals(0xFFFF0000, read.getRGB(58, 58), "inside the square at 2x");
            assertEquals(0xFFFFFFFF, read.getRGB(62, 62), "past the square at 2x");
            assertEquals(2.0, FxTestSupport.callOnFx(() -> stage.getRenderScaleX()), "canvases rasterise at 2x too");
        } finally {
            FxTestSupport.runOnFx(stage::hide);
        }
    }

    /** Behind {@code -Deditora.test.shots=<dir>}: the sample script drives the real window and writes a 2x PNG. */
    @Test
    void theSampleScriptWritesATwiceSizedPngOfTheWindow() throws Exception {
        String shots = System.getProperty("editora.test.shots");
        Assumptions.assumeTrue(shots != null, "set -Deditora.test.shots=<dir> to run the screenshot harness");
        Path out = Path.of(shots);
        Path script = Path.of("scripts/screenshots/sample.txt");
        Shots harness = new Shots(dir.resolve("config"));
        int failures;
        try {
            failures = harness.run(
                    Files.readAllLines(script),
                    Map.of("workspace", Path.of("").toAbsolutePath().toString(), "out", out.toString(), "suffix", ""));
        } finally {
            FxTestSupport.runOnFx(
                    () -> javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(Themes.DEFAULT)));
        }

        assertEquals(0, failures, "every command of the sample ran");
        List<Shots.Written> written = harness.written;
        assertEquals(1, written.size());
        Shots.Written shot = written.getFirst();
        assertEquals(out.resolve("sample.png"), shot.file());
        BufferedImage png = javax.imageio.ImageIO.read(shot.file().toFile());
        assertTrue(shot.sceneWidth() > 800, "the 900-wide window's scene: " + shot.sceneWidth());
        assertEquals((int) (shot.sceneWidth() * 2), png.getWidth());
        assertEquals((int) (shot.sceneHeight() * 2), png.getHeight());
    }
}

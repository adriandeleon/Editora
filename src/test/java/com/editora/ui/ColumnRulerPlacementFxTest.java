package com.editora.ui;

import java.nio.file.Path;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where the column ruler lands. It must mark column 80 of the editor font whatever text happens to be on
 * screen: it used to sit ~6 columns left on every freshly opened file (measured before the font was
 * applied), ~10–20 columns right when the visible lines were short, and off-screen — hidden — when the
 * longest visible line started with a tab or was CJK text.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ColumnRulerPlacementFxTest {

    /** 80 digits, then a bar: the bar is the 81st character, so its left edge is the 80-column boundary. */
    private static final String EIGHTY = "0123456789".repeat(8) + "|<- the bar starts at column 80\n";

    private EditingFx e;

    @BeforeAll
    void setUp() throws Exception {
        e = EditingFx.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (e != null) {
            e.dispose();
        }
    }

    private double rulerX(EditorBuffer b) throws Exception {
        e.pulses(30);
        Line ruler = FxTestSupport.field(b, "columnRuler");
        return FxTestSupport.callOnFx(() -> ruler.isVisible() ? ruler.getStartX() : -1);
    }

    /** Root-local left edge of the character at {@code column} of {@code line}. */
    private double charX(EditorBuffer b, int line, int column) throws Exception {
        Region root = FxTestSupport.field(b, "root");
        return FxTestSupport.callOnFx(() -> {
            CodeArea a = b.getArea();
            int abs = a.getAbsolutePosition(line, column);
            Bounds on = a.getCharacterBoundsOnScreen(abs, abs + 1).orElseThrow();
            return root.screenToLocal(on).getMinX();
        });
    }

    private void shot(String name) throws Exception {
        String dir = System.getProperty("editora.test.shots");
        if (dir == null) {
            return; // screenshots are for a human looking at the result; off in a normal run
        }
        e.pulses(20);
        WritableImage img = FxTestSupport.callOnFx(() -> e.stage().getScene().snapshot(null));
        javax.imageio.ImageIO.write(
                SwingFXUtils.fromFXImage(img, null),
                "png",
                Path.of(dir, name + ".png").toFile());
    }

    @Test
    void aFreshlyOpenedFileHasTheRulerOnColumn80() throws Exception {
        for (int i = 1; i <= 3; i++) { // every open, not only the first one in the window
            EditorBuffer b = e.open("long" + i + ".txt", EIGHTY + "short\n");
            assertEquals(charX(b, 0, 80), rulerX(b), 1.5, "file #" + i + ": ruler vs. the 81st character");
        }
        shot("ruler-fresh");
    }

    @Test
    void shortVisibleLinesDoNotMoveTheRuler() throws Exception {
        EditorBuffer b = e.open("notes.txt", "ab\ncd\nef\n");
        double before = rulerX(b);
        FxTestSupport.runOnFx(() -> b.getArea().appendText(EIGHTY));
        e.pulses(10);
        assertEquals(charX(b, 3, 80), before, 1.5, "placed correctly while only short lines were visible");
        assertEquals(charX(b, 3, 80), rulerX(b), 1.5);
        shot("ruler-short-lines");
    }

    @Test
    void aTabIndentedFileStillShowsTheRulerOnColumn80() throws Exception {
        String go = "package main\n\nfunc main() {\n\tif x {\n"
                + "\t\tfmt.Println(\"a fairly ordinary tab-indented line of Go code here\")\n\t}\n}\n";
        EditorBuffer b = e.open("main.go", go + EIGHTY);
        // Scroll nothing: the longest visible line starts with two tabs, which used to hide the ruler.
        assertEquals(charX(b, 7, 80), rulerX(b), 1.5);
        shot("ruler-tabs");
    }

    @Test
    void wideGlyphsDoNotMoveTheRuler() throws Exception {
        EditorBuffer b = e.open("cjk.txt", "日本語のテキストがここにあります。これは長い行です。\n" + EIGHTY);
        assertEquals(charX(b, 1, 80), rulerX(b), 1.5);
        shot("ruler-cjk");
    }
}

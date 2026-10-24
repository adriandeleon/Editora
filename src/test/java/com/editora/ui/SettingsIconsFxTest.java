package com.editora.ui;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Settings sidebar glyphs parse and render (JavaFX's {@link SVGPath} drops a malformed path silently,
 * leaving an invisible icon — see {@link IconsFxTest}), and each sits in the same 16 px box, so the
 * category names beside them line up.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettingsIconsFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void everySidebarGlyphRendersANonEmptyShapeInASixteenPixelBox() throws Exception {
        List<Method> glyphs = new ArrayList<>();
        for (Method m : SettingsIcons.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && !Modifier.isPrivate(m.getModifiers())
                    && m.getParameterCount() == 0
                    && m.getReturnType() == Node.class) {
                glyphs.add(m);
            }
        }
        assertEquals(36, glyphs.size(), "one glyph per Settings category");
        for (Method m : glyphs) {
            FxTestSupport.runOnFx(() -> {
                Node box;
                try {
                    box = (Node) m.invoke(null);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                Region region = (Region) box;
                region.applyCss();
                for (double size : new double[] {
                    region.minWidth(-1), region.prefWidth(-1), region.maxWidth(-1),
                    region.minHeight(-1), region.prefHeight(-1), region.maxHeight(-1)
                }) {
                    assertEquals(16, size, 0.01, m.getName() + " is not a fixed 16 px box");
                }
                SVGPath svg = (SVGPath) box.lookup("SVGPath");
                Bounds b = svg.getBoundsInLocal();
                assertTrue(
                        b.getWidth() > 0 && b.getHeight() > 0,
                        m.getName() + " glyph parsed to an empty shape (" + b.getWidth() + "x" + b.getHeight() + ")");
            });
        }
    }
}

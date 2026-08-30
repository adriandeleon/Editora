package com.editora.ui;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's right-click menu and the toolbar / main menu bar must draw the same action with the same
 * glyph.
 *
 * <p>The editor package cannot depend on {@code ui}, so {@code editor/MenuIcons} is a hand-copied mirror of
 * {@code ui/Icons} — and a mirror drifts. It did: the UI Kit migration moved Undo, Redo, Cut, Copy, Paste,
 * Bookmark, Personal Note, Find, Info and Debug in {@code Icons} to the stroked line family while the
 * {@code MenuIcons} copies stayed filled Material, so Edit &rarr; Undo and right-click &rarr; Undo showed two
 * different icons for one command. Nothing failed — each half was internally consistent — which is exactly
 * why this is asserted rather than reviewed.
 *
 * <p>Asserted as an agreement between the two classes (same path data, same style class), never against a
 * named glyph, so re-drawing an icon in {@code Icons} keeps passing and only a divergence fails.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MenuIconFamilyFxTest {

    /** {@code MenuIcons} method &rarr; the {@code Icons} method that draws the same action elsewhere. */
    private static final Map<String, String> TWINS = Map.of(
            "undo", "undo",
            "redo", "redo",
            "cut", "cut",
            "copy", "copy",
            "paste", "paste",
            "bookmark", "bookmark",
            "note", "notes",
            "find", "find",
            "about", "about",
            "debug", "debug");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void everySharedActionUsesTheSameGlyphInBothIconSets() throws Exception {
        for (Map.Entry<String, String> e : TWINS.entrySet()) {
            SVGPath menu = pathOf(menuIcon(e.getKey()));
            SVGPath icons = pathOf(icon(e.getValue()));
            assertEquals(
                    icons.getContent(),
                    menu.getContent(),
                    "MenuIcons." + e.getKey() + " draws a different shape than Icons." + e.getValue()
                            + ", so the same action looks different in the editor's context menu than on the"
                            + " toolbar / menu bar");
            assertEquals(
                    icons.getStyleClass(),
                    menu.getStyleClass(),
                    "MenuIcons." + e.getKey() + " is in a different icon family than Icons." + e.getValue()
                            + " (filled 'toolbar-icon' vs stroked 'icon-line')");
        }
    }

    /**
     * A line glyph must never also carry {@code toolbar-icon}: that class is the fill-based colouring
     * convention and any {@code X .toolbar-icon} rule outranks a bare {@code .icon-line}, so the outline
     * would be painted solid — a silent regression the shape assertion above cannot see.
     */
    @Test
    void aLineGlyphIsNeverAlsoTaggedAsAFilledOne() throws Exception {
        for (String name : TWINS.keySet()) {
            List<String> classes = pathOf(menuIcon(name)).getStyleClass();
            if (classes.contains("icon-line")) {
                assertTrue(
                        !classes.contains("toolbar-icon"),
                        "MenuIcons." + name + " wears both icon classes, so a fill rule would fill the outline");
            }
        }
    }

    /**
     * A stroked glyph whose path fails to parse renders as nothing at all (JavaFX's SVGPath parser fails
     * silently), and unlike a filled one there is no black blob to notice. Each converted glyph is measured.
     */
    @Test
    void everyConvertedGlyphRendersANonEmptyShape() throws Exception {
        for (String name : TWINS.keySet()) {
            var b = menuIcon(name).getLayoutBounds();
            assertTrue(
                    b.getWidth() > 0 && b.getHeight() > 0 && Double.isFinite(b.getWidth()),
                    "MenuIcons." + name + " renders an empty shape — its path did not parse");
        }
    }

    // ---- reflection: both classes are package-private in packages this test is not in ----------------

    private static Node menuIcon(String method) throws Exception {
        return call("com.editora.editor.MenuIcons", method);
    }

    private static Node icon(String method) throws Exception {
        return call("com.editora.ui.Icons", method);
    }

    private static Node call(String className, String method) throws Exception {
        Method m = Class.forName(className).getDeclaredMethod(method);
        m.setAccessible(true);
        Node n = (Node) m.invoke(null);
        assertNotNull(n, className + "." + method + " returned null");
        return n;
    }

    /** Both factories wrap the scaled {@link SVGPath} in a {@link Group}. */
    private static SVGPath pathOf(Node icon) {
        assertTrue(icon instanceof Group, "expected a Group-wrapped icon, got " + icon.getClass());
        Node inner = ((Group) icon).getChildren().get(0);
        assertTrue(inner instanceof SVGPath, "expected an SVGPath inside the icon group");
        return (SVGPath) inner;
    }
}

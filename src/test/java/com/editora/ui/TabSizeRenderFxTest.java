package com.editora.ui;

import javafx.geometry.Bounds;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tab size reaches the text: a tab character is drawn as wide as the setting says. It used to change the
 * status bar, the minimap and copy-as-HTML only — every tab on screen was JavaFX's default 8 columns.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TabSizeRenderFxTest {

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

    /** How many columns the leading tab of line 1 occupies: where the 'x' after it starts, in 'x' widths. */
    private double tabColumns(EditorBuffer b) throws Exception {
        e.pulses(15);
        return FxTestSupport.callOnFx(() -> {
            CodeArea a = b.getArea();
            Bounds tab = a.getCharacterBoundsOnScreen(0, 1).orElseThrow();
            Bounds x1 = a.getCharacterBoundsOnScreen(1, 2).orElseThrow();
            Bounds x2 = a.getCharacterBoundsOnScreen(2, 3).orElseThrow();
            return (x1.getMinX() - tab.getMinX()) / (x2.getMinX() - x1.getMinX());
        });
    }

    @Test
    void aTabIsDrawnAsWideAsTheTabSize() throws Exception {
        EditorBuffer b = e.open("tabs.txt", "\txx\n\t\tyy\n");
        assertEquals(4, FxTestSupport.callOnFx(b::getTabSize), "the default tab size");
        assertEquals(4.0, tabColumns(b), 0.05, "a tab is four columns at the default tab size");

        FxTestSupport.runOnFx(() -> b.setTabSize(2));
        assertEquals(2.0, tabColumns(b), 0.05, "and follows a change of the setting");

        FxTestSupport.runOnFx(() -> b.setTabSize(8));
        assertEquals(8.0, tabColumns(b), 0.05);
    }
}

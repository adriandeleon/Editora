package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several rulers, per-language rulers and indent guides on a real, laid-out editor: each ruler on its column,
 * the language's own list used for its files, {@code .editorconfig} replacing the first, and a guide at each
 * indentation level of the visible lines, through blank lines.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RulersAndIndentGuidesFxTest {

    /** 130 digits: long enough for rulers at 80 and 120 to sit over real characters. */
    private static final String WIDE = "0123456789".repeat(13) + "\n";

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

    @Test
    void eachRulerStandsOnItsColumn() throws Exception {
        EditorBuffer b = e.open("wide.txt", WIDE + WIDE);
        FxTestSupport.runOnFx(() -> {
            b.setWordWrap(false);
            b.setColumnRulerVisible(true);
            b.setRulerColumns(List.of(20, 40, 1000), Map.of());
        });
        List<Double> xs = rulers(b);
        assertEquals(2, xs.size(), "two rulers; column 1000 is past the window: " + xs);
        assertEquals(charX(b, 0, 20), xs.get(0), 1.5);
        assertEquals(charX(b, 0, 40), xs.get(1), 1.5);

        FxTestSupport.runOnFx(() -> b.setRulerColumn(30)); // .editorconfig max_line_length
        List<Double> overridden = rulers(b);
        assertEquals(2, overridden.size());
        assertEquals(charX(b, 0, 30), overridden.get(0), 1.5, "max_line_length replaces the first");
        assertEquals(charX(b, 0, 40), overridden.get(1), 1.5);

        FxTestSupport.runOnFx(() -> b.setRulerColumn(com.editora.editorconfig.EditorConfigProperties.OFF));
        assertTrue(rulers(b).isEmpty(), "max_line_length = off hides them all");
    }

    @Test
    void aLanguagesOwnRulersAreUsedForItsFiles() throws Exception {
        EditorBuffer py = e.open("script.py", WIDE);
        EditorBuffer txt = e.open("notes.txt", WIDE);
        String language = FxTestSupport.callOnFx(py::getLanguage);
        for (EditorBuffer b : List.of(py, txt)) {
            FxTestSupport.runOnFx(() -> {
                b.setWordWrap(false);
                b.setColumnRulerVisible(true);
                b.setRulerColumns(List.of(50), Map.of(language, List.of(10, 25)));
            });
        }
        e.select(py.getPath());
        List<Double> pyRulers = rulers(py);
        assertEquals(2, pyRulers.size(), language + " has its own two: " + pyRulers);
        assertEquals(charX(py, 0, 10), pyRulers.get(0), 1.5);
        e.select(txt.getPath());
        List<Double> txtRulers = rulers(txt);
        assertEquals(1, txtRulers.size(), "plain text keeps the global one");
        assertEquals(charX(txt, 0, 50), txtRulers.get(0), 1.5);
    }

    @Test
    void indentGuidesStandAtEachLevelAndRunThroughBlankLines() throws Exception {
        String code = "def f():\n    if x:\n        y = 1\n\n    z = 2\nw = 3\n";
        EditorBuffer b = e.open("guides.py", code);
        List<double[]> painted = new ArrayList<>();
        setGuidesForTest(painted);
        try {
            FxTestSupport.runOnFx(() -> {
                b.setIndentGuidesVisible(true); // already on (the default) …
                b.setTabSize(b.getTabSize()); // … so ask for a frame to record
            });
            e.pulses(30);
            List<double[]> last = lastFrame(painted);
            TreeSet<Long> columns = new TreeSet<>();
            for (double[] guide : last) {
                columns.add(Math.round(guide[0]));
            }
            assertEquals(2, columns.size(), "guides at two levels: " + columns);
            assertEquals(charX(b, 1, 0), columns.first(), 1.5, "the first at column 0");
            assertEquals(charX(b, 2, 4), columns.last(), 1.5, "the second at column 4");
            // Lines 1-4 have a column-0 guide (the blank line 3 included); line 2 also has the column-4 one.
            assertEquals(5, last.size(), "one guide per level per indented line");
            assertTrue(lineTop(b, 3) >= minTop(last) && lineTop(b, 3) <= maxBottom(last), "the blank line has one");

            FxTestSupport.runOnFx(() -> b.setIndentGuidesVisible(false));
            Region overlay = FxTestSupport.field(b, "indentGuides");
            assertFalse(FxTestSupport.callOnFx(overlay::isVisible));
        } finally {
            setGuidesForTest(null);
        }
    }

    @Test
    void theSettingsTurnIndentGuidesOnForNewBuffers() throws Exception {
        EditorBuffer b = e.open("on.py", "if x:\n    y\n");
        e.pulses(10);
        Region overlay = FxTestSupport.field(b, "indentGuides");
        assertTrue(overlay != null && FxTestSupport.callOnFx(overlay::isVisible), "on by default");
    }

    // --- helpers ---

    /** The x of each ruler line shown, after the deferred measure. */
    private List<Double> rulers(EditorBuffer b) throws Exception {
        e.pulses(30);
        Group group = FxTestSupport.field(b, "columnRuler");
        return FxTestSupport.callOnFx(() -> {
            List<Double> out = new ArrayList<>();
            if (!group.isVisible()) {
                return out;
            }
            for (Node n : group.getChildren()) {
                if (n instanceof Line line && line.isVisible()) {
                    out.add(line.getStartX());
                }
            }
            return out;
        });
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

    private double lineTop(EditorBuffer b, int line) throws Exception {
        Region overlay = FxTestSupport.field(b, "indentGuides");
        return FxTestSupport.callOnFx(() -> overlay.screenToLocal(
                        b.getArea().getParagraphBoundsOnScreen(line).orElseThrow())
                .getCenterY());
    }

    /** The guides of the last redraw: those after the last one that starts a frame again at a smaller y. */
    private static List<double[]> lastFrame(List<double[]> painted) {
        int start = 0;
        for (int i = 1; i < painted.size(); i++) {
            if (painted.get(i)[1] < painted.get(i - 1)[1]) {
                start = i;
            }
        }
        return painted.subList(start, painted.size());
    }

    private static double minTop(List<double[]> guides) {
        return guides.stream().mapToDouble(g -> g[1]).min().orElse(0);
    }

    private static double maxBottom(List<double[]> guides) {
        return guides.stream().mapToDouble(g -> g[2]).max().orElse(0);
    }

    private static void setGuidesForTest(List<double[]> list) throws Exception {
        var field = Class.forName("com.editora.editor.IndentGuideOverlay").getDeclaredField("guidesForTest");
        field.setAccessible(true);
        field.set(null, list);
    }
}

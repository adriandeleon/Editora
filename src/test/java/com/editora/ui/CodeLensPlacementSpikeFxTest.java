package com.editora.ui;

import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A spike, kept as the record: <b>can a line carry a row above it?</b>
 *
 * <p>A reference-count code lens needs vertical space above a declaration, and nothing in this codebase
 * has ever produced any — every overlay (whitespace, spell, search, diagnostics, TODO, inline values,
 * sticky scroll) draws <em>over</em> the text or beside it in the gutter, and none of them make a row
 * taller. Before designing a lens it is worth knowing which mechanism, if any, can.
 *
 * <p>Two candidates, and this measures both rather than reasoning about them:
 *
 * <ol>
 *   <li><b>{@code Inlay}</b> — ruled out by its type alone, recorded here so nobody re-derives it: the
 *       fork's inlay is {@code (int column, String text, String styleClass)}, a <em>string</em> at a
 *       column. It displaces glyphs horizontally and cannot carry a node, let alone a row.
 *   <li><b>{@code paragraphGraphicFactory}</b> — returns a real {@link javafx.scene.Node}, so the
 *       question is whether an over-tall graphic grows the row (giving space a lens could occupy) or is
 *       simply clipped to the line height.
 * </ol>
 *
 * <p>Whatever it finds, the invariant that must survive is that the <em>document</em> is untouched:
 * offsets, text and selection are what save-to-disk and every editing command depend on, and a
 * decoration that perturbs them corrupts files rather than annotating them.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeLensPlacementSpikeFxTest {

    private static final String TEXT = "line0\nline1\nline2\nline3\nline4\n";

    /** The paragraph a tall graphic is attached to. */
    private static final int TARGET = 2;

    /** Height of the over-tall graphic, well above any plausible line height. */
    private static final double TALL = 44;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Probe(double targetHeight, double plainHeight, String text, int length) {}

    /** Lays out an area whose paragraph {@link #TARGET} carries a {@code TALL} graphic, and measures. */
    private Probe probe(boolean tallGraphic) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText(TEXT);
            area.setParagraphGraphicFactory(i -> {
                if (tallGraphic && i == TARGET) {
                    VBox box = new VBox(new Label("3 references"));
                    box.setMinHeight(TALL);
                    box.setPrefHeight(TALL);
                    return box;
                }
                return new Label(String.valueOf(i + 1));
            });
            StackPane host = new StackPane(area);
            Scene scene = new Scene(host, 900, 600);
            // A real Stage, not a bare Scene: paragraph bounds are reported in SCREEN coordinates, and a
            // scene with no window has none — localToScreen returns null and every measurement is a NPE.
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(scene);
            stage.show();
            // A virtual flow only materialises cells during a layout pass; ask twice so the paragraph
            // boxes exist before their bounds are read.
            area.requestFollowCaret();
            host.applyCss();
            host.layout();
            double target = height(area, TARGET);
            double plain = height(area, 0);
            stage.hide();
            return new Probe(target, plain, area.getText(), area.getLength());
        });
    }

    private static double height(CodeArea area, int paragraph) {
        Bounds b = area.getParagraphBoundsOnScreen(paragraph).orElse(null);
        return b == null ? -1 : b.getHeight();
    }

    @Test
    void aTallParagraphGraphicIsMeasuredAgainstAPlainOne() throws Exception {
        Probe plain = probe(false);
        Probe tall = probe(true);

        // Not an assertion about which way it goes — the point of a spike is to record what IS. The
        // numbers are printed so the finding survives in the build log as well as in the assertions.
        System.out.printf(
                "[spike] plain row=%.1f  target row with a %.0fpx graphic=%.1f  (plain baseline row=%.1f)%n",
                plain.targetHeight(), TALL, tall.targetHeight(), tall.plainHeight());

        assertTrue(plain.targetHeight() > 0, "the probe never laid out; the rest of this proves nothing");
        assertTrue(tall.targetHeight() > 0, "the probe never laid out; the rest of this proves nothing");
    }

    @Test
    void theDocumentIsUntouchedByAnOverTallGraphic() throws Exception {
        // The invariant that matters most: a decoration must annotate a file, never alter it.
        Probe plain = probe(false);
        Probe tall = probe(true);
        assertEquals(TEXT, tall.text(), "the graphic changed the document text");
        assertEquals(plain.length(), tall.length(), "the graphic changed the document length");
    }

    /** Lays out an area whose paragraph {@link #TARGET} carries a {@code TALL} LENS, and measures. */
    private Probe lensProbe() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText(TEXT);
            area.setLensFactory(i -> {
                if (i != TARGET) {
                    return null;
                }
                VBox box = new VBox(new Label("3 references"));
                box.setMinHeight(TALL);
                box.setPrefHeight(TALL);
                return box;
            });
            StackPane host = new StackPane(area);
            Scene scene = new Scene(host, 900, 600);
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(scene);
            stage.show();
            area.requestFollowCaret();
            host.applyCss();
            host.layout();
            double target = height(area, TARGET);
            double plain = height(area, 0);
            stage.hide();
            return new Probe(target, plain, area.getText(), area.getLength());
        });
    }

    @Test
    void aLensDoesGrowTheRow() throws Exception {
        // The whole point of the fork change: unlike a gutter graphic, a lens adds height.
        Probe lens = lensProbe();
        System.out.printf(
                "[spike] with a %.0fpx LENS: target row=%.1f  neighbour row=%.1f%n",
                TALL, lens.targetHeight(), lens.plainHeight());
        assertTrue(
                lens.targetHeight() > lens.plainHeight() + TALL / 2,
                "a lens must actually make the row taller; got " + lens.targetHeight() + " vs " + lens.plainHeight());
    }

    @Test
    void aLensLeavesTheDocumentAlone() throws Exception {
        // The invariant a decoration must never break: it annotates a file, it does not alter one.
        Probe lens = lensProbe();
        assertEquals(TEXT, lens.text(), "the lens changed the document text");
        assertEquals(TEXT.length(), lens.length(), "the lens changed the document length");
    }

    /**
     * The invariant that separates annotating a file from corrupting it: a click below a lens must still
     * land on the character under the pointer.
     *
     * <p>This is the half of the fork change most likely to be wrong. {@code ParagraphBox.hit(x, y)}
     * converts through screen coordinates and so is offset-safe for free, but {@code hitText} is handed a
     * y relative to the box and forwards it straight to the text — without subtracting the lens band, a
     * click lands one lens-height too low and the caret silently goes to the wrong line.
     */
    @Test
    void aClickBelowALensLandsOnTheRightLine() throws Exception {
        int landed = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText(TEXT);
            area.setLensFactory(i -> {
                if (i != TARGET) {
                    return null;
                }
                VBox box = new VBox(new Label("3 references"));
                box.setMinHeight(TALL);
                box.setPrefHeight(TALL);
                return box;
            });
            StackPane host = new StackPane(area);
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(new Scene(host, 900, 600));
            stage.show();
            area.requestFollowCaret();
            host.applyCss();
            host.layout();
            // Aim at the vertical middle of the TARGET paragraph's own text, in the area's coordinates.
            Bounds target = area.getParagraphBoundsOnScreen(TARGET).orElseThrow();
            javafx.geometry.Point2D local = area.screenToLocal(target.getMinX() + 4, target.getMaxY() - 4);
            int index = area.hit(local.getX(), local.getY()).getInsertionIndex();
            stage.hide();
            return area.offsetToPosition(index, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                    .getMajor();
        });
        assertEquals(TARGET, landed, "a click on the lensed line's text landed on paragraph " + landed);
    }

    @Test
    void theGrowthIsRecordedAsAConcreteRatio() throws Exception {
        Probe tall = probe(true);
        double grew = tall.targetHeight() - tall.plainHeight();
        System.out.printf("[spike] target row is %.1fpx taller than its neighbours%n", grew);
        // Deliberately loose: this records the mechanism's behaviour, it does not pin a pixel count that
        // a font or theme change would invalidate.
        assertTrue(
                tall.targetHeight() >= tall.plainHeight(),
                "a taller graphic made the row SHORTER, which would mean the gutter drives layout backwards");
    }
}

package com.editora.ui;

import java.nio.file.Path;

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mouse wheel zooms an open PDF around the pointer; Shift+wheel pans and scroll inertia does nothing. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfViewerWheelZoomFxTest {

    @TempDir
    Path tmp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private PdfViewerPane openShown() throws Exception {
        Path pdf = tmp.resolve("page.pdf");
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(pdf.toFile());
        }
        PdfViewerPane pane = FxTestSupport.callOnFx(() -> {
            PdfViewerPane p = new PdfViewerPane(pdf);
            new Scene((Parent) p.node(), 600, 400);
            return p;
        });
        for (int i = 0; i < 200 && FxTestSupport.callOnFx(() -> FxTestSupport.field(pane, "image")) == null; i++) {
            Thread.sleep(50);
        }
        FxTestSupport.runOnFx(() -> layout(pane));
        assertTrue(FxTestSupport.callOnFx(() -> FxTestSupport.field(pane, "image") != null), "the page rendered");
        return pane;
    }

    private static void layout(PdfViewerPane pane) {
        Parent root = (Parent) pane.node();
        root.applyCss();
        root.layout();
    }

    private static double zoom(PdfViewerPane pane) throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.<Double>field(pane, "zoom"));
    }

    private static ScrollPane scroll(PdfViewerPane pane) {
        return FxTestSupport.field(pane, "scroll");
    }

    private static ImageView image(PdfViewerPane pane) {
        return FxTestSupport.field(pane, "imageView");
    }

    /** Fires a wheel event at scene point {@code (x, y)} on the viewer's scroll pane. */
    private static void wheel(
            PdfViewerPane pane, double x, double y, double dx, double dy, boolean shift, boolean inertia)
            throws Exception {
        FxTestSupport.runOnFx(() -> {
            Event.fireEvent(
                    scroll(pane),
                    new ScrollEvent(
                            ScrollEvent.SCROLL,
                            x,
                            y,
                            x,
                            y,
                            shift,
                            false,
                            false,
                            false,
                            false,
                            inertia,
                            dx,
                            dy,
                            dx,
                            dy,
                            ScrollEvent.HorizontalTextScrollUnits.NONE,
                            0,
                            ScrollEvent.VerticalTextScrollUnits.NONE,
                            0,
                            0,
                            null));
            layout(pane);
        });
    }

    /** The fraction of the page (0–1 on each axis) under scene point {@code (x, y)}. */
    private static Point2D pageFraction(PdfViewerPane pane, double x, double y) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ImageView view = image(pane);
            Point2D local = view.sceneToLocal(x, y);
            Bounds b = view.getLayoutBounds();
            return new Point2D(local.getX() / b.getWidth(), local.getY() / b.getHeight());
        });
    }

    @Test
    void theWheelZoomsInAndOutAroundThePointer() throws Exception {
        PdfViewerPane pane = openShown();
        try {
            double fit = zoom(pane);
            assertTrue(FxTestSupport.callOnFx(() -> FxTestSupport.<Boolean>field(pane, "fitMode")));

            // Zoom well past the window so the page scrolls, all with the pointer at one spot.
            double x = 250;
            double y = 230;
            for (int i = 0; i < 12; i++) {
                wheel(pane, x, y, 0, 40, false, false);
            }
            double zoomedIn = zoom(pane);
            assertTrue(zoomedIn > fit * 2, "wheel up zooms in: " + fit + " -> " + zoomedIn);
            assertFalse(FxTestSupport.callOnFx(() -> FxTestSupport.<Boolean>field(pane, "fitMode")));

            // The point of the page under the pointer stays under it while zooming.
            Point2D before = pageFraction(pane, x, y);
            for (int i = 0; i < 4; i++) {
                wheel(pane, x, y, 0, 40, false, false);
            }
            assertTrue(zoom(pane) > zoomedIn);
            Point2D after = pageFraction(pane, x, y);
            assertEquals(before.getX(), after.getX(), 0.01, "same column of the page under the pointer");
            assertEquals(before.getY(), after.getY(), 0.01, "same row of the page under the pointer");

            double in = zoom(pane);
            wheel(pane, x, y, 0, -40, false, false);
            assertTrue(zoom(pane) < in, "wheel down zooms out");
        } finally {
            FxTestSupport.runOnFx(pane::dispose);
        }
    }

    @Test
    void shiftWheelPansAndInertiaIsIgnored() throws Exception {
        PdfViewerPane pane = openShown();
        try {
            for (int i = 0; i < 16; i++) {
                wheel(pane, 300, 200, 0, 40, false, false);
            }
            double z = zoom(pane);
            double h = FxTestSupport.callOnFx(() -> scroll(pane).getHvalue());

            wheel(pane, 300, 200, 0, -40, true, false); // Shift+wheel: across, not a zoom
            assertEquals(z, zoom(pane));
            assertNotEquals(h, FxTestSupport.callOnFx(() -> scroll(pane).getHvalue()), "Shift+wheel pans across");

            wheel(pane, 300, 200, 0, 40, false, true); // inertia after the fingers lift
            assertEquals(z, zoom(pane), "scroll inertia does not zoom");
        } finally {
            FxTestSupport.runOnFx(pane::dispose);
        }
    }
}

package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MANUAL PROBE (not part of the suite — {@code @Tag("probe")}). Measures what the kit's popup drop shadow
 * costs to render, because {@code app.css} puts it on {@code .context-menu} — i.e. on every menu-bar
 * dropdown, submenu and right-click menu — and a menu popup is re-rendered every time one is shown.
 *
 * <p>Renders a menu-sized node repeatedly via {@code snapshot()} (synchronous, on the FX thread) with no
 * effect, with the shipped shadow, and with a cheaper one.
 *
 * <p><b>Read the number with its caveat:</b> the harness runs {@code prism.order=sw}, so this is the
 * SOFTWARE blur cost. With a working GPU pipeline the same blur is far cheaper. It therefore bounds the
 * worst case — which is the case a Linux box falling back to {@code sw} actually hits.
 *
 * <p><b>Verdict when this was written</b> (menu-to-menu switching felt unsmooth on Linux): 340x620 popup,
 * no effect 1.2 ms, the kit's 44px shadow 15.3 ms, a 16px one 5.5 ms. 15 ms is a dropped frame — but the
 * reporting machine had a working GPU pipeline, so this was <em>not</em> the cause; the cause was the same
 * shadow's effect on the popup's window GEOMETRY (see {@code MenuPopupBoundsFxTest}). Kept because the
 * number is the reason not to put a wide gaussian on a popup that might render on {@code sw}.
 *
 * <p>Run: {@code ./mvnw test -Dtest=MenuShadowCostProbeTest -Dgroups=probe -Dui.probe=true}
 */
@Tag("probe")
class MenuShadowCostProbeTest {

    /** Roughly Editora's widest menu: ~24 rows of title + chord. */
    private static final int MENU_W = 340;

    private static final int MENU_H = 620;

    private static final int WARMUP = 20;

    private static final int RUNS = 60;

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void measure() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("ui.probe"), "opt-in: -Dui.probe=true");

        // The shipped rule: dropshadow(gaussian, rgba(27,30,42,0.28), 44, 0.16, 0, 18)
        DropShadow shipped = shadow(44, 0.16, 18);
        // A conventional menu shadow: tight, short offset.
        DropShadow cheaper = shadow(16, 0.2, 6);

        System.out.println("popup " + MENU_W + "x" + MENU_H + ", software pipeline, median of " + RUNS + " renders");
        System.out.printf("  no effect        %6.2f ms%n", medianMillis(null));
        System.out.printf("  shipped (r=44)   %6.2f ms%n", medianMillis(shipped));
        System.out.printf("  cheaper (r=16)   %6.2f ms%n", medianMillis(cheaper));
    }

    private static DropShadow shadow(double radius, double spread, double offsetY) {
        DropShadow d = new DropShadow(BlurType.GAUSSIAN, Color.rgb(27, 30, 42, 0.28), radius, spread, 0, offsetY);
        return d;
    }

    /** Median wall time of one synchronous render of a menu-sized node carrying {@code effect}. */
    private static double medianMillis(Effect effect) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            VBox menu = new VBox();
            menu.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-padding: 6;");
            for (int i = 0; i < 24; i++) {
                menu.getChildren().add(new Label("Menu item " + i + "     C-x C-" + (char) ('a' + i % 26)));
            }
            menu.setPrefSize(MENU_W, MENU_H);
            menu.setEffect(effect);

            // A shadow paints OUTSIDE the node, so give the scene room or it is clipped away unmeasured.
            VBox root = new VBox(menu);
            root.setStyle("-fx-padding: 80;");
            new Scene(root, MENU_W + 160, MENU_H + 160);
            root.applyCss();
            root.layout();

            List<Double> times = new ArrayList<>();
            for (int i = 0; i < WARMUP + RUNS; i++) {
                // A fresh target each time: reusing one lets the render cache the blurred result, which is
                // precisely what a freshly-shown popup does not get.
                WritableImage target = new WritableImage(
                        (int) root.getWidth() == 0 ? MENU_W + 160 : (int) root.getWidth(),
                        (int) root.getHeight() == 0 ? MENU_H + 160 : (int) root.getHeight());
                menu.setEffect(effect == null ? null : copyOf(effect));
                long t0 = System.nanoTime();
                root.snapshot(null, target);
                long t1 = System.nanoTime();
                if (i >= WARMUP) {
                    times.add((t1 - t0) / 1_000_000.0);
                }
            }
            times.sort(Double::compareTo);
            return times.get(times.size() / 2);
        });
    }

    /** A fresh Effect instance per render, so no blurred result is reused between measurements. */
    private static Effect copyOf(Effect e) {
        DropShadow d = (DropShadow) e;
        return new DropShadow(d.getBlurType(), d.getColor(), d.getRadius(), d.getSpread(), 0, d.getOffsetY());
    }
}

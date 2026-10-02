package com.editora.ui;

import java.util.List;

import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.TraversalDirection;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-party keyboard-focus layer in app.css: every focusable control shows the ring when focus
 * arrives from the keyboard, including the ones whose theme focus state app.css's base rules erase
 * (flat/icon buttons, the transparent toggle classes, combos) and the ones the theme never styled
 * (check boxes, switches, hyperlinks).
 */
@Tag("fx")
class FocusRingFxTest {

    private static final PseudoClass FOCUS_VISIBLE = PseudoClass.getPseudoClass("focus-visible");

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static boolean hasRing(Region region) {
        return region.getBorder() != null
                && !region.getBorder().getStrokes().isEmpty()
                && region.getBorder().getStrokes().get(0).getWidths().getTop() == 2
                && region.getBorder().getStrokes().get(0).getTopStroke() instanceof Color c
                && c.getOpacity() == 1;
    }

    @Test
    void keyboardFocusDrawsTheRingOnEveryControlFamilyWithoutMovingLayout() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Button plain = new Button("Plain");
            Button flat = new Button("Flat");
            flat.getStyleClass().add("flat");
            Button icon = new Button("x");
            icon.getStyleClass().addAll("button-icon", "flat");
            ToggleButton viewToggle = new ToggleButton("View");
            viewToggle.getStyleClass().add("project-view-toggle");
            Button mapButton = new Button("Fit");
            mapButton.getStyleClass().add("project-map-nav-button");
            Button infoTab = new Button("Info");
            infoTab.getStyleClass().add("file-info-tab");
            Button imageButton = new Button("+");
            imageButton.getStyleClass().add("image-viewer-btn");
            Button pdfButton = new Button("+");
            pdfButton.getStyleClass().add("pdf-viewer-btn");
            Button chip = new Button("3");
            chip.getStyleClass().add("test-chip");
            Button stripe = new Button("s");
            stripe.getStyleClass().addAll("tool-stripe-button", "flat");
            Hyperlink link = new Hyperlink("Open");
            link.getStyleClass().add("welcome-action");
            ComboBox<String> combo = new ComboBox<>();
            CheckBox check = new CheckBox("Check");
            SettingSwitch sw = new SettingSwitch();
            List<Region> ringed = List.of(
                    plain,
                    flat,
                    icon,
                    viewToggle,
                    mapButton,
                    infoTab,
                    imageButton,
                    pdfButton,
                    chip,
                    stripe,
                    link,
                    combo);
            VBox root = new VBox(12, new TextField());
            root.getChildren().addAll(ringed);
            root.getChildren().addAll(check, sw);
            Scene scene = new Scene(root, 400, 900);
            scene.getStylesheets()
                    .add(FocusRingFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();
                for (Region r : ringed) {
                    assertFalse(hasRing(r), r.getStyleClass() + " shows a ring without focus");
                }
                double flatWidth = flat.getWidth();
                double flatX = flat.getLayoutX();
                double stripeHeight = stripe.prefHeight(-1);

                // Real keyboard traversal sets :focus-visible; a programmatic/mouse focus does not.
                plain.requestFocus();
                root.applyCss();
                assertFalse(plain.isFocusVisible());
                assertFalse(hasRing(plain), "a click-style focus leaves no ring");
                root.getChildren().get(0).requestFocus();
                assertTrue(root.getChildren().get(0).requestFocusTraversal(TraversalDirection.NEXT));
                assertTrue(plain.isFocusVisible(), "Tab focus is :focus-visible");
                root.applyCss();
                assertTrue(hasRing(plain), "Tab focus draws the ring");

                for (Node n : root.getChildren()) {
                    n.pseudoClassStateChanged(FOCUS_VISIBLE, true);
                }
                root.applyCss();
                root.layout();
                for (Region r : ringed) {
                    assertTrue(hasRing(r), r.getStyleClass() + " has no keyboard-focus ring");
                }
                Region box = (Region) check.lookup(".box");
                Region thumbArea = (Region) sw.lookup(".thumb-area");
                assertNotNull(box);
                assertNotNull(thumbArea);
                assertTrue(hasRing(box), "check box");
                assertTrue(hasRing(thumbArea), "toggle switch");

                assertEquals(flatWidth, flat.getWidth(), 0.01, "the ring must not resize the control");
                assertEquals(flatX, flat.getLayoutX(), 0.01);
                assertEquals(
                        0,
                        flat.getInsets().getLeft() - flat.getPadding().getLeft(),
                        0.01,
                        "outside ring adds no insets");
                assertEquals(stripeHeight, stripe.prefHeight(-1), 0.01, "the inset stripe ring is paid for in padding");
            } finally {
                stage.hide();
            }
        });
    }
}

package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Context-menu typography must not inherit file-, editor-, or toolbar-specific owner styles. */
@Tag("fx")
class ContextMenuFontFxTest {

    private static FxWindowFixture fx;

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            Scene scene = FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
            scene.getRoot().resize(1500, 800);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void menuIgnoresItsOwnersCompleteFontConfiguration() throws Exception {
        Node root = FxTestSupport.callOnFx(() ->
                FxTestSupport.<Stage>field(fx.controller, "stage").getScene().getRoot());

        Font normal = menuFont(root, null);
        Font fromFileStyledOwner = menuFont(
                root,
                "-fx-font-family: 'JetBrains Mono'; -fx-font-size: 27px;"
                        + " -fx-font-style: italic; -fx-font-weight: bold;");

        assertNotNull(normal);
        assertNotNull(fromFileStyledOwner);
        assertEquals(14.0, fromFileStyledOwner.getSize(), 0.01);
        assertEquals(normal.getFamily(), fromFileStyledOwner.getFamily());
        assertEquals(normal.getStyle(), fromFileStyledOwner.getStyle());
    }

    private static Font menuFont(Node owner, String ownerStyle) throws Exception {
        String oldStyle = FxTestSupport.callOnFx(owner::getStyle);
        ContextMenu menu = FxTestSupport.callOnFx(() -> {
            if (ownerStyle != null) {
                owner.setStyle(ownerStyle);
            }
            ContextMenu result = new ContextMenu(new MenuItem("Rename…"));
            result.show(owner, 100, 100);
            return result;
        });
        try {
            return FxTestSupport.callOnFx(() -> {
                Node skin = menu.getSkin().getNode();
                skin.applyCss();
                if (skin instanceof javafx.scene.Parent parent) {
                    parent.layout();
                }
                return skin.lookupAll(".label").stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .filter(label -> "Rename…".equals(label.getText()))
                        .map(Label::getFont)
                        .findFirst()
                        .orElse(null);
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                menu.hide();
                owner.setStyle(oldStyle);
            });
        }
    }
}

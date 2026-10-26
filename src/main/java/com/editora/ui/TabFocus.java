package com.editora.ui;

import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseEvent;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Keyboard focus follows the tab the user chose: a click on a tab's header, or Window ▸ Next Tab, left it on the tab
 * pane or the breadcrumbs, so the first keys went nowhere, the arrows switched tabs, and the bulb was not asked again
 * (eighth review AQ2). Closing the focused tab or splitting it off left it on the toolbar's first button, where a
 * Space opened a new buffer, and a right- or middle-click on a header left it on the tab strip (ninth review AP1,
 * AP2). A tab opened in the background or restored takes no focus.
 */
final class TabFocus {

    private TabFocus() {}

    /**
     * Focuses the active tab's editor after a mouse release in {@code area} that left the focus on its tab pane, and
     * when the focused editor leaves the window (its tab closed, moved to another group) and focus fell elsewhere.
     */
    static void install(EditorArea area, Supplier<@Nullable EditorBuffer> active) {
        area.addEventFilter(
                MouseEvent.MOUSE_RELEASED,
                e -> Platform.runLater(() -> {
                    Scene scene = area.node().getScene();
                    if (scene != null && scene.getFocusOwner() instanceof TabPane) {
                        focus(active.get());
                    }
                }));
        area.node().sceneProperty().addListener((o, was, scene) -> watch(scene, active));
        watch(area.node().getScene(), active);
    }

    private static void watch(@Nullable Scene scene, Supplier<@Nullable EditorBuffer> active) {
        if (scene == null || scene.getProperties().containsKey(TabFocus.class)) {
            return;
        }
        scene.getProperties().put(TabFocus.class, Boolean.TRUE);
        scene.focusOwnerProperty().addListener((o, was, now) -> {
            boolean editorLeft =
                    was != null && was.getScene() == null && was.getStyleClass().contains("editor-area");
            boolean hiddenEditor = now != null && now.getStyleClass().contains("editor-area") && !shown(now);
            if (editorLeft || hiddenEditor) {
                Platform.runLater(() -> {
                    EditorBuffer buffer = active.get();
                    Node owner = scene.getFocusOwner();
                    // the focused editor left: whatever JavaFX chose next (the toolbar's first button, a hidden tab's
                    // editor, which stays in the scene) is no choice of the user's
                    if (buffer != null && owner != buffer.getFocusedArea() && owner != buffer.getArea()) {
                        focus(buffer);
                    }
                });
            }
        });
    }

    /** Whether {@code node} and all its parents are visible: a hidden tab's editor stays in the scene, invisible. */
    private static boolean shown(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** Puts keyboard focus in {@code buffer}'s editor, its last-focused split view. */
    static void focus(@Nullable EditorBuffer buffer) {
        if (buffer != null) {
            buffer.getFocusedArea().requestFocus();
        }
    }

    /** Whether {@code target} is (in) a tab's header, not its close button. */
    static boolean onTabHeader(@Nullable Object target) {
        for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("tab-close-button")) {
                return false;
            }
            if (n.getStyleClass().contains("tab")
                    && n.getParent() != null
                    && n.getParent().getStyleClass().contains("headers-region")) {
                return true;
            }
        }
        return false;
    }
}

package com.editora.ui;

import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
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
        area.node().sceneProperty().addListener((o, was, scene) -> watch(scene, area, active));
        watch(area.node().getScene(), area, active);
    }

    private static void watch(@Nullable Scene scene, EditorArea area, Supplier<@Nullable EditorBuffer> active) {
        if (scene == null || scene.getProperties().containsKey(TabFocus.class)) {
            return;
        }
        scene.getProperties().put(TabFocus.class, Boolean.TRUE);
        boolean[] inArea = {within(scene.getFocusOwner(), area.node())};
        scene.focusOwnerProperty().addListener((o, was, now) -> {
            // what had the focus was in a tab (an editor, Doctor's link, a viewer) and left with its tab: once removed,
            // its parents no longer lead to the area, so where it was is kept as it gets the focus (eleventh review
            // AM1)
            boolean editorLeft = was != null && was.getScene() == null && inArea[0];
            inArea[0] = within(now, area.node());
            boolean hiddenEditor = now != null && now.getStyleClass().contains("editor-area") && !shown(now);
            if (editorLeft || hiddenEditor) {
                Platform.runLater(() -> {
                    EditorBuffer buffer = active.get();
                    Node owner = scene.getFocusOwner();
                    // the focused editor left: whatever JavaFX chose next (the toolbar's first button, a hidden tab's
                    // editor, which stays in the scene) is no choice of the user's
                    if (buffer != null && owner != buffer.getFocusedArea() && owner != buffer.getArea()) {
                        focus(buffer);
                    } else if (buffer == null && editorLeft) {
                        // the Welcome tab, Doctor or a viewer is left: its page, not the toolbar's New button, where
                        // a Space opened a new buffer (tenth review AP1)
                        Tab tab = area.selectedTab();
                        Node page = tab == null ? null : tab.getContent();
                        if (page != null && !within(owner, page)) {
                            focusPage(page);
                        }
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

    /** Puts the focus on a non-editor tab's page: the Welcome page itself, not its first entry (AP1). */
    static void focusPage(Node page) {
        if (page instanceof WelcomePane welcome) {
            welcome.focusPage();
        } else {
            page.requestFocus();
        }
    }

    /** Whether {@code node} is {@code ancestor} or inside it. */
    private static boolean within(@Nullable Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Puts keyboard focus in {@code buffer}'s editor, its last-focused split view. */
    static void focus(@Nullable EditorBuffer buffer) {
        if (buffer != null) {
            buffer.getFocusedArea().requestFocus();
        }
    }
}

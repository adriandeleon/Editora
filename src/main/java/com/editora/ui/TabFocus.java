package com.editora.ui;

import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Keyboard focus follows the tab the user chose: a click on a tab's header, or Window ▸ Next Tab, left it on the tab
 * pane or the breadcrumbs, so the first keys went nowhere, the arrows switched tabs, and the bulb was not asked again
 * (eighth review AQ2). A tab opened in the background or restored takes no focus.
 */
final class TabFocus {

    private TabFocus() {}

    /** Focuses the active tab's editor after a primary click on any tab's header in {@code area}. */
    static void install(EditorArea area, Supplier<@Nullable EditorBuffer> active) {
        area.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && onTabHeader(e.getTarget())) {
                Platform.runLater(() -> focus(active.get()));
            }
        });
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

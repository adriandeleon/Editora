package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Tab;

/**
 * Keeps a restored background tab's editor <b>out of the scene</b> until the tab is first shown.
 *
 * <p>A {@code TabPane} sizes every tab's content, selected or not, so a hidden editor has a full-height
 * viewport. RichTextFX recomputes the caret's on-screen bounds after every document update, which forces a
 * {@code VirtualFlow} layout — and in a sized, scene-attached area that layout builds a screenful of
 * paragraph cells and resolves their CSS. Filling and highlighting a tab nobody is looking at therefore cost
 * the same FX-thread time and heap as showing it (measured on a 30-file session: ~90 ms per background tab
 * on the load and ~55 ms on its first highlight pass, ~540 scene nodes and ~4.5 MB each). Detached, the same
 * area has no size and no scene: the forced layout touches one cell and applies no CSS. The document, its
 * styles, folds, caret and every model-level consumer are exactly as before — only the view waits.
 *
 * <p>The tab's own {@code selected} flag is the trigger, not the window's active tab: in a split layout each
 * group shows its own selected tab, focused or not.
 */
final class DeferredTabContent {

    private static final Object KEY = new Object();

    /** A tab's detached content, and what is waiting for it to be shown. */
    private static final class Pending {
        final Node content;
        final List<Runnable> onShown = new ArrayList<>();
        ChangeListener<Boolean> listener;
        boolean armed;

        Pending(Node content) {
            this.content = content;
        }
    }

    private DeferredTabContent() {}

    /** Marks {@code content} so the tab it is next {@linkplain #install installed} in starts detached. */
    static void defer(Node content) {
        content.getProperties().put(KEY, Boolean.TRUE);
    }

    /**
     * Gives {@code tab} its content: at once, or — for content marked by {@link #defer} — on the tab's first
     * selection after it has been {@linkplain #arm armed}.
     */
    static void install(Tab tab, Node content) {
        if (content.getProperties().remove(KEY) == null) {
            tab.setContent(content);
            return;
        }
        Pending pending = new Pending(content);
        pending.listener = (obs, was, now) -> {
            if (now && pending.armed) {
                show(tab);
            }
        };
        tab.getProperties().put(KEY, pending);
        tab.selectedProperty().addListener(pending.listener);
    }

    /**
     * Lets a deferred tab attach its content when selected, attaching right away if it already is. Until
     * then a selection is ignored: an empty {@code TabPane} selects the first tab added to it, which during a
     * restore is rarely the tab that ends up selected.
     */
    static void arm(Tab tab) {
        if (tab.getProperties().get(KEY) instanceof Pending pending) {
            pending.armed = true;
            if (tab.isSelected()) {
                show(tab);
            }
        }
    }

    /** True while {@code tab}'s content has not been attached yet. */
    static boolean isDeferred(Tab tab) {
        return tab != null && tab.getProperties().get(KEY) instanceof Pending;
    }

    /** Attaches a deferred tab's content now and runs what was waiting for it; a no-op otherwise. */
    static void show(Tab tab) {
        if (!(tab.getProperties().remove(KEY) instanceof Pending pending)) {
            return;
        }
        tab.selectedProperty().removeListener(pending.listener);
        // The buffer may have been re-homed while detached (moved to another window builds a new tab around
        // the same node); stealing it back would blank the tab that owns it now.
        if (pending.content.getParent() == null && tab.getContent() == null) {
            tab.setContent(pending.content);
        }
        for (Runnable action : pending.onShown) {
            action.run();
        }
    }

    /** Runs {@code action} once {@code tab}'s content is attached: now, unless the tab is still deferred. */
    static void whenShown(Tab tab, Runnable action) {
        if (tab != null && tab.getProperties().get(KEY) instanceof Pending pending) {
            pending.onShown.add(action);
        } else {
            action.run();
        }
    }
}

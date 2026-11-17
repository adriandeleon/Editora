package com.editora.ui;

import java.util.List;

import javafx.scene.control.Label;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A small list the user or the data could grow without a limit, shown in a plain pane: the app shows the first
 * {@code max} items and says so ("Showing the first 50 of 73."), so no list makes a node per item past a number it
 * names (PERF-08, Pauta 6.0.1). For lists that are a few items in practice, where a virtualized list would change how
 * the pane looks and works for nothing. Pure but for the label.
 */
final class ListCap {

    private ListCap() {}

    /** The first {@code max} of {@code items}. */
    static <T> List<T> head(List<T> items, int max) {
        return items.size() <= max ? items : items.subList(0, max);
    }

    /** "Showing the first {@code max} of {@code total}." while {@code total} is over {@code max}; null otherwise. */
    static @Nullable String note(int total, int max) {
        return total > max ? tr("list.capped", max, total) : null;
    }

    /** {@link #note} as a muted, wrapping label with {@code styleClass}, or null when nothing was left out. */
    static @Nullable Label label(int total, int max, String styleClass) {
        String text = note(total, max);
        if (text == null) {
            return null;
        }
        Label l = new Label(text);
        l.getStyleClass().addAll("list-capped", styleClass);
        l.setWrapText(true);
        return l;
    }
}

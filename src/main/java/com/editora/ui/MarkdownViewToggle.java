package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorBuffer.MarkdownViewMode;

import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/**
 * The IntelliJ-style floating control overlaid at the top-right of a Markdown editor: three segments —
 * Editor / Editor + Preview / Preview — bound to one {@link EditorBuffer}'s view mode. Selecting a
 * segment switches the buffer's mode; the buffer's {@code onViewModeChanged} keeps the selection in
 * sync (so a command or restored session reflects here too).
 */
public class MarkdownViewToggle extends HBox {

    private final EditorBuffer buffer;
    private final ToggleButton editor = segment(Icons.previewEditor(), tr("markdown.editor"));
    private final ToggleButton split = segment(Icons.previewSplit(), tr("markdown.split"));
    private final ToggleButton preview = segment(Icons.previewOnly(), tr("markdown.preview"));
    private boolean syncing;

    public MarkdownViewToggle(EditorBuffer buffer) {
        this.buffer = buffer;
        getStyleClass().add("md-viewmode-toggle");
        setPickOnBounds(false); // clicks between segments fall through to the editor
        // Critical: an HBox reports an unbounded max size, so a StackPane overlay would stretch it to
        // fill (and its background would cover) the whole editor. Pin it to its preferred size so the
        // StackPane keeps it small and honors the TOP_RIGHT alignment.
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);

        ToggleGroup group = new ToggleGroup();
        editor.setToggleGroup(group);
        split.setToggleGroup(group);
        preview.setToggleGroup(group);
        getChildren().addAll(editor, split, preview);

        editor.setOnAction(e -> apply(MarkdownViewMode.EDITOR));
        split.setOnAction(e -> apply(MarkdownViewMode.SPLIT));
        preview.setOnAction(e -> apply(MarkdownViewMode.PREVIEW));

        sync(); // reflect the buffer's current mode (the controller wires onViewModeChanged → sync)
    }

    private void apply(MarkdownViewMode mode) {
        if (!syncing) {
            buffer.setMarkdownViewMode(mode);
            sync(); // a no-op selection (clicking the active segment) must stay selected
        }
    }

    /** Reflects the buffer's current mode in the segmented control (call after an external mode change). */
    public void sync() {
        syncing = true;
        try {
            switch (buffer.getMarkdownViewMode()) {
                case EDITOR -> editor.setSelected(true);
                case SPLIT -> split.setSelected(true);
                case PREVIEW -> preview.setSelected(true);
            }
        } finally {
            syncing = false;
        }
    }

    private static ToggleButton segment(javafx.scene.Node icon, String tooltip) {
        ToggleButton b = new ToggleButton();
        b.setGraphic(icon);
        b.getStyleClass().addAll("md-viewmode-segment", "flat");
        b.setTooltip(new Tooltip(tooltip));
        b.setFocusTraversable(false);
        return b;
    }
}

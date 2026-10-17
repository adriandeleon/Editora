package com.editora.ui;

import java.util.function.BooleanSupplier;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;

import static com.editora.i18n.Messages.tr;

/**
 * What the editor area shows once its last tab is closed: a quiet, centered list of the ways back in (Open
 * File, Open Folder, Command Palette, Welcome Page), each with its chord in the active keymap. Without it the
 * area was a blank panel and focus fell to the toolbar.
 *
 * <p>The caller stacks {@link #node()} over the editor area and calls {@link #update(boolean)} whenever the
 * tab list changes. Rows are rebuilt on each show, so a keymap or Projects switch made meanwhile is reflected.
 */
final class EditorEmptyState {

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private final BooleanSupplier projectsEnabled;
    private final GridPane rows = new GridPane();
    private final StackPane root = new StackPane(rows);

    EditorEmptyState(CommandRegistry registry, KeymapManager keymap, BooleanSupplier projectsEnabled) {
        this.registry = registry;
        this.keymap = keymap;
        this.projectsEnabled = projectsEnabled;
        root.getStyleClass().add("editor-empty-state");
        root.setFocusTraversable(true); // takes focus from a closed tab, so typed chords still reach the window
        root.setPickOnBounds(false); // only the rows take clicks
        rows.getStyleClass().add("editor-empty-rows");
        rows.setAlignment(Pos.CENTER);
        rows.setMaxSize(GridPane.USE_PREF_SIZE, GridPane.USE_PREF_SIZE);
        setShowing(false);
    }

    Node node() {
        return root;
    }

    boolean isShowing() {
        return root.isVisible();
    }

    /** Shows the hint when the editor area is empty, hides it otherwise. Focuses it when it appears. */
    void update(boolean editorAreaEmpty) {
        boolean was = isShowing();
        if (editorAreaEmpty && !was) {
            rebuild();
        }
        setShowing(editorAreaEmpty);
        if (editorAreaEmpty && !was && root.getScene() != null) {
            root.requestFocus();
        }
    }

    private void setShowing(boolean show) {
        root.setVisible(show); // stays managed: it is stacked over the editor area and sized with it
    }

    private void rebuild() {
        rows.getChildren().clear();
        int r = 0;
        r = row(r, tr("welcome.openFile"), "file.open", "file.find");
        if (projectsEnabled.getAsBoolean()) {
            r = row(r, tr("welcome.openFolder"), "project.open", null);
        }
        r = row(r, tr("command.palette.show"), "palette.show", null);
        row(r, tr("welcome.page"), "view.welcome", null);
    }

    private int row(int r, String text, String commandId, String fallbackChordId) {
        Hyperlink link = new Hyperlink(text);
        link.getStyleClass().add("editor-empty-action");
        link.setOnAction(e -> registry.run(commandId));
        rows.add(link, 0, r);
        String chord = keymap == null ? null : keymap.displayChord(commandId);
        if (chord == null && fallbackChordId != null && keymap != null) {
            chord = keymap.displayChord(fallbackChordId);
        }
        if (chord != null) {
            Label key = new Label(chord);
            key.getStyleClass().add("editor-empty-key");
            rows.add(key, 1, r);
            link.setAccessibleHelp(chord);
        }
        return r + 1;
    }
}

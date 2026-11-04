package com.editora.editor;

import java.util.List;

import javafx.scene.Parent;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

/**
 * Keeps a popup's own copy of {@code app.css}/{@code syntax.css} from undoing the editor theme. A popup's scene
 * already inherits its owner's stylesheets (app, syntax and the editor-theme override), but a node's own
 * stylesheets win over its scene's: the hover card and the completion documentation pane added the light {@code
 * syntax.css} on their content, so in a dark theme the code in them took the light palette and plain identifiers
 * were drawn near-black on the dark card (JS review B107, seen in TypeScript hovers). The owner scene's other
 * stylesheets — the editor theme — go after the node's own, so they win again.
 */
public final class PopupSheets {

    private PopupSheets() {}

    /** Appends to {@code node}'s stylesheets the ones of {@code owner}'s scene it lacks, in their order. */
    public static void followOwnerTheme(Parent node, @Nullable Window owner) {
        if (owner == null || owner.getScene() == null) {
            return;
        }
        List<String> mine = node.getStylesheets();
        List<String> extra = extraSheets(mine, owner.getScene().getStylesheets());
        if (!extra.isEmpty()) {
            node.getStylesheets().addAll(extra);
        }
    }

    /** The {@code owner} sheets not in {@code mine}, in their order. Pure. */
    static List<String> extraSheets(List<String> mine, List<String> owner) {
        return owner.stream().filter(s -> !mine.contains(s)).toList();
    }
}

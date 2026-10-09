package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import com.editora.config.WorkspaceState;
import com.editora.editor.TabContent;

/**
 * A restored tab whose editor has not been built yet.
 *
 * <p>A session restore puts every saved file's tab in the strip before the first frame — title, file icon,
 * pin marker, group and order — but builds an {@link com.editora.editor.EditorBuffer} only for the tab the
 * user will see. The rest get this stand-in as their {@code userData} and an empty content node; the editor
 * arrives on the restore cadence ({@link RestoreSchedule}), or at once when something needs it first (the
 * tab is selected, a workspace edit targets the file, …). The {@link Tab} object itself is kept, so pins,
 * group membership, the MRU list and the selection carry over untouched.
 *
 * <p>Everything that iterates buffers sees a placeholder as "no buffer", exactly like an image or PDF tab:
 * a setting pushed to open buffers reaches the editor when it is built (the wiring reads the current
 * settings), and a file-level operation that finds no buffer treats the file as on disk — which is what the
 * placeholder's content is. {@code MainController.ensureBuffer} builds the editor for callers that need it.
 */
final class RestorePlaceholder implements TabContent {

    private final WorkspaceState.OpenFile entry;
    private final int index;
    private Path path;
    private final Region node = new Region();

    /**
     * @param entry the saved session entry (caret, pin, group) the tab was restored from
     * @param path the file, as the restore resolved it
     * @param index the entry's position in the restore's file list
     */
    RestorePlaceholder(WorkspaceState.OpenFile entry, Path path, int index) {
        this.entry = entry;
        this.path = path;
        this.index = index;
    }

    /** The placeholder in {@code tab}, or {@code null} when the tab is anything else (including a built buffer). */
    static RestorePlaceholder of(Tab tab) {
        return tab != null && tab.getUserData() instanceof RestorePlaceholder p ? p : null;
    }

    WorkspaceState.OpenFile entry() {
        return entry;
    }

    int index() {
        return index;
    }

    Path path() {
        return path;
    }

    /** Follows a move of the file (or of a folder above it) made before the editor was built. */
    void relocate(Path target) {
        this.path = target;
    }

    /** The saved caret, so a session written before the editor exists keeps the position. */
    int caret() {
        return entry.getCaret();
    }

    @Override
    public Node node() {
        return node;
    }

    @Override
    public String title() {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    @Override
    public Node icon() {
        Node type = FileIcons.forFileName(title());
        type.getStyleClass().add("tab-file-icon");
        if (!entry.isPinned()) {
            return type;
        }
        HBox icons = new HBox(6, Icons.pin(), type);
        icons.getStyleClass().add("tab-placeholder-icons");
        return icons;
    }
}

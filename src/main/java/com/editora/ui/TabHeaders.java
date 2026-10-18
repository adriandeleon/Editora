package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;

import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;

/**
 * The graphic header of an editor tab — icon, title, state markers and the drag handle that reorders the
 * strip — for any {@link TabContent} and, with its pin/dirty/read-only/remote markers, for a buffer. The tab
 * text itself stays empty: the header is the title. Owned by {@code MainController}, which decides when a
 * header is (re)rendered and keeps the window-title and preview-tab bookkeeping around those calls.
 */
final class TabHeaders {

    private final Supplier<EditorArea> editorArea;
    private final Predicate<Tab> pinned;

    /** The tab whose header is being dragged, for drops on a group body ({@link EditorArea}). */
    private Tab draggedTab;

    TabHeaders(Supplier<EditorArea> editorArea, Predicate<Tab> pinned) {
        this.editorArea = editorArea;
        this.pinned = pinned;
    }

    Tab draggedTab() {
        return draggedTab;
    }

    /**
     * Gives {@code tab} the generic header of a {@link TabContent}: its icon and title in a draggable
     * graphic. Buffer tabs replace it via {@link #bufferHeader}; a restore placeholder gets it again when
     * its file moves before its editor exists.
     */
    void contentHeader(Tab tab, TabContent content) {
        Label title = new Label(content.title());
        title.getStyleClass().add("tab-title");
        HBox header = new HBox(6);
        header.getStyleClass().add("tab-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Node icon = content.icon();
        if (icon != null) {
            header.getChildren().add(icon);
        }
        header.getChildren().add(title);
        enableTabDrag(header, tab);
        if (pinned.test(tab)) {
            header.getStyleClass().add("pinned");
        }
        tab.setGraphic(header);
    }

    /**
     * Renders a buffer tab's header: pin and dirty markers, the remote glyph, the file-type icon, the title
     * (italic when {@code preview}: the tab will be reused), the state style classes and the full-path tooltip.
     */
    void bufferHeader(Tab tab, EditorBuffer buffer, boolean preview) {
        boolean dirty = buffer.isDirty();
        boolean isPinned = pinned.test(tab);
        // The graphic header supports drag reordering and optional state/file icons.
        Label title = new Label((dirty ? "• " : "") + buffer.getTitle());
        title.getStyleClass().add("tab-title");
        if (preview) {
            title.getStyleClass().add("preview-tab-title"); // italic: this tab will be reused
        }
        Path p = buffer.getPath();
        boolean remote = com.editora.vfs.Vfs.isRemote(p);
        HBox header = new HBox(6);
        header.getStyleClass().add("tab-header");
        if (dirty) header.getStyleClass().add("dirty");
        if (isPinned) header.getStyleClass().add("pinned");
        if (!buffer.isEditable()) header.getStyleClass().add("read-only");
        if (remote) header.getStyleClass().add("remote");
        header.setAlignment(Pos.CENTER_LEFT);
        if (isPinned) {
            header.getChildren().add(Icons.pin());
        }
        if (remote) {
            header.getChildren().add(Icons.remote()); // cloud glyph: this file lives on a remote host
        }
        boolean encrypted = buffer.encryption().isEncrypted();
        if (encrypted) {
            header.getStyleClass().add("encrypted");
        }
        // A file-type glyph reflecting the file's kind (Java/Python/image/…) — by its resolved language when the
        // name says nothing (a shebang script, a picked language) — or a generic doc.
        Node typeIcon = FileIcons.forBuffer(buffer.getTitle(), buffer.getLanguage());
        typeIcon.getStyleClass().add("tab-file-icon");
        header.getChildren().add(typeIcon);
        if (encrypted) {
            header.getChildren().add(Icons.lock()); // beside the type glyph: what is inside, and that it is encrypted
        }
        header.getChildren().add(title);
        enableTabDrag(header, tab);
        tab.setText("");
        tab.setGraphic(header);
        toggleClass(tab, "dirty", dirty);
        toggleClass(tab, "pinned", isPinned);
        toggleClass(tab, "read-only", !buffer.isEditable());
        toggleClass(tab, "remote", remote);
        Tooltip tip = tooltip(p);
        if (encrypted) {
            tip.setText(EncryptionViews.tabTooltip(
                    tip.getText(), buffer.encryption().state()));
        }
        tab.setTooltip(tip);
    }

    /** The full-path tooltip of a file tab ("untitled" for none; a remote file shows its display label). */
    static Tooltip tooltip(Path p) {
        return new Tooltip(
                p == null
                        ? "untitled"
                        : com.editora.vfs.Vfs.isRemote(p)
                                ? com.editora.vfs.Vfs.displayLabel(p)
                                : p.toAbsolutePath().toString());
    }

    /** Wires drag-and-drop on a tab's header so the user can reorder the strip with the mouse. */
    private void enableTabDrag(Node header, Tab tab) {
        header.setOnDragDetected(e -> {
            Dragboard db = header.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString("tab"); // marker; the dragged tab is tracked in draggedTab
            db.setContent(content);
            // A snapshot of the tab follows the cursor so it's clear what's being dragged.
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            db.setDragView(header.snapshot(params, null), e.getX(), e.getY());
            draggedTab = tab;
            header.getStyleClass().add("tab-dragging");
            e.consume();
        });
        header.setOnDragOver(e -> {
            if (draggedTab != null && draggedTab != tab) {
                e.acceptTransferModes(TransferMode.MOVE);
                // Show an insertion line on the side the tab would land.
                boolean after = e.getX() > header.getBoundsInLocal().getWidth() / 2;
                toggleStyle(header, "tab-drop-after", after);
                toggleStyle(header, "tab-drop-before", !after);
            }
            e.consume();
        });
        header.setOnDragExited(e -> clearDropMarkers(header));
        header.setOnDragDropped(e -> {
            clearDropMarkers(header);
            boolean done = false;
            if (draggedTab != null && draggedTab != tab) {
                // Drop on the right half of the target inserts after it, left half before it.
                done = editorArea
                        .get()
                        .moveBeside(
                                draggedTab,
                                tab,
                                e.getX() > header.getBoundsInLocal().getWidth() / 2,
                                pinned);
            }
            e.setDropCompleted(done);
            e.consume();
        });
        header.setOnDragDone(e -> {
            header.getStyleClass().remove("tab-dragging");
            clearDropMarkers(header);
            draggedTab = null;
        });
    }

    private static void clearDropMarkers(Node header) {
        header.getStyleClass().removeAll("tab-drop-before", "tab-drop-after");
    }

    private static void toggleStyle(Node node, String styleClass, boolean on) {
        node.getStyleClass().remove(styleClass);
        if (on) {
            node.getStyleClass().add(styleClass);
        }
    }

    /** Adds or removes a style class on the tab itself (once: no duplicates on repeated renders). */
    static void toggleClass(Tab tab, String styleClass, boolean on) {
        if (on) {
            if (!tab.getStyleClass().contains(styleClass)) {
                tab.getStyleClass().add(styleClass);
            }
        } else {
            tab.getStyleClass().remove(styleClass);
        }
    }
}

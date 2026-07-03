package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

import com.editora.editor.TabContent;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * A read-only hex viewer that lives in an editor tab ({@link TabContent}) — so opening a binary file (an
 * executable, archive, {@code .class}, …) shows the classic {@code offset | hex | ASCII} dump ({@link HexDump})
 * instead of dumping its bytes as garbage text. The bytes are read up to {@link #MAX_DISPLAY_BYTES} (a large
 * binary shows its first slice with a "truncated" note, so a multi-GB file can't exhaust memory); the dump
 * sits in a read-only monospace {@link CodeArea} (selectable + copyable, never editable). {@link #dispose()}
 * drops the rendered text when the tab closes.
 */
public final class HexViewerPane implements TabContent {

    /** Show at most this many bytes (1 MiB → 65 536 rows); a larger file is truncated with a note. */
    static final int MAX_DISPLAY_BYTES = 1 << 20;

    private final Path path;
    private final String title;
    private final BorderPane root = new BorderPane();
    private final CodeArea area = new CodeArea();
    private boolean loaded;

    public HexViewerPane(Path path) {
        this.path = path;
        this.title = path.getFileName() == null
                ? path.toString()
                : path.getFileName().toString();
        root.getStyleClass().add("hex-viewer");
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("hex-viewer-area");
        // A bare CodeArea virtualizes but shows no scrollbars; wrap it like the editor does so the dump
        // scrolls vertically (65k+ rows) and horizontally (a narrow window clips the ASCII column).
        root.setCenter(new VirtualizedScrollPane<>(area));
        load();
    }

    private void load() {
        try {
            long size = Files.size(path);
            byte[] bytes;
            try (InputStream in = Files.newInputStream(path)) {
                bytes = in.readNBytes(MAX_DISPLAY_BYTES); // reads up to the cap (provider-agnostic: local + SFTP)
            }
            boolean truncated = size > bytes.length;
            area.replaceText(HexDump.format(bytes, 0));
            area.moveTo(0);
            area.scrollToPixel(0, 0);
            loaded = true;
            root.setTop(buildBar(size, bytes.length, truncated));
        } catch (IOException | RuntimeException e) {
            Label err = new Label(tr("hexviewer.loadFailed"));
            err.getStyleClass().add("hex-viewer-error");
            StackPane center = new StackPane(err);
            center.setPadding(new Insets(24));
            root.setCenter(center);
        }
    }

    private Node buildBar(long size, int shown, boolean truncated) {
        Label sizeLabel = new Label(tr("hexviewer.size", size));
        sizeLabel.getStyleClass().add("hex-viewer-size");
        Label spacer = new Label();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, sizeLabel);
        if (truncated) {
            Label note = new Label(tr("hexviewer.truncated", shown, size));
            note.getStyleClass().add("hex-viewer-truncated");
            bar.getChildren().add(note);
        }
        Label readOnly = new Label(tr("hexviewer.readOnly"));
        readOnly.getStyleClass().add("hex-viewer-readonly");
        bar.getChildren().addAll(spacer, readOnly);
        bar.getStyleClass().add("hex-viewer-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        return bar;
    }

    /** True once the dump was built (false when the read failed). Test accessor. */
    boolean isLoaded() {
        return loaded;
    }

    /** Frees the rendered dump text when the tab closes. */
    public void dispose() {
        area.replaceText("");
    }

    public Path getPath() {
        return path;
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Node icon() {
        return FileIcons.forFileName(title);
    }
}

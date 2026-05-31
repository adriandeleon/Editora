package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;

import javafx.beans.InvalidationListener;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The bottom status bar: a transient "echo area" message on the left (driven by
 * {@link com.editora.ui.MainController#setStatus}) and right-aligned, clickable segments showing
 * the active buffer's cursor position/selection, language, indentation, line endings, and encoding.
 *
 * <p>Clickable segments dispatch a registered {@link com.editora.command.Command} (per the project's
 * command-driven convention) rather than wiring logic directly. Segments update live: the bar
 * re-binds its listeners to the active buffer via {@link #attach(EditorBuffer)} on every tab switch,
 * mirroring {@link FileInformationPanel}.
 */
public final class StatusBar extends HBox {

    private final Supplier<EditorBuffer> activeBuffer;
    private final CommandRegistry registry;
    private final Supplier<Settings> settings;

    private final Label echo = new Label("Ready");
    private final Label position = segment("nav.goToLine", "Go to line");
    private final Label language = segment("buffer.setLanguage", "Set language");
    private final Label indent = segment("buffer.setTabSize", "Set tab size");
    private final Label endings = segment("buffer.convertLineEndings", "Convert line endings");
    private final Label size = new Label();
    private final Label encoding = new Label("UTF-8");

    private EditorBuffer attached;
    /** A single listener refreshes every segment on caret / text / selection changes. */
    private final InvalidationListener changeListener = obs -> refresh();

    public StatusBar(Supplier<EditorBuffer> activeBuffer, CommandRegistry registry,
            Supplier<Settings> settings) {
        this.activeBuffer = activeBuffer;
        this.registry = registry;
        this.settings = settings;

        getStyleClass().add("status-bar");
        echo.getStyleClass().add("status-message");
        size.getStyleClass().add("status-segment");
        size.setTooltip(new Tooltip("File size"));
        encoding.getStyleClass().add("status-segment");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(echo, spacer, position, language, indent, endings, size, encoding);
        refresh();
    }

    /** Builds a clickable segment label that dispatches {@code commandId} when clicked. */
    private Label segment(String commandId, String hint) {
        Label label = new Label();
        label.getStyleClass().addAll("status-segment", "status-segment-clickable");
        label.setTooltip(new Tooltip(hint));
        label.setOnMouseClicked(e -> registry.run(commandId));
        return label;
    }

    /** Sets the transient echo-area message (called by MainController.setStatus). */
    public void setMessage(String message) {
        echo.setText(message == null ? "" : message);
    }

    /** Re-binds live listeners to {@code buffer} (or none) and refreshes the segments. */
    public void attach(EditorBuffer buffer) {
        if (attached != null) {
            attached.getArea().caretPositionProperty().removeListener(changeListener);
            attached.getArea().textProperty().removeListener(changeListener);
            attached.getArea().selectionProperty().removeListener(changeListener);
        }
        attached = buffer;
        if (buffer != null) {
            buffer.getArea().caretPositionProperty().addListener(changeListener);
            buffer.getArea().textProperty().addListener(changeListener);
            buffer.getArea().selectionProperty().addListener(changeListener);
        }
        refresh();
    }

    /** Recomputes every segment from the active buffer's current state. */
    public void refresh() {
        EditorBuffer buffer = activeBuffer.get();
        boolean hasBuffer = buffer != null;
        for (Label seg : new Label[]{position, language, endings, size}) {
            seg.setVisible(hasBuffer);
            seg.setManaged(hasBuffer);
        }

        indent.setText("Tab Size: " + settings.get().getTabSize());
        if (!hasBuffer) {
            return;
        }
        var area = buffer.getArea();
        int line = area.getCurrentParagraph() + 1;
        int col = area.getCaretColumn() + 1;
        int selected = area.getSelection().getLength();
        String text = "Ln " + line + ", Col " + col;
        if (selected > 0) {
            long lines = area.getSelectedText().lines().count();
            text += lines > 1 ? " (" + selected + " selected, " + lines + " lines)"
                    : " (" + selected + " selected)";
        }
        position.setText(text);
        position.getTooltip().setText("Offset " + area.getCaretPosition() + " · click to go to line");

        language.setText(displayLanguage(buffer.getLanguage()));
        endings.setText(buffer.getLineEnding());
        size.setText(formatSize(buffer.getContent().getBytes(StandardCharsets.UTF_8).length));
    }

    /** Human-readable byte size (e.g. {@code 512 B}, {@code 1.2 kB}, {@code 3.4 MB}). */
    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f kB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Capitalizes a language name for display (e.g. {@code "java"} -> {@code "Java"}). */
    private static String displayLanguage(String name) {
        if (name == null || name.isEmpty()) {
            return "Plain Text";
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}

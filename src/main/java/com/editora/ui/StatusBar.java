package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;

import javafx.beans.InvalidationListener;
import javafx.scene.control.Button;
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
    /** Git branch + ahead/behind; clickable to switch branches. Hidden outside a Git repo. */
    private final Label git = segment("git.switchBranch", "Git branch — click to switch (M-x git.switchBranch)");
    private final Label position = segment("nav.goToLine", "Go to line");
    private final Label language = segment("buffer.setLanguage", "Set language");
    private final Label indent = segment("buffer.setTabSize", "Set tab size");
    private final Label endings = segment("buffer.convertLineEndings", "Convert line endings");
    private final Label size = new Label();
    private final Label encoding = new Label("UTF-8");
    /** Read-only ("View mode") indicator; shown only when the active buffer is non-editable. */
    private final Label readOnly = segment("view.toggleReadOnly", "Read-only — click to allow edits (C-x C-q)");
    /** Text-zoom percentage (clickable to reset to 100%). */
    private final Label zoomPercent = new Label("100%");

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

        readOnly.setText("Read-Only");
        readOnly.getStyleClass().add("status-readonly");

        git.getStyleClass().add("status-git");
        git.setText("No VCS"); // always shown; updated by setGitBranch

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(echo, spacer, git, readOnly, zoomGroup(), position, language, indent, endings,
                size, encoding);
        refresh();
    }

    /** The text-zoom control: {@code [ −  100%  + ]}, dispatching the zoom commands. */
    private HBox zoomGroup() {
        Button out = zoomButton("−", "view.textZoomOut", "Zoom out text");
        Button in = zoomButton("+", "view.textZoomIn", "Zoom in text");
        zoomPercent.getStyleClass().addAll("status-segment", "status-segment-clickable", "text-zoom-percent");
        zoomPercent.setTooltip(new Tooltip("Text zoom — click to reset to 100%"));
        zoomPercent.setOnMouseClicked(e -> registry.run("view.textZoomReset"));
        HBox group = new HBox(out, zoomPercent, in);
        group.getStyleClass().add("text-zoom");
        return group;
    }

    private Button zoomButton(String text, String commandId, String hint) {
        Button b = new Button(text);
        b.getStyleClass().addAll("text-zoom-button", "flat");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(hint));
        b.setOnAction(e -> registry.run(commandId));
        return b;
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

    /** The Git branch segment node, so the branch dropdown can anchor itself to it. */
    public javafx.scene.Node gitSegmentNode() {
        return git;
    }

    /**
     * Enables/disables the Git VCS segment. When Git support is off the segment is shown disabled
     * ("Git off", greyed, non-clickable); when on, {@link #setGitBranch} repopulates it.
     */
    public void setGitEnabled(boolean enabled) {
        git.setDisable(!enabled);
        if (!enabled) {
            git.setText("Git off");
            git.getTooltip().setText("Git is disabled — enable it in Settings");
        }
    }

    /**
     * Updates the Git branch segment. The segment is <em>always</em> visible: with a {@code null}/blank
     * {@code branch} (not under version control) it shows "No VCS" — clicking still opens the dropdown,
     * which then offers "Clone Git repository…". Otherwise it shows {@code ⎇ branch} with optional
     * {@code ↑ahead ↓behind}.
     */
    public void setGitBranch(String branch, int ahead, int behind) {
        if (branch == null || branch.isBlank()) {
            git.setText("No VCS");
            git.getTooltip().setText("Not under version control — click to clone a repository");
            return;
        }
        StringBuilder sb = new StringBuilder("⎇ ").append(branch); // ⎇
        if (ahead > 0) {
            sb.append("  ↑").append(ahead); // ↑
        }
        if (behind > 0) {
            sb.append("  ↓").append(behind); // ↓
        }
        git.setText(sb.toString());
        git.getTooltip().setText("Git branch — click to switch / branch actions");
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
        // The read-only segment is a toggle: always shown (when there's a buffer), reflecting and
        // flipping the state. "Read-Only" (amber/active) ⇄ "Editable" (muted); click runs the command.
        readOnly.setVisible(hasBuffer);
        readOnly.setManaged(hasBuffer);
        if (hasBuffer) {
            boolean ro = !buffer.isEditable();
            readOnly.setText(ro ? "Read-Only" : "Editable");
            if (ro) {
                if (!readOnly.getStyleClass().contains("active")) {
                    readOnly.getStyleClass().add("active");
                }
            } else {
                readOnly.getStyleClass().remove("active");
            }
            readOnly.getTooltip().setText(ro
                    ? "Read-only — click to allow edits (C-x C-q)"
                    : "Editable — click to make read-only (C-x C-q)");
        }

        indent.setText("Tab Size: " + settings.get().getTabSize());
        zoomPercent.setText(Math.round(settings.get().getFontZoom() * 100) + "%");
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

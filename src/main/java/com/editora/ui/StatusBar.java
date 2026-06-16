package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;

import javafx.beans.InvalidationListener;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

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

    private final Label echo = new Label(tr("statusbar.ready"));
    /** In-memory, session-only history of echo messages, shown by clicking the echo area. */
    private final MessageLog messageLog = new MessageLog();

    private final MessageLogPopup messageLogPopup = new MessageLogPopup();
    /** Git branch + ahead/behind; clickable to switch branches. Hidden outside a Git repo. */
    private final Label git = segment("git.switchBranch", tr("statusbar.tip.gitSwitch"));
    /** Active language server for the current file (e.g. "LSP: jdtls"); clickable → Problems. Hidden when
     *  the active buffer isn't served by LSP. */
    private final Label lsp = segment("tool.problems", tr("statusbar.tip.lsp"));
    /** Indeterminate progress shown while a language server is starting/loading; hidden once ready. */
    // Starts determinate (0) — an *indeterminate* ProgressBar runs an animation timeline that pulses the
    // whole scene continuously (even while hidden), starving nearby repaints. setLspLoading() flips it to
    // indeterminate only while a server is actually loading.
    private final ProgressBar lspProgress = new ProgressBar(0);
    /** Debugger state for the current session (e.g. "Debug: running"); clickable → Debug window. Hidden
     *  when there's no active debug session. */
    private final Label debug = segment("tool.debug", tr("statusbar.tip.debug"));
    /** Indeterminate progress while the debug session is starting; hidden once running/suspended. */
    private final ProgressBar debugProgress = new ProgressBar(0);

    /** MCP server running indicator; clickable → copy the connection command. Hidden when the server is off. */
    private final Label mcp = segment("mcp.copyEndpoint", tr("statusbar.tip.mcp"));

    private final Label position = segment("nav.goToLine", tr("statusbar.tip.goToLine"));
    private final Label language = segment("buffer.setLanguage", tr("statusbar.tip.setLanguage"));
    private final Label indent = segment("buffer.setTabSize", tr("statusbar.tip.setTabSize"));
    private final Label endings = segment("buffer.convertLineEndings", tr("statusbar.tip.convertEndings"));
    private final Label size = new Label();
    private final Label encoding = new Label("UTF-8");
    /** Read-only ("View mode") indicator; shown only when the active buffer is non-editable. */
    private final Label readOnly = segment("view.toggleReadOnly", tr("statusbar.tip.readOnly"));
    /** Text-zoom percentage (clickable to reset to 100%). */
    private final Label zoomPercent = new Label("100%");

    private EditorBuffer attached;
    /** Simple UI mode hides the git / language / tab-size / line-ending / encoding / LSP segments (size is kept). */
    private boolean simpleMode;
    /** Latest LSP server name + loading state, so Simple-mode toggling can re-apply their visibility. */
    private String lspServerName = "";

    private boolean lspLoadingState;
    /** Whether the app-wide MCP server is running, so Simple-mode toggling can re-apply its visibility. */
    private boolean mcpRunning;
    /** A single listener refreshes every segment on caret / text / selection changes. */
    private final InvalidationListener changeListener = obs -> refresh();

    public StatusBar(Supplier<EditorBuffer> activeBuffer, CommandRegistry registry, Supplier<Settings> settings) {
        this.activeBuffer = activeBuffer;
        this.registry = registry;
        this.settings = settings;

        getStyleClass().add("status-bar");
        echo.getStyleClass().addAll("status-message", "status-segment-clickable");
        echo.setTooltip(new Tooltip(tr("statusbar.tip.messageLog")));
        echo.setOnMouseClicked(e -> registry.run("view.messageLog"));
        size.getStyleClass().add("status-segment");
        size.setTooltip(new Tooltip(tr("statusbar.tip.fileSize")));
        encoding.getStyleClass().add("status-segment");

        readOnly.setText(tr("statusbar.readOnly"));
        readOnly.getStyleClass().add("status-readonly");

        git.getStyleClass().add("status-git");
        git.setText(tr("statusbar.noVcs")); // always shown; updated by setGitBranch

        lsp.getStyleClass().add("status-lsp");
        lsp.setVisible(false); // shown only when the active file is served by a language server
        lsp.setManaged(false);

        mcp.getStyleClass().add("status-mcp");
        mcp.setText(tr("statusbar.mcp"));
        mcp.setVisible(false); // shown only while the MCP server is running
        mcp.setManaged(false);

        lspProgress.getStyleClass().add("status-lsp-progress");
        lspProgress.setPrefWidth(90);
        lspProgress.setMaxHeight(10);
        lspProgress.setVisible(false); // shown only while a language server is loading
        lspProgress.setManaged(false);
        lspProgress.setTooltip(new Tooltip(tr("statusbar.tip.lspLoading")));
        debugProgress.getStyleClass().add("status-lsp-progress");
        debugProgress.setPrefWidth(90);
        debugProgress.setMaxHeight(10);
        debugProgress.setVisible(false);
        debugProgress.setManaged(false);
        debugProgress.setTooltip(new Tooltip(tr("statusbar.tip.debugLoading")));
        debug.setVisible(false);
        debug.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren()
                .addAll(
                        echo,
                        spacer,
                        debugProgress,
                        debug,
                        lspProgress,
                        git,
                        lsp,
                        mcp,
                        readOnly,
                        zoomGroup(),
                        position,
                        language,
                        indent,
                        endings,
                        encoding,
                        size);
        refresh();
    }

    /** The text-zoom control: {@code [ −  100%  + ]}, dispatching the zoom commands. */
    private HBox zoomGroup() {
        Button out = zoomButton("−", "view.textZoomOut", tr("statusbar.tip.zoomOut"));
        Button in = zoomButton("+", "view.textZoomIn", tr("statusbar.tip.zoomIn"));
        zoomPercent.getStyleClass().addAll("status-segment", "status-segment-clickable", "text-zoom-percent");
        zoomPercent.setTooltip(new Tooltip(tr("statusbar.tip.zoomReset")));
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

    /** Adds a plugin-contributed segment (text + optional command) at the right end of the status bar. */
    public void addPluginSegment(String text, String commandId) {
        Label seg = new Label(text == null ? "" : text);
        seg.getStyleClass().add("status-segment");
        if (commandId != null && !commandId.isBlank()) {
            seg.getStyleClass().add("status-segment-clickable");
            seg.setOnMouseClicked(e -> registry.run(commandId));
        }
        getChildren().add(seg);
    }

    /** Sets the transient echo-area message (called by MainController.setStatus) and logs it for the session.
     *  The echo shows a single line only (a Label renders embedded newlines as line breaks, so a multi-line
     *  message — e.g. a compiler error dump — would grow the whole status bar); the full text goes to the
     *  message log, whose rows wrap and are copyable. */
    public void setMessage(String message) {
        echo.setText(echoLine(message));
        messageLog.add(message); // full text; no-ops for null/blank (a blank clears the echo)
    }

    /** Maximum characters shown in the echo line; longer messages are truncated with an ellipsis. */
    static final int MAX_ECHO_CHARS = 200;

    /** First line of {@code message}, capped at {@link #MAX_ECHO_CHARS}, with {@code …} marking anything
     *  cut (more lines or overlength). Pure — tested. */
    static String echoLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        int cr = message.indexOf('\r');
        int cut = nl < 0 ? cr : (cr < 0 ? nl : Math.min(nl, cr));
        String line = (cut < 0 ? message : message.substring(0, cut)).stripTrailing();
        boolean truncated = cut >= 0;
        if (line.length() > MAX_ECHO_CHARS) {
            line = line.substring(0, MAX_ECHO_CHARS);
            truncated = true;
        }
        return truncated ? line + " …" : line;
    }

    /** Injects the shared in-scene overlay host into the message-log popup. */
    public void setOverlayHost(OverlayHost overlayHost) {
        messageLogPopup.setOverlayHost(overlayHost);
    }

    /** Toggles the session message-log popup (anchored just above the echo area). */
    public void showMessageLog() {
        Window owner = getScene() == null ? null : getScene().getWindow();
        if (owner != null) {
            messageLogPopup.toggle(owner, echo, messageLog);
        }
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
            git.setText(tr("statusbar.gitOff"));
            git.getTooltip().setText(tr("statusbar.tip.gitDisabled"));
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
            git.setText(tr("statusbar.noVcs"));
            git.getTooltip().setText(tr("statusbar.tip.noVcs"));
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
        git.getTooltip().setText(tr("statusbar.tip.gitBranch"));
    }

    /**
     * Shows/updates the LSP segment for the active file. {@code serverName} non-blank → "LSP: name"
     * (segment shown); null/blank → the segment is hidden (no language server for this file).
     */
    public void setLsp(String serverName) {
        lspServerName = serverName == null ? "" : serverName;
        applyLspStatusVisibility();
    }

    /** Shows/hides the indeterminate progress bar while a language server is starting/loading. */
    public void setLspLoading(boolean loading) {
        lspLoadingState = loading;
        applyLspStatusVisibility();
    }

    /** Applies the LSP segment + loading-bar visibility from the stored state — both suppressed in Simple
     *  mode. The progress bar is only set indeterminate while actually shown; a fixed value otherwise stops
     *  the animation timeline (which would keep pulsing the scene and starve repaints — see field comment). */
    private void applyLspStatusVisibility() {
        boolean showLsp = !lspServerName.isBlank() && !simpleMode;
        lsp.setText(lspServerName.isBlank() ? "" : tr("statusbar.lsp", lspServerName));
        lsp.setVisible(showLsp);
        lsp.setManaged(showLsp);
        boolean showProgress = lspLoadingState && !simpleMode;
        lspProgress.setProgress(showProgress ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        lspProgress.setVisible(showProgress);
        lspProgress.setManaged(showProgress);
    }

    /** Shows/hides the MCP-server-running indicator (suppressed in Simple UI mode). */
    public void setMcpRunning(boolean running) {
        mcpRunning = running;
        applyMcpStatusVisibility();
    }

    private void applyMcpStatusVisibility() {
        boolean show = mcpRunning && !simpleMode;
        mcp.setVisible(show);
        mcp.setManaged(show);
    }

    /** Shows/updates the Debug segment ({@code state} non-blank → "Debug: state"; null/blank → hidden). */
    public void setDebug(String state) {
        boolean show = state != null && !state.isBlank();
        debug.setText(show ? tr("statusbar.debug", state) : "");
        debug.setVisible(show);
        debug.setManaged(show);
    }

    /** Shows/hides the indeterminate progress bar while a debug session is starting. */
    public void setDebugLoading(boolean loading) {
        debugProgress.setProgress(loading ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        debugProgress.setVisible(loading);
        debugProgress.setManaged(loading);
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
        position.setVisible(hasBuffer);
        position.setManaged(hasBuffer);
        // Ln/Col and the file size follow buffer presence even in Simple mode (kept visible there).
        size.setVisible(hasBuffer);
        size.setManaged(hasBuffer);
        // Simple UI mode hides these segments; otherwise they follow buffer presence.
        for (Label seg : new Label[] {language, endings}) {
            boolean vis = hasBuffer && !simpleMode;
            seg.setVisible(vis);
            seg.setManaged(vis);
        }
        // These are normally always shown; Simple mode hides them.
        for (Label seg : new Label[] {git, indent, encoding}) {
            seg.setVisible(!simpleMode);
            seg.setManaged(!simpleMode);
        }
        // The read-only segment is a toggle: always shown (when there's a buffer), reflecting and
        // flipping the state. "Read-Only" (amber/active) ⇄ "Editable" (muted); click runs the command.
        readOnly.setVisible(hasBuffer);
        readOnly.setManaged(hasBuffer);
        if (hasBuffer) {
            boolean ro = !buffer.isEditable();
            readOnly.setText(ro ? tr("statusbar.readOnly") : tr("statusbar.editable"));
            if (ro) {
                if (!readOnly.getStyleClass().contains("active")) {
                    readOnly.getStyleClass().add("active");
                }
            } else {
                readOnly.getStyleClass().remove("active");
            }
            readOnly.getTooltip().setText(ro ? tr("statusbar.tip.readOnly") : tr("statusbar.tip.editable"));
        }

        // Indent + encoding reflect the active buffer's effective values (an .editorconfig can override
        // the global tab size / charset per file), else the global default.
        indent.setText(tr(
                "statusbar.tabSize",
                hasBuffer ? buffer.getTabSize() : settings.get().getTabSize()));
        encoding.setText(
                hasBuffer
                        ? com.editora.editorconfig.EditorConfigCharset.displayName(buffer.getEffectiveCharset())
                        : "UTF-8");
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
            text += lines > 1 ? " (" + selected + " selected, " + lines + " lines)" : " (" + selected + " selected)";
        }
        position.setText(text);
        position.getTooltip().setText(tr("statusbar.tip.offset", area.getCaretPosition()));

        language.setText(displayLanguage(buffer.getLanguage()));
        endings.setText(buffer.getLineEnding());
        size.setText(formatSize(buffer.getContent().getBytes(StandardCharsets.UTF_8).length));
    }

    /** Simple UI mode: hide the git / language / tab-size / line-ending / encoding segments (size is kept). */
    public void setSimpleMode(boolean simpleMode) {
        if (this.simpleMode != simpleMode) {
            this.simpleMode = simpleMode;
            refresh();
            applyLspStatusVisibility(); // the LSP segment + loading bar are event-driven, not in refresh()
            applyMcpStatusVisibility(); // the MCP segment is event-driven too
        }
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
            return tr("statusbar.plainText");
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}

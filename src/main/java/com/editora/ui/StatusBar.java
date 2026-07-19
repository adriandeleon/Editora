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
    /** GitHub PR CI checks roll-up (✓/✗/○ + fail count); clickable → refresh. Hidden unless the current
     *  branch has a PR with checks. */
    private final Label githubChecks = segment("github.refresh", tr("statusbar.tip.githubChecks"));
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

    /** "Update available" indicator; clickable → open the release page (and dismiss the notice). Hidden unless a
     *  newer, non-dismissed release is known. */
    private final Label update = segment("update.openDownloadPage", tr("statusbar.tip.update"));
    /** MCP server running indicator; clickable → copy the connection command. Hidden when the server is off. */
    private final Label mcp = segment("mcp.copyEndpoint", tr("statusbar.tip.mcp"));
    /** "● REC" indicator shown only while a keyboard macro is being recorded; clickable → stop recording. */
    private final Label macroRec = segment("macro.stopRecording", tr("statusbar.tip.macroRec"));
    /** Remote-file indicator (⇅ SFTP); shown only for a remote buffer, clickable → manage connections. Its
     *  tooltip carries the {@code host:/path}. */
    private final Label remote = segment("remote.manageConnections", tr("statusbar.tip.remote"));

    private final Label position = segment("nav.goToLine", tr("statusbar.tip.goToLine"));
    /** CSV/TSV column indicator ("Field N of M"); clickable → copy the file as a Markdown table. */
    private final Label csvField = segment("csv.copyAsMarkdownTable", tr("statusbar.tip.csvField"));

    private final Label language = segment("buffer.setLanguage", tr("statusbar.tip.setLanguage"));
    private final Label indent = segment("buffer.setTabSize", tr("statusbar.tip.setTabSize"));
    private final Label endings = segment("buffer.convertLineEndings", tr("statusbar.tip.convertEndings"));
    private final Label size = new Label();
    private final Label encoding = new Label("UTF-8");
    /** Shown when an {@code .editorconfig} governs the active buffer; clickable → open that file. */
    private final Label editorConfig = segment("editorConfig.openActive", tr("statusbar.tip.editorConfig"));
    /** Read-only ("View mode") indicator; shown only when the active buffer is non-editable. */
    private final Label readOnly = segment("view.toggleReadOnly", tr("statusbar.tip.readOnly"));
    /** Text-zoom percentage (clickable to reset to 100%). */
    private final Label zoomPercent = new Label("100%");

    private EditorBuffer attached;
    /** Simple UI mode hides the git / language / tab-size / line-ending / encoding / LSP segments (size is kept). */
    private boolean simpleMode;
    /** Git feature on (setGitEnabled) and the active file actually in a repo (setGitBranch) — both gate the
     *  branch segment's visibility, so it's hidden for files outside any repo (or the no-file Welcome tab). */
    private boolean gitFeatureEnabled = true;

    private boolean gitInRepo;
    /** Latest GitHub PR check roll-up (null = hidden), so Simple-mode toggling can re-apply its visibility. */
    private com.editora.github.ChecksParser.ChecksSummary githubChecksSummary;
    /** Latest LSP server name + loading state, so Simple-mode toggling can re-apply their visibility. */
    private String lspServerName = "";

    private boolean lspLoadingState;
    /** Whether the app-wide MCP server is running, so Simple-mode toggling can re-apply its visibility. */
    private boolean mcpRunning;
    /** Caret / selection changes update only the cheap caret-dependent segments (Ln/Col + the CSV field) —
     *  NOT the file-size segment, which is O(document) and doesn't change when the caret moves. */
    private final InvalidationListener caretListener = obs -> refreshCaretSegments();
    /** The debounced edit subscription that recomputes the (O(n)) file-size segment once per typing burst. */
    private org.reactfx.Subscription sizeSub;

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

        editorConfig.getStyleClass().add("status-editorconfig");
        editorConfig.setText(tr("statusbar.editorConfig"));
        editorConfig.setVisible(false); // shown only when an .editorconfig governs the active buffer
        editorConfig.setManaged(false);

        readOnly.setText(tr("statusbar.readOnly"));
        readOnly.getStyleClass().add("status-readonly");

        git.getStyleClass().add("status-git");
        git.setText(tr("statusbar.noVcs")); // always shown; updated by setGitBranch

        githubChecks.getStyleClass().add("status-git");
        githubChecks.setVisible(false); // shown only when the current branch's PR has checks
        githubChecks.setManaged(false);

        lsp.getStyleClass().add("status-lsp");
        lsp.setVisible(false); // shown only when the active file is served by a language server
        lsp.setManaged(false);

        mcp.getStyleClass().add("status-mcp");
        update.getStyleClass().add("status-update");
        update.setVisible(false); // shown only when a newer, non-dismissed release is available
        update.setManaged(false);

        mcp.setText(tr("statusbar.mcp"));
        mcp.setVisible(false); // shown only while the MCP server is running
        mcp.setManaged(false);

        macroRec.getStyleClass().add("status-macro-rec");
        macroRec.setText(tr("statusbar.macroRec"));
        macroRec.setVisible(false); // shown only while a macro is being recorded
        macroRec.setManaged(false);

        remote.getStyleClass().add("status-remote");
        remote.setText(tr("statusbar.remote"));
        remote.setVisible(false); // shown only for a remote (SFTP) buffer
        remote.setManaged(false);

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
                        update,
                        macroRec,
                        remote,
                        debugProgress,
                        debug,
                        lspProgress,
                        git,
                        githubChecks,
                        lsp,
                        mcp,
                        readOnly,
                        zoomGroup(),
                        position,
                        csvField,
                        language,
                        editorConfig,
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
        gitFeatureEnabled = enabled;
        git.setDisable(!enabled);
        if (!enabled) {
            git.setText(tr("statusbar.gitOff"));
            git.getTooltip().setText(tr("statusbar.tip.gitDisabled"));
        }
        applyGitVisibility();
    }

    /**
     * Updates the Git branch segment. Shown only when the active file is inside a repo: a {@code null}/blank
     * {@code branch} (no version control for this file, or no file) <em>hides</em> the segment entirely;
     * otherwise it shows {@code ⎇ branch} with optional {@code ↑ahead ↓behind}.
     */
    public void setGitBranch(String branch, int ahead, int behind) {
        if (branch == null || branch.isBlank()) {
            gitInRepo = false;
            applyGitVisibility();
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
        gitInRepo = true;
        applyGitVisibility();
    }

    /** The branch segment shows only when Git is on, the active file is in a repo, and not in Simple UI mode. */
    private void applyGitVisibility() {
        boolean vis = gitFeatureEnabled && gitInRepo && !simpleMode;
        git.setVisible(vis);
        git.setManaged(vis);
    }

    /**
     * Updates the GitHub PR CI-checks roll-up ({@code ✓/✗/○} + fail count). A {@code null} summary (or one with
     * no meaningful runs) hides the segment; otherwise it shows the overall status. Hidden in Simple UI mode.
     */
    public void setGitHubChecks(com.editora.github.ChecksParser.ChecksSummary summary) {
        githubChecksSummary =
                summary == null || summary.overall() == com.editora.github.ChecksParser.Overall.NONE ? null : summary;
        if (githubChecksSummary != null) {
            String glyph =
                    switch (githubChecksSummary.overall()) {
                        case PASS -> "✓"; // ✓
                        case FAIL -> "✗"; // ✗
                        case PENDING -> "○"; // ○
                        case NONE -> "";
                    };
            String text = glyph + " " + tr("statusbar.checks");
            if (githubChecksSummary.fail() > 0) {
                text += " " + githubChecksSummary.fail();
            }
            githubChecks.setText(text);
        }
        applyChecksVisibility();
    }

    private void applyChecksVisibility() {
        boolean vis = githubChecksSummary != null && !simpleMode;
        githubChecks.setVisible(vis);
        githubChecks.setManaged(vis);
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

    /** Shows the "update available" indicator for {@code version} (e.g. "Update: 1.0.0"), or hides it. Shown even
     *  in Simple UI mode — an available update is worth surfacing regardless of chrome. */
    public void setUpdateAvailable(boolean available, String version) {
        if (available) {
            update.setText(tr("statusbar.update", version));
        }
        update.setVisible(available);
        update.setManaged(available);
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

    /** Shows/hides the "● REC" macro-recording indicator (shown only while a macro is being recorded). */
    public void setMacroRecording(boolean recording) {
        macroRec.setVisible(recording);
        macroRec.setManaged(recording);
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
            attached.getArea().caretPositionProperty().removeListener(caretListener);
            attached.getArea().selectionProperty().removeListener(caretListener);
        }
        if (sizeSub != null) {
            sizeSub.unsubscribe();
            sizeSub = null;
        }
        attached = buffer;
        if (buffer != null) {
            // Ln/Col + the CSV field track the caret cheaply (no full-document scan).
            buffer.getArea().caretPositionProperty().addListener(caretListener);
            buffer.getArea().selectionProperty().addListener(caretListener);
            // The file size only changes on an EDIT — and computing it materializes the whole document (an
            // O(n) String + byte[]), so it must not run per keystroke. It was previously bound to textProperty
            // (which itself re-materializes the whole document per keystroke) AND recomputed on every caret
            // move; now it's a debounced plainTextChanges pulse — once per typing burst, delta-based.
            sizeSub = buffer.getArea()
                    .plainTextChanges()
                    .successionEnds(java.time.Duration.ofMillis(200))
                    .subscribe(ignore -> {
                        EditorBuffer b = activeBuffer.get();
                        if (b != null) {
                            refreshSize(b);
                        }
                    });
        }
        refresh();
    }

    /** Recomputes every segment from the active buffer's current state. */
    public void refresh() {
        EditorBuffer buffer = activeBuffer.get();
        boolean hasBuffer = buffer != null;
        // Remote-file (SFTP) indicator: shown for a remote buffer, with host:/path in its tooltip.
        java.nio.file.Path path = hasBuffer ? buffer.getPath() : null;
        boolean isRemote = com.editora.vfs.Vfs.isRemote(path) && !simpleMode;
        remote.setVisible(isRemote);
        remote.setManaged(isRemote);
        remote.setTooltip(isRemote ? new Tooltip(com.editora.vfs.Vfs.displayLabel(path)) : null);
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
        for (Label seg : new Label[] {indent, encoding}) {
            seg.setVisible(!simpleMode);
            seg.setManaged(!simpleMode);
        }
        // The git segment has its own gate (feature on + active file in a repo + not Simple mode).
        applyGitVisibility();
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
        // Show the EditorConfig indicator only when an .editorconfig actually applies to the active buffer
        // (the buffer's resolved properties are non-empty). Hidden in Simple UI mode.
        var ecProps = hasBuffer ? buffer.getEditorConfigProps() : null;
        boolean ecActive = ecProps != null && !ecProps.isEmpty() && !simpleMode;
        editorConfig.setVisible(ecActive);
        editorConfig.setManaged(ecActive);
        zoomPercent.setText(Math.round(settings.get().getFontZoom() * 100) + "%");
        if (!hasBuffer) {
            csvField.setVisible(false);
            csvField.setManaged(false);
            return;
        }
        refreshPositionAndCsv(buffer, buffer.getArea());
        language.setText(displayLanguage(buffer.getLanguage()));
        endings.setText(buffer.getLineEnding());
        refreshSize(buffer);
    }

    /** Updates only the caret-dependent segments for the active buffer (the {@link #caretListener} target). */
    private void refreshCaretSegments() {
        EditorBuffer b = activeBuffer.get();
        if (b != null) {
            refreshPositionAndCsv(b, b.getArea());
        }
    }

    /** The caret-dependent segments — Ln/Col (+ selection) and the CSV "Field N of M" readout. Cheap: only
     *  single-line scans, no full-document materialization, so it's safe on the per-caret-move path (updated
     *  by {@link #caretListener} without recomputing the O(n) file size). */
    private void refreshPositionAndCsv(EditorBuffer buffer, org.fxmisc.richtext.CodeArea area) {
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

        // CSV/TSV column readout — "Field N of M". Two single-line scans (the header row for the column count,
        // the caret's line up to the caret for the field index). Approximation: a field whose quotes span
        // multiple physical lines is counted per line (RFC-4180 multi-line fields are rare in practice).
        boolean csv = buffer.isCsv() && !simpleMode;
        csvField.setVisible(csv);
        csvField.setManaged(csv);
        if (csv) {
            String header = area.getText(0); // always ≥ 1 paragraph, even for an empty file
            char delim = com.editora.csv.CsvParser.detectDelimiter(header);
            int total = com.editora.csv.CsvParser.fieldCount(header, delim);
            String cur = area.getText(area.getCurrentParagraph());
            int idx = com.editora.csv.CsvParser.fieldIndexAt(cur, delim, area.getCaretColumn());
            csvField.setText(tr("statusbar.csvField", idx, total));
        }
    }

    /** The file-size segment. Materializes the whole document (an O(n) String + byte[]) to count UTF-8 bytes,
     *  so it is kept OFF the per-caret / per-keystroke path — computed only on a full {@link #refresh()} (tab
     *  switch / state change) and a debounced edit pulse, never per caret move (it doesn't change on one). */
    private void refreshSize(EditorBuffer buffer) {
        size.setText(formatSize(buffer.getContent().getBytes(StandardCharsets.UTF_8).length));
    }

    /** Simple UI mode: hide the git / language / tab-size / line-ending / encoding segments (size is kept). */
    public void setSimpleMode(boolean simpleMode) {
        if (this.simpleMode != simpleMode) {
            this.simpleMode = simpleMode;
            refresh();
            applyLspStatusVisibility(); // the LSP segment + loading bar are event-driven, not in refresh()
            applyMcpStatusVisibility(); // the MCP segment is event-driven too
            applyChecksVisibility(); // the GitHub checks segment is event-driven too
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

package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.agent.AcpJson;
import com.editora.editor.MarkdownRenderer;

import static com.editora.i18n.Messages.tr;

/**
 * The "AI Agent" tool window: a chat transcript streaming the ACP agent's replies + tool activity, a
 * multi-line prompt input (Enter sends, Shift+Enter inserts a newline), and Stop / New Session actions.
 * A {@link ToolWindowContent} modeled on {@link RunPanel}; {@link AgentCoordinator} drives it via
 * {@code appendChunk/appendLine/setBusy} on the FX thread.
 *
 * <p>The transcript is a {@link VBox} of entries, not a single control: a user prompt / tool-activity /
 * status line ({@link #appendLine}) is a plain {@link Label}, but the agent's own reply
 * ({@link #appendChunk}) is rendered as <b>Markdown</b> via {@link MarkdownRenderer} (the same renderer
 * the Markdown preview uses) — so code blocks, lists, bold, tables, and even Mermaid/math in a reply
 * render properly instead of showing raw markup. A reply streams in one chunk at a time; each chunk
 * accumulates into that message's own raw-text buffer and the message is re-rendered at a fixed pace
 * ({@link #RENDER_INTERVAL_MILLIS}): at once for the first chunk, then at most that often while chunks keep
 * arriving — so text appears as it streams rather than when the stream pauses. A streaming render parses
 * only the blocks that can still change ({@link AgentStreamSplit}); finished blocks keep their nodes.
 * {@link #appendLine} (a new prompt, a tool-call line, an error) finalizes — renders once more, as a
 * whole — whatever agent message was streaming, so the next chunk starts a fresh message
 * rather than continuing the previous one. Capped at {@link #MAX_ENTRIES} transcript nodes (oldest
 * dropped first) so a long session can't grow memory without bound.
 *
 * <p>Every rendered reply is also walked ({@link #wireInlineCodePaths}) to make an inline-code span that
 * looks like a file path ({@link #looksLikePath}) clickable — opens it via the injected {@code onOpenPath}
 * callback, so a path the agent mentions (e.g. "The script is at `/home/me/script.py`") is one click away
 * instead of copy-pasted into a file-open dialog.
 */
public final class AgentPanel extends VBox implements ToolWindowContent {

    /** Trim the transcript once it exceeds this many entries (keeps the most recent messages/lines). */
    private static final int MAX_ENTRIES = 2000;

    /**
     * The longest a streamed chunk waits to be shown. This used to be a 150 ms debounce restarted by every
     * chunk — and an agent sends chunks faster than that, so a reply showed nothing until it paused.
     */
    static final int RENDER_INTERVAL_MILLIS = 120;

    private final Label status = new Label();
    private final Label agentLabel = new Label();
    private final Label modelLabel = new Label();
    private final Label modeLabel = new Label();
    private final VBox planBox = new VBox(2);
    private final VBox transcriptBox = new VBox(4);
    private final ScrollPane transcriptScroll = new ScrollPane(transcriptBox);
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button();
    private final Button stopButton = new Button();
    private final Button newSessionButton = new Button();
    private final Button historyButton = new Button();
    private final Button detachButton = new Button();
    /** Pops the panel out into (or back from) a separate window. Set by the coordinator; no-op until then. */
    private Runnable onDetach = () -> {};
    /** Receives the prompt text when the user sends (coordinator → agent). */
    private Consumer<String> onSend;
    /** Cancels the in-flight turn (the header ■ + the Send→Stop toggle + Esc all run this). */
    private final Runnable onStop;
    /** True while a prompt turn is in flight (drives the Send↔Stop toggle + the Esc-stops shortcut). */
    private boolean busy;
    /** Receives a clicked inline-code span's raw text that looks like a file path (coordinator resolves +
     *  opens it, or reports it doesn't exist). */
    private final Consumer<String> onOpenPath;
    /** The plain-text line font, applied to each new {@code agent-line} Label ({@link #setPanelFont}). */
    private String lineFontFamily;

    private double lineFontSize = -1;
    /** The currently-streaming agent message's single-child wrapper, or null between messages. */
    private VBox currentAgentWrapper;
    /** Raw Markdown accumulated so far for {@link #currentAgentWrapper}. */
    private final StringBuilder currentAgentMarkdown = new StringBuilder();

    /** Paces the streaming message's renders: the first chunk at once, then one per interval. */
    private final FxThrottle renderThrottle =
            new FxThrottle(RENDER_INTERVAL_MILLIS, false, () -> renderCurrentMarkdown(false));

    /** The block container of the streaming message's render, or null when it must be rendered whole. */
    private javafx.scene.layout.Pane streamBlocks;
    /** How much of {@link #currentAgentMarkdown} is rendered into blocks that are final. */
    private int settledLength;
    /** How many of {@link #streamBlocks}' children those final blocks are. */
    private int settledBlocks;

    private int wholeRenders;
    private int tailRenders;
    private int tailChars;

    /** Renders of a whole message — lets a test show a stream is not re-parsed from its start each time. */
    int wholeRenderCount() {
        return wholeRenders;
    }

    /** Streaming renders that parsed only the unsettled tail. */
    int tailRenderCount() {
        return tailRenders;
    }

    /** Characters parsed by those tail renders, in all. */
    int tailCharsRendered() {
        return tailChars;
    }

    public AgentPanel(
            Runnable onStop,
            Runnable onNewSession,
            Runnable onPickModel,
            Runnable onPickMode,
            Runnable onPickAgentClient,
            Runnable onResumeSession,
            Consumer<String> onOpenPath) {
        this.onOpenPath = onOpenPath;
        this.onStop = onStop;
        getStyleClass().add("agent-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("agent-status");
        agentLabel.getStyleClass().add("agent-header-label");
        agentLabel.setOnMouseClicked(e -> onPickAgentClient.run());
        modelLabel.getStyleClass().add("agent-header-label");
        modelLabel.setOnMouseClicked(e -> onPickModel.run());
        modeLabel.getStyleClass().add("agent-header-label");
        modeLabel.setOnMouseClicked(e -> onPickMode.run());
        iconButton(detachButton, Icons.detach(), "agent.detach", () -> onDetach.run());
        iconButton(historyButton, Icons.history(), "agent.history", onResumeSession);
        iconButton(newSessionButton, Icons.newFile(), "agent.newSession", onNewSession);
        iconButton(stopButton, Icons.debugStop(), "agent.stop", onStop);
        stopButton.setDisable(true);
        HBox header = new HBox(
                8,
                status,
                agentLabel,
                modelLabel,
                modeLabel,
                spacer(),
                detachButton,
                historyButton,
                newSessionButton,
                stopButton);
        header.setAlignment(Pos.CENTER_LEFT);

        planBox.setManaged(false);
        planBox.setVisible(false);

        transcriptBox.getStyleClass().add("agent-transcript");
        transcriptScroll.setContent(transcriptBox);
        transcriptScroll.setFitToWidth(true);
        transcriptScroll.getStyleClass().add("agent-transcript-scroll");
        // Always-scroll-to-bottom on new/updated content (matches the old TextArea's behavior); height
        // changes on every append and every debounced re-render of the in-progress message.
        transcriptBox.heightProperty().addListener((obs, oldV, newV) -> transcriptScroll.setVvalue(1.0));

        input.setPromptText(tr("agent.inputPrompt"));
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.getStyleClass().add("agent-input");
        // Chat convention: Enter sends, Shift+Enter inserts a newline; Esc stops an in-flight turn.
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                send();
            } else if (e.getCode() == KeyCode.ESCAPE && busy) {
                e.consume();
                onStop.run();
            }
        });
        sendButton.setText(tr("agent.send"));
        sendButton.setDefaultButton(false);
        // The Send button doubles as Stop while a turn runs (ChatGPT/Claude convention — far more visible
        // than the header ■), so it's never disabled; it just switches action + label + danger styling.
        sendButton.getStyleClass().add("agent-send-button");
        sendButton.setOnAction(e -> {
            if (busy) {
                onStop.run();
            } else {
                send();
            }
        });
        HBox inputRow = new HBox(6, input, sendButton);
        inputRow.setAlignment(Pos.BOTTOM_RIGHT);
        HBox.setHgrow(input, Priority.ALWAYS);

        VBox.setVgrow(transcriptScroll, Priority.ALWAYS);
        getChildren().addAll(header, planBox, transcriptScroll, inputRow);
        setBusy(false);
        status.setText(tr("agent.idle"));
    }

    public void setOnSend(Consumer<String> onSend) {
        this.onSend = onSend;
    }

    /** Sets the detach/pop-out handler (coordinator moves this panel between the dock and a floating window). */
    public void setOnDetach(Runnable onDetach) {
        this.onDetach = onDetach == null ? () -> {} : onDetach;
    }

    /** Updates the detach button's tooltip to reflect the current state (docked ⇢ pop out, floating ⇢ dock). */
    public void setDetached(boolean detached) {
        detachButton.setTooltip(new Tooltip(tr(detached ? "agent.redock" : "agent.detach")));
    }

    private void send() {
        String text = input.getText() == null ? "" : input.getText().strip();
        if (text.isEmpty() || onSend == null || busy) {
            return; // empty, unwired, or a turn is already running
        }
        input.clear();
        onSend.accept(text);
    }

    /** Matches the input + plain transcript lines to the editor's font family/size (rendered Markdown
     *  replies keep their own typography — Inter for prose, JetBrains Mono for code — matching the app's
     *  Markdown preview everywhere else, so it isn't overridden here). */
    public void setPanelFont(String family, int size) {
        input.setFont(javafx.scene.text.Font.font(family, size));
        lineFontFamily = family;
        lineFontSize = size;
    }

    /** Appends one streaming chunk of the agent's reply (arrives without trailing newlines). Starts a new
     *  message on the first chunk after a line/finalize; later chunks accumulate into the same message and
     *  schedule a debounced Markdown re-render. */
    public void appendChunk(String text) {
        if (currentAgentWrapper == null) {
            currentAgentWrapper = new VBox();
            transcriptBox.getChildren().add(currentAgentWrapper);
            trimIfNeeded();
        }
        currentAgentMarkdown.append(text);
        renderThrottle.request();
    }

    /** Appends {@code line} as its own plain-text entry (tool activity, prompts, status notes) — finalizes
     *  (flushes) whatever agent message was streaming first, so the next chunk starts a fresh message. */
    public void appendLine(String line) {
        finalizeCurrentMessage();
        Label label = new Label(line);
        label.getStyleClass().add("agent-line");
        label.setWrapText(true);
        applyLineFont(label);
        transcriptBox.getChildren().add(label);
        trimIfNeeded();
    }

    /** Appends a tool-call / command line ({@code command}, glyph-prefixed) with light shell syntax
     *  highlighting — the command word, flags, quoted strings, operators, variables, and trailing comments
     *  get the editor's token colors (via {@link AgentCommandHighlighter}); plain args/paths stay default.
     *  Finalizes any streaming agent message first, like {@link #appendLine}. */
    public void appendToolLine(String command) {
        finalizeCurrentMessage();
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("agent-line");
        Text glyph = new Text("⚙ ");
        glyph.getStyleClass().add("agent-tool-glyph");
        applyRunFont(glyph);
        flow.getChildren().add(glyph);
        for (AgentCommandHighlighter.Span span : AgentCommandHighlighter.spans(command == null ? "" : command)) {
            Text run = new Text(span.text());
            if (span.styleClass() != null) {
                run.getStyleClass().addAll("text", span.styleClass()); // reuse .text.<class> token colors
            } else {
                run.getStyleClass().add("agent-cmd-plain"); // default foreground (theme-aware)
            }
            applyRunFont(run);
            flow.getChildren().add(run);
        }
        transcriptBox.getChildren().add(flow);
        trimIfNeeded();
    }

    private void applyLineFont(Label label) {
        if (lineFontFamily != null) {
            label.setFont(javafx.scene.text.Font.font(lineFontFamily, lineFontSize));
        }
    }

    private void applyRunFont(Text run) {
        if (lineFontFamily != null) {
            run.setFont(javafx.scene.text.Font.font(lineFontFamily, lineFontSize));
        }
    }

    private void trimIfNeeded() {
        while (transcriptBox.getChildren().size() > MAX_ENTRIES) {
            transcriptBox.getChildren().remove(0);
        }
    }

    /** Renders the current message in full, synchronously, and stops streaming it (the next
     *  {@link #appendChunk} starts a new one). A no-op when nothing is streaming. */
    private void finalizeCurrentMessage() {
        renderThrottle.cancel();
        if (currentAgentWrapper != null) {
            renderCurrentMarkdown(true);
            currentAgentWrapper = null;
            currentAgentMarkdown.setLength(0);
            streamBlocks = null;
        }
    }

    /**
     * Brings {@link #currentAgentWrapper} up to date with {@link #currentAgentMarkdown} — MUST run on the FX
     * thread ({@link MarkdownRenderer#renderDocument} builds FX nodes).
     *
     * <p>While the message is streaming only its unsettled tail is parsed and rebuilt. {@code complete}
     * renders the whole message in one piece instead: the finished reply is then exactly what a single
     * parse makes of it, whatever the stream was split into along the way.
     */
    private void renderCurrentMarkdown(boolean complete) {
        if (currentAgentWrapper == null) {
            return;
        }
        if (!complete && renderTail()) {
            return;
        }
        String md = currentAgentMarkdown.toString();
        Node rendered = renderMarkdown(md);
        currentAgentWrapper.getChildren().setAll(rendered);
        wholeRenders++;
        streamBlocks = blocksOf(rendered);
        settledLength = 0;
        settledBlocks = 0;
    }

    /** Falls back to a plain wrapped label if rendering throws (malformed input should never break the chat). */
    private Node renderMarkdown(String md) {
        Node rendered;
        try {
            rendered = MarkdownRenderer.renderDocument(
                    MarkdownRenderer.parseToDocument(md), null, null, MarkdownRenderer.ImagePolicy.DATA_ONLY);
        } catch (RuntimeException ex) {
            Label fallback = new Label(md);
            fallback.setWrapText(true);
            rendered = fallback;
        }
        wireInlineCodePaths(rendered);
        return rendered;
    }

    /**
     * The container holding one node per top-level block of a rendered document, or null when
     * {@code rendered} is not shaped that way (the error fallback) and so cannot be extended block by block.
     */
    private static javafx.scene.layout.Pane blocksOf(Node rendered) {
        if (rendered instanceof javafx.scene.layout.StackPane wrap
                && wrap.getChildren().size() == 1
                && wrap.getChildren().get(0) instanceof VBox blocks
                && blocks.getStyleClass().contains("markdown-preview")) {
            return blocks;
        }
        return null;
    }

    /**
     * Re-renders only the blocks of the streaming message that can still change, keeping the nodes of the
     * ones that cannot. Returns false when the message has to be rendered whole (its first render, or a
     * render whose shape is not block-per-node).
     */
    private boolean renderTail() {
        if (streamBlocks == null) {
            return false;
        }
        List<Node> blocks = streamBlocks.getChildren();
        String md = currentAgentMarkdown.toString();
        int from = settledLength;
        int end = AgentStreamSplit.settledEnd(md, from);
        List<Node> settled = end > from ? renderBlocks(md.substring(from, end)) : List.of();
        List<Node> tail = end < md.length() ? renderBlocks(md.substring(end)) : List.of();
        if (settled == null || tail == null) {
            streamBlocks = null;
            return false;
        }
        blocks.subList(settledBlocks, blocks.size()).clear(); // the previous tail
        blocks.addAll(settled);
        settledLength = end;
        settledBlocks = blocks.size();
        blocks.addAll(tail);
        tailRenders++;
        tailChars += md.length() - from;
        return true;
    }

    /** The top-level block nodes of {@code md}, detached from their own container; null if not block-shaped. */
    private List<Node> renderBlocks(String md) {
        javafx.scene.layout.Pane rendered = blocksOf(renderMarkdown(md));
        if (rendered == null) {
            return null;
        }
        List<Node> blocks = new java.util.ArrayList<>(rendered.getChildren());
        rendered.getChildren().clear();
        return blocks;
    }

    /**
     * Walks a just-rendered Markdown node tree, making every inline-code span ({@code .md-inline-code}
     * Label) whose text {@link #looksLikePath} clickable: opens it via the injected {@code onOpenPath}
     * callback. The real "does this exist" check happens there (in the coordinator, at click time), not
     * here, so rendering a reply never touches disk — only the cheap syntactic pre-filter runs per span.
     */
    private void wireInlineCodePaths(Node node) {
        if (node instanceof Label label && label.getStyleClass().contains("md-inline-code")) {
            String text = label.getText();
            if (looksLikePath(text)) {
                label.getStyleClass().add("agent-code-path");
                label.setOnMouseClicked(e -> onOpenPath.accept(text));
                Tooltip.install(label, new Tooltip(tr("agent.openFileTooltip")));
            }
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                wireInlineCodePaths(child);
            }
        }
    }

    /** Pure: whether inline-code text looks like a file path worth offering as clickable — contains a
     *  path separator or a home-tilde, no whitespace, and isn't a URL. Package-private + static: directly
     *  unit-tested (the {@link #glyphFor} idiom). */
    static boolean looksLikePath(String text) {
        if (text == null) {
            return false;
        }
        String s = text.strip();
        return !s.isEmpty() && !s.contains(" ") && !s.contains("://") && (s.contains("/") || s.startsWith("~"));
    }

    /** Clears the transcript (a new session). */
    public void clearTranscript() {
        transcriptBox.getChildren().clear();
        renderThrottle.cancel();
        streamBlocks = null;
        currentAgentWrapper = null;
        currentAgentMarkdown.setLength(0);
    }

    /** Toggles the running state: Stop enabled + status while a prompt turn is in flight. Also finalizes
     *  the just-completed turn's streaming message so its last chunk renders immediately rather than
     *  waiting out the debounce after the status has already flipped back to "Idle". */
    public void setBusy(boolean busy) {
        this.busy = busy;
        stopButton.setDisable(!busy); // the header ■ still enables only while running
        // The Send button becomes a red "Stop" while running (and back to "Send" when idle); never disabled.
        sendButton.setText(tr(busy ? "agent.stop" : "agent.send"));
        sendButton.getStyleClass().remove("danger");
        if (busy) {
            sendButton.getStyleClass().add("danger");
        }
        status.setText(tr(busy ? "agent.running" : "agent.idle"));
        if (!busy) {
            finalizeCurrentMessage();
        }
    }

    /** Sets the agent-picker label's text (e.g. "Agent: Claude Code"); {@code null}/blank shows a placeholder. */
    public void setAgentLabel(String name) {
        agentLabel.setText(tr("agent.clientLabel", name == null || name.isEmpty() ? "—" : name));
    }

    /** Sets the model-picker label's text (e.g. "Model: Claude Opus"); {@code null}/blank shows a placeholder. */
    public void setModelLabel(String name) {
        modelLabel.setText(tr("agent.modelLabel", name == null || name.isEmpty() ? "—" : name));
    }

    /** Sets the mode-picker label's text (e.g. "Mode: Plan"); {@code null}/blank shows a placeholder. */
    public void setModeLabel(String name) {
        modeLabel.setText(tr("agent.modeLabel", name == null || name.isEmpty() ? "—" : name));
    }

    /** Renders the agent's current plan as a checklist (one line per entry, status glyph prefixed).
     *  Each {@code plan} update is a full replacement, never incremental, so this replaces the whole
     *  checklist wholesale rather than appending. */
    public void setPlan(List<AcpJson.PlanEntry> entries) {
        planBox.getChildren().clear();
        for (AcpJson.PlanEntry e : entries) {
            Label line = new Label(glyphFor(e.status()) + " " + e.content());
            line.getStyleClass()
                    .add("plan-entry-" + (e.status() == null || e.status().isEmpty() ? "pending" : e.status()));
            line.setWrapText(true);
            planBox.getChildren().add(line);
        }
        boolean show = !entries.isEmpty();
        planBox.setManaged(show);
        planBox.setVisible(show);
    }

    /** Clears the plan checklist (a new session starts with no plan shown). */
    public void clearPlan() {
        setPlan(List.of());
    }

    /** The checkbox-style glyph for a plan entry's status. Package-private + static: pure, no FX toolkit
     *  needed, so it's directly unit-tested. */
    static String glyphFor(String status) {
        return switch (status == null ? "" : status) {
            case "completed" -> "☑";
            case "in_progress" -> "◐";
            default -> "☐"; // "pending" and any unrecognized status
        };
    }

    @Override
    public void focusFirstItem() {
        input.requestFocus();
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Icon-only header button (mirrors {@code DebugPanel.btn}): the {@code key}'s translated string
     *  becomes the tooltip instead of the button's (now absent) text, keeping the header compact. */
    private static void iconButton(Button b, Node icon, String key, Runnable action) {
        b.setGraphic(icon);
        b.setTooltip(new Tooltip(tr(key)));
        b.getStyleClass().addAll("flat", "agent-toolbar-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
    }
}

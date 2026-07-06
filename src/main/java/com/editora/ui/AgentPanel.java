package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

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
 * accumulates into that message's own raw-text buffer and a debounced ({@link #RENDER_DEBOUNCE}) re-parse
 * replaces that message's single rendered node in place, so a long response isn't re-parsed on every
 * chunk. {@link #appendLine} (a new prompt, a tool-call line, an error) finalizes — flushes any pending
 * render synchronously — whatever agent message was streaming, so the next chunk starts a fresh message
 * rather than continuing the previous one. Capped at {@link #MAX_ENTRIES} transcript nodes (oldest
 * dropped first) so a long session can't grow memory without bound.
 */
public final class AgentPanel extends VBox implements ToolWindowContent {

    /** Trim the transcript once it exceeds this many entries (keeps the most recent messages/lines). */
    private static final int MAX_ENTRIES = 2000;

    /** Coalesces rapid streaming chunks into one Markdown re-parse+re-render per pause. */
    private static final Duration RENDER_DEBOUNCE = Duration.millis(150);

    private final Label status = new Label();
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
    /** Receives the prompt text when the user sends (coordinator → agent). */
    private Consumer<String> onSend;
    /** The plain-text line font, applied to each new {@code agent-line} Label ({@link #setPanelFont}). */
    private String lineFontFamily;

    private double lineFontSize = -1;
    /** The currently-streaming agent message's single-child wrapper, or null between messages. */
    private VBox currentAgentWrapper;
    /** Raw Markdown accumulated so far for {@link #currentAgentWrapper}. */
    private final StringBuilder currentAgentMarkdown = new StringBuilder();

    private PauseTransition renderPause;

    public AgentPanel(
            Runnable onStop,
            Runnable onNewSession,
            Runnable onPickModel,
            Runnable onPickMode,
            Runnable onResumeSession) {
        getStyleClass().add("agent-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("agent-status");
        modelLabel.getStyleClass().add("agent-header-label");
        modelLabel.setOnMouseClicked(e -> onPickModel.run());
        modeLabel.getStyleClass().add("agent-header-label");
        modeLabel.setOnMouseClicked(e -> onPickMode.run());
        stopButton.setText(tr("agent.stop"));
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> onStop.run());
        historyButton.setText(tr("agent.history"));
        historyButton.setOnAction(e -> onResumeSession.run());
        newSessionButton.setText(tr("agent.newSession"));
        newSessionButton.setOnAction(e -> onNewSession.run());
        HBox header = new HBox(8, status, modelLabel, modeLabel, spacer(), historyButton, newSessionButton, stopButton);
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
        // Chat convention: Enter sends, Shift+Enter inserts a newline.
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                send();
            }
        });
        sendButton.setText(tr("agent.send"));
        sendButton.setDefaultButton(false);
        sendButton.setOnAction(e -> send());
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

    private void send() {
        String text = input.getText() == null ? "" : input.getText().strip();
        if (text.isEmpty() || onSend == null || !stopButton.isDisable()) {
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
        scheduleMarkdownRender();
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

    private void applyLineFont(Label label) {
        if (lineFontFamily != null) {
            label.setFont(javafx.scene.text.Font.font(lineFontFamily, lineFontSize));
        }
    }

    private void trimIfNeeded() {
        while (transcriptBox.getChildren().size() > MAX_ENTRIES) {
            transcriptBox.getChildren().remove(0);
        }
    }

    private void scheduleMarkdownRender() {
        if (renderPause == null) {
            renderPause = new PauseTransition(RENDER_DEBOUNCE);
            renderPause.setOnFinished(e -> renderCurrentMarkdown());
        }
        renderPause.stop();
        renderPause.playFromStart();
    }

    /** Flushes any pending debounced render synchronously and stops streaming the current message
     *  (the next {@link #appendChunk} starts a new one). A no-op when nothing is streaming. */
    private void finalizeCurrentMessage() {
        if (renderPause != null) {
            renderPause.stop();
        }
        if (currentAgentWrapper != null) {
            renderCurrentMarkdown();
            currentAgentWrapper = null;
            currentAgentMarkdown.setLength(0);
        }
    }

    /** Re-parses {@link #currentAgentMarkdown} and replaces {@link #currentAgentWrapper}'s content —
     *  MUST run on the FX thread ({@link MarkdownRenderer#renderDocument} builds FX nodes). Falls back to
     *  a plain wrapped label if rendering throws (malformed input should never break the chat). */
    private void renderCurrentMarkdown() {
        if (currentAgentWrapper == null) {
            return;
        }
        String md = currentAgentMarkdown.toString();
        Node rendered;
        try {
            rendered = MarkdownRenderer.renderDocument(MarkdownRenderer.parseToDocument(md), null);
        } catch (RuntimeException ex) {
            Label fallback = new Label(md);
            fallback.setWrapText(true);
            rendered = fallback;
        }
        currentAgentWrapper.getChildren().setAll(rendered);
    }

    /** Clears the transcript (a new session). */
    public void clearTranscript() {
        transcriptBox.getChildren().clear();
        if (renderPause != null) {
            renderPause.stop();
        }
        currentAgentWrapper = null;
        currentAgentMarkdown.setLength(0);
    }

    /** Toggles the running state: Stop enabled + status while a prompt turn is in flight. Also finalizes
     *  the just-completed turn's streaming message so its last chunk renders immediately rather than
     *  waiting out the debounce after the status has already flipped back to "Idle". */
    public void setBusy(boolean busy) {
        stopButton.setDisable(!busy);
        sendButton.setDisable(busy);
        status.setText(tr(busy ? "agent.running" : "agent.idle"));
        if (!busy) {
            finalizeCurrentMessage();
        }
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
}

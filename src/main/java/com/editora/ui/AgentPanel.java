package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.agent.AcpJson;

import static com.editora.i18n.Messages.tr;

/**
 * The "AI Agent" tool window: a chat transcript streaming the ACP agent's replies + tool activity, a
 * multi-line prompt input (Enter sends, Shift+Enter inserts a newline), and Stop / New Session actions.
 * A {@link ToolWindowContent} modeled on {@link RunPanel}; {@link AgentCoordinator} drives it via
 * {@code appendChunk/appendLine/setBusy} on the FX thread. The transcript is a read-only wrapped
 * {@link TextArea} (cheap and robust for streaming text), capped so a long session can't grow memory
 * without bound.
 */
public final class AgentPanel extends VBox implements ToolWindowContent {

    /** Trim the transcript once it exceeds this many characters (keeps the most recent output). */
    private static final int MAX_CHARS = 400_000;

    private final Label status = new Label();
    private final Label modelLabel = new Label();
    private final Label modeLabel = new Label();
    private final VBox planBox = new VBox(2);
    private final TextArea transcript = new TextArea();
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button();
    private final Button stopButton = new Button();
    private final Button newSessionButton = new Button();
    /** Receives the prompt text when the user sends (coordinator → agent). */
    private Consumer<String> onSend;

    public AgentPanel(Runnable onStop, Runnable onNewSession, Runnable onPickModel, Runnable onPickMode) {
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
        newSessionButton.setText(tr("agent.newSession"));
        newSessionButton.setOnAction(e -> onNewSession.run());
        HBox header = new HBox(8, status, modelLabel, modeLabel, spacer(), newSessionButton, stopButton);
        header.setAlignment(Pos.CENTER_LEFT);

        planBox.setManaged(false);
        planBox.setVisible(false);

        transcript.setEditable(false);
        transcript.setWrapText(true);
        transcript.getStyleClass().add("agent-transcript");

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

        VBox.setVgrow(transcript, Priority.ALWAYS);
        getChildren().addAll(header, planBox, transcript, inputRow);
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

    /** Matches the transcript/input font family to the editor's (size stays the UI default). */
    public void setPanelFont(String family, int size) {
        transcript.setFont(javafx.scene.text.Font.font(family, size));
        input.setFont(javafx.scene.text.Font.font(family, size));
    }

    /** Appends streaming text verbatim (agent message chunks arrive without trailing newlines). */
    public void appendChunk(String text) {
        appendRaw(text);
    }

    /** Appends {@code line} on its own line (tool activity, prompts, status notes). */
    public void appendLine(String line) {
        String text = transcript.getText();
        appendRaw((text.isEmpty() || text.endsWith("\n") ? "" : "\n") + line + "\n");
    }

    private void appendRaw(String text) {
        transcript.appendText(text);
        int len = transcript.getLength();
        if (len > MAX_CHARS) {
            String kept = transcript.getText(len - MAX_CHARS, len);
            int nl = kept.indexOf('\n');
            transcript.replaceText(0, len, nl >= 0 ? kept.substring(nl + 1) : kept);
        }
        transcript.positionCaret(transcript.getLength());
        transcript.setScrollTop(Double.MAX_VALUE);
    }

    /** Clears the transcript (a new session). */
    public void clearTranscript() {
        transcript.clear();
    }

    /** Toggles the running state: Stop enabled + status while a prompt turn is in flight. */
    public void setBusy(boolean busy) {
        stopButton.setDisable(!busy);
        sendButton.setDisable(busy);
        status.setText(tr(busy ? "agent.running" : "agent.idle"));
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

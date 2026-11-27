package com.editora.ui;

import java.util.concurrent.CompletableFuture;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.editora.editor.TabContent;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * An agent's proposed edit of one buffer (the MCP {@code propose_edit} tool), as a tab: a bar that names the agent and
 * the file, the agent's reason, a field for the user's own reason, Accept and Reject, over a read-only diff of the
 * buffer's text before and after. The decision completes {@link #decided()} once; the tab is closed then (by its
 * opener). Closing the tab is a rejection without a reason.
 */
final class ProposalPane implements TabContent {

    /** What the user decided: {@code accepted}, or rejected with a {@code reason} (may be empty), or no answer in time. */
    record Decision(boolean accepted, boolean timedOut, String reason) {
        static Decision accept() {
            return new Decision(true, false, "");
        }

        static Decision reject(String reason) {
            return new Decision(false, false, reason == null ? "" : reason.strip());
        }

        static Decision timeout() {
            return new Decision(false, true, "");
        }
    }

    private final BorderPane root = new BorderPane();
    private final String title;
    private final DiffViewerPane diff;
    private final CompletableFuture<Decision> decided;
    private final TextField reasonField = new TextField();
    private final Button acceptButton = new Button(tr("mcp.proposal.accept"));
    private final Button rejectButton = new Button(tr("mcp.proposal.reject"));

    ProposalPane(
            String fileName,
            String agent,
            @Nullable String why,
            DiffViewerPane diff,
            CompletableFuture<Decision> decided) {
        this.title = tr("mcp.proposal.title", fileName);
        this.diff = diff;
        this.decided = decided;
        Label lead = new Label(tr("mcp.proposal.lead", agent, fileName));
        lead.getStyleClass().add("proposal-banner-lead");
        lead.setWrapText(true);
        VBox text = new VBox(lead);
        if (why != null && !why.isBlank()) {
            Label reason = new Label(why.strip());
            reason.getStyleClass().add("proposal-banner-text");
            reason.setWrapText(true);
            text.getChildren().add(reason);
        }
        UiZoom.spacing(text, 2);
        reasonField.setPromptText(tr("mcp.proposal.reasonPrompt"));
        reasonField.setAccessibleText(tr("mcp.proposal.reasonPrompt"));
        reasonField.setOnAction(e -> reject());
        HBox.setHgrow(reasonField, Priority.ALWAYS);
        acceptButton.getStyleClass().add("accent");
        acceptButton.setDefaultButton(false);
        acceptButton.setOnAction(e -> accept());
        rejectButton.setOnAction(e -> reject());
        HBox actions = UiZoom.spacing(new HBox(reasonField, rejectButton, acceptButton), 6);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox bar = UiZoom.spacing(new VBox(text, actions), 6);
        bar.getStyleClass().add("proposal-banner");
        UiZoom.padding(bar, 8);
        root.setTop(bar);
        root.setCenter(diff.node());
        decided.whenComplete((d, err) -> javafx.application.Platform.runLater(() -> {
            acceptButton.setDisable(true);
            rejectButton.setDisable(true);
            reasonField.setDisable(true);
        }));
    }

    /** Applies the edit: the window's bridge does, once it hears this. */
    void accept() {
        decided.complete(Decision.accept());
    }

    /** Turns the edit down, with what the reason field says. */
    void reject() {
        decided.complete(Decision.reject(reasonField.getText()));
    }

    /** The tab went away undecided: a rejection without a reason. */
    void closed() {
        decided.complete(Decision.reject(""));
    }

    CompletableFuture<Decision> decided() {
        return decided;
    }

    DiffViewerPane diff() {
        return diff;
    }

    TextField reasonField() {
        return reasonField;
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return title;
    }
}
